# 尽调报告「勾稽」能力盘点（dev-board#100，只读）

日期：2026-08-21。范围：律师单人用的尽调报告模块所依赖的「报告段落 ↔ 底稿文件」勾稽与核查能力，现状精确到文件:行号。与面向客户的「尽调清单」插件（`DdRequest`/`DdItem`，`DdService.java`）无关，本文不盘它。

行号以分支 `claude/ai-legal-due-diligence-optimization-822c5a`（commit 735c919a）为准。姊妹文档：`2026-08-21-due-diligence-lite-evaluation.md` §1.3/§1.4（同一需求的早期评估，本文在它之上把数据模型与保活/反查/触发点钉死）。

---

## 0. 一页结论

- 现有「拖拽关联」的真身是 **选区超链接 + 一张 `doc_file_link` 表**：文档里写 `https://checkba-internal.local/open?u=checkba://filelink?k=<linkKey>`，表里记 `linkKey → fileIds[]`。**没有目标文件内的位置**（页码/坐标/时间点），**不能反查**（只能按 linkKey 精确查），**不随文档编辑保活**（靠字符属性跟着文字走，文字没了记录就成孤儿，无人清理）。AI 工具面完全不感知它。
- tag 系统是纯文件级多对多，三型只是 `String type` 白名单；**与 DocFileLink 零交集**；文件树不能按标签筛选（筛选在搜索面板），文件夹不能打标签（前端挡、后端不挡）。「主体 = PARTY 标签、章节 = 文件夹」单章节够用；多章节归属现模型表达不了（`parentId` 单值）。
- 定位能力：docx 侧书签锚点体系完整（`find_text_locations` → `set_selection`），但 `openFileLinkTarget` 打开目标文件后**一个定位参数都不传**；PDF 是 iframe 原生渲染，无跳页/叠加高亮入口（`pdf_highlight` 是永久写入）；图片只有缩放；音频有进度条可 seek 但无外部时间点参数。「一键打开底稿并定位」今天停在「打开文件首屏」。
- 改文字提示新底稿：全链路**没有任何段落级变更事件**。最细粒度的信号是 worker `modified` → 前端 autosave → `POST /api/files/{id}/upload` → `onChangeSignal(projectId)`（项目级）。修订接受只 `$emit('changed')` 给面板。最合适的挂点是 worker 侧「按书签锚点比对」而非后端。
- 外部截图落地：`WebFavorite`（`favorites/<userId>/` 独立存储）与 `ProjectFile` 是**两套存储**；已有「网核」链路（右键 → capturePage → createFavorite → `insert_link_with_bookmark` 写 `checkba://webfav?id=`）可抄，但要落到项目文件夹并建 DocFileLink 需要走 `ProjectFileService.createFile/createOrUpdateFile` + `DocFileLinkService.createOrAppend`，两者后端内部均可直接调用。

---

## 1. 拖拽关联

### 1.1 现状

| 环节 | 文件:行号 | 说明 |
|---|---|---|
| 实体 | `backend/src/main/java/com/checkba/model/entity/DocFileLink.java:17-66` | 表 `doc_file_link`，唯一索引 `(project_id, link_key)`（:18）。字段：`projectId`、`userId`（创建者）、`docWpsFileId`（关联所在文档，:35）、`linkKey`（写进超链接的 key，:41）、`anchorText`（选区文本快照，注释「仅用于展示/排查」，:47）、`rangeStart/rangeEnd`（注释「仅用于排查；文档编辑后会变化，不作为定位依据」，:50-56）、`fileIdsJson`（JSON 数组，一条链接可挂多文件，:62）。**没有任何「目标文件内位置」字段**（页码/引文/坐标/时间点）。 |
| Repository | `repository/DocFileLinkRepository.java:9` | 唯一方法 `findByProjectIdAndLinkKey`。无按 fileId 反查、无按 docWpsFileId 列表。 |
| Service | `service/DocFileLinkService.java:46-107` | `createOrAppend`：校验 fileIds 归属项目（:61-67），linkKey 空则生成 `lk_<uuid>`（:69），已存在则合并 fileIds 去重（:90-102）；**只允许创建者修改**（:83-85）。`getByKey`（:110-123）**只允许创建者读取**（:119-121），返回 `files` 元数据时 `findById(...).ifPresent` 静默跳过已删文件（:145）。 |
| Controller | `controller/DocFileLinkController.java:12-51` | `POST /api/projects/{pid}/doc-links`（:19）、`GET /api/projects/{pid}/doc-links/{linkKey}`（:41）。无 DELETE、无列表。 |
| 前端 API | `frontend/src/services/api.js:1754-1768` | `createDocFileLink` / `getDocFileLink`。 |
| 拖拽源 | `frontend/src/components/FileTree.vue:431-434, 2442-2494` | `dragstart` 写 `application/x-checkba-file` / `text/checkba-file-json`（:2473-2475）+ `$emit('file-drag-start')`，仅非文件夹。 |
| 投放区 | `frontend/src/components/FileLinkDropZone.vue:74-78`；挂载 `project-overview.vue:684` | **不是拖到编辑器画布上的某处**，是拖到侧栏的「左/右投放区」，只回传 `{side}`。仅当某窗格打开的是 Word 类文档时显示（`hasOpenWpsWord`）。 |
| 建关联 | `project-overview.vue:3788-3865`（`createWpsSelectionFileLink`） | ① `get_selection_hyperlink` 读**拖拽瞬间编辑器里的当前选区**文本与既有链接（:3802）；无选区提示「先选中文字」（:3810-3813）。② 选区已带 `checkba://filelink` 则复用 linkKey（:3816-3830），否则生成 `lk_<ts>_<rand>`，拼 `checkba://filelink?k=&projectId=`，`wrapWpsInternalLink`（:3735-3742）包成 `https://checkba-internal.local/open?u=…`（常量 `frontend/src/config/workbenchActions.js:17,23`），调 `set_selection_hyperlink` 写入（:3836-3843）。③ `createDocFileLink` 入库，**`rangeStart/rangeEnd` 恒传 `null`**（:3851-3859）。 |
| worker 写链接 | `frontend/src/zetaoffice/public/office_thread.js:3126-3140`（`set_selection_hyperlink`） | 只给选区 cursor 设 `HyperLinkURL` 字符属性，**不建书签**。 |
| `insert_link_with_bookmark` | `office_thread.js:3164-3186` | **这是「网核」链路用的，不是文件拖拽用的**：光标处插入文本 + 可选超链接 + 一个 `com.sun.star.text.Bookmark` 包住插入的 run，书签名 `(bookmarkName || 'MARK_'+ts)` 去非法字符、重名加 `_n`。调用方 `project-overview.vue:4286-4311`（`handleWebLinkDrop`，bookmarkName=`WEB_EVID_<favId>`，url=`checkba://webfav?id=`）。 |
| 点击回宿主 | `office_thread.js:3192-3212`（`get_hyperlink_at_cursor`）；`components/LibreOfficeEditor.vue:452-453`（`open-url`）；`project-overview.vue:839/858/987`（`@open-url="onLibreOpenUrl"`） | LO WASM 不触发超链接激活，页面监听画布点击后问 worker 光标所在链接。 |
| 解包与打开 | `project-overview.vue:2850-2872`（桌面 `onOpenInternal`）与 `:2927-2949`（web 包装链接），两处同逻辑 | `getDocFileLink` → 0 个文件 toast「关联文件不存在」；1 个直接 `openFileLinkTarget`；多个弹 `filelink-dialog`（:1436-1461）逐项 `openFileLinkTarget(f.id)`（:1446）。 |
| `openFileLinkTarget` | `project-overview.vue:3870-3886` | `getFileDetail(pid, fid)` → `this.openFile(file)`。**没有页码/锚点/时间点参数**；`openFile`（`pages/project-overview/fileOpenTabs.js:108-160`）签名只收 `file` 对象。 |
| AI 侧 | `service/ai/tools/DocumentEditTools.java:1276-1299`（`doc_set_hyperlink`） | 只收 http/https（worker `set_hyperlink_at_anchor` :3150 同样卡白名单），不落 DocFileLink。全 `service/ai/**` 无任何文件引用 `DocFileLink`。 |

**保活**：关联「跟随」完全依赖文档格式对「带 HyperLinkURL 属性的文字 run」的保真——段落整体移动、行内改字能带着走；选区被整段删除重打、修订拒绝把插入文字回退、或把这段文字剪切为纯文本再粘回时，链接属性消失。后端 `DocFileLink` 行**不会被感知、不会被清理**（全仓 grep `DocFileLinkRepository` 只有 Service 内两处调用，无文件删除/回收站/版本退回钩子）。版本退回/检查点恢复（`service/ai/DocumentCheckpointService.java:79-99`）按 fileId 整体覆盖字节，`docWpsFileId` 不变，记录仍查得到，但链接文字是否还在取决于快照。

**反查**：两个方向都没有。给定 fileId 查「报告里哪些段落引用了它」——Repository 无方法、Controller 无端点、UI 无列表；给定一段文字查底稿——只有点击后的被动弹窗，没有「选中段落 → 右栏列底稿」的主动视图。

### 1.2 距离目标的缺口

1. 关联没有目标文件内位置（页/坐标/时间/引文），点开只能到首屏。
2. 不可反查（fileId → 段落、文档 → 全部关联），做不了「底稿被哪些结论引用」「这份报告哪些段落还没底稿」两张表。
3. 无保活/孤儿感知：无法回答「这段文字的链接是否还在」「这条记录还对应得上文字吗」。
4. `anchorText` 只是快照，不参与定位；`rangeStart/End` 名存实亡。
5. 权限「创建者私有」（:83-85, :119-121）——律师单人用暂不碍事，但与同事共看报告时会报「无权限」。
6. AI 不能建、不能读、不能核对关联。
7. 投放口径反直觉：拖到侧栏投放区而非文字上，且依赖拖拽前的选区。

### 1.3 建议的最小改动

- **锚点从字符属性升级为书签**：建关联时同时给选区套一个命名书签（名字就用 linkKey，`insert_link_with_bookmark` 已有同款写法，改成「对既有选区套书签」即可，`office_thread.js` 新增一个 `bookmark_selection(name)` 或给 `set_selection_hyperlink` 加 `bookmarkName` 参数）。书签随 docx 往返（`office_thread.js:104-110` 注释已证实 bookmark round-trip OOXML），段落移动/改字跟着走，`getBookmarks().hasByName(linkKey)` 一句就能判断关联是否还活着。
- **表加位置列**：`DocFileLink` 加一个 `locatorJson`（每个 fileId 一条：`{fileId, page, rect?, quote?, timeMs?}`），或拆成子表 `doc_file_link_target`。先做 `page` + `quote` + `timeMs` 三个字段即可覆盖 pdf/图片/音频。
- **Repository 补两个查询**：`findByProjectIdAndDocWpsFileId`（列文档全部关联）、`findByProjectIdAndFileIdsJsonContaining`（或改存子表后按 fileId 查）。Controller 补 `GET /doc-links?docWpsFileId=`、`GET /doc-links?fileId=`、`DELETE /{linkKey}`。
- **AI 工具**：新增 `doc_link_source(anchorId, fileIds[], locator?)`（建 DocFileLink + 写书签 + 设内链，一次完成）与 `doc_list_links(docWpsFileId?)`；`doc_set_hyperlink` 的 http/https 白名单加 `https://checkba-internal.local/` 前缀例外。
- 权限放宽为项目成员可读（Service 注释本就写着「后续可扩展为项目内共享」）。

---

## 2. tag 系统

### 2.1 现状

| 项 | 文件:行号 | 说明 |
|---|---|---|
| Tag 实体 | `model/entity/Tag.java:19-78` | 表 `project_tag`，唯一 `(project_id,name)`。`type` 是 `String`（:61），注释「NORMAL/PARTY/ISSUE；null 视同 NORMAL」，白名单校验在 `service/TagService.java:26-33`，默认色 :19-21/:87-95。`isSystem`（:55）标自动打标。 |
| FileTag | `model/entity/FileTag.java:19-53` | 表 `project_file_tag`，唯一 `(file_id,tag_id)`，字段 `fileId/tagId/createdBy/createdAt`。**无 anchor/range 字段**——标签只能挂文件，不能挂段落。一个文件多标签天然支持。 |
| Repository | `repository/TagRepository.java:14-30`；`repository/FileTagRepository.java:13-43` | `findByTagIdIn`（按标签列文件，搜索用）、`findByFileIdIn`（批量取标签）。**无按 type 查询**，分组全在应用层。 |
| REST | `controller/TagController.java:35-71`（标签 CRUD，`/api/projects/{pid}/tags`）；`controller/ProjectFileController.java:510-541`（`POST/DELETE /files/{fileId}/tags[/{tagId}]`）；文件列表 :89-92 批量 populate `tags` | 挂标端点**不判 isFolder**——后端允许给文件夹打标签。 |
| AI 工具 | `service/ai/tools/TagTools.java:41-181` | `tag_list`（按三型分组）、`tag_file(fileId, tagName, type)`（get-or-create，撞同名不同型时复用不改型）、`tag_remove_from_file`。不判文件夹。 |
| 文件树展示 | `components/FileTree.vue:441-443, 578-580` | 每个**非文件夹**文件行左侧 `tag-strip` 渐变色条；右键「管理标签」仅 `!isFolder`（:316）。**文件树没有按标签筛选/分组/排序**（排序只有 name/date/type，:379-389, :987）。 |
| 按标签筛选 | `components/SearchPanel.vue:40-49, 204-286`；后端 `service/ContentSearchService.java:52-84` | 在搜索面板：多选标签 AND 过滤，支持纯标签无关键词搜索；按 PARTY/ISSUE/NORMAL 分组头渲染（`utils/tagTypes.js`）。 |
| 自动打标 | `service/ai/AutoTaggingService.java:53, 137` | 上传完成后 LLM 出 5 个系统标签（`isSystem=true`），有幂等闸。 |
| 与 DocFileLink 交集 | 全仓 grep | 后端零文件同时引用两者；前端只在 `project-overview.vue`/`api.js` 两个聚合文件里同时出现，无功能耦合。 |
| 文件夹模型 | `model/entity/ProjectFile.java:39-45` | 文件与文件夹同表，`parentId` 单值外键 + `isFolder`；无冗余 path 列。移动：`PUT /files/{id}/move`（`ProjectFileController.java:357`）、`POST /files/batch/move`（:309）；AI `move_project_file`/`move_file`（`FileTools.java:545`）/`create_folder`（:643）。 |

### 2.2 对「主体 × 章节」两个正交维度的评估

- 主体用 PARTY 标签：**够**。多对多，一份底稿涉及多个主体就挂多个 PARTY 标签；搜索面板能按其筛。缺的是文件树直接按 PARTY 分组浏览。
- 章节用文件夹：**单章节够**（`股东大会核查/<公司>/01..05` 已是先例）。
- 同一底稿既属第三章又属子公司 B：**现模型可表达**——放进「第三章」文件夹 + 挂 PARTY「子公司 B」，两个维度各走各的载体。
- 同一底稿既属第三章又属第五章（章节维度多值）：**表达不了**，`parentId` 单值；只能复制文件或用 NORMAL 标签冒充章节（失去树形浏览）。
- 「章节」其实是报告的结构而不是文件的属性：真正的章节归属应该由 **DocFileLink 推导**（这份底稿被第三章的段落引用 = 它属于第三章），而不是靠人手把文件挪进章节夹。现状两套系统零交集，这条推导今天做不了。

### 2.3 缺口与最小改动

1. 文件树加「按标签分组/筛选」视图（复用 `ContentSearchService` 的 tagIds AND 过滤或前端就地按 `item.tags` 过滤；`FileTree.vue` 已有 `sortMode` 开关可照抄加 `groupBy`）。
2. 若要章节也成多值维度：`TagService.validateType` 白名单加 `CHAPTER`，`tagTypes.js` 的 `TAG_TYPE_ORDER/COLORS/I18N` 各加一项，`SearchPanel`/`TagSelector` 分组自动跟上（spec 2026-08-20 已预留「加档位=加枚举值」）。但更推荐：**章节归属从 DocFileLink 反查派生**（见 §1.3 补的 `findBy…FileId` 查询），不再让律师手工维护第二份章节归属。
3. 给文件夹打标签只需放开前端三处 `!isFolder`（:316/:441/:578），后端已通。
4. `TagRepository` 补 `findByProjectIdAndType`，`TagService.deleteTag` 补 FileTag 级联清理（:153-166 注释自陈未做）。

---

## 3. 编辑器侧定位 / 高亮

### 3.1 现状

| 载体 | 文件:行号 | 能做什么 | 不能做什么 |
|---|---|---|---|
| docx（LOWA） | `office_thread.js:88-102`（`anchorBookmark`/`anchorRange`，隐藏书签 `__ai_anchor_N`）、`:1665-1700`（`find_text_locations`，返回稳定 anchorId + 前后文，上限 50）、`:1853-1862`（`set_selection`，只收 anchor，拟人滚动选中）、`:3268`（`add_comment` 收 anchor）、`:3465`/`:3527`（`goto_revision`/`goto_comment`，仅 ReviewPanel 用，无 AI 工具）、`:5481-5493`（`clear_anchors`，全仓无自动调用点——`__ai_anchor_*` 书签是否随保存进 docx 未核实） | 文本定位、选中、滚动到位、批注，书签体系完整且可命名（`insert_link_with_bookmark` :3181） | `goto` 只支持 start/end（:1842）；宿主打开文件无「带锚点打开」参数：`fileOpenTabs.js:108` 的 `openFile(file)`、后端 `EditorBridgeService.sendOpenFileAction`（:107-123）payload 只有 fileId/fileName/fileType/wpsFileId/trackRevisions/userName，前端 `agentClientActions.js:41-42` 直接 `handleEditorOpenFile`。 |
| PDF 预览 | `components/FilePreview.vue:53-60`（H5 `<iframe :src="blobUrl">`，Chromium 原生 PDF 引擎）、:303-306 | 标准 annotation 可见；`file.wpsFileId` 变化自动重拉 | 无跳页参数（blob URL 后可加 `#page=N`，Chromium viewer 支持，但代码未接）；无叠加高亮层；无文本选区事件回宿主。 |
| `pdf_highlight` | `service/ai/tools/PdfTools.java:101-122`；`service/ai/PdfEditService.java:248-269` | PDFBox 写 `PDAnnotationHighlight` 后 `doc.save` —— **永久写入文件**（`fileEffect=MODIFIED`，有检查点） | 不是叠加层；不能「临时高亮给你看」。`pdf_inspect`（`PdfTools.java:81-96`）逐页返回文本与 `has_text_layer`，页码 0 起，可用于算页码。 |
| 图片 | `FilePreview.vue:67-92, 776-861` | 缩放/平移/百分比 | 无标注、框选、矩形高亮。 |
| 音频 | `FilePreview.vue:117, 381-470`（`new Audio()` 自绘进度条，`onSeekDown` 设 `currentTime`） | UI 内 seek | 无外部 `startTime` 参数；转写 `MeetingTranscriptParser.java:52-81` 的 `Segment` 有 start/end 毫秒，存于 `MeetingRecording.transcriptJson`（`model/entity/MeetingRecording.java:82`）；`components/MeetingRecordingPanel.vue:249-256` 把时间戳渲染成纯文本、无 `@tap`，`togglePlay`（:863-885）只能从头播——时间戳与播放器没打通。 |
| 网核收藏 | `project-overview.vue:2820-2848` | `checkba://webfav?id=` → 打开收藏面板 + `focusFavorite(id)` 卡片高亮 | 只是卡片，不是项目文件。 |

**「从报告某段一键打开底稿并定位」链路今天走到**：点击链接 → 解 linkKey → 查表 → 打开文件（首屏）。断在「打开之后」：DocFileLink 无位置 → `openFile` 无参数 → 各预览器无定位入口。

### 3.2 最小改动

- `openFile(file, {locator})` 加第二参数并透传到标签对象；`LibreOfficeEditor` ready 后若有 `locator.bookmark`/`locator.quote` 调 `find_text_locations`+`set_selection`（worker 原语现成，零改动）；`FilePreview` PDF 分支 `blobUrl + '#page=' + (page+1)`；图片分支按 `locator.rect` 画一个绝对定位的高亮框（只是一层 div）；音频分支 ready 后设 `currentTime=locator.timeMs/1000`。
- PDF 要精确到文字高亮而不改文件：把 iframe 换成 pdf.js 文本层才有叠加能力，属中改，先用 `#page=` 过渡。
- 后端 `sendOpenFileAction(file, locator)` 同步加字段，AI 侧 `doc_open_file` 加可选 `locator`。

---

## 4. 改文字时提示「是否有新底稿」

### 4.1 现有信号与钩子

| 信号 | 文件:行号 | 粒度 | 评估 |
|---|---|---|---|
| worker `modified` | `components/LibreOfficeEditor.vue:447-454, 768-783`（`scheduleAutoSave`） | 文档级布尔（「脏了」） | 最早、最频繁；不知道改了哪段。 |
| autosave 落点 | `controller/FileController.java:362-514`（`POST /api/files/{id}/upload` legacy 分支）→ :474 `refreshProjectKnowledgeIncremental`、:478/:501 `autoTagFile`、:514 `signalChange` | 文件级 | 每次存盘都跑，已踩过「副作用按次累积」的坑（AutoTagging 幂等闸）；挂段落级检查代价高（要重新抽全文比对）。 |
| `onChangeSignal` | `version/WorkSessionService.java:216`（`(long projectId, Long userId, String userName)`）；调用点 `FileController.java:127-130`、`service/ProjectFileService.java:1205-1210`（8 处）、`service/ai/tools/TextFileEditTools.java:209-212` | `(projectId, userId, userName)`，**项目级**，连 fileId 都不带 | 只用于开工作段 + 防抖存档，不是内容事件。 |
| 修订接受/拒绝 | `components/ReviewPanel.vue:156-173`（`resolve_revision` 后 `$emit('changed')`）→ `LibreOfficeEditor.vue:89`（`@changed="onReviewChanged"`）→ `:762` → `onDocModified`（:765） | 面板级 | 被压扁成与打字相同的「文档变脏」；AI 侧 `doc_accept_revision` 等（`DocumentEditTools.java:1143-1194`）同样只转发 worker。worker `list_revisions` 能给每条修订的文本与位置，是补事件的素材。 |
| AI 工具 MODIFIED | `service/ai/AgentOrchestrator.java:250-258`（首个 MODIFIED 前建检查点）、`:356-373`（`applyToolSideEffects`）、`:1647-1665`（`notifyFileChange`：SSE `file_change` + 落 `ConversationFileChange{conversationId,fileName,changeType}`，无 fileId/无 diff） | 轮次级 | 无可订阅的内容事件；全仓唯一 `ApplicationEvent` 是 `MainlineMergedEvent`（`WorkSessionService.java:722`）。 |
| 版本记录 | `version/ProjectRepoService.java:342`（JGit `DiffCommand`，文件级 name-status） | 文件级 | 无段落 diff；docx 是二进制，git diff 无意义。`VersionCompareTab.vue` 走 worker `compare_document` 做的是视觉对比。 |
| 文本抽取 | `service/DocumentTextService.java:37-74`；`service/ai/ProjectRagService.java:71-77`（`refreshProjectKnowledgeIncremental` 实为 `retrieverCache.remove`，懒重建）；`FileController.java:585`（`/compare` 手动一次性全文比对） | 全文字符串，Tika，无缓存 | 可做「上次快照 vs 本次」粗比对，但要自建快照表。 |
| 事实/证据层 | `service/ai/evidence/ClaimLink.java:20-31`（`claimId/evidenceIds/relation/confidence/reviewer/missingEvidence`，纯内存记录，未落库）；`EvidenceItem.java:27-53`（`sourceUri/contentHash/locator`，检索时现算）；`MemoryEvidenceRetriever.java:44-86`（`contentHash=sha256(记忆文本)`，不是来源文件哈希；`sourceUri=checkba://file/<id>`）；`model/entity/MemoryEntry.java:84`（`sourceFileId` 外键，无来源内容哈希/版本）；`model/entity/ProjectProfileField.java:66`（`evidence` 字符串，概览页专用） | 无「报告段落 ↔ 文件」登记 | 概念上最接近（claim ↔ evidence ↔ missingEvidence），但没有持久化也没接编辑器；文件改了不会级联标记相关记忆过期。 |

结论：**没有任何段落级「被改了」的事件**，也没有「段落 ↔ 底稿」的一致性检查。

### 4.2 挂点比较与建议

| 挂点 | 粒度 | 代价 | 判定 |
|---|---|---|---|
| worker 内：每次 `modified` 后（防抖）遍历 linkKey 书签，比对书签当前文本与 `anchorText` 快照 | 段落/句级，精准 | 书签按名取 `getAnchor().getString()` 是 O(关联数) 的同步调用，几百条在 office 线程上要节流（`find_text_locations` 上限 50 的教训，:1669-1671） | **首选**。书签消失 = 底稿链接被删；文本变化 = 提示「这段改过，底稿是否仍支撑」。结果经 `lo-relay` 回宿主，由右栏渲染。 |
| 修订面板：accept/reject 后对涉及的修订区间查有无书签重叠 | 修订级 | 小 | 补充路径，覆盖「接受 AI 改写」这一高风险动作。 |
| autosave 后端：抽全文与上次快照 diff，再按 anchorText 匹配 | 文件级→段落级 | 每次存盘全文抽取；anchorText 相似匹配不可靠 | 不推荐作主路径，可作离线核查（「整份报告哪些关联已失效」报表）。 |
| AI 工具 MODIFIED post-hook | 轮次级 | 小 | 只覆盖 AI 改动，漏人工改动。 |

最小改动：① `DocFileLink` 建关联时写书签（§1.3）；② worker 新增 `check_link_anchors(names[])` 返回 `{name, exists, text}`；③ `LibreOfficeEditor.vue` 在 `modified` 防抖（与 autosave 同节奏）后调用它并 emit `link-anchors-changed`；④ 宿主右栏把「文字已变 / 书签已丢」标黄并给「重新指定底稿」按钮。

---

## 5. 外部截图服务落地

### 5.1 现状

| 路径 | 文件:行号 | 适配度 |
|---|---|---|
| 「网核」既有链路 | `desktop/main/main.js:808-840`（BrowserView 右键「加入网核收藏」→ `capturePage` → `checkba:webmark`）；`pages/project-overview/ocrActions.js:480-575`（组 `meta{kind:'webmark', capturedAt, sourceUrl, sourceHost, title, selection, docFileName}` → `createProjectFavorite` → `insert_link_with_bookmark`） | 概念最接近：截图 + 网址 + 时间 + 落文档标记。但产物是 **WebFavorite，不是项目文件**。 |
| WebFavorite 存储 | `model/entity/WebFavorite.java:12-60`（`sourceUrl/title/content/imagePath/meta`，抓取时间在 `meta` JSON 里）；`service/WebFavoriteService.java:45-71`（base64 → `favorites/<userId>/<uuid>.png`，同一 `StorageService` 但 `storage/ProjectStorageResolver.java:19-26` 把 `favorites/` 列为独立于 `projects/{id}/` 的全局命名空间）；`controller/WebFavoriteController.java:66-89, 108` | 不在文件树、不能被 DocFileLink 引用（它要 fileId）、不能挪进 `尽调/<主体>/<章节>/网核/`；全仓无「收藏转项目文件」代码路径。 |
| 项目文件写入（前端） | `controller/ProjectFileController.java:217-245`（`POST /files/file`，body `{parentId,name,fileType,fileSize}` 先建记录）→ `controller/FileController.java:362`（`POST /api/files/{id}/upload` 传字节） | 两步、面向前端；后端内部不必走 HTTP。 |
| 项目文件写入（后端内部） | `service/ProjectFileService.java:78`（`createFolder(projectId,parentId,name,userId)`）、:184（`createOrUpdateFile(projectId,parentId,name,fileType,size,storagePath,…)`，幂等）、:211（`createFile`）；存储 `storageServiceFactory.getStorageService().save(path, stream)`（`WebFavoriteService.java:71` 同款） | **最适合**：服务内直接 建夹 → save 字节 → 登记 → `signalChange`。 |
| AI `write_file` | `service/ai/tools/FileTools.java:333-390` | 只写文本、只写根目录（子目录要求再 `scan_files`，:369-375），不适合图片。 |
| AI `write_docx` | `FileTools.java:392-419`（`parentFolderId` 走 `service/ai/AiDocxExportService.java:41-122`：`createFile` → `storageService.save(filePath, bis)` → 回填 fileSize） | 有「指定文件夹落盘 + 注册 + RAG 刷新」的完整范式可抄。 |
| 多级文件夹确保 | `FileTools.java:587-614`（`move_file` 内联「逐段查/建文件夹」循环，非公共方法）；`service/meeting/MeetingRecordingService.java:293-304`（`ensureFolder`，仅一级） | 没有公共的 `ensureFolderPath(segments)`，要抽出来。 |
| 外部服务先例 | `pdf_to_word`（`PdfTools.java:279-291`：`createFile` → `Files.move` → `repository.save` → `sendRefreshFilesAction`+`sendOpenFileAction`）、`MeetingRecordingService.exportTranscript`（:220-237，**最贴近**：`ensureFolder` → `uniqueName` → `createFile` → `StorageService.save(bytes)`） | 都是「后端拿到字节 → ProjectFileService 登记 → 刷新」，直接复用。注意 `sendRefreshFilesAction`/`sendOpenFileAction` 依赖 `currentConversationId` ThreadLocal（`EditorBridgeService.java:107-112`），非对话上下文（后台批量导入）调用会静默跳过，文件树不刷新。 |
| 元数据承载 | `ProjectFile.java:27-122` | **无** sourceUrl/capturedAt/meta 列；`DocFileLink` 也没有。WebFavorite 的 `meta` 是唯一现成的 JSON 槽。 |
| 批量与事务 | `DocFileLinkService.createOrAppend` 是普通 `@Transactional` Spring bean，可被任何后端服务注入直接调用（:45-53） | 无批量 API，但一批图片循环调用即可，在一个外层事务里。 |
| 无头截图 | `main.js:1081-1100`（`browser-wait-ready`）、:1175（`ocr-capture-view`）；后端 `service/ai/tools/WebTools.java:194-275`（`browse_url`，Playwright headless，带 SSRF 守卫，只取 `page.content()`，未调 `page.screenshot()`） | 桌面只能截**已打开的 BrowserView**；后端 Playwright 加一行 `screenshot()` 即可出 PNG，是「自产截图」的最小改动点；外部服务返图则不需要。 |

### 5.2 最小改动

- 新建后端 `EvidenceCaptureService.ingest(projectId, userId, items[{url, title, capturedAt, imageBytes, subject, chapter, anchor?}])`：
  1. `ProjectFileService.createFolder` 逐级确保 `尽调/<主体>/<章节>/网核/`（按 name+parentId 查重，`createFolder` 本身不幂等要自己查）；
  2. `StorageService.save("projects/<pid>/…/<host>_<ts>.png")` + `createOrUpdateFile`；
  3. 来源元数据：在 `ProjectFile` 加一列 `metaJson`（或先复用 `tag_file` 打 PARTY=主体 + NORMAL=`来源:<host>`，零表改动但信息弱）；
  4. 若 `anchor` 非空，`DocFileLinkService.createOrAppend` 建关联，返回包装 URL 给调用方写进文档；
  5. `editorBridgeService.sendRefreshFilesAction()` + `workSessionService.onChangeSignal`。
- WebFavorite 与项目文件不合并，只加一个「转为项目文件」动作（读 `imagePath` 字节 → 上面的 ingest），让老的网核卡片也能进文件夹、被 DocFileLink 引用。

---

## 6. 勾稽数据模型现状图（文字版）

```
ProjectFile (project_file)                       Tag (project_tag)
  id, projectId, parentId ──┐单值树               id, projectId, name, color,
  isFolder, name, fileType  │                      type: "NORMAL"|"PARTY"|"ISSUE"|null,
  filePath, wpsFileId, uid  │                      isSystem
  [无 sourceUrl/meta]        │                          ▲
        ▲   ▲               └── parentId ──► ProjectFile │
        │   │                                           │
        │   └──────── FileTag (project_file_tag) ───────┘
        │             fileId ↔ tagId  多对多，文件级，无 anchor
        │             （前端只让非文件夹挂；后端不拦）
        │
        │ fileIdsJson: [fileId,…]（JSON 字符串，无外键）
        │
   DocFileLink (doc_file_link)
     projectId + linkKey (唯一)
     userId（创建者私有）
     docWpsFileId ──────────────► ProjectFile.wpsFileId（报告所在文档，字符串软引用）
     anchorText（快照，不定位）
     rangeStart/rangeEnd（恒 null）
     [无 目标文件内位置：page/rect/quote/timeMs]
        ▲
        │ 文档内字符属性 HyperLinkURL =
        │   https://checkba-internal.local/open?u=checkba://filelink?k=<linkKey>&projectId=
        │ （无书签；文字没了属性就没了，表行无人清理）
   报告 .docx（LOWA 内）
        │
        │ 另一条并行链：insert_link_with_bookmark → 书签 WEB_EVID_<favId>
        │   + HyperLinkURL = …?u=checkba://webfav?id=<favId>
        ▼
   WebFavorite (web_favorite)                ← 与 ProjectFile 无外键、独立存储前缀 favorites/<userId>/
     sourceUrl, title, content, imagePath, meta(JSON: capturedAt/sourceHost/selection/docFileName)

   第三种内部 scheme：checkba://file/<id>（MemoryEvidenceRetriever.java:88，evidence.retrieve.v1 的 sourceUri）
   ClaimLink（内存 record：claimId, evidenceIds[], relation, missingEvidence[]）—— 未落库、未接编辑器

变更信号（均非段落级）：
   worker modified ─► LibreOfficeEditor autosave ─► POST /api/files/{id}/upload
     ─► refreshProjectKnowledgeIncremental / autoTagFile / signalChange(projectId) ─► WorkSessionService.onChangeSignal
   ReviewPanel resolve_revision ─► $emit('changed')（仅刷新面板）
   AgentOrchestrator: 首个 MODIFIED 工具前建检查点（无 post-hook）
```

三套「引用」彼此不通：FileTag（文件级分类）、DocFileLink（段落→文件，无位置、无反查）、WebFavorite（截图卡片，不在文件树）。要做勾稽，核心是把 DocFileLink 升级为「有书签锚点 + 有目标位置 + 可双向查询」的事实表，并让网核截图落成 ProjectFile 走同一张表。
