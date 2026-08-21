# 尽调报告模块总方案（合成稿）

日期：2026-08-21 ｜ 看板卡：dev-board#100 ｜ 状态：待维护者拍板后进 brainstorm → 正式 spec → 分期 plan
依据：四份盘点（同目录 `2026-08-21-dd-linkage-inventory.md` / `dd-scale-stability-inventory.md` / `dd-style-learning-inventory.md` / `dd-sample-corpus-profile.md`）+ 维护者三次口径。
本文取代上午的 `2026-08-21-due-diligence-lite-evaluation.md`（那份按「轻量 skill」理解，作废）。

---

## 0. 一页结论

**定位**：律师单人用的「底稿驱动起草」工作台——输入是一堆零散客户文件 + 网核截图 zip + 团队模板，输出是一份按律所版式的报告初稿，**报告里每条事实陈述都挂着底稿**，且关联可双向查、可定位到底稿内位置、改字时能提醒。与现有面向客户协作的「尽调清单」插件无关。

**架构判断**：原语层基础确实好（LOWA 书签/定位/内部链接协议、文件树、标签、docx4j 写端、Tika/OCR、Playwright、平台网关），但**勾稽的事实表要重建**——今天的拖拽关联只是「选区超链接 + linkKey→fileIds 表」，没有目标位置、不能反查、文字删了就成孤儿、AI 零感知；tag / DocFileLink / WebFavorite 三套引用互不相通。

**四根支柱**（对应四份盘点）：
1. **勾稽事实表** `EvidenceLink`：书签锚点 + 目标位置 + 双向查询，网核截图落成项目文件走同一张表。
2. **规模底座**：样本才 36 页 / 103 份底稿就已触到 `MAX_IMPORT_ENTRIES=3000` 之外的多条红线；上百页 + 千份底稿前必须先修 10 条中的前 6 条。
3. **样式画像**：docx4j 直读 XML（不是 POI、不是 LOWA），`styleProfile` 带单位，HOUSE 三处常量改成可注入 profile。
4. **语料契约**：以样本项目为黄金对照（6 章对照 + 全局断言），流水线 10 条硬要求是验收清单。

**分期**：P0 底座与事实表（2 周）→ P1 入库整理 + 模板学习 + 初稿（2 周）→ P2 勾稽核查 + 改字提醒 + 底稿目录/缺口清单（1.5 周）→ P3 网核 zip 接入 + 定位高亮 + 规模打磨（1.5 周）。约 7 周，可并行压到 5 周。

---

## 1. 数据模型：把「引用」统一成一张事实表

### 1.1 EvidenceLink（升级自 DocFileLink，同表加列不换表）

| 列 | 含义 | 来源 |
|---|---|---|
| `linkKey` | 书签名 = 文档内锚点，`EVID_<ulid>` | 现有 |
| `docFileId` | 报告所在 ProjectFile（改用 id，不再用 wpsFileId 软引用） | 改 |
| `anchorText` / `anchorHash` | 锚点文字快照 + 归一化 hash（改字检测用） | 加 hash |
| `sectionPath` | 报告章节路径（`一/（二）/3`），由书签所在标题链派生，落库便于按章节聚合 | 新 |
| `targets[]` | 子表 `evidence_link_target`：`fileId`、`locatorJson`、`relation`（supports / contradicts / partial）、`method`（书面审查/书面说明/网络核查/第三方材料/访谈）、`confidence`、`createdBy`（human / ai） | 新 |
| `locatorJson` | `{page, quote, rect}`（pdf/docx）/ `{rect}`（图片）/ `{startMs,endMs}`（音频视频）/ `{url, capturedAt}`（网核） | 新 |
| `status` | active / orphan（书签不在了）/ stale（锚点文字变了）/ unverified | 新 |

关键改变：
- **锚点用命名书签而不是字符属性**：书签随段落移动、跨修订接受存活；`insert_link_with_bookmark` 已有这条路，拖拽关联改走它。
- **双向查询**：`findByTargetFileId`、`findByDocFileId`、`findBySectionPath`，加索引。
- **网核截图 = ProjectFile**：zip 解包后落 `尽调/<章节>/网核/<站点>_<日期>.png`，`ProjectFile.metaJson` 记 `{sourceUrl, capturedAt, provider}`；WebFavorite 保留给浏览器面板随手收藏，不再是尽调主链。
- **主体**：物理树 = 章节（报告目录即文件夹），主体 = `PARTY` 标签（样本 12 个主体、同一主体材料散在 9 处，做成文件夹层会逼用户复制文件）。章节多重归属不用复制文件，由 EvidenceLink 反查派生「该文件被哪些章节引用」。
- **文件稳定 ID**：样本里律师手工维护的 `章-节-序` 编号要解析进 `metaJson.docketNo`，缺则分配，重复则合并；字节级去重只合并实体不删视图。

### 1.2 三种内部 scheme 收敛

`checkba://filelink?k=`（保留，为主）/ `checkba://webfav?id=`（浏览器收藏）/ `checkba://file/<id>`（evidence.retrieve）——P0 起 `filelink` 支持 `&t=<targetIndex>` 直达某个 target 的 locator；后两者不再新增用法。

---

## 2. 流水线（AI 侧）

```
入库整理 ──► 模板学习 ──► 起草 ──► 勾稽核查 ──► 交付件
```

**A. 入库整理**（样本硬要求 1-5）：解压 zip（zip 当快照，目录为准）；过滤 `.DS_Store`/`~$`/`__MACOSX`；解析编号主键；字节级 + 近似去重（同名同尺寸、mp4 差几字节的两次导出）；状态词（【修订】【用印稿】(1) ---副本）抽成元数据；版本链判定（阶段词 > 日期 > vN > HHMM > mtime，按标题主干分组）；按章节夹/文件名/首页 OCR 归类到报告章节树；主体识别打 PARTY 标签。工具：`extract_file_text`、`ocr_*`、`move_project_file`、`tag_file` 现成，新增 `dd_ingest`（一次性批处理，后台任务 + 进度卡，避免 AI 逐文件调用撞 30s）。

**B. 模板学习**（样式盘点）：`docx_inspect_template(fileId)` 用 docx4j 直读 styles/numbering/document.xml，输出 `styleProfile`（每个长度字段 `{value, unit}`；字体分 eastAsia/western/theme 槽；编号区分 auto/literal；表格到单元格级边框、列宽 twips、表头、数字格式）。样本实证：楷体 GB2312 12pt / 表格 10pt / 无首行缩进 / 段后 18pt / 最小行距 16pt / `一、（一）1.` 多级列表 / 单元格级边框 / 目录域 `\o "1-2"`——与 HOUSE 默认完全不同，**出稿必须按律所模板**。同时抽「引用句式库」（样本六种固定句式，句式本身就是引用）与「表格模板」（主体 × 字段，4 张基本情况表同构）。画像落 `_模板/画像.json`，团队共享、进版本记录。

**C. 起草**：按章节、按主体 × 表格模板生成；每条事实句用句式库写，并**同步写 EvidenceLink**（AI 工具 `doc_link_evidence(anchor, targets[], method)`）；数字从 xls/审计报告结构化源取并回指单元格；找不到底稿的事实写「【待补：…】」并进缺口清单，不得编造（EVIDENCE_CONTRACT 不变式）。写端：`write_docx` 加 `styleProfileJson`，docx4j 路径补编号/列宽/页眉页脚/目录。

**D. 勾稽核查**：对每个 EvidenceLink 做「陈述 ↔ 底稿」一致性判定（执照三要素 OCR 对账、日期/金额/股比对账、网核截图 OCR 对 URL 与主体名）；输出 relation/confidence；审阅面板新增「底稿」标签页，按章节/主体/状态筛选，点击定位。

**E. 交付件**（样本四件套）：报告 docx；**文件级底稿目录**（人工流程缺的那一环，= EvidenceLink 按章节导出，每条带反向链接）；查验计划（method 枚举自动归类）；缺口清单（验收：⊇ 人工 `未提供文件.xlsx` 8 条）；章节树 zip。

**F. 改字提醒**：worker 侧 `modified` 事件后按命名书签比对 `anchorHash`，变了标 stale 并在审阅面板「底稿」页亮黄；AI 改稿（doc_* 工具）后自动重跑受影响 link 的核查；「是否有新底稿」= 入库整理时新文件归类到某章节后，扫该章节 stale/unverified 的 link 提示。

---

## 3. 规模底座（稳定性盘点前 10 条，按本模块相关度重排）

实测基线（真引擎 r4，150 页/30 表/20 图/6.6MB）：打开 2.7-3.8s 可接受；`get_document_text` 每次 2.2-5.2s 全扫；修订模式 `find_replace` 150 命中 20.5s；`apply_house_style` >30s 超时且 5000 元素静默截断；页面内存约 2.4GB。

P0 必修：
1. `MAX_IMPORT_ENTRIES=3000` 静默丢文件（LocalProjectService.java:40/351/397）→ 分批 + 明确告警。
2. 新建文件同级 O(N²) 扫描 + 同名直接报错（ProjectFileService.java:224-229）→ 索引 + 同名自动加序；这是几百张截图落盘的直接痛点。
3. 批量改稿撞 30s 桥超时而 worker 继续改（双改风险）→ 分段提交 + 可取消 + 进度。
4. `apply_house_style` 改成按段落范围分批，truncated 必须上报。
5. `get_document_text` 分页全扫 → 段落索引缓存（书签/标题链一次建好，改动增量维护）。
6. 文件树整棵拉取无虚拟滚动（FileTree.vue）→ 懒加载 + 虚拟列表；`list_files` 分页。

P3 再修：审阅面板批量处置 O(K·N)、保活池按体积计权、Git 全量 add 无体积过滤、`doc_insert_image` 2MB 上限、手机端镜像 `MAX_DIR_ENTRIES=1000`。
发版前把实测配方进 lowa-e2e「大文档基线组」，三次取中位数。

---

## 4. UI/UX 补充点

- **模板上传**：项目 `_模板/` 文件夹为权威存放 + AI 面板拖入快捷入口；上传即触发画像并在概览页显示「已学习：楷体 12pt / 四级编号 / 17 类表格」。
- **审阅面板「底稿」页**：章节树 ⇄ 主体两种视图切换；每条 link 显示状态色（active/stale/orphan/unverified）、method、confidence；点击打开底稿并定位（pdf 跳页 + 引文高亮叠加层，图片画框，音视频 seek）。
- **拖拽关联升级**：把文件拖到**文字上**（不是侧栏投放区）即建书签 + link，并弹 method 选择。
- **文件树**：按 PARTY 标签筛选、显示「被引用 N 次」角标、未引用底稿灰显（样本 21 份零引用里 18 份是重复、2 份误入、1 份引而不注——灰显能直接暴露）。
- **缺口清单与底稿目录**：一键导出 xlsx / docx。
- **版本链**：同名多版只在树里显示最终版，其余折叠进「历史版本」。

---

## 5. 分期与工期

| 期 | 内容 | 代码面 | 工期 |
|---|---|---|---|
| P0 底座 | EvidenceLink 表与双向查询、书签锚点化的拖拽关联、`filelink&t=` 定位、稳定性前 6 条、lowa-e2e 大文档基线组 | backend: DocFileLink→EvidenceLink 实体/Repo/Service/Controller；frontend: project-overview 拖拽链路、FileTree 虚拟滚动；worker: 段落索引、分批 house style | 2 周 |
| P1 起草 | `dd_ingest` 后台任务、`docx_inspect_template` + styleProfile、`write_docx(styleProfileJson)`、引用句式库与表格模板、`doc_link_evidence` 工具、尽调 skill prompt | backend: TemplateTools/DdIngestService/FileTools/DocxStyleHelper；skills/due-diligence/ | 2 周 |
| P2 勾稽 | 一致性核查、审阅面板「底稿」页、改字 stale 检测、底稿目录/查验计划/缺口清单导出 | backend: EvidenceVerifyService；frontend: ReviewPanel 新 tab；worker: anchorHash 比对 | 1.5 周 |
| P3 网核与定位 | zip 接入（公司名 → 外部服务 → 解包落盘 → 自动 link）、pdf 叠加层高亮、图片画框、音视频 seek、稳定性余项 | backend: WebVerifyIngest + 平台网关一条新服务；frontend: FilePreview | 1.5 周 |

验收：P1 末用样本项目跑黄金对照 6 章 + 全局断言（去重后实体≈85、骨干文件≥2 章、缺口清单 ⊇ 8 条、误入文件不归类、底稿目录条目数=实体数）。

---

## 6. 刻意不做

拖拽调序大纲 UI（大纲=文件夹，文件树可拖）；短信通知；token 独立计费（Credits 统一）；新左栏面板（审阅面板加 tab 即可）；自动逐站爬取（接外部 zip 服务，验证码与合规都不碰）；独立阅读器。

---

## 7. 待拍板（4 个）

1. **网核 zip 的内部格式**：需要一个真实返回样例（是否每张图带 URL/时间戳的 manifest？没有就只能靠 OCR 抽 URL）。这决定 P3 的落盘与 link 自动化程度。
2. **HOUSE 常量改成 profile 注入**会动三处逐字一致的契约（worker / DocxStyleHelper / Office 插件），是否允许在 P1 一并做（否则只能走「先按 HOUSE 出稿再 apply_style_profile 二次套版」，大文档上慢）。
3. **改字提醒的力度**：只在审阅面板亮黄（推荐，不打断），还是编辑时弹提示。
4. **商业定位**：内置（官方版用户升级即得）还是广场付费项接 entitlement。影响 skill.yml 与 registry。

---

## 8. 下一步

拍板后按 superpowers 流程走：brainstorm（对齐 P0 数据模型细节）→ writing-plans 出 P0 任务级 plan → subagent-driven 执行。P0 不依赖任何待拍板项，可以先动。
