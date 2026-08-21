# 尽调 P1 设计：模板画像 + 插件宿主 SPI + 尽调 JAR 插件（入库整理/起草/勾稽写入）

日期：2026-08-21 ｜ 母卡：dev-board#100 ｜ 上游：总方案 §2 / §5 / §7、样式盘点、样本画像（本机）、P0 设计（EvidenceLink）
状态：2026-08-21 22:50 维护者通过；私有仓由维护者自建，建好后告知仓名。

## 0. 本期拍板（2026-08-21 22:40）

- **尽调插件 = 独立 JAR 插件**，不入主仓。源码放私有仓 `zeweihan/aiworkdeck-dd-plugin`（待建），走 `PluginMarketService` 签名分发，广场付费项 `plugin:due-diligence`。
- 由此 P1 必须先把**插件宿主 SPI** 做出来：今天 JAR 插件只有「无参构造 + @Tool」一种能力，拿不到项目文件/OCR/标签/EvidenceLink/后台任务。SPI 是内置、开源、只加不破。

## 1. 分层（谁在主仓、谁在插件）

| 能力 | 落点 | 门控 |
|---|---|---|
| `docx_inspect_template` + `styleProfile` schema + `house-default.json` 单源 + `write_docx(styleProfileJson)` + worker `set_style_profile/apply_style_profile` | 主仓（内置） | 无 |
| `doc_link_evidence` / `doc_list_evidence` AI 工具 | 主仓（内置，EvidenceLink 的 AI 面） | 无 |
| 插件宿主 SPI `com.checkba.plugin.api.*`（新 Maven 模块 `plugin-api`，发布到本地仓库 + GitHub Packages） | 主仓（内置） | 无 |
| `dd_ingest` 后台任务、编号/去重/版本链/状态词/归类/PARTY 标签、引用句式库、表格模板、尽调 skill prompt、黄金对照跑分器 | `aiworkdeck-dd-plugin`（JAR + skills/due-diligence/） | `plugin:due-diligence` 已购且已启用 |

## 2. 插件宿主 SPI（`plugin-api` 模块）

### 2.1 注入方式

JAR 工具类仍无参构造；若实现 `com.checkba.plugin.api.HostAware`，`PluginService.loadJar` 实例化后调用 `setHost(PluginHost host)`。`PluginHost` 是宿主实现的门面（主仓 `service/plugin/PluginHostImpl`），按插件 id 绑定（日志、配额、任务归属都带 pluginId）。

```java
public interface HostAware { void setHost(PluginHost host); }

public interface PluginHost {
    String pluginId();
    ToolCall call();                     // 当前工具调用上下文：projectId/userId/conversationId/modelId（ThreadLocal 透传，非调用期为 null）
    Files files();
    Text text();
    Tags tags();
    Evidence evidence();
    Jobs jobs();
    Docs docs();
    Settings settings();
    Llm llm();
}
```

### 2.2 子接口（P1 最小集，字段只加不破）

```java
interface Files {
    List<FileInfo> list(long projectId, Long parentId, boolean recursive);          // FileInfo{id,name,parentId,isFolder,fileType,size,path,sha256?,metaJson}
    FileInfo get(long projectId, long fileId);
    InputStream open(long projectId, long fileId);
    FileInfo createFolderPath(long projectId, List<String> segments);               // 逐级确保
    FileInfo write(long projectId, Long parentId, String name, InputStream bytes, ConflictPolicy policy); // RENAME/FAIL
    FileInfo move(long projectId, long fileId, Long newParentId);
    FileInfo rename(long projectId, long fileId, String newName);
    void setMeta(long projectId, long fileId, Map<String,Object> metaPatch);        // 合并进 ProjectFile.metaJson
    String sha256(long projectId, long fileId);                                     // 宿主缓存到 metaJson.sha256
}
interface Text {
    String extract(long projectId, long fileId, int maxChars);                      // Tika；docx/pdf/xls
    OcrResult ocr(long projectId, long fileId, OcrOptions o);                       // 走平台网关（扣 Credits）；OcrResult{text, blocks[{text,rect,page}]}
    List<String> pdfPageTexts(long projectId, long fileId, int fromPage, int toPage);
}
interface Tags {
    TagInfo getOrCreate(long projectId, String name, String type);                  // type NORMAL/PARTY/ISSUE；同名不同型复用不改型
    void tagFile(long projectId, long fileId, long tagId);
    List<TagInfo> tagsOf(long projectId, long fileId);
}
interface Evidence {                                                                // 直接映射 EvidenceLinkService（P0）
    LinkView create(long projectId, long docFileId, String linkKey, String anchorText, String sectionPath, String sectionTitle, List<TargetInput> targets);
    LinkView addTargets(long projectId, String linkKey, List<TargetInput> targets);
    List<LinkView> listByDoc(long projectId, long docFileId);
    List<LinkView> listByFile(long projectId, long fileId);
}
interface Jobs {
    JobHandle start(String kind, String title, JobBody body);                       // 后台线程池（每插件最多 2 并发），JobBody.run(JobContext ctx) 里 ctx.progress(done,total,message) / ctx.checkCancelled()
    JobStatus status(String jobId);
    void cancel(String jobId);
}
interface Docs {                                                                    // 经 EditorBridgeService，仅在有 conversationId 时可用
    String exec(String action, Map<String,Object> params);                          // 白名单：doc_* 已在 EDITOR_ACTIONS 的 action
    void refreshFiles();                                                            // sendRefreshFilesAction
    void openFile(long fileId, Map<String,Object> locator);
}
interface Settings {
    String get(String key); void set(String key, String value);                     // 键自动加前缀 plugin.<id>.
    String projectStyleProfileJson(long projectId);                                 // 解析顺序见 §3.4
}
interface Llm {
    String complete(String systemPrompt, String userPrompt, LlmOptions o);          // 走平台通道，扣 Credits；记 pluginId
}
```

- **鉴权**：所有方法先校 `call().userId()` 对 projectId 的读/写权限（复用 ProjectMemberService）；无调用上下文（后台任务线程）时用 `JobContext` 里快照的 userId。
- **后台任务**：`PluginJobService`（主仓）——内存 + `plugin_job` 表（id, pluginId, kind, title, status queued/running/done/failed/cancelled, done/total/message, resultJson, projectId, userId, createdAt/updatedAt）；REST `GET /api/plugin-jobs?projectId=`、`GET /api/plugin-jobs/{id}`、`POST /{id}/cancel`；有 conversationId 时同时经 SSE `client_action: plugin_job_progress` 推给对话；前端 `ChatInterface` 的 `BackgroundTaskIndicator` 接这条事件（它已是通用任务指示器）。
- **配额**：`Llm`/`Text.ocr` 都走平台网关（按用户 Credits），不给插件独立计费；每插件每分钟 60 次宿主调用上限（防 runaway），超限抛 `HostQuotaException`。
- 模块发布：`plugin-api` 作为主仓子模块（`backend/plugin-api/`，`com.checkba:plugin-api:1.0.0`），插件以 `provided` 依赖它；版本号独立于桌面版本，只加不破。
- `docs/PLUGIN_SPEC.md` 升 v2.4：§4 加 `HostAware`、§11 SPI 方法表；`examples/hello-plugin` 加一个用 `host.files().list` 的工具示范。

## 3. 模板画像（内置）

### 3.1 `docx_inspect_template(fileIds[], options?)` → styleProfile v1

- docx4j 直读 `styles.xml / numbering.xml / document.xml / theme1.xml / sectPr / header*.xml`；schema 照样式盘点 §2（schemaVersion 1，全部长度字段带 unit；font 分槽；numbering.kind auto/literal；table 到单元格级边框 + gridCol twips；toc 域；headerFooter）。
- 多份取众数、置信度；`.doc` → 提示「另存 docx 或在编辑器打开后学习」（LOWA 兜底 `inspect_template_lowa` 放 P3）。
- 实现：新 `service/ai/tools/TemplateTools.java`（`AgentToolComponent`）+ `util/style/StyleProfile.java`（记录 + Jackson + 单位换算）+ `util/style/DocxProfileReader.java`；注册三处（@Component 自动 / `RealToolBeans` / `toolDisplayNames.js`）。
- 样本验收：对样本报告 1 定稿 docx 跑出的 profile 必须满足：body.font.eastAsia=`KaiTi_GB2312`、size 12pt、firstLineIndent 0、spaceAfter 18pt、lineSpacing atLeast 16pt；headings[0].numbering `{kind:auto, numFmt:chineseCountingThousand, lvlText:'%1、'}`、headings[1] `（%2）`、headings[2] `%3.`；table.cell.size 10pt；table.borders.source=`cell`；toc `\o "1-2"`。

### 3.2 HOUSE 单源化

- `backend/src/main/resources/style-profiles/house-default.json`（HOUSE 的 JSON 化）。
- `DocxStyleHelper.applyStandardFormat(pkg, StyleProfile)`：常量全部改读 profile；补标题多级自动编号（`NumberingDefinitionsPart`）、literal 编号拼接、单元格级边框、`tblGrid` 列宽、表头底纹/重复表头、页面 `SectPr`、页眉页脚 + PAGE/NUMPAGES 域、TOC 域 + `updateFields`。
- worker：`HOUSE` → `ACTIVE_PROFILE`（默认内嵌同一份 JSON）；新 action `set_style_profile {profile}`、`apply_style_profile {scope: document|selection|styles-only}`（先改 ParagraphStyles 定义再最小直接格式，保留 `keepWeight`）；`format_selection` 加 `fontNameAsian`；`format_table` 露出 `borderColor/borderStyle/outside-inside/headerFill/repeatHeader/columnWidthsCm`；`insert_toc`、`set_page_setup`、`edit_header_footer` 加页码域/字体。
- Office 插件 `officeExecutor.js` 的 `HOUSE` 改读同一 JSON（构建时内联）。
- **对拍测试**：`HouseProfileParityTest`（Java 读 house-default.json 与 DocxStyleHelper 实际落盘值对拍）+ `tests/evidence/houseProfile.test.mjs`（worker 内嵌 JSON 与资源文件 sha256 一致）+ office-addin 同款；替代「三处逐字一致」。
- `write_docx(markdown, fileName, parentFolderId?, styleProfileJson?)`；缺省按 §3.4 解析。`doc_open_file` 成功后 `EditorBridgeService` 追发 `set_style_profile`。

### 3.3 模板上传 UX

- 权威存放：项目 `_模板/` 文件夹；`_模板/画像.json` + `画像.md`（人话摘要，AI 写）。
- AI 面板拖入 → skill 引导调 `docx_inspect_template` → `<question>` 反问确认关键项（标题级数 / 编号 auto 或 literal / 表格边框层级）→ 落 `_模板/`。
- 概览页 `ProjectHomePane` 显示「已学习：楷体 12pt / 三级编号 / N 类表格」一行（读 `画像.json`）。

### 3.4 画像解析顺序

工具显式 `styleProfileJson` > 项目 `_模板/画像.json` > `SystemSetting dd.styleProfile.default` > `house-default.json`。

## 4. EvidenceLink 的 AI 面（内置）

- `doc_link_evidence(anchorQuote | anchorId, targetsJson, method?, relation?, note?)`：worker 定位（`find_text_locations` 唯一命中或已有 anchorId）→ `bookmark_selection(EVID_…)` + `set_selection_hyperlink` + `get_bookmark_context` → `EvidenceLinkService.create(createdByKind=ai → unverified)`；`targetsJson: [{fileId|path, locator?, relation?, method?}]`。返回 `{linkKey, targetIds, sectionPath}`。
- `doc_list_evidence(docFileId?, fileId?, sectionPath?, status?)` → 精简 LinkView 列表（供模型自查「哪些段落还没底稿」）。
- 四件套：DocumentEditTools @Tool、EDITOR_ACTIONS（复用 P0 五原语，无新 action）、`toolDisplayNames.js`、`RealToolBeans`。`ContextAssemblerService` docx 分支加一句：事实陈述写完必须 `doc_link_evidence`。

## 5. 尽调插件（`aiworkdeck-dd-plugin`）

### 5.1 仓库与包

```
aiworkdeck-dd-plugin/
├── manifest.json              # id due-diligence, permissions [file_read,file_write,editor,network], tools[], skills ["skills/due-diligence"], backendJars
├── pom.xml                    # provided: plugin-api, langchain4j-core
├── src/main/java/com/aiworkdeck/dd/…
├── skills/due-diligence/{skill.yml, prompt.md, phrasebook.yml, table-templates.yml}
├── golden/                    # 黄金对照跑分器（本地跑，读 DD_SAMPLE_ROOT；期望值文件只在本机）
└── README.md
```

### 5.2 `dd_ingest(projectId, rootFolderId, reportDocFileId?, options)`（后台任务）

流水线（样本硬要求 1-5 + 主体识别）：
1. **扫描**：`files.list(recursive)`；过滤 `.DS_Store`、`~$*`、`__MACOSX`；zip 只记 `metaJson.snapshot={zipFileId, mtime}` 不解压（目录为准；无同名目录时才解压到 `_解压/<zip名>/`）。
2. **编号主键**：文件名前缀 `^(\d+)-(\d+)(?:-(\d+))?(?:-(\d+))?\s` → `metaJson.docketNo`（章-节-序[-主体序]）；缺则按所在章节夹分配 `章-节-自增`；重复编号 → 合并候选。
3. **去重**：`files.sha256` 字节级；近似：同标题主干 + 同扩展名 + 大小差 < 1%（mp4 两次导出）或图像尺寸倍数关系（扫描件）→ 标 `metaJson.dupOf=<实体 fileId>`，不删文件；实体 = 该组最早编号/最完整者。
4. **状态词**：`【修订】【用印稿】【有标黄】(1) ---副本 -mkckh-mkhzw（待补充）` 抽成 `metaJson.stateWords[]`、`proofreaders[]`、`copyIndex`，标题主干 `metaJson.title`。
5. **版本链**：按标题主干分组，排序键 `(阶段词优先级 初稿<内核稿<内核回复<拟定稿<定稿, 日期, vN, HHMM, mtime)`；`.doc/.pdf` 成对时 pdf 标 `exportOf`；组内最终版 `metaJson.versionFinal=true`，其余 `versionOf`。
6. **归类**：章节 = 文件所在章节夹（`N-章节名`）→ `metaJson.chapter`；无章节夹的散件按文件名关键词 + 首页文本（`text.extract` 前 2000 字 / 图片 `text.ocr`）用 `llm.complete` 归到章节树；置信度 < 0.6 进「待归类」。
7. **主体识别**：文件名前缀主体简称（`收购人关于…`、`甲关于…`）+ 执照/身份证 OCR 名称 → `tags.getOrCreate(name, PARTY)` + `tagFile`；主体别名表来自 `dd_ingest` options.parties（skill 第一步让用户确认主体清单）。
8. **输出**：`resultJson {entities, duplicates, versions, chapters, unclassified, parties, gaps:[]（P1 只收「（待补充）」子夹与空章节）}`，写 `_尽调/入库报告.md`（人话）+ `_尽调/入库.json`。进度 `ctx.progress(done,total,当前文件名)`。

### 5.3 起草相关工具

- `dd_chapter_materials(projectId, chapter)` → 该章实体文件列表（去重后、最终版、带 PARTY、带 docketNo、带已被哪些 link 引用）。
- `dd_table(templateId, partyTagId)` → 按 `table-templates.yml`（主体基本情况 9×2 / 自然人 / 出资结构 / 控制企业 / 三年财务）生成 `rowsJson` + 每格 `source:{fileId, locator}`（执照 OCR 字段、xls 单元格 `{type:'sheet',sheet,cell}`）；数字格式：千分位两位小数、百分比两位小数、负数 `-` 前置、空 `-`、身份证中段 `********`。
- `dd_phrase(kind, slots)` → 句式库渲染（六种固定句式，`phrasebook.yml`），返回句子 + 该句应挂的证据类型。
- `dd_gap_candidates(projectId)` → 「报告引用了但池里没有」候选（P1：来自 `（待补充）` 子夹、空章节、句式槽位缺文件；P2 接核查）。
- `dd_docket_index(projectId, reportDocFileId)` → 文件级底稿目录数据（实体 × 章节 × 被引段落反向链接），P2 再导出 docx/xlsx。

### 5.4 skill `due-diligence`

prompt.md 工作流：确认主体清单 → `dd_ingest`（等进度）→ 读入库报告 → 学模板（`docx_inspect_template`）→ 按章节循环：`dd_chapter_materials` → 表格段 `dd_table` + `doc_insert_table` → 事实句 `dd_phrase` + `doc_insert_at_cursor`/流式 → **每句 `doc_link_evidence`** → 找不到底稿写「【待补：…】」并记缺口 → 章末 `doc_list_evidence` 自查。约束段挂消息末位（`prompt-constraints-need-last-position` 记忆）。`skill.yml` `allowedTools` 列全（含 doc_*、dd_*、docx_inspect_template、tag_*、extract_file_text）。

### 5.5 黄金对照跑分器（`golden/`）

- `DD_SAMPLE_ROOT` 指向样本只读目录；跑分器先把底稿池 1 **复制到本机 scratch**（机密不出本机），建一个 IDE 本地文件夹项目，跑 `dd_ingest`，再用 skill 跑 6 章起草（真 LLM），然后断言：
  - 去重后实体数 ∈ [80, 90]；骨干 6 文件各出现在 ≥2 章 `dd_chapter_materials`；
  - 6 章黄金对照表（本机期望文件 `golden/expect.local.json`，**不入库**）：必含文件全部出现在该章材料，不应出现的不出现；
  - 缺口候选 ⊇ 人工 8 条（语义匹配：用 `llm.complete` 判同义，阈值 0.8）；
  - 误入文件（池根目录意向书 pdf）`chapter=null`；`10-专业机构/` 三份视频 `dupOf` 指向 `1-6/` 视频；
  - 底稿目录条目数 = 实体数，每条 `referencedBy.length ≥ 0` 且骨干文件 ≥ 2；
  - 出稿 docx 用 `docx_inspect_template` 回读：楷体 GB2312 12pt / `一、（一）1.` / 单元格级边框 / 目录域 `\o "1-2"`。
- 输出 `golden/report.local.md`；交付时只报数字，不报文件名。

## 6. 验证

- 主仓：`TemplateToolsTest`（fixtures 一份合成模板 docx）、`DocxStyleHelperProfileTest`（写后 docx4j 回读对拍）、`HouseProfileParityTest`、`PluginHostImplTest`（鉴权/配额/任务状态机）、`PluginJobServiceTest`、`DocumentEditToolsEvidenceTest`（doc_link_evidence 四件套 + unverified）、lowa-e2e 新组（`set_style_profile` → `get_formatting` 对拍；`insert_toc`）。
- 插件仓：单测（编号解析/去重/版本链/状态词各一组向量）、`golden/` 本地跑。
- 真机走查：拖模板 → 学 → 概览页一行 → 起草一章 → 审阅面板证据页全部 unverified → 点击定位。

## 7. 刻意不做（P1）

核查与 relation 自动判定（P2）；导出 docx/xlsx 四件套（P2）；网核（P3）；LOWA 兜底读模板（P3）；多报告共池的投影 UI（P2 用 EvidenceLink 反查派生）；设置页「团队默认画像」UI（只留 SystemSetting 键）。

## 8. 已确认

1. 私有仓 `zeweihan/aiworkdeck-dd-plugin`（名字暂定）由维护者自建；建好前插件代码在本机 scratch 目录起草，不进主仓。
2. `plugin-api` 先只发本地 `~/.m2` + 随主仓源码；GitHub Packages 等插件仓 CI 需要时再加。
