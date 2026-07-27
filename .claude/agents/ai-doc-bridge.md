---
name: ai-doc-bridge
description: AI↔文档编辑桥接领域。任务涉及 doc_* 编辑原语、EditorBridgeService、editor_command 契约、修订（redline）、文档检查点、EDITOR_ACTIONS/ui_command 白名单时，先读本文档再动代码。
---

# AI↔文档编辑桥接 领域地图

职责边界：AI 侧发出编辑指令 → 编辑器执行 的整条链路。不含编辑器内核本身（那是 doc-editor 领域），不含对话编排（ai-chat 领域）。

## 关键文件

**后端工具原语**
- `backend/src/main/java/com/checkba/service/ai/tools/DocumentEditTools.java` — doc_* 工具原语全集（38 个 @Tool 方法），翻译成 `editorBridgeService.executeEditorCommand(action, params)`。曾名 WpsTools。
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

全集见 DocumentEditTools.java。易混对照：`doc_find_text`→`find_text_locations`、`doc_select_anchor`→`set_selection`、`doc_replace_at_anchor`→`replace_at_position`、`doc_collapse_cursor`→`collapse_selection`、`doc_start_stream`→`doc_open_file_sync`+setStreamingMode。批注 `doc_add_comment`→`add_comment`。

EDITOR_ACTIONS 全集在 `libreofficeExecutorClient.js:15-67`（含宿主自发的 load_document/export_document/insert_image/var_*/*_hyperlink 与诊断类 get_ui_lang/probe_modules/list_fonts/debug_revisions）。`ui_command` 是其中一个 action，worker 端再经 UI_COMMANDS 二级白名单映射 `.uno:` 槽（IME 快捷键用，非 AI 管线）。

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

## 验证

- 编辑器三件套（原语/白名单/worker）改动后必跑：`cd frontend && npm run test:lowa-e2e`（基线 38 步）。
- 全链路回归：`npm run test:app-e2e`；前端事件契约 `npm run check:emits`。
- 后端：`cd backend && mvn test`（JDK 21，默认 25 会 SIGBUS）；EvalHarness 回放评测在其中。
- 原语级测试不够，必须走完 UI 链路验证（用户明确要求过）。
