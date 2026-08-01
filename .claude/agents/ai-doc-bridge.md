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

**流式写入去 markdown 化**：doc_stream_data 的消费端不再 `insert_at_cursor` 原样落字，改走 worker 的 `stream_insert`（按行增量剥离 markdown 标记，按律所标准格式落字：楷体_GB2312/Arial、主标题 16 磅粗居中、正文 12 磅两端对齐段后 18 磅首行缩进 2 字符、markdown 表格转真 TextTable 套 Grid 1.5 磅）。流结束时编排器发 SSE `doc_stream_end`（onComplete 与 onError 两处），前端冲缓冲后调 `stream_flush` 收尾（写尾行/建尾表/复位状态机）；`stream_flush {discard:true}` 是换文档前的硬复位（open_sync 步骤 5）。标准格式常量在 office_thread.js 的 `HOUSE`。

EDITOR_ACTIONS 全集在 `libreofficeExecutorClient.js:15-67`（含宿主自发的 load_document/export_document/insert_image/var_*/*_hyperlink 与诊断类 get_ui_lang/probe_modules/list_fonts/debug_revisions）。`ui_command` 是其中一个 action，worker 端再经 UI_COMMANDS 二级白名单映射 `.uno:` 槽（IME 快捷键用，非 AI 管线）。

**电子表格（Calc / xlsx）sheet_\* 原语**：doc_\* 是 Writer 专属（`xModel.getText()` 在 Calc 文档上必然失败），xlsx 走 sheet_\* 七件：`sheet_get_overview / sheet_read_range / sheet_write_cells / sheet_select_range / sheet_format_cells / sheet_set_borders / sheet_set_row_col`（工具名=action 名，不做映射）。worker 端 `resolveSheet` 统一守卫文档类型（非 Calc 返回明确错误）并把命中的工作表切为活动表。Calc 没有 redline，写入即生效——安全网是 doc_undo 与文档检查点（写类工具都打了 `fileEffect="MODIFIED"`）。地雷：`VertJustify` 声明 long、个别引擎按 short 校验（失败退 shortAny）；写入时数字样式字符串落数值但**前导 0 的编号保持文本**；数字格式经 `getNumberFormats().queryKey/addNew`（非法格式码会抛）。

## 命名双轨现状（PR#192，下个发布周期摘旧名）

仍活着的 wps_* 旧名：后端 `sendDualNamedAction`（doc_open_file/wps_open_file、doc_reload_file/wps_reload_file）、executeEditorCommand 双发 editor_command/wps_command、doc_open_file_sync↔wps_open_file_sync、doc_stream_data/wps_stream_data 双发、`/wps-result` 路由别名；前端 handleClientAction 显式识别全部旧名+latch 去重、toolDisplayNames 把 wps_* 归一为 doc_*。
**`wpsFileId` 不属于命令双轨**——是持久化字段名（ProjectFile 实体），贯穿前后端，无改名计划，别动。

## 已知地雷

- **新增 doc_* 工具四件套**：DocumentEditTools 加 @Tool + EDITOR_ACTIONS 白名单加 action + office_thread.js 加实现 + toolDisplayNames.js 加中文名。漏任何一环都是静默失败（PR#180 教训）。
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

## 验证

- 编辑器三件套（原语/白名单/worker）改动后必跑：`cd frontend && npm run test:lowa-e2e`（基线 38 步）。
- 全链路回归：`npm run test:app-e2e`；前端事件契约 `npm run check:emits`。
- 后端：`cd backend && mvn test`（JDK 21，默认 25 会 SIGBUS）；EvalHarness 回放评测在其中。
- 原语级测试不够，必须走完 UI 链路验证（用户明确要求过）。
