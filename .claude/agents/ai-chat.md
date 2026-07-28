---
name: ai-chat
description: AI 对话编排领域。任务涉及编排器 AgentOrchestrator、ToolRegistry、SSE 事件流、上下文组装、记忆系统、MCP、子 Agent、模型路由、回放评测时，先读本文档再动代码。
---

# AI 对话编排 领域地图

职责边界：AI 对话功能本身（编排循环、工具注册分发、记忆、SSE、前端聊天 UI、评测）。AI→编辑器指令链路属 ai-doc-bridge 领域；skill 机制属 plugin-system 领域（但 SkillRouter 在编排循环里有两处旁路接入点）。

## 关键文件（后端包根 backend/src/main/java/com/checkba/）

**编排核心**
- `controller/ai/AiAgentController.java` — 主入口（/api/agent）：GET /connect/{cid}（建 SSE）、POST /chat（异步 200）、POST /cancel/{cid}、/history/rollback、/tasks/active、/ppt/generate。AiChatController 是已被取代的 v1，非主链路。
- `service/ai/AgentOrchestrator.java`（862 行）— **编排器**：handleUserMessage（@Async("taskExecutor")）+ runLoop（递归）。RunGuard：重复调用熔断 MAX_IDENTICAL_TOOL_CALLS=3、连续失败提示=3、步数预算 MAX_LOOP_DEPTH=30。工具分发 dispatchTool（~:160）、artifact/<title> 处理、检查点触发。
- `service/ai/AgentStreamHandler.java`（493 行）— StreamingResponseHandler：token 流→SSE；<bubble_type>/<artifact> 边界解析缓冲、编辑器流过滤、token 用量上报。每次 runLoop 新建实例。
- `service/ai/AgentRunStateService.java` — 每会话运行状态登记簿（内存）：RUNNING/PAUSED/AWAITING_APPROVAL/FINISHED/ERROR/CANCELLED。**新增终止分支必打状态点**（PR#173 状态机契约）。
- `service/ai/ContextAssemblerService.java`（507 行）— assemble()：prompts/system_prompt.md + enforcement 段 + 模式约束 + Skill 注入 + 记忆 + 文件上下文 + 历史栈。
- `service/ai/ChatModelFactory.java` — 供应商路由（OpenRouter/Gemini/Ollama）；provider 优先 DB `ai.activeProvider` 再回退 yml（PR#144）。`AllowedModels.java` 白名单（含单价）。
- context/ 子包：ContextCompressor、ConversationSummarizer、FileContextLoader、LegalInfoProtector、ProjectContextHolder。

**工具注册与执行**
- `service/ai/ToolRegistry.java`（428 行）— @PostConstruct 扫 AgentToolComponent 的 @Tool；getAllSpecifications / execute（反射+服务端强注入 projectId/conversationId/userId+容错类型转换）/ resolve；别名表 TOOL_NAME_ALIASES/ARG_ALIASES/LEGACY_DEFAULTS。**插件启停过滤也在这三处消费点**。
- `service/ai/XmlToolCallParser.java` — XML <tool_code> 协议兜底（位置参数按签名映射为命名参数，PR#193）。
- tools/：FileTools(8)、LegalTools(5)、WebTools(2)、PythonTools(1)、TodoTools(1)、SubAgentTools(1，**@Lazy 防启动死环** PR#98)、EvidenceTools(1)、MemoryTools(8)、DocumentEditTools(32)、CheckpointTools(1)、PptxTools(13)、PptxEditTools(7)。

**记忆/证据/MCP/子 Agent**
- memory/：MemoryPipelineService（轮次结束异步触发写侧管线）、MemoryManager（检索）、AgenticRetriever、MemCellExtractor、ProjectMemoryExtractor、MemoryEvidenceFormatter（证据账本：时间锚点/来源/更新信号，PR#155）。记忆五作用域 + 拟人化排序（重要性×衰减×随机）。
- evidence/：evidence.retrieve.v1（PR#186）——EvidenceRetriever SPI + Registry + Memory/Mcp 实现。两大不变式：**缺定位符即丢弃、缺证据≠矛盾**。
- mcp/：McpClientService 门面 + StreamableHttpMcpProvider；配置驱动 mcp.servers（langchain4j-mcp 需 1.0.0+）。
- subagent/：SubAgentService（dispatch_subtask，发 subtask_progress）。

**SSE**
- `service/ai/SseEmitterService.java` — 连接池（cid→SseEmitter，超时 30 分钟，建连发 connected）。**所有事件唯一出口**。生产者：Orchestrator、StreamHandler、Controller、TodoListService(plan_update)、BackgroundTaskService(background_task_*/heartbeat/task_progress)、SubAgentService、EditorBridgeService。

**前端消费**
- `frontend/src/composables/useAgentStream.js`（1233 行）— SSE 核心：connectSSE（fetch+ReadableStream，非 EventSource）、sendMessage、abort、handleEvent（~:352 分派）、handleTag/processTextDelta（XML 标签驱动气泡组装）、handleStateRecovery。
- `frontend/src/components/ChatInterface.vue`（3620 行）+ `AgentMessage/`（RootBubble/ProcessCard/ThinkingCard/TodoProgressCard/WalkthroughCard/TitleCard）。

## 一条消息的完整链路

ChatInterface.handleSubmit（~:927）→ useAgentStream.sendMessage（确保 SSE 已连 → POST /chat）→ Controller 异步 200 → handleUserMessage（@Async：存 USER 消息→标题→SkillRouter.activateForTurn→assemble→取流式模型→mark(RUNNING)→runLoop）→ StreamHandler.onNext 逐 token 发 SSE → onComplete 回调检测工具请求（原生 function calling 或 XML 兜底）→ dispatchTool→ToolRegistry.execute→副作用（file_change/refresh_files）→ 结果追加 messages → **递归 runLoop(depth+1)** → 无工具时收尾：artifact 解析（implementation_plan 停机待审批/task_list 继续）→ 存 ASSISTANT → MemoryPipeline 异步 → mark(FINISHED) → bubble_end → 关 SSE。

## SSE 事件名清单

connected / bubble_start / text_delta / artifact / token_usage / bubble_end（status: finished|paused|awaiting_approval）/ error / cancelled / file_change / client_action / title_update / doc_stream_data（旧名 wps_stream_data 双轨待摘）/ state_recovery（断线重连快照）/ run_state / plan_update / background_task_start|complete / task_progress / heartbeat / subtask_progress。前端分派均在 useAgentStream.handleEvent。超限 paused 契约见 PR#172。

## ChatInterface.vue 内部地图

template :1-539；script :541-1879（模式/模型选择 :648-766、文件变更 :767-817、PPT 配置 :818-862、回滚 :864-920、**提交主链路 handleSubmit :921-1056**、历史加载 :1057-1226、富文本输入/粘贴 :1227-1387、文件上下文 :1388-1450、上传对话框 :1451-1726、上传实现 :1727-1879）；style :1881-3620。

## 配置

`backend/src/main/resources/application.yml` :84 起 `ai:` 段（model.provider 默认 open-router、ai.context.*、ai.subagent.*、ai.skills.*）；生产覆盖 application-prod.yml、桌面 application-desktop.yml。

## 已知地雷

- **改 AgentOrchestrator 构造器必须同步 EvalHarness**（已踩两次）。
- 新增工具不要改编排器（Phase 1 五条不变式）：实现 AgentToolComponent + @Tool + @ToolMeta 即自动注册；显示名要同步 toolDisplayNames.js。
- SubAgentTools 注入必须 @Lazy（启动死环）。
- 30 秒覆盖启发式曾致历史丢回复，现为轮次级 upsert（PR#153）——改历史持久化先读该记录。
- **只写进 system prompt 的行为约束会被弱模型稳定无视**：活跃文档声明（连正文一起注入）曾放在
  system prompt，真机日志实证注入后 6 秒模型照样调 doc_list_project_files 重新发现文档（PR#187 加强
  措辞无效）。现改为在**用户消息尾部**追加 `[系统提醒]`（ContextAssemblerService.activeDocumentReminder，
  PR#208）——末位是注意力最高的位置。**新增"必须/禁止"类约束一律挂末位，不要只写 system prompt。**
- 排障需要后端日志时注意：桌面端复用已在跑的后端进程时不会重建日志管道，`~/.aiworkdeck/logs/backend.log`
  会停止更新（表现为日志停在几天前）。要拿新日志先彻底退出 app 让后端随之重启。
- 防走神注入/todo_write 进度卡/文档检查点机制见 PR#161/162。
- RunGuard 阈值改动影响回放评测断言。

## 验证

- `cd backend && mvn test`（JDK 21！默认 25 SIGBUS）——含回放评测 OrchestratorReplayEvalTest（用例 `backend/src/test/resources/ai-eval/cases/cases-*.json`，10 组）+ DesktopContextSmokeTest。
- 只跑回放：`mvn test -Dtest=OrchestratorReplayEvalTest`；真实 LLM 冒烟：`OPENROUTER_API_KEY=… mvn test -Dtest=RealLlmSmokeTest`。
- 前端：`npm run check:emits`；UI 链路 `npm run test:app-e2e`。
