---
name: ai-chat
description: AI 对话编排领域。任务涉及编排器 AgentOrchestrator、ToolRegistry、SSE 事件流、上下文组装、记忆系统、MCP、子 Agent、模型路由、回放评测时，先读本文档再动代码。
---

# AI 对话编排 领域地图

职责边界：AI 对话功能本身（编排循环、工具注册分发、记忆、SSE、前端聊天 UI、评测）。AI→编辑器指令链路属 ai-doc-bridge 领域；skill 机制属 plugin-system 领域（但 SkillRouter 在编排循环里有两处旁路接入点）。

## 关键文件（后端包根 backend/src/main/java/com/checkba/）

**编排核心**
- `controller/ai/AiAgentController.java` — 主入口（/api/agent）：GET /connect/{cid}（建 SSE）、POST /chat（异步 200）、POST /cancel/{cid}、/history/rollback、/tasks/active、/ppt/generate。AiChatController 是已被取代的 v1，非主链路。
- `service/ai/AgentOrchestrator.java`（1193 行）— **编排器**：handleUserMessage（@Async("taskExecutor")）+ runLoop（递归）。RunGuard：打转检测（StuckDetector 滑动窗口，先干预后熔断）、连续失败提示=3、步数预算 MAX_LOOP_DEPTH=30、故障转移已试模型集。工具分发 dispatchTool、artifact/<title> 处理、检查点触发。
- `service/ai/AgentStreamHandler.java`（493 行）— StreamingResponseHandler：token 流→SSE；<bubble_type>/<artifact> 边界解析缓冲、编辑器流过滤、token 用量上报。每次 runLoop 新建实例。
- `service/ai/AgentRunStateService.java` — 每会话运行状态登记簿（内存）：RUNNING/PAUSED/AWAITING_APPROVAL/FINISHED/ERROR/CANCELLED。**新增终止分支必打状态点**（PR#173 状态机契约）。
- `service/ai/ContextAssemblerService.java`（507 行）— assemble()：prompts/system_prompt.md + enforcement 段 + 模式约束 + Skill 注入 + 记忆 + 文件上下文 + 历史栈。activeContext 正文来源二选一：ContextItem.inlineContent（Office 插件等外部客户端随请求内联携带，200k 截断）优先，否则 read_document(fileId)——见 resolveActiveDocumentContent；末位 [系统提醒] 两条路径共用不变。
- `service/ai/ChatModelFactory.java` — 供应商路由（OpenRouter/Gemini/Ollama/**AWD_CLOUD**）；provider 优先 DB `ai.activeProvider` 再回退 yml（PR#144）。`AllowedModels.java` 白名单（含单价）。
- **平台通道 AWD_CLOUD「AI Workdeck 云端」（商业化 PR-B）**：key 由官网 provision（`service/ai/PlatformAiChannel.java`，缓存 `~/.aiworkdeck/platform-ai-key.json` 0600），判定**先于白名单短路**，取不到 key **绝不静默回退 BYOK**（会花用户自己的钱）。`service/ai/PlatformUsageAccountant.java` 用 OpenRouter `GET /api/v1/key` 累计消费差分补 `TokenUsage.costSource=platform` 的真实扣费（langchain4j 0.36 拿不到响应里的 `usage.cost`）；BYOK 仍是单价表估算，标 `costSource=estimate`。两套数字**分开标注不得合并**（Spec §3）。账户连接在 `service/account/`，权益在 `service/entitlement/`，两者与解锁门、计费契约一并见 `.claude/agents/licensing-billing.md`。
- **AccountException 不是内部错误**：AgentOrchestrator 与 AiChatService 单独 catch 它，把中文文案（如「尚未分配 AI 额度，请到官网账户页从余额分配」）原样透出，不加 `Internal Error:` / `Sorry, I encountered an error:` 前缀——这是用户可自行处理的状态，未分配额度时每条消息都会走到。**账户类文案里不许出现「登录」「未授权」「请先」**：`services/api.js` 用这三个子串判定未登录，会清会话（浏览器端还跳登录页），`AccountServiceTest.accountMessagesDoNotLookLikeAuthErrors` 守这条。
- **平台通道的三个状态钩子**（改账户连接时容易漏）：① connect/disconnect 必须 `PlatformUsageAccountant.resetBaseline()`，否则两把 key 的累计消费之差会整个记到下一条消息头上；② 平台模型创建前调 `ensureBaselineAsync()`，否则进程重启后第一条消息永久显示「待结算」；③ disconnect 必须 `ChatModelFactory.demotePlatformProvider()` 把 `ai.activeProvider` 从 AWD_CLOUD 摘下来（返回落到的供应商，随 `/api/account/disconnect` 的 `aiProviderFallback` 下发给前端），否则设置页显示平台通道正常选中、每条消息却报未连接账户。
- context/ 子包：ContextCompressor、ConversationSummarizer、FileContextLoader、LegalInfoProtector、ProjectContextHolder。

**工具注册与执行**
- `service/ai/ToolRegistry.java`（428 行）— @PostConstruct 扫 AgentToolComponent 的 @Tool；getAllSpecifications / execute（反射+服务端强注入 projectId/conversationId/userId+容错类型转换）/ resolve；别名表 TOOL_NAME_ALIASES/ARG_ALIASES/LEGACY_DEFAULTS。**插件启停过滤也在这三处消费点**。
- `service/ai/XmlToolCallParser.java` — XML <tool_code> 协议兜底（位置参数按签名映射为命名参数，PR#193）。
- tools/：FileTools(12，含 create_folder/rename_project_file/move_project_file/move_file 四个 DB 感知文件树原语——直通 ProjectFileService，与前端右键菜单同路径；move_file 2026-08 由停用复活为路径版移动：按路径经 dbPathIndex 解析 project_file 记录、缺失目标文件夹自动补建，真机实证 txt 类文件拿不到 fileId 时模型会绕道 read_file+write_file 整篇重写；list_files/search_project_files 对 DB 已登记条目附带 fileId/folderId，未登记提示先 scan_files；含 extract_file_text——Tika/PDFBox 全文抽取，Word/Excel/PDF 均可读；write_docx 支持可选 parentFolderId 落指定文件夹)、LegalTools(5)、WebTools(2)、PythonTools(1)、TodoTools(1)、SubAgentTools(1，**@Lazy 防启动死环** PR#98)、EvidenceTools(1)、MemoryTools(8)、DocumentEditTools(32)、CheckpointTools(1)、PptxTools(13，含 pptx_inspect_format/pptx_apply_format 走 pptx-service 自有端点 /api/pptx/*)、PdfTools(7，PDFBox 层：pdf_list_files/pdf_inspect/pdf_highlight/pdf_annotate/pdf_redact/pdf_replace_text/pdf_to_word，实现在 PdfEditService；定位类限文本型未加密 PDF、靠引用原文，fileId 必须从 pdf_list_files 拿——doc_list_project_files 不列 PDF、search_project_files 不带 ID。pdf_to_word 三路由：文本型走 pptx-service /api/pdf/to-docx 版式级(pdf2docx)→失败回退 Java 结构级提取；扫描件走 /api/pdf/ocr-markdown 本地 MinerU OCR，不用第三方云 OCR)。PptxEditTools 已删（7 个工具全走编辑器桥 ppt_* 命令，前端明确拒绝，死路径；pptx_smart_modify/pptx_get_page_screenshot 同因服务端点不存在下线）。

**记忆/证据/MCP/子 Agent**
- memory/：MemoryPipelineService（轮次结束异步触发写侧管线）、MemoryManager（检索）、AgenticRetriever、MemCellExtractor、ProjectMemoryExtractor、MemoryEvidenceFormatter（证据账本：时间锚点/来源/更新信号，PR#155）。记忆五作用域 + 拟人化排序（重要性×衰减×随机）。
- evidence/：evidence.retrieve.v1（PR#186）——EvidenceRetriever SPI + Registry + Memory/Mcp 实现。两大不变式：**缺定位符即丢弃、缺证据≠矛盾**。
- mcp/：McpClientService 门面 + StreamableHttpMcpProvider；配置驱动 mcp.servers（langchain4j-mcp 需 1.0.0+）。
- subagent/：SubAgentService（dispatch_subtask，发 subtask_progress）。

**SSE**
- `service/ai/SseEmitterService.java` — 连接池（cid→SseEmitter，超时 30 分钟，建连发 connected）。**所有事件唯一出口**。生产者：Orchestrator、StreamHandler、Controller、TodoListService(plan_update)、BackgroundTaskService(background_task_*/heartbeat/task_progress)、SubAgentService、EditorBridgeService。**15s 心跳广播**（@PostConstruct 调度器，穿透代理空闲回收 + 前端判活依据）；同 ID 重连会 complete 旧 emitter，回调移除一律用两参 remove(id, emitter) 防摘掉新连接。

**可靠性层（2026-08 harness 加固，治"跑一半停了"）**
- LLM timeout 600s（application.yml open-router.timeout；0.36 的单值=OkHttp callTimeout 整通墙钟上限，不是空闲超时）。
- `AgentStreamHandler`：终态幂等（AtomicBoolean terminated）+ **无活动看门狗** armInactivityWatchdog(180s)——流停滞主动走 onError。
- `AgentOrchestrator.setOnError`：失败按 `LlmErrorClassifier.Kind` 分类（RATE_LIMITED / TRANSIENT / MODEL_UNAVAILABLE / FATAL，OpenAiHttpException 的结构化状态码优先于文本匹配），且**零 token 已流出**才允许重放。限流退避 30/60s ×2（限流窗口按分钟计，用 8/16/32 会在同一窗口连撞三次白烧预算），瞬时 8/16/32s ×3（RunGuard.llmRetries，成功轮与切模型后清零）；用户文案两套，限流说「限流等待中」不说「服务不可用」。
- **故障转移链**（`ai.failover.models`，默认两个区域无关常青模型）：重试预算耗尽仍是限流/瞬时错误、或模型下线 404（PR#144 坑的一般化）时，`switchToFailoverModel` 换模型同 depth 重放本轮并发 SSE 明示切到了哪个。候选必须在 `AllowedModels` 白名单内——非白名单会被工厂静默回落默认模型，切了等于没切。**计费红线：只换 modelId，通道由 `ChatModelFactory.resolveProvider()` 决定，与 modelId 无关**；平台通道下取不到 key 抛的 AccountException 原样透出并终止，绝不回退 BYOK（会花用户自己的钱）。FATAL（400/401/403 与未知错误）不换模型。
- **自动 compaction**（`context/RunLoopCompactor` + `ai.context.compaction`）：runLoop 每轮 generate 前估算 token，超「历史可用预算 × 0.8」时把中段折叠成一条摘要，保留 system prompt + 首条用户消息 + 最近 8 条。**结构感知**：保留段绝不以 ToolExecutionResultMessage 打头（拆散 tool_calls 配对会让 OpenAI 兼容通道直接 400），这也是不能直接复用 ContextCompressor 的原因——那套会把消息重建成纯文本、抹掉 toolExecutionRequests。摘要本地生成不调 LLM（交互路径中间插同步 LLM 调用等于新增一处卡死成因），上一版摘要会并进新摘要。压缩失败一律原样继续；中段不足 4 条不压，回放评测用例碰不到阈值。
- **StuckDetector**（先干预后熔断）：RunGuard 的单槽 lastCallSignature 换成 6 格滑动窗口，识别 A/A/A 与 **A/B/A/B 交替**（旧实现对交替完全无感，一路空转到步数预算耗尽）。首次检出只往 **messages 末位**追加 `[系统提醒]` UserMessage、工具照常执行；二次检出才拒绝执行并回喂 `Error:` 前缀的熔断反馈。末位是硬要求——只写 system prompt 的约束会被弱模型无视（PR#209 实证）。
- 截断 `<tool_code>`（有开无闭）不再静默正常收尾：回喂纠正提示重试，最多 2 轮（RunGuard.malformedToolRounds）。
- `ToolResult.success()` 除 "Error" 前缀外还识别 `{"error"...}` JSON 形态（编辑器桥超时曾被判 SUCCESS 致绿勾空转 30 步）；工具参数 JSON 解析失败返回可行动错误回喂模型，不再静默空参硬跑。
- connect 端点：run_state=RUNNING 时**无条件**发 state_recovery（哪怕快照为空）——前端靠它重建气泡指针，否则终态事件被守卫吞掉、isStreaming 永久锁死。
- 前端 `useAgentStream`：心跳 45s 无字节判死 + 指数退避自动重连（1s→30s 封顶）+ online/visibilitychange 钩子（模块级单例，防页面栈多实例重复订阅）；bubble_end/error/cancelled 在气泡指针为 null 时也解锁 isStreaming；sendMessage 防重入有 toast 提示。
- 线程池：`config/AsyncExecutorConfig.java` 显式 taskExecutor(16/32/队列200) + memoryExecutor(2/4)——MemoryPipelineService 的同步 LLM 调用已隔离，别再挂回 taskExecutor。

**前端消费**
- `frontend/src/composables/useAgentStream.js`（1233 行）— SSE 核心：connectSSE（fetch+ReadableStream，非 EventSource）、sendMessage、abort、handleEvent（~:352 分派）、handleTag/processTextDelta（XML 标签驱动气泡组装）、handleStateRecovery。
- `frontend/src/components/ChatInterface.vue`（3620 行）+ `AgentMessage/`（RootBubble/ProcessCard/ThinkingCard/TodoProgressCard/WalkthroughCard/TitleCard）。
- **计划审批卡（2026-08）**：ArtifactCard 对 task_list/plan/implementation_plan 三类 draft 计划内联渲染正文并给「按此推进 / 修订」按钮（仅最新一条助手消息可操作，RootBubble 的 isLatest→actionable 链）；修订态就地编辑，提交时行级 LCS 统计改动处数，handleArtifactApprove 把「已修订 N 处 + 修订版全文」回喂模型。工具过程卡一律收进可折叠组（无步骤归属的归「执行过程」组），流式中展开最新组、结束后全收起。

## 一条消息的完整链路

ChatInterface.handleSubmit（~:927）→ useAgentStream.sendMessage（确保 SSE 已连 → POST /chat）→ Controller 异步 200 → handleUserMessage（@Async：存 USER 消息→标题→SkillRouter.activateForTurn→assemble→取流式模型→mark(RUNNING)→runLoop）→ StreamHandler.onNext 逐 token 发 SSE → onComplete 回调检测工具请求（原生 function calling 或 XML 兜底）→ dispatchTool→ToolRegistry.execute→副作用（file_change/refresh_files）→ 结果追加 messages → **递归 runLoop(depth+1)** → 无工具时收尾：artifact 解析（implementation_plan 停机待审批/task_list 继续）→ 存 ASSISTANT → MemoryPipeline 异步 → mark(FINISHED) → bubble_end → 关 SSE。

## SSE 事件名清单

connected / bubble_start / text_delta / artifact / token_usage / bubble_end（status: finished|paused|awaiting_approval）/ error / cancelled / file_change / client_action / title_update / doc_stream_data（旧名 wps_stream_data 双轨待摘）/ state_recovery（断线重连快照）/ run_state / plan_update / background_task_start|complete / task_progress / heartbeat / subtask_progress。前端分派均在 useAgentStream.handleEvent。超限 paused 契约见 PR#172。

## ChatInterface.vue 内部地图

template :1-539；script :541-1879（模式/模型选择 :648-766、文件变更 :767-817、PPT 配置 :818-862、回滚 :864-920、**提交主链路 handleSubmit :921-1056**、历史加载 :1057-1226、富文本输入/粘贴 :1227-1387、文件上下文 :1388-1450、上传对话框 :1451-1726、上传实现 :1727-1879）；style :1881-3620。

## 配置

`backend/src/main/resources/application.yml` :84 起 `ai:` 段（model.provider 默认 open-router、ai.failover.*、ai.context.*（含 compaction 子段）、ai.subagent.*、ai.skills.*）；生产覆盖 application-prod.yml、桌面 application-desktop.yml。

## 已知地雷

- **改 AgentOrchestrator 构造器必须同步 EvalHarness**（已踩两次）。
- 新增工具不要改编排器（Phase 1 五条不变式）：实现 AgentToolComponent + @Tool + @ToolMeta 即自动注册；显示名要同步 toolDisplayNames.js。
- SubAgentTools 注入必须 @Lazy（启动死环）。
- 30 秒覆盖启发式曾致历史丢回复，现为轮次级 upsert（PR#153）——改历史持久化先读该记录。
- **只写进 system prompt 的行为约束会被弱模型稳定无视**：活跃文档声明（连正文一起注入）曾放在
  system prompt，真机日志实证注入后 6 秒模型照样调 doc_list_project_files 重新发现文档（PR#187 加强
  措辞无效）。现改为在**用户消息尾部**追加 `[系统提醒]`（ContextAssemblerService.activeDocumentReminder，
  PR#209）——末位是注意力最高的位置。**新增"必须/禁止"类约束一律挂末位，不要只写 system prompt。**
  末位提醒仍是概率性的，确定性兜底在分发层：`dispatchTool` 短路"打开活跃文档本身"的 doc_open_file、
  给 doc_list_project_files 结果钉活跃文档提示（`activeDocOpenShortCircuit` / `appendActiveDocNotice`，
  PR#210）。**跨文档场景不拦截**——改这两个 helper 前先确认别把"对比另一份合同"之类的正常流程堵死。
- 排障需要后端日志时注意：桌面端复用已在跑的后端进程时不会重建日志管道，`~/.aiworkdeck/logs/backend.log`
  会停止更新（表现为日志停在几天前）。要拿新日志先彻底退出 app 让后端随之重启。
- 防走神注入/todo_write 进度卡/文档检查点机制见 PR#161/162。
- RunGuard 阈值改动影响回放评测断言。

## 验证

- `cd backend && mvn test`（JDK 21！默认 25 SIGBUS）——含回放评测 OrchestratorReplayEvalTest（用例 `backend/src/test/resources/ai-eval/cases/cases-*.json`，10 组）+ DesktopContextSmokeTest。
- 只跑回放：`mvn test -Dtest=OrchestratorReplayEvalTest`；真实 LLM 冒烟：`OPENROUTER_API_KEY=… mvn test -Dtest=RealLlmSmokeTest`。
- 前端：`npm run check:emits`；UI 链路 `npm run test:app-e2e`。
