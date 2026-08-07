---
name: ai-doc-bridge
description: AI↔文档编辑桥接领域。任务涉及 doc_* 编辑原语、EditorBridgeService、editor_command 契约、修订（redline）、文档检查点、EDITOR_ACTIONS/ui_command 白名单时，先读本文档再动代码。
---

# AI↔文档编辑桥接 领域地图

职责边界：AI 侧发出编辑指令 → 编辑器执行 的整条链路。不含编辑器内核本身（那是 doc-editor 领域），不含对话编排（ai-chat 领域）。

## 关键文件

**后端工具原语**
- `backend/src/main/java/com/checkba/service/ai/tools/DocumentEditTools.java` — doc_* 工具原语全集（43 个 @Tool 方法），翻译成 `editorBridgeService.executeEditorCommand(action, params)`。曾名 WpsTools。格式面：doc_format_selection（字符）、doc_set_paragraph_format（对齐/标题级别/行距/段距/缩进）、doc_set_numbering（bullet/decimal/chinese/multilevel）、doc_format_table、doc_insert_table、doc_get_formatting（读格式）、doc_apply_standard_format（全文标准格式化）。
- `backend/src/main/java/com/checkba/util/DocxStyleHelper.java` — write_docx/AiDocxExportService 两条 flexmark 生成路径的样式：`applyStandardFormat()` 在 render 后、save 前调用，与 worker 端 HOUSE 常量同一套律所标准格式规范。
- `backend/src/main/java/com/checkba/service/ai/tools/CheckpointTools.java` — `doc_restore_checkpoint`。
- `backend/src/main/java/com/checkba/service/ai/tools/ToolMeta.java` — `@ToolMeta(displayName/category/fileEffect)`；`fileEffect="MODIFIED"` 是检查点触发依据。

**桥接服务**
- `backend/src/main/java/com/checkba/service/ai/EditorBridgeService.java` — 核心桥接：生成 requestId、经 SSE `client_action` 下发、CompletableFuture 阻塞等前端结果（超时 30s）。曾名 WpsActionService。
- `backend/src/main/java/com/checkba/controller/ai/EditorResultController.java` — `POST /editor-result`（旧别名 `/wps-result`）回调解锁 Future。
- `backend/src/main/java/com/checkba/service/ai/AgentOrchestrator.java` — dispatchTool 在首个 MODIFIED 工具前建检查点（~:168）；doc_open_file 后切 activeFileId（~:179）；流式 token 双发 doc_stream_data/wps_stream_data（~:371-376）。
- `backend/src/main/java/com/checkba/service/ai/AgentStreamHandler.java` — 流式写入编辑器的过滤逻辑（~:69 起）。
- `backend/src/main/java/com/checkba/service/ai/DocumentCheckpointService.java` — run 级快照 `ensureCheckpoint/restore/clearForNewRun`，存 `checkpoints/{conversationId}/{fileId}_{ts}`，恢复后 sendReloadFileAction。

**前端桥接消费**
- `frontend/src/composables/useEditorBridge.js` — 编辑器无关的分发接缝（薄封装），执行器可插拔。
- `frontend/src/composables/libreofficeExecutorClient.js` — **EDITOR_ACTIONS 白名单定义处**（:15-67）+ reqId 关联的 worker port 客户端；白名单外 action 直接拒绝。
- `frontend/src/composables/useAgentStream.js` — SSE 消费：`client_action`（~:447）；doc_stream_data/wps_stream_data 双轨去重（~:467-488）。
- `frontend/src/pages/project-overview/project-overview.vue` — 命令路由中枢：`handleClientAction`（~:5546，双轨去重 latch `_editorContractV2`）→ `handleEditorCommand`（~:5819，打 `__agent:true` 标记后调 executor）。
- `frontend/src/zetaoffice/public/office_thread.js` — worker 端所有 action 的真实 UNO 实现 + UI_COMMANDS 白名单（:321-332）+ 修订机制。
- `frontend/src/utils/toolDisplayNames.js` — 工具名→中文显示名映射表（NAMES 表 :8-97）。**新增工具必须同步加中文名**。

**修订机制（redline）**
- `office_thread.js` 内：`minimalEdits()`（~:212-266，公共前后缀裁剪+有界 LCS 的字符级最小编辑）与 `applyMinimalRedline()`（~:270-307，从右到左应用，只对差异段打修订）；`setRedlineAuthor`（~:1467）：`__agent` → 署名 "AI Workdeck"，否则用户名。

## 核心数据流（以 doc_find_replace 为例）

1. `DocumentEditTools.doc_find_replace` 校验参数 → `executeEditorCommand("find_replace", …)`。
2. 编排器在此前已因 `fileEffect="MODIFIED"` 对 activeFileId 建检查点（幂等，一轮一次）。
3. EditorBridgeService 生成 requestId、Future 入 pendingRequests，经 SSE client_action **双轨各发一份**（先 `tool=editor_command` 后 `tool=wps_command`），然后阻塞 30s。
4. 前端 useAgentStream → project-overview.vue `handleClientAction`（latch 去重）→ `handleEditorCommand` 附 `__agent:true` → executor。
5. executor 校验 EDITOR_ACTIONS → worker postMessage → office_thread.js 执行（RecordChanges=true → 逐处 applyMinimalRedline，失败回退 setString）。
6. 结果按 reqId 回流 → `POST /api/ai/agent/editor-result` → completeEditorAction 解锁 Future → 工具返回给模型。

## doc_* 原语速查（工具名 ≠ 下发 action 名）

全集见 DocumentEditTools.java。易混对照：`doc_find_text`→`find_text_locations`、`doc_select_anchor`→`set_selection`、`doc_replace_at_anchor`→`replace_at_position`、`doc_collapse_cursor`→`collapse_selection`、`doc_start_stream`→`doc_open_file_sync`+setStreamingMode。批注 `doc_add_comment`→`add_comment`。格式面：`doc_apply_standard_format`→`apply_house_style`、`doc_set_numbering`→`set_numbering`、`doc_format_table`→`format_table`、`doc_insert_table`→`insert_table`（rowsJson 后端解析成 rows 数组下发）、`doc_get_formatting`→`get_formatting`。

**Word 表格单元格级原语 `doc_table_*`**（issue #261；工具名去掉 `doc_` 前缀即 action 名）：`doc_table_read`→`table_read`（读成二维数组，改表前的眼睛）、`doc_table_set_cell`→`table_set_cell`（cell 形如 `B2`）、`doc_table_add_row`/`doc_table_delete_row`→`table_add_row`/`table_delete_row`、`doc_table_add_col`/`doc_table_delete_col`→`table_add_col`/`table_delete_col`。与整张表粒度的 `insert_table`/`format_table` 互补。worker 侧：定位与校验集中在 `resolveWriterTable`（tableName / tableIndex 0 开始 / 缺省用光标所在表；非 Writer 文档返回 `NOT_TEXT_DOC_MSG`），单元格坐标解析 `parseCellRef`/`parseColumnRef`，失败统一走 `tableFail()`——**同时写 `error` 与 `message` 两个字段**：前端 `handleEditorCommand` 只把 `result.error` 回传后端，只写 `message` 的话模型收到的是 `{"error": "null"}`（判得出失败但看不到原因）。`table_set_cell` 在 RecordChanges 开启且原格非空时走 `applyMinimalRedline`（与 replace_selection 同口径，只对差异字符落修订，返回 `via: 'minimalRedline'`），并把视图光标停在该格（后续原语可省 tableIndex）。增删行列用 `XTableRows/XTableColumns.insertByIndex/removeByIndex`，**删除侧按"行数变化 OR 修订条数变化"双口径判定生效**（修订模式下引擎可能记成删除修订而行数不变），返回 `removedRows/removedCols`、`redlineDelta`、`trackedAsRevision`。合并单元格本期不做；表里有合并/拆分格时 `table_read` 返回 `note` 提示那些网格位置取不到、单元格级修改会失败。真机回归见 lowa-e2e 组 20。

**流式写入去 markdown 化**：doc_stream_data 的消费端不再 `insert_at_cursor` 原样落字，改走 worker 的 `stream_insert`（按行增量剥离 markdown 标记，按律所标准格式落字：楷体_GB2312/Arial、主标题 16 磅粗居中、正文 12 磅两端对齐段后 18 磅首行缩进 2 字符、markdown 表格转真 TextTable 套 Grid 1.5 磅）。流结束时编排器发 SSE `doc_stream_end`（onComplete 与 onError 两处），前端冲缓冲后调 `stream_flush` 收尾（写尾行/建尾表/复位状态机）；`stream_flush {discard:true}` 是换文档前的硬复位（open_sync 步骤 5）。标准格式常量在 office_thread.js 的 `HOUSE`。

EDITOR_ACTIONS 全集在 `libreofficeExecutorClient.js:15-67`（含宿主自发的 load_document/export_document/insert_image/var_*/*_hyperlink 与诊断类 get_ui_lang/probe_modules/list_fonts/debug_revisions）。`ui_command` 是其中一个 action，worker 端再经 UI_COMMANDS 二级白名单映射 `.uno:` 槽（IME 快捷键用，非 AI 管线）。

**电子表格（Calc / xlsx）sheet_\* 原语**：doc_\* 是 Writer 专属（`xModel.getText()` 在 Calc 文档上必然失败），xlsx 走 sheet_\* 全集（工具名=action 名，不做映射）——单元格面：`sheet_get_overview / sheet_read_range / sheet_write_cells / sheet_select_range / sheet_format_cells / sheet_set_borders / sheet_set_row_col`；结构面：`sheet_manage_sheets`（工作表增删改名移动）/ `sheet_edit_rows_cols`（插删行列）/ `sheet_merge_cells` / `sheet_sort_range`（XSortable，SortFields 是纯 Array of TableSortField 值结构体）/ `sheet_set_autofilter`（经命名 DatabaseRanges，set 语义而非 .uno: toggle）/ `sheet_freeze_panes`（XViewFreezable）/ `sheet_conditional_format`（命中外观落自建 CellStyle `__awd_cf_N`，每次调用替换区域现有规则）；`sheet_create_file` 是纯后端工具（POI 建最小空白 xlsx + 注册 + sendOpenFileAction，无 worker action）。worker 端 `resolveSheet` 统一守卫文档类型（非 Calc 返回明确错误）并把命中的工作表切为活动表。Calc 没有 redline，写入即生效——安全网是 doc_undo 与文档检查点（写类工具都打了 `fileEffect="MODIFIED"`）。地雷：`VertJustify` 声明 long、个别引擎按 short 校验（失败退 shortAny）；写入时数字样式字符串落数值但**前导 0 的编号保持文本**；数字格式经 `getNumberFormats().queryKey/addNew`（非法格式码会抛）；**setFormula 走 API 文法**——参数分隔符必须分号（逗号 Err:508）、跨表引用必须 `Sheet.A1`（`Sheet!A1` 报 #NAME?），worker 的 `normalizeFormula` 在字符串字面量外做 `,`→`;`、`!`→`.` 归一（Excel 数组字面量与 Calc 交集操作符 `!` 被牺牲，AI 公式里趋近于零）；引擎 LO 24.2 **无 XLOOKUP**（24.8 才有），出错公式经 `sheet_write_cells` 返回值 `formulaErrors` 报给 AI 自纠，读回侧 `readCellOut` 先查 `getError()`（否则错误格伪装成 0）。

## 第二条桥：office_* 工具桥（Word 插件，Phase C）

与 LOWA 桥并存的独立桥，服务 `office-addin/`（Word/Excel/PowerPoint 任务窗格插件）。**逐字同构但零共享**：不复用 EditorBridgeService、超时常量独立、单名契约（无双轨旧名）。

- `backend/src/main/java/com/checkba/service/ai/OfficeBridgeService.java` — requestId + CompletableFuture + SSE `client_action`（tool 固定 `office_command`，payload `{requestId, command, args, conversationId}`）+ 30s 超时。失败一律 `{"error": ...}`（Jackson 序列化，非手拼），ToolResult.success() 靠该前缀防绿勾空转。
- `backend/src/main/java/com/checkba/controller/ai/OfficeResultController.java` — `POST /api/agent/office/result`（body `{requestId, ok, data|error}`）。会话归属**不信任请求体**：以桥挂起表按 requestId 登记的 conversationId 为准，再过 canUseConversation。
- `backend/src/main/java/com/checkba/service/ai/tools/OfficeEditTools.java` — office_* 工具集（工具名 ≠ command 名）。Word 面：office_get_text→get_text、office_get_selection→get_selection、office_search→search、office_replace_text→replace_text、office_insert_text→insert_text、office_add_comment→add_comment；Word 格式面（批次 4A）：office_format_text→format_text（字符面：fontName/fontSize/bold/italic/underline(none/single/double/dotted/wave)/strikeThrough/doubleStrikeThrough/color，anchorText 定位 + applyToAll）、office_set_paragraph_format→set_paragraph_format（段落面：alignment(left/center/right/justify)、lineSpacing/spaceBefore/spaceAfter/firstLineIndent/leftIndent/rightIndent 全按磅、styleBuiltIn(normal/heading1~4)）、office_get_formatting→get_formatting（读现有格式，anchorText 可选，缺省读选区/光标段）——枚举白名单后端与执行器各校验一道，插件端映射表（UNDERLINE_TYPES/ALIGNMENTS/PARAGRAPH_STYLES）在 officeExecutor.js；Excel 面：office_excel_get_range→excel_get_range、office_excel_set_values→excel_set_values（valuesJson 后端解析成二维数组下发，上限 2000 格）、office_excel_search→excel_search；PPT 面：office_ppt_get_slides→ppt_get_slides、office_ppt_replace_text→ppt_replace_text（PowerPointApi 1.4，Excel/PPT 无修订、写入直接生效）。conversationId 参数由 ToolRegistry 服务端注入（不走 EditorBridgeService 的 ThreadLocal）。
- `office-addin/taskpane/lib/officeExecutor.js` — 插件端执行器（Office.js 实现全集 + COMMAND_DISPLAY_NAMES 中文名表）；ChatView 消费 client_action(tool=office_command) → 执行 → postOfficeResult 回传。修改类命令执行前置 `changeTrackingMode=TrackAll`（Word 原生修订）、执行后恢复原值；WordApi 1.4 不支持时降级直接修改并标 `tracked:false`。

**会话级客户端能力过滤**：chat 请求可选 `clientCapability`（lowa/office/none，缺省 lowa）+ `officeHost`（word/excel/powerpoint，缺省 word）→ `ClientCapabilityService`（按 conversationId 内存登记）→ ToolRegistry 三消费点过滤（getAllSpecifications(conversationId)/execute/resolve(name, conversationId)）：office 会话隐藏 doc_* 与 sheet_* 且**按宿主再细分**（word 只见 Word 面 office_*、excel 只见 office_excel_*、ppt 只见 office_ppt_*，`hostOfTool` 最长前缀优先），lowa 会话隐藏 office_*，none 全隐藏。末位提醒与 <active_document> 段文案在 ContextAssemblerService 按能力三分支+office 宿主三分支切换。

## 命名双轨现状（PR#192，下个发布周期摘旧名）

仍活着的 wps_* 旧名：后端 `sendDualNamedAction`（doc_open_file/wps_open_file、doc_reload_file/wps_reload_file）、executeEditorCommand 双发 editor_command/wps_command、doc_open_file_sync↔wps_open_file_sync、doc_stream_data/wps_stream_data 双发、`/wps-result` 路由别名；前端 handleClientAction 显式识别全部旧名+latch 去重、toolDisplayNames 把 wps_* 归一为 doc_*。
**`wpsFileId` 不属于命令双轨**——是持久化字段名（ProjectFile 实体），贯穿前后端，无改名计划，别动。

## 已知地雷

- **新增 doc_* 工具四件套**：DocumentEditTools 加 @Tool + EDITOR_ACTIONS 白名单加 action + office_thread.js 加实现 + toolDisplayNames.js 加中文名。漏任何一环都是静默失败（PR#180 教训）。
- **worker 失败返回必须带 `error` 字段**：前端 `handleEditorCommand` 只把 `result.error` 回传后端（`result.message` 不看），只写 `message` 的话模型收到 `{"error": "null"}`——判得出失败但拿不到原因，白白浪费一轮。`doc_table_*` 用 `tableFail()` 统一写两个字段。
- **表格删行/删列不进修订**（真机实测 LO 24.2）：`XTableRows/XTableColumns.removeByIndex` 走 API 路线直接删除，RecordChanges 开着也是 `redlineDelta=0`——AI 删表格行的安全网是 doc_undo 与文档检查点，不是修订面板，工具描述里已对模型明说。生效判定仍按"行列数变化 OR 修订条数变化"双口径（防将来引擎改口径），别只看 `getRows().getCount()`。
- **区间批注必须走 `.uno:InsertAnnotation` 派发**；LO API 路线（addAnnotation）会抛虚假异常且只批注锚点（PR#191）。
- **replace_selection 仅在 RecordChanges 开启时启用最小修订路径**；修订应用必须从右到左，否则前面的编辑使后面的偏移失效（PR#188）。
- **office 线程会被 export 冻结**：长 export 期间同步命令假死是已知模式，autoSave 需让路（PR#182）。
- **工具位置参数按签名映射为命名参数**（PR#193），改工具签名要考虑旧会话回放。
- **删除修订跨 reset 会残留**，相关测试口径见 PR#188 记录。
- 修订模式下手工删除卡死曾因覆盖层吞键，用 `.uno:` 调度修复（PR#164/166），别退回 DOM 键盘事件路线。
- 改 AgentOrchestrator 构造器必须同步 EvalHarness（踩过两次）。
- **`insert_at_cursor` 对带 markdown 标记的文本走剥离转换**（`MD_MARKER_RE` 判定，**→真粗体、行首 # 剥掉），纯文本原样插入；改插入路径要想到这两条分支。`insert_under_heading` 曾经后端一直派发但 worker 没实现+白名单没收录（静默失败年久失修），已补齐。
- **改标准格式规范要改两处**：worker `HOUSE`（office_thread.js）+ 后端 `DocxStyleHelper`（编辑器流式 与 write_docx 两条路径各一份，规范必须一致）。
- 流式中断（onError）也会发 doc_stream_end——新增流式相关终止分支时别忘了这个收尾信号，否则 worker 状态机残留半张表。
- **新增 office_* 工具三件套**：OfficeEditTools 加 @Tool + officeExecutor.js 加 HANDLERS 实现 + COMMAND_DISPLAY_NAMES 加中文名（主前端 toolDisplayNames.js 兜底同步），并在 officeExecutor 的 COMMAND_HOSTS 标宿主。没有客户端实现的远端工具 = 30s 超时空转（与 doc_* 四件套同款地雷）。
- **能力过滤靠工具名前缀**（doc_/sheet_/office_，office 内再按 office_excel_/office_ppt_ 细分宿主）：新增 LOWA 专属或插件专属工具必须沿用对应前缀，否则会漏到不该见它的会话里（Excel 面起名不带 office_excel_ 前缀 = 漏进 Word 会话的死路径）；ToolRegistry 构造器加了 ClientCapabilityService，改它要同步 RecordingToolRegistry（EvalHarness）与各测试构造点。

## 验证

- 编辑器三件套（原语/白名单/worker）改动后必跑：`cd frontend && npm run test:lowa-e2e`（基线 38 步）。
- 全链路回归：`npm run test:app-e2e`；前端事件契约 `npm run check:emits`。
- 后端：`cd backend && mvn test`（JDK 21，默认 25 会 SIGBUS）；EvalHarness 回放评测在其中。
- 原语级测试不够，必须走完 UI 链路验证（用户明确要求过）。
