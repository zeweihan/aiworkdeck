# EvidenceLink P0 设计（内置底座）：文字 ↔ 文件关联事实表

日期：2026-08-21 ｜ 母卡：dev-board#100 ｜ 上游：`2026-08-21-due-diligence-module-proposal.md` §1 / §5 / §7
状态：brainstorm 定稿（四项拍板见 §0），待维护者过目后进 writing-plans。

本文只定 P0——平台内置、不门控的部分。尽调插件（P1 起）只是它的第一个消费方。

---

## 0. 本次拍板（2026-08-21 21:55）

| 问题 | 结论 |
|---|---|
| 存储形态 | **新表** `evidence_link` + `evidence_link_target`，启动时幂等迁移 `doc_file_link`；旧表只读保留一个发版周期 |
| 拖到文字上无选区时 | **必须先选中**，toast「先选中文字」；不做拖放点→光标的合成点击 |
| 查验方法选择 | 建链即成，编辑器内浮动小条（默认「书面审查」，五个 chip 可改，3s 自动收起） |
| SDK 锚点来源 | `anchor:{selection:true}`（宿主当前选区）与 `anchor:{quote}`（宿主 `find_text_locations` 取唯一命中）两种 |

其余均按总方案：书签锚点、目标位置子表、双向查询、状态机、改字弹窗、`filelink&t=`、审阅面板「证据」页、稳定性前 6 条、lowa-e2e 大文档基线组。

---

## 1. 数据模型

### 1.1 `evidence_link`（主表，一条 = 报告里一个锚点）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | |
| `project_id` | bigint, not null, idx | |
| `doc_file_id` | bigint, not null, idx | 报告所在 `ProjectFile.id`（不再用 wpsFileId 软引用） |
| `link_key` | varchar(64), not null | **= 文档内书签名**。新建 `EVID_<26 位 ULID>`（31 字符，低于 Word 40 字符上限）；迁移行保留原 `lk_*`。唯一索引 `(project_id, link_key)` |
| `anchor_text` | varchar(1000) | 锚点文字快照（截 1000） |
| `anchor_hash` | char(64) | `sha256(normalize(anchorText))`，normalize = 去全部空白 + 全角/半角标点归一 + NFKC |
| `section_path` | varchar(512), idx | 书签所在标题链，`一/（二）/3`；由 worker 派生，不可靠时为空 |
| `section_title` | varchar(512) | 最近一级标题文字（展示用） |
| `status` | varchar(16), not null | `active` / `unverified` / `stale` / `orphan`（§1.3） |
| `created_by_kind` | varchar(8), not null | `human` / `ai` / `plugin` |
| `created_by` | bigint | userId |
| `created_at` / `updated_at` / `checked_at` | timestamp | `checked_at` = 最近一次 worker 核对书签的时间 |

### 1.2 `evidence_link_target`（子表，一条 = 一个底稿位置）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | 即 `filelink&t=` 里的 `t` |
| `link_id` | bigint, not null, idx | FK → evidence_link（应用层级联删除） |
| `file_id` | bigint, not null, idx | `ProjectFile.id`，必须属于同项目（IDOR 校验沿用） |
| `locator_json` | text | §1.4，可空 = 整个文件 |
| `locator_hash` | char(64), not null | sha256(canonical locatorJson)，空 locator 记 `-`；只为唯一索引服务 |
| `relation` | varchar(16), not null | `supports` / `contradicts` / `partial`；默认 `supports`。**`contradicts` 必须有真实 target**——与 evidence.retrieve.v1 的 ClaimLink 不变式一致，「查无此据」用缺口清单表达，不用 contradicts |
| `method` | varchar(16) | `written_review`（书面审查）/ `written_statement`（书面说明）/ `web_check`（网络核查）/ `third_party`（第三方材料）/ `interview`（访谈）；可空 |
| `confidence` | smallint | 0-100，人工建 = null |
| `note` | varchar(512) | 自由备注 |
| `sort_order` | int | 同一 link 内显示顺序 |
| `created_by_kind` / `created_by` / `created_at` | | 同主表 |

唯一索引 `(link_id, file_id, locator_hash)`，`locator_hash` = sha256(canonical locatorJson)（空 locator 记 `-`），防同位置重复挂。

### 1.3 状态机

```
          建链(human/plugin)            建链(ai)
               │                          │
               ▼                          ▼
            active ◄── 用户「保留关联」── unverified
               │  ▲                         │
   anchorHash 变│  │用户「保留关联」          │anchorHash 变
               ▼  │                         ▼
             stale ─────────────────────► stale
               │
   书签不在了   ▼（任何状态）
             orphan ── 用户「重新指定」→ active（换 linkKey 书签）/ 删除
```

- 状态**只由 worker 核对结果与用户动作驱动**，后端不猜。
- P2 的核查服务会加 `verified_at` 与按 relation/confidence 回写，不改这张状态机。
- `orphan` 不自动删：它是「这段文字被删了但底稿还在」的证据，审阅面板列出来让用户处置。

### 1.4 `locatorJson` 分型 schema

`type` 判别；**页码 1 基**；坐标 0..1 归一化（相对该页/图片的宽高）；毫秒整数。未知字段忽略不报错，缺 `type` 视为整文件。

```jsonc
{ "type": "pdf",   "page": 3, "quote": "统一社会信用代码 91…", "rects": [{ "page": 3, "x": 0.12, "y": 0.30, "w": 0.60, "h": 0.04 }] }
{ "type": "docx",  "bookmark": "EVID_…", "quote": "…", "paragraphIndex": 57 }   // paragraphIndex 0 基，与 doc_* 一致
{ "type": "image", "rect": { "x": 0.1, "y": 0.2, "w": 0.3, "h": 0.1 } }
{ "type": "media", "startMs": 125000, "endMs": 143000 }
{ "type": "web",   "url": "https://…", "capturedAt": "2026-08-21T10:00:00+08:00", "rect": { … } }
{ "type": "sheet", "sheet": "资产负债表", "cell": "C12" }
```

- `web` 的 `url/capturedAt` 同时落 `ProjectFile.metaJson`（P0 新增 text 列 `meta_json`，`{sourceUrl, capturedAt, provider}`），locator 里的是该 link 视角的冗余，便于单条导出。
- P0 定位能力：`docx` 走书签/quote；`pdf` 走 `#page=`；`image` 画框；`media` seek。pdf 引文级叠加高亮（`rects`）P3 接 pdf.js 时启用，P0 只存不画。

### 1.5 旧数据迁移

`EvidenceLinkMigrationRunner`（ApplicationRunner，幂等，`evidence_link` 非空或 `doc_file_link` 不存在即跳过）：
- 每行 `doc_file_link` → 一条 `evidence_link`（`link_key` 原值，`doc_file_id` 按 `(project_id, wps_file_id)` 反查 `ProjectFile`，查不到的行跳过并 WARN 计数）+ `fileIdsJson` 每个 fileId 一条 target（`locator_json` 空、`relation=supports`、`method` 空、`created_by_kind=human`）；`status=unverified`（还没核对过书签）。
- 文档里旧链接只有字符属性没有书签：worker 新原语 `adopt_legacy_links` 在文档打开后（`ready` 之后、首轮 `check_link_anchors` 之前）扫全文 `HyperLinkURL` 含 `filelink?k=` 的 run，对没有同名书签的就地套书签（名 = k）。之后状态由正常核对得出。
- 旧表、旧 `DocFileLinkController`（`/api/projects/{pid}/doc-links`）保留但**改为只读代理到新 Service**（前端老版本仍能点开链接），下个发版周期删。

---

## 2. 后端

### 2.1 `EvidenceLinkService`（单一出口）

```
create(userId, projectId, docFileId, linkKey?, anchorText, sectionPath?, sectionTitle?, createdByKind, targets[])  → LinkView
addTargets(userId, projectId, linkKey, targets[])                                                                → LinkView
updateTarget(userId, projectId, targetId, {relation?, method?, confidence?, note?, locatorJson?})                → TargetView
removeTarget(userId, projectId, targetId)
delete(userId, projectId, linkKey)
getByKey(userId, projectId, linkKey)                                                                              → LinkView
listByDoc(userId, projectId, docFileId, {status?, sectionPath?})                                                  → LinkView[]
listByFile(userId, projectId, fileId)                                                                             → LinkView[]   // 反查：这份底稿被哪些段落引用
listBySection(userId, projectId, docFileId, sectionPathPrefix)                                                    → LinkView[]
listByParty(userId, projectId, docFileId, tagId)                                                                  → LinkView[]   // 主体视图：targets.file 挂了该 PARTY 标签
reportAnchors(userId, projectId, docFileId, [{linkKey, exists, text}])                                            → {changed:[linkKey…]}   // worker 核对结果回写状态
keepAnchor(userId, projectId, linkKey)                                                                            // stale→active，刷新 anchorText/anchorHash
rebind(userId, projectId, linkKey, newLinkKey, anchorText)                                                        // orphan→active，换书签
refCounts(projectId, fileIds[])                                                                                   → Map<fileId, count>   // 文件树「被引用 N 次」角标
```

- 权限：读 = `hasReadPermission`，写 = `hasWritePermission`（不再创建者私有）。
- `LinkView = {id, linkKey, docFileId, anchorText, sectionPath, sectionTitle, status, createdByKind, createdAt, updatedAt, targets:[{id, fileId, file:{id,name,fileType,parentId,isDeleted}, locator, relation, method, confidence, note}]}`。
- 文件删除/回收站：`ProjectFileService` 软删文件时**不删 link**，`LinkView.file.isDeleted=true` 由面板灰显；彻底删除时级联删 target，target 空的 link 标 `orphan`（这是唯一由后端改状态的例外，因为 worker 看不见文件系统）。

### 2.2 REST `/api/projects/{pid}/evidence-links`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/` | create |
| GET | `/{linkKey}` | getByKey |
| GET | `/?docFileId=&status=&sectionPath=` | listByDoc |
| GET | `/?fileId=` | listByFile |
| GET | `/?docFileId=&partyTagId=` | listByParty |
| POST | `/{linkKey}/targets` | addTargets |
| PATCH | `/targets/{targetId}` | updateTarget |
| DELETE | `/targets/{targetId}` | removeTarget |
| DELETE | `/{linkKey}` | delete |
| POST | `/anchors/report` | reportAnchors（worker 核对结果） |
| POST | `/{linkKey}/keep` | keepAnchor |
| POST | `/{linkKey}/rebind` | rebind |
| GET | `/ref-counts?fileIds=` | refCounts |

错误信封沿用项目惯例；付费闸门不在这里（内置能力）。

### 2.3 AI 工具面

P0 **不**加 `doc_link_evidence`（P1 随起草一起上，避免工具先于 prompt 上线被模型乱用）。但 `doc_set_hyperlink` 的 http/https 白名单**在 P0 放行** `https://checkba-internal.local/open?u=checkba://filelink` 前缀（否则 P1 的工具没法复用同一条写链接路径）。

---

## 3. worker（office_thread.js）新原语

| action | 参数 | 返回 | 说明 |
|---|---|---|---|
| `bookmark_selection` | `{name}` | `{success, name, text}` | 给**当前选区**套命名书签；选区空 → 失败；重名 → 失败（不像 insert_link_with_bookmark 那样加 `_n`，linkKey 必须精确） |
| `get_bookmark_context` | `{name}` | `{exists, text, sectionPath, sectionTitle, paragraphIndex}` | 从书签锚点向前扫 `OutlineLevel>0` 的段落拼标题链（最多 6 级），配合 `get_outline` 的同一判据 |
| `check_link_anchors` | `{names[]}` | `{items:[{name, exists, text}]}` | 批量核对；单次 ≤ 200 个，超出由宿主分批 |
| `adopt_legacy_links` | `{}` | `{adopted:[name…], skipped}` | §1.5；只处理 `filelink?k=` 且无同名书签的 run |
| `goto_bookmark` | `{name}` | `{success}` | `anchorRange(name)` + `selectVisibly`；不再借 `set_selection`（它只认 `__ai_anchor_*`） |

- 四件套地雷：`EDITOR_ACTIONS` 白名单 + `toolDisplayNames.js` 不需要（P0 无 AI 工具），但 **`libreofficeExecutorClient.js` 与 `zetaOfficeRelay.js` 的 action 透传不需改**（按名透传）。
- 失败返回同时写 `message` 与 `error`（已知地雷）。
- `check_link_anchors` 在 office 线程上是 O(关联数) 的同步调用：宿主侧节流——`modified` 防抖 3s 或保存后触发，且只核对**当前文档**的 link；单文档超过 200 条分批、批间让路 autosave。

---

## 4. 前端

### 4.1 拖拽建链升级

- 投放区从侧栏 `FileLinkDropZone` 改到**编辑器区域本身**：`LibreOfficeEditor.vue` 外层容器 `@dragover/@drop` 接 `application/x-checkba-file`（文件夹不接）。拖拽进行中整个编辑器描一圈高亮边，提示「松开即关联到选中文字」。原 `FileLinkDropZone` 删除。
- drop 处理（`evidenceLinkActions.js`，从 project-overview.vue 拆出的新模块）：
  1. `get_selection_hyperlink` 读选区；空 → toast「先选中文字」返回。
  2. 选区已带 `filelink?k=` → 复用 linkKey（追加 target）；否则生成 `EVID_<ulid>`，`bookmark_selection(name)` + `set_selection_hyperlink(wrapped url)`。
  3. `get_bookmark_context(name)` 取 sectionPath/sectionTitle。
  4. `POST /evidence-links`（或 `/targets`），target `{fileId, relation:'supports', method:'written_review'}`。
  5. 浮动小条 `EvidenceMethodBar.vue`：锚在编辑器底部，「已关联《文件名》· 方法：[书面审查][书面说明][网络核查][第三方材料][访谈]」，点 chip → `PATCH /targets/{id}`；3s 无操作自动收起；连续拖放只保留最后一条。
- web 包装链接、`checkba://filelink?k=&projectId=` 内层格式**不变**，新增可选 `&t=<targetId>`。

### 4.2 点击链接与定位

- `onLibreOpenUrl` 解包：`k` → `GET /evidence-links/{k}`；带 `t` 且命中 → 直接打开该 target；不带 `t` 且 targets>1 → 现有 `filelink-dialog` 改为列出 target（文件名 + 方法 + 定位摘要）。
- `openFile(file, {locator})`：`fileOpenTabs.openFile` 加可选第二参，tab 对象带 `pendingLocator`；消费方：
  - `LibreOfficeEditor` ready 后：`locator.bookmark` → `goto_bookmark`；否则 `quote` → `find_text_locations` 首个命中 → `set_selection`；消费后清 `pendingLocator`。
  - `FilePreview` pdf：`blobUrl + '#page=' + page`（Chromium 原生 viewer）；image：按 `rect` 叠一个绝对定位高亮框（随缩放联动）；audio/video：`loadedmetadata` 后 `currentTime = startMs/1000`。
- 后端 `sendOpenFileAction(file, locator?)` 同步加字段（P1 AI 工具要用）。

### 4.3 审阅面板「证据」页

`ReviewPanel.vue` 加第三个 tab「证据」（`editor.review.evidenceTab`），内容组件 `EvidencePanel.vue`（独立文件，ReviewPanel 只做 tab 壳）：
- 顶部切换「按章节 / 按主体」+ 状态筛选（全部 / 待核 / 已变 / 失联）。
- 按章节：按 `sectionPath` 分组折叠，组头显示章节标题与计数；按主体：按 targets 文件的 PARTY 标签分组（无 PARTY 的归「未归属」）。
- 每条卡片：锚点文字（截两行）· 状态色点（active 绿 / unverified 灰 / stale 黄 / orphan 红）· targets 列表（文件名 + 方法 chip + 定位摘要如「第 3 页」「02:05」）。
- 动作：点卡片 → `goto_bookmark`；点 target → 打开并定位；stale 卡「保留关联」；orphan 卡「重新指定」（进入「选中新文字后点确认」模式）/「删除」；target 行可改方法、删。
- 数据：打开 tab 时 `listByDoc(docFileId)` 一次 + 监听 `evidence-changed` 事件刷新；状态变化来自 4.4。

### 4.4 改字 stale 检测与弹窗

- `LibreOfficeEditor.vue`：`onDocModified` 之后起 3s 防抖（与 autosave 共用 `_cmdBusy` 让路规则），到点或 `flushSave` 前调 `check_link_anchors(names)`（names = 当前文档 `listByDoc` 缓存里的 linkKey）。
- 对比本地缓存的 `anchorHash`（归一化算法前后端各一份，**对拍测试**钉住同一份向量）：变了 → `POST /anchors/report` → 更新缓存状态 → emit `evidence-changed`。
- 弹窗 `EvidenceStaleBar.vue`：编辑器顶部非阻塞条「这段文字有 N 份底稿，文字已改动：[保留关联] [查看底稿] [忽略]」。合并规则：同一 linkKey 在一次连续编辑（两次 `modified` 间隔 <3s 视为连续）里只弹一次；多条同时 stale 合并为一条「N 段文字已改动」并展开列表；忽略 = 本会话不再为该 linkKey 弹，状态仍是 stale，面板照常亮黄。
- 审阅面板关闭时也要弹（弹窗不依赖面板打开）。

### 4.5 文件树

- 「被引用 N 次」角标：树刷新时 `GET /ref-counts?fileIds=`（只查当前可见节点，分批 ≤ 200）。
- 零引用文件**不**在 P0 灰显（要等 P1 入库整理后才有意义，灰显会误伤普通项目）。

---

## 5. 三方 Web 插件 SDK v1 新增（契约只加不破）

| 方法 | 参数 | 返回 | 权限 |
|---|---|---|---|
| `evidence.link` | `{ anchor: { selection: true } \| { quote }, docPath?, targets: [{ path, locator?, relation?, method?, note? }] }` | `{ linkKey, targetIds: [] }` | `editor` |
| `evidence.list` | `{ docPath?, path?, sectionPath?, status? }` | `{ links: [{ linkKey, docPath, anchorText, sectionPath, status, targets: [{ targetId, path, locator, relation, method }] }] }` | `file_read` |
| `evidence.locate` | `{ linkKey, targetId? }` | `{}` | `editor` |

- 路径口径与 `files.*` 一致（项目内相对路径），宿主负责 path ↔ fileId。
- `anchor.quote`：宿主 `find_text_locations` 必须**恰好 1 个**命中，0 或多 → `{code:'anchor_ambiguous'}`；`selection:true` 而当前无选区 → `{code:'no_selection'}`。错误码新增这两个 + `not_found`（链接/文件不存在）。
- `docPath` 缺省 = 当前聚焦窗格打开的 Word 文档；没有 → `no_active_document`。
- 四处同步：`sdk/plugin-sdk/awd-plugin-sdk.js`（源头）、官网 `lib/plugin-template.ts` 内联副本、`examples/hello-web-plugin/` 副本（三者 sha256 一致的测试要更新）、`PluginPane.vue` 宿主实现；官网模板的宿主模拟器补这三个方法的假实现。
- `docs/PLUGIN_SPEC.md` §8 方法表同步。

---

## 6. 稳定性前 6 条（P0 必修，按盘点编号）

| # | 改动 | 验证 |
|---|---|---|
| 1 | `MAX_IMPORT_ENTRIES` 3000 → 30000，超出时 `truncated` 进对账结果 + 前端一次性 toast「本次导入截断，N 项未纳入」；对账改为按 watcher 事件子树 | 单测造 3001 条目录 → 全部入库；30001 条 → 截断提示可见 |
| 2 | `createFile/createFolder` 的同级全量扫描 → `select max(sort_order)` 单查；同名冲突 → 服务端统一加 ` (n)`（`createFile` 新增 `onConflict=rename\|fail` 参数，默认 `rename`，老调用点显式传 `fail` 保持行为） | 单测：300 次同名 create 各得唯一名；查询计数断言 |
| 3 | `find_replace` 等批量改稿：worker 超过 50 命中分批（每批 ≤ 30）并经 relay 回传进度；`EditorBridgeService` 按 action 分级超时（open/stream 180s、批量 120s、其余 30s）；取消 = 后续批次不再执行 | lowa-e2e 大文档组：150 命中 < 8s |
| 4 | `apply_house_style` 按段落范围分批（每批 500 元素）+ `truncated` 字段 + 进度 | 大文档组：921 段不超时、无 truncated |
| 5 | `get_document_text` 段落索引缓存：一次建 `index → XTextRange` 数组，`modified` 时失效、下次按需重建；`total` 缓存 | 大文档组：第二次翻页 < 300ms |
| 6 | `FileTree.vue` 首屏只拉根层、展开按 parentId 拉；超过 100 子项的文件夹窗口化渲染；`buildTreeView` 先按 parentId 分组 | 单测 + app-e2e 现有旅程不红 |

---

## 7. lowa-e2e「大文档基线组」

- 夹具生成脚本 `frontend/tests/lowa-e2e/fixtures/gen-big-doc.py`（python-docx + PIL，150 页/30 表/20 图，按稳定性盘点附录 A），产物不入库、首次运行生成到 scratch。
- 组内步骤：`load_document` / `get_document_text` 两次 / `find_text_locations` 600 命中 / `find_replace` 修订 150 命中 / `apply_house_style` / `export_document` / 连续 30s 无 `modified`。
- **三次取中位数**，阈值 = 基线 × 3（同机抖动 2 倍已实证）；`find_replace` 150 命中 < 8s、`apply_house_style` 不超时且 `truncated=false`、`get_document_text` 二次 < 300ms 是硬阈。
- `LOWA_E2E_BIG=1` 时才跑（默认 e2e 基线不拖慢）；CI 不跑，发版前本机跑。

---

## 8. 测试与验证

- 后端：`EvidenceLinkServiceTest`（建/追加/反查/状态机/权限/IDOR/迁移幂等）、`AnchorHashParityTest`（与前端共用 `fixtures/anchor-hash-vectors.json`）、`ProjectFileServiceConflictTest`、`LocalProjectImportCapTest`。
- 前端：`anchorHash.test.js`（同一向量）、`evidenceLinkActions.test.js`（drop 四步与 method bar）、`EvidencePanel` 分组逻辑单测、`check:emits`。
- worker：lowa-e2e 新组「证据锚点」（选中 → bookmark_selection → 改字 → check_link_anchors 变 → 删字 → exists=false → adopt_legacy_links 对旧链接套书签）+ 大文档组。
- SDK：`sdk/plugin-sdk` 的 parity 测试加三方法；官网模拟器同步。
- 真机走查（交付时列给维护者）：拖拽到文字 → 小条改方法 → 点链接定位 pdf 第 N 页 → 改字弹窗 → 面板按章节/主体切换。

---

## 9. 刻意不做（P0）

AI 工具 `doc_link_evidence`（P1）；pdf 引文叠加高亮与 pdf.js（P3）；零引用文件灰显（P1 后）；核查服务与 relation 自动判定（P2）；拖放点→光标合成点击（已拍板不做）；新左栏面板；`CHAPTER` 标签类型（章节归属由反查派生）。

---

## 10. 实施切分（供 writing-plans）

可并行的独立单元（各一棵 worktree）：
A. 后端实体/Repo/Service/Controller/迁移 + 测试（契约文件，主模型）
B. worker 五原语 + lowa-e2e 证据锚点组（契约文件，主模型）
C. 前端拖拽建链 + method bar + 链接定位 + openFile(locator)（依赖 A/B 的契约，可按契约先写）
D. 审阅面板「证据」页 + stale 弹窗（依赖 A/B 契约）
E. SDK 三方法 + PluginPane 宿主实现 + 官网同步（契约文件）
F. 稳定性 #1/#2/#6（后端 + FileTree，机械，Sonnet 可做；校验自己做）
G. 稳定性 #3/#4/#5 + 大文档基线组（worker 性能改造，主模型）

合并顺序：A → B → (C, D, E, F, G 任意，后合者 rebase 重跑)。每个单元一个 PR；A/B 合并后 `.claude/agents/ai-doc-bridge.md` 的契约段随 PR 落地。
