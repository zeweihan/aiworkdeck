---
name: ai-chat
description: AI 对话编排领域。任务涉及编排器 AgentOrchestrator、ToolRegistry、SSE 事件流、上下文组装、记忆系统、MCP、子 Agent、模型路由、回放评测时，先读本文档再动代码。
---

# AI 对话编排 领域地图

职责边界：AI 对话功能本身（编排循环、工具注册分发、记忆、SSE、前端聊天 UI、评测）。AI→编辑器指令链路属 ai-doc-bridge 领域；skill 机制属 plugin-system 领域（但 SkillRouter 在编排循环里有两处旁路接入点）。

## 关键文件（后端包根 backend/src/main/java/com/checkba/）

**编排核心**
- `controller/ai/AiAgentController.java` — 主入口（/api/agent）：GET /connect/{cid}（建 SSE）、POST /chat（异步 200）、POST /cancel/{cid}、/history/rollback、/tasks/active、/ppt/generate、**POST /subtask/cancel**、**POST /tasks/cancel**。**对话只有这一条链路**。
  - **任务级取消（长任务可控）**：`POST /api/agent/subtask/cancel` body `{conversationId, subtaskId}` 停一个 `dispatch_subtask`；`POST /api/agent/tasks/cancel` body `{conversationId, taskId}` 停一个后台任务（PPT 生成等，接的是早就写好却零调用方的 `BackgroundTaskService.cancelTask`）。返回 200 `{"status":"ok","message":"正在停止…"}` / 404「已经结束，无需停止」/ 403 无权。**两层鉴权**：控制器判 `canUseConversation`，服务再判「这个 subtaskId/taskId 确实登记在这个会话名下」——少一层就能拿自己的会话 ID + 猜到的 ID 去掐别人的任务。**这两个端点不打 `AgentRunStateService.mark`**：掐的是一个子任务/后台任务，会话仍是 RUNNING、主循环继续跑（PR#173 要求的状态点只针对轮次终态）。**文案只许说「正在停止」**：`future.cancel(true)` 打不断阻塞的 HTTP 读，子 Agent 的中断检查在每轮开头，最坏白烧一次在途 LLM 调用；后台任务取消更只是簿记 + 广播，pptx-service 那边照样跑完落盘。子任务被停后回喂模型的文案明说 "stopped by the user, do NOT dispatch again automatically"——否则模型下一轮立刻重派，用户看到的是「点了停止反而又跑起来」。
  - **`/ppt/generate` 的 runAsync 现在会落一条 ASSISTANT 消息**（原来整段成功文本被丢弃：文件生成了但历史里一个字都没有，主 Agent 下一轮不知道这个文件存在、刷新页面用户也看不出发生过什么）。走契约 D 双通道：`content` = 工具原样全文（fileId / PPTX 服务项目 ID / 可编辑与否都在里面，模型需要），`displayContent` = 一句人话。落库失败只 log。
  - **`POST /chat` 的 `skillIds`（可选字符串数组）= 用户主动选择的 skill，本轮强制生效**（`AgentChatRequest.skillIds`）。与触发词自动命中取**并集**；无效 id（不存在/已停用/所属插件停用/当前应用语言不可用）静默忽略——SSE `skill_update` 下发的是真正生效的清单，用户看得见它没被点亮。**无状态**：后端不持久化，前端每次请求携带。旧字段 `pinnedSkillId` 已 `@Deprecated`，语义收编成「只有一项的 skillIds」（仍受理，供不发 skillIds 的存量客户端）。ASK 模式下整体不参与。
    - **必须同时注入 prompt 与参与工具可见性**——这两件事的判据现在同源收敛在 `SkillRouter.activateForTurn`。旧的 pinnedSkillId 静默 bug 就出在这里：编排器按钉选裁工具，而 `ContextAssemblerService` 自己又 `match(userPrompt)` 重新匹配了一遍，于是钉选的 skill 被裁了工具却拿不到 prompt。**组装器一律读 `skillRouter.activeSkills(conversationId)`，不许再 match 一次。**
- **契约 D「发送内容 ≠ 显示内容」**：`model/entity/ProjectAiMessage` 的可空列 `displayContent`（TEXT，ddl-auto 自动建列）。**语义红线：模型永远只看 `content`，用户看 `displayContent`、为空回退 `content`**——`ContextAssemblerService` 的历史栈与所有上下文组装一律读 `content`，一个字都不许改成读 `displayContent`（否则模型丢掉计划审批卡回喂的修订版全文、PPT 结果里的 fileId 这类它真正需要的细节）。写入口：`ProjectAiMessageService.saveMessage(...)` 的六参重载（五参版本 = displayContent 传 null），空白一律归一为 null——「缺省 = 与今天行为完全一致」是存量兼容前提。请求侧：`POST /api/agent/chat` 可选字段 `displayText`；读侧：`GET /api/ai/history` 直接序列化实体，自动带上 `displayContent`，前端渲染 `displayContent || content`。用途是「点一个按钮时用户气泡里不该出现代拟的机器口吻长句」（病灶：计划审批卡把「我已修订计划（共 N 处改动…）」当用户消息发出去）。
- `controller/ai/AiChatController.java` — 已不含任何对话端点，只剩会话周边：`GET /history`、`GET /conversations`（合并 AgentRunStateService 运行状态）、`GET /conversation/{id}/metadata`、`GET /config`、`POST /export-docx`。**v1 同步端点 `POST /api/ai/chat` 已于 2026-08 供应商三档改造中删除**，连带 `AiChatService`、`MultiModalContentService`、`GeminiChatLanguageModel`、`GeminiCacheService` 与三个 DTO（AiChatRequest/AiChatResponse/AiChatContext）。删除依据：端点虽仍映射，但前端唯一调用方（project-overview.vue 的 handleAiSend）在 AI 面板换成 ChatInterface 组件后模板里已无任何绑定，且 `api.js` 的 payload 还漏传 contexts 与 assistantId——双重死。随之废弃的 system_setting 键：`ai.systemPrompt.OLLAMA`、`ai.systemPrompt.GEMINI`（唯一读者是 AiChatService，且它按**模型名字符串**而非 provider 选 key，所以那两个 admin 提示词 tab 对全部通道早已失效）。**今天真正生效的 system prompt 由 `ContextAssemblerService` 拼装、provider 无关、admin 无入口。**
- **项目级会话列表（2026-08 项目概览页 A 期）**：`GET /api/projects/{projectId}/conversations`，控制器在 `controller/ProjectOverviewController.java`（**不在 ai 包下**——它是概览页那一组端点之一），业务落既有 `service/ProjectAiMessageService.listProjectConversations(...)`（**`com.checkba.service`，没有 `.ai` 子包**；放这里是为了就地复用它的 private `cleanTitle` / `extractPreview` / `truncatePreview`，不新起服务）。仓储是新增的 `ProjectAiMessageRepository.findProjectConversationSummaries(projectId, before, beforeId)`，**与既有 `findConversationSummaries` 并存、后者一行不改**（那条服务 `/api/ai/conversations`，动了会牵动整个 AI 面板）。
  - **与 `/api/ai/conversations` 是两条独立通道，别合并**：既有那条是 user-scoped（同时按 projectId 与 userId 过滤，「我在这个项目里的会话」）且返回**裸数组**；新这条去掉 userId 条件变成「这个项目的全部会话」且返回**信封** `{code:0,data:{conversations:[...],nextBefore,nextBeforeId}}`。
  - **可见性是分层的，这是唯一的语义变更**：**只放开列表层**（title / lastMessage / updatedAt / runStatus / ownerUserId / ownerName），**正文层一行都不放开**——正文仍按 `ProjectAiMessageService.canUseConversation` 判权。放开正文正是 2026-08 安全审计修过的那类问题，不要顺手做进去。
  - **鉴权口径是全站的 200 + code，不是 401/403**：`AuthController.getUserIdFromSession(sessionId)` 为 null 时抛 `IllegalArgumentException("未登录")`，由 `config/GlobalExceptionHandler.java` 统一转成 **HTTP 200 + `{"code":4010,"message":"未登录"}`**（PR4-0：未登录统一 code=4010——handler 对 `UnauthorizedException` 恒回 4010，对 `IllegalArgumentException` 仅当 message **恰为**「未登录」「请先登录」两个字面量时回 4010，其余仍 code=1；全站 90+ 端点同一口径，前端 `services/api.js` 的 request 包装器只认 `code === 4010` 清会话/跳登录，不再做「登录/未授权/请先」中文子串匹配）。**关键是不许像 `/api/ai/conversations` 那样静默返回空数组**——那让人以为「没有对话」而不是「你没登录」。再过 `hasReadPermission(projectId, userId)`（注意参数序 projectId 在前），失败抛「无权访问该项目」。**不拒 CLIENT**（列表层按项目全员可见是产品决策）。
  - **runStatus 读表不读内存**：批量走 `AgentRunRecordRepository.findByConversationIdIn`（防 N+1），**不读 `AgentRunStateService` 的内存 Map**。既有 `/api/ai/conversations`（`AiChatController.java:99-102`）用的是内存态，进程重启后全变 null；概览页要把全部历史铺开，用内存态会整片显示无状态。两个端点因此可能对同一个会话给出不同的 runStatus，**这是有意的、不需要对齐**。
  - **分页是复合游标，不是单字段**：`ORDER BY MAX(m.createdAt) DESC, m.conversationId DESC`，`HAVING (:before IS NULL OR MAX(m.createdAt) < :before OR (MAX(m.createdAt) = :before AND m.conversationId < :beforeId))`，响应同时回 `nextBefore` 与 `nextBeforeId`，下一页两个都要带。**只用 `MAX(createdAt)` 一维会永久丢条**：同批导入 / 同毫秒落库 / MySQL 秒级截断都会让两个会话的 `MAX(createdAt)` 完全相等，翻页时其中一条再也看不到。
  - **limit 只能在 Java 层做**：这条 JPQL 用了 4 个标量子查询 + GROUP BY + HAVING，套 `Pageable` 会逼出手写 countQuery 或两段式。服务层取全部汇总行后 `stream().limit(limit + 1)`，第 limit+1 条存在即 hasMore，游标取第 limit 条的 `(updatedAt, conversationId)`。
  - **前端不许再清洗一次**：title / lastMessage 已由服务端过 `cleanTitle` / `extractPreview` / `truncatePreview`，`ConversationList.vue` 不许再剥标签、不许再截字数（仓里已有两套并行漂移的正则，不许出第三套）。两个已知展示形态要有兜底：`lastMessage` 可能是**空串**（`extractPreview` 对以 import/def/function/class/const/let/var/public/private 开头的正文直接返回空串，服务端此时回退到用户第一条消息，**回退条件只判空串、不判长度**——「已核对」「好的」是合法短回复），`title` 可能是字面量**「新对话」**（清洗兜底与 LLM 起标题失败写库同文案，前端无法区分）。
  - **点开一条历史 → 进工作台并打开它**：概览页 `reLaunch` 到工作台时带 `conversationId` query，工作台 `onLoad` 读到后调既有 `loadHistoryChat({ conversationId })`（`pages/project-overview/project-overview.vue:4729`）。那个方法内部要 `$refs.chatInterface.loadMessages(...)`，**必须在 mounted 且 AI 面板已渲染之后调**；它同时会清掉该会话的未读蓝点、并带竞态防护（快速切换时丢弃已不是当前会话的旧响应）。概览页本身**绝不内嵌 ChatInterface**——`loadHistoryChat` 是完整切换会话，会在用户还没进工作台时就抢占当前会话。
- **「智慧助手」（AiAssistantConfig / AiAssistantService / `GET /assistants`）已于 2026-08-19 整体移除**：生产库 `ai.assistants` 只有四条从未被真配置过的远古脚手架默认值，功能从未生效——`assistantId` 在前端 `useAgentStream.sendMessage` 组装 payload 时就被丢弃，后端从不消费。裁决为不做数据迁移的干净删除；system_setting 里遗留的 `ai.assistants` 行不清理（不读不写即废弃）。
- **conversationId 服务端签发**（安全审计遗留 + Office 插件 Phase D）：`controller/ai/ConversationIssuanceController.java` `POST /api/agent/conversations` body `{projectId}` → `{"conversationId":"conv-<毫秒>-<16位随机base64url>"}`（鉴权 + hasReadPermission）。登记簿 `service/ai/ConversationIssuanceService.java`（内存 Map，惰性 24h 过期）；`ProjectAiMessageService.canUseConversation` 开头先查登记——签发给谁就归谁，关掉「空会话首条消息落库前任何登录用户可抢占」的窗口。开关 `security.conversation-issuance-required`（默认 false）：true（官方云配）时**尚无消息**的未登记会话一律拒绝（已有消息仍按 DB 归属，进程重启丢登记不影响历史）；local-mode 恒不强制，桌面自造 conv-毫秒 ID 流程不变。
- `service/ai/AgentOrchestrator.java`（1381 行）— **编排器**：handleUserMessage（@Async("taskExecutor")）+ runLoop（递归）。RunGuard：打转检测（StuckDetector 滑动窗口，先干预后熔断）、连续失败提示=3、步数预算 MAX_LOOP_DEPTH=30、故障转移已试模型集。工具分发 dispatchTool、artifact/<title> 处理、检查点触发、反问停机（见下文「一条消息的完整链路」）。
- `service/ai/AgentStreamHandler.java`（493 行）— StreamingResponseHandler：token 流→SSE；<bubble_type>/<artifact> 边界解析缓冲、编辑器流过滤、token 用量上报。每次 runLoop 新建实例。
- `service/ai/AgentRunStateService.java` — 每会话运行状态登记簿：RUNNING/PAUSED/AWAITING_APPROVAL/**AWAITING_INPUT**/FINISHED/ERROR/CANCELLED/**INTERRUPTED**。内存 map 是快路径，同时写透 `agent_run_record` 表（entity `model/entity/AgentRunRecord`，ddl-auto 自动建表；DB 写失败只 log 不阻断）。**新增终止分支必打状态点**（PR#173 状态机契约）。
  - **AWAITING_INPUT = 模型反问（`<question>` 标签）等用户回答**，SSE `bubble_end` 的 status 字面量是 `awaiting_input`。刻意不复用 AWAITING_APPROVAL：会话列表要把「待回答」与「待审批」显示成两种文案。停机语义与审批完全一致——答案是**下一轮普通用户消息**，不做阻塞式挂起（工具分发在流式回调线程上，撞 600s callTimeout 与 180s 看门狗；用户关掉 app 明天再来那一轮必死）。
  - **新增停机/终止状态必须同步四处**（漏一处是静默故障，AWAITING_INPUT 那次就漏了第四处）：① `service/telemetry/TelemetryTurnTracker.TERMINAL`（不加则 ai.turn 轮次永不闭合）；② `office-addin/taskpane/lib/chatSession.js` 的状态分档（**不是**单一 stillRunning：`generating` 锁输入 / `awaitingUser` 解锁并给 notice——任务窗格没有「继续」按钮，把等人类的状态并进「仍在跑」会把输入框永久锁死）；③ `frontend/src/composables/useAgentStream.js` 里 bubble_end 的 status 解析（**有两处**：无气泡兜底分支与正常分支）**外加 run_state 分支**，共三处；④ 会话列表的状态文案表与圆点判定，具体是 `pages/project-overview/project-overview.vue` 的 `convStatusLabel`（文案）+ `convDotClass` + `historyBadge`（圆点）与 `ChatInterface.recentDotClass` ——**文案表最容易漏**，只加圆点的话新状态在列表上和「待审批」长得一模一样，等于新状态白加。
  - 启动回收（`AgentRunRecoveryService`）只捞 RUNNING（→INTERRUPTED）与 INTERRUPTED（塞回内存），**AWAITING_APPROVAL/AWAITING_INPUT 跨重启保持 DB 原样、不进内存**：问题卡与审批卡由历史消息渲染，用户回来点选项照样有效；代价是重启后会话列表的「待回答」圆点会消失（`AiChatController` 的 runStatus 只读内存）。要改这条得连 AWAITING_APPROVAL 一起改，别只给一个状态开后门。
- `service/ai/TodoListService.java` — 任务清单（`todo_write` → `plan_update` 事件）。与 run 状态同款「内存 map 快路径 + 写透 DB」：新表 `agent_todo_list`（entity `model/entity/AgentTodoList`，整表 JSON 一行，ddl-auto 自动建表；写失败只 log 不阻断——进度卡坏掉不该让对话中断）。唯一读路径 `currentList()` 未命中时按 conversationId **惰性回填**：此前清单是纯内存的、进程重启即丢，而 run 状态却能回收成 INTERRUPTED 并给用户「继续」按钮，点下去清单已经没了——是个假承诺。空 list 是「查过 DB 确实没有」的**负缓存**占位（`reminder()` 每轮工具执行都调，不占位会每轮打库）；读失败刻意不写负缓存（留恢复窗口）。`purgeStaleLists()` 每日清 30 天未更新的行并摘掉内存条目。**清单刻意不并进 `agent_run_record`**：两条写路径各自 findByConversationId→save 会互相盖字段（lost update），且清理口径不同。`plan_update` 事件形状未变。
- `service/ai/AgentRunRecoveryService.java` — 启动回收（harness 二期）：ApplicationReadyEvent 把 DB 里遗留的 RUNNING 全部翻成 INTERRUPTED 并塞回内存 map（/connect 的 run_state 只读内存），同时给该会话最后一条半截 ASSISTANT 消息追加 `> **[进程中断]** …`（按「含 [进程中断] 即跳过」幂等）。前端 run_state=INTERRUPTED → `agentPaused={reason:'process_interrupted'}` → 复用「继续」按钮（发一条「继续」消息，编排器起跑照常翻回 RUNNING）。**刻意不做 runLoop 快照重放**：工具副作用无法保证幂等；恢复粒度就是「从已持久化的轮次级执行日志继续」，丢失窗口只有最后一个未完成的 LLM 轮。
- `service/ai/ContextAssemblerService.java` — assemble()：prompts/system_prompt.md + enforcement 段 + 模式约束 + Skill 注入 + 记忆 + 文件上下文 + 历史栈。**应用语言二选一（EN 版 PR5）**：注入 AppLanguageService，en-US 时基底 prompt 换 `prompts/system_prompt.en.md`（缺失回退中文版），enforcement/模式约束/系统时间格式（Locale.ENGLISH，时区仍 Asia/Shanghai）/活跃文档指引/readHint/末位提醒全部切英文文本（文件尾部的 EN_* 常量与 *En 方法）；zh-CN 路径代码与文本一字未动。**两版协议面（标签/停机条件/工具规则）必须逐条一致**——改中文版任一硬编码段时必须同步对应英文段与 system_prompt.en.md（en 文件里有 zh § 行号对照注释）。语言切换测试在 ContextAssemblerServiceTest 的「应用语言切换」组。activeContext 正文来源二选一：ContextItem.inlineContent（Office 插件等外部客户端随请求内联携带，200k 截断）优先，否则 read_document(fileId)——见 resolveActiveDocumentContent；末位 [系统提醒] 两条路径共用不变。
  - **enforcement 段与模式约束是「比 system_prompt.md 更末位」的文本，两边打架时它赢**（本仓实证：末位注意力最高，只写在 system prompt 里的约束被弱模型稳定无视，PR#209）。所以给模型加任何新的停机/输出形态时，**必须同时改这里**，否则功能整条是死的。反问那次就踩了三处：① Stop Conditions 原文是「**STOP ONLY** when you output implementation_plan」——把反问停机明确排除在外了，已改成 STOP + 补一条 **ALSO STOP** for `<question>`；② Output Structure 第 5 项「`<final>` REQUIRED for all non-chitchat」会让模型为了满足 REQUIRED 而在问完之后硬编一段答案，已补「以 `<question>` 收尾时不要求 `<final>`」的例外；③ AGENT 模式约束第 1 条「自动执行，无需等待用户确认」已补「但缺少影响成果正确性的前提时先用 `<question>` 问」。
- `service/ai/ChatModelFactory.java` — 供应商路由，2026-08 起收敛为**三档**：`AWD_CLOUD`（平台通道）/ `OPENROUTER`（自备 Key）/ `OLLAMA`（本地，实验档）。**GEMINI 档已下线**（手写的 GeminiChatLanguageModel 不支持 tools 也没有流式，AGENT/PLAN 下是死路；Gemini 系列模型改由 OpenRouter 的 `google/*` 提供），存量库里的 `ai.activeProvider=GEMINI` 由 `migrateRetiredGeminiProvider()`（ApplicationReadyEvent，幂等）改写成 OLLAMA——不迁移的话 `resolveProvider()` 只 warn 一句就静默回退 yml，用户的选择被改掉而设置页显示的又是另一回事。provider 优先 DB `ai.activeProvider` 再回退 yml（PR#144）。公有解析 API：`resolveProvider()` / `resolveDefaultModel()`（DB `ai.defaultModel` → yml `open-router.default-model`）/ `getAuxChatModel()`（辅助模型，非白名单抛 `FeatureNotConfiguredException(feature="ai-aux-model")`，不静默回落）/ `resolveOllamaModelName()` / `resolveOllamaBaseUrl()`。**判定顺序不许改**：平台通道短路 → 白名单短路 → provider 分流（由 ChatModelFactoryTest 固化）。`AllowedModels.java` 白名单（分档单价，见下节）。
- **平台通道 AWD_CLOUD「AI WorkDeck 云端」（商业化 PR-B）**：key 由官网 provision（`service/ai/PlatformAiChannel.java`，缓存 `~/.aiworkdeck/platform-ai-key.json` 0600），判定**先于白名单短路**，取不到 key **绝不静默回退 BYOK**（会花用户自己的钱）。`service/ai/PlatformUsageAccountant.java` 用 OpenRouter `GET /api/v1/key` 累计消费差分补 `TokenUsage.costSource=platform` 的真实扣费（langchain4j 0.36 拿不到响应里的 `usage.cost`）；BYOK 仍是单价表估算，标 `costSource=estimate`。两套数字**分开标注不得合并**（Spec §3）。账户连接在 `service/account/`，权益在 `service/entitlement/`，两者与解锁门、计费契约一并见 `.claude/agents/licensing-billing.md`。
- **AccountException 不是内部错误**：AgentOrchestrator 单独 catch 它，把中文文案（如「尚未分配 AI 额度，请到官网账户页从余额分配」）原样透出，不加 `Internal Error:` / `Sorry, I encountered an error:` 前缀——这是用户可自行处理的状态，未分配额度时每条消息都会走到。**账户类信封不许带 code=4010**：`services/api.js` 只认 code=4010 判定未登录（PR4-0，已不做中文子串匹配），会清会话（浏览器端还跳登录页）；账户类失败必须走 code=1 信封，`AccountServiceTest.accountMessagesDoNotLookLikeAuthErrors` 守这条。
- **平台通道的三个状态钩子**（改账户连接时容易漏）：① connect/disconnect 必须 `PlatformUsageAccountant.resetBaseline()`，否则两把 key 的累计消费之差会整个记到下一条消息头上；② 平台模型创建前调 `ensureBaselineAsync()`，否则进程重启后第一条消息永久显示「待结算」；③ disconnect 必须 `ChatModelFactory.demotePlatformProvider()` 把 `ai.activeProvider` 从 AWD_CLOUD 摘下来（返回落到的供应商，随 `/api/account/disconnect` 的 `aiProviderFallback` 下发给前端），否则设置页显示平台通道正常选中、每条消息却报未连接账户。
- context/ 子包：ContextCompressor、ConversationSummarizer、FileContextLoader、LegalInfoProtector、ProjectContextHolder。

**`project_memory`（项目级长期记忆，喂模型用；不是项目档案）**

- 实体 `model/entity/ProjectMemory.java`，表 `project_memory`，**15 列**：`id`、`project_id`（`nullable=false, unique=true`，一个项目一行）、`project_name(200)`、`project_type(100)`、`listed_company(200)`、`target_company(200)`、`transaction_structure(TEXT)`、`transaction_amount(NUMERIC(20,2))`、四个 JSON 列 `key_dates(Map)` / `parties(List<Map>)` / `key_variables(Map)` / `legal_refs(List<String>)` / `check_conclusions(List<Map>)`、`created_at`、`updated_at`。`toCoreContext()` 把它拼成注入 system prompt 的那段文本。仓储 `ProjectMemoryRepository`：`findByProjectId` / `existsByProjectId` / `deleteByProjectId`。
- 写入方两条：`service/ai/memory/ProjectMemoryExtractor.extractAndUpdateProjectMemory(:49)`（每轮异步跑的**纯正则**抽取，`LEGAL_REF:29 / AMOUNT:32 / DATE:38 / COMPANY:41 / PARTY:44`）与 `MemoryTools.update_project_info(:232)` → `MemoryManager.updateProjectField(:649)`（模型自觉调用，只写五个字段）。读取方三处：`ContextAssemblerService:401` 与 `:458`、`ContextCompressor:328`（经 `toCoreContext()`）、`MemoryTools:206`。
- **`project_memory` 不是项目档案的落点**（2026-08 项目概览页 A 期的决策，新建了 `project_profile_field` 表；这条写在这里是因为「为什么不用 project_memory」会被反复问）：
  1. **消费者不同**。`project_memory` 服务的是 AI 上下文装配——它是喂给模型的记忆。档案是给律师看、律师能改的字段。记忆错了模型会绕过去，档案错了律师会当真。
  2. **写入是整行覆盖且无乐观锁**。`MemoryManager.saveProjectMemory(:629)` 只从 existing 抄回 `id` 和 `createdAt`，然后 `save` 整个游离实体；**全仓 39 个实体上 `@Version` 零命中**。正则抽取器（每轮异步）与 `update_project_info`（模型随时调）写同一行，后到的整行覆盖会抹掉对方刚写的字段——律师手填的值放进去必被覆盖。
  3. **字段对不齐**。15 列里没有「客户」（只有 listedCompany / targetCompany）、没有「立项时间」、没有「下一步」。
- 补充事实，两个方向都别说错：那五个字段（projectName/projectType/listedCompany/targetCompany/transactionStructure）**有写入通道但完全靠模型自觉**（`update_project_info`），本机实测 68 行里这些列非空计数均为 0。既不能说「没有通道」（会导致重复造轮子），也不能说「有数据可用」。
- `ProjectMemoryExtractor` 与 `project_memory` **保持现状不动**，概览页只是不读它。

**模型目录与区域判定（2026-08 供应商三档改造）**
- `service/ai/AllowedModels.java` 是**模型目录的唯一事实来源**（14 条：GLOBAL 9 + INTERNATIONAL 5）。枚举带元数据：displayName（中文）/ Vendor（中文厂商名，前端按它分组）/ Region / contextLength / priceTiers。前端**不许再硬编码任何模型清单**——历史上有三份互不同步的副本（本枚举、ChatInterface.vue 硬编码数组、project-overview.vue 死代码），后果是「后端加模型用户看不到、前端加模型被工厂静默回落默认模型」。
- **分档计价**：OpenRouter 对部分模型按输入长度分档涨价，白名单里 4 个模型有档（qwen3.7-flash 三档，seed-2.0-lite / gpt-5.6-terra / grok-4.5 两档）。价格是 `PriceTier(minPromptTokens, inputPricePerM, outputPricePerM)` 列表，按 minPromptTokens 升序、首档下限恒为 0，取档用 `priceTierFor(promptTokens)`（负数/0 回落首档，取档不许抛异常——记账抛异常会把整条流式对话带崩）。`TokenUsageService.calculateCost` 已按档计价；**只读首档会在长上下文下系统性低报**。刻意不建模提示缓存命中价（langchain4j 0.36 的 TokenUsage 只回 input/output，拿不到命中 token 数），因此估算值对命中缓存的轮次偏高——**已知偏差不是 bug**，真花的钱以 PlatformUsageAccountant 对账为准。
- **价格漂移唯一护栏**：`AllowedModelsLiveContractTest`（联网对拍 `GET https://openrouter.ai/api/v1/models`，断言在线 + supported_parameters 含 tools + 单价一致，容差 1%）。门控 `RUN_LIVE_MODEL_CHECK=1`，默认跳过——mvn test 默认离线可跑是硬要求。首次对拍就抓到 5 条价格错，其中 kimi-k2.6 的输入/输出价分别对用户超收 14% 与 40%。**2026-08-10 第二次对拍又抓到两条**：glm-5.2 与 kimi-k2.6 的上游单价分别涨了 3.0 倍与 1.6 倍，而枚举还是旧值——方向是低报，BYOK 估算系统性偏低且没有任何东西会报警（平台通道走真实扣费，看不出来）。**因为这条护栏是 env 门控、不进 CI，漂移只会在有人手动跑的时候被发现**，所以改动模型相关的 PR 顺手跑一次 `RUN_LIVE_MODEL_CHECK=1 mvn test -Dtest=AllowedModelsLiveContractTest`。**测试红了不许放宽容差**，先核对线上再改枚举。结构性前提（首档为 0、严格升序）与区域集合大小由离线的 `AllowedModelsTest` 守。
- `service/ai/NetworkRegionService.java` — 区域判定，**走桌面本地判定（后端 JVM 信号）**，不走官网回传、不走前端 `navigator.language`（渲染进程的 `utils/zetaOfficeBoot.js` 把 navigator.language shim 成 zh-CN，前端读到的语言不可信）。API：`SETTING_KEY="ai.networkRegion"`、`MODE_AUTO/MODE_DOMESTIC/MODE_INTERNATIONAL`、`mode()`（非法值回落 auto）/ `effectiveRegion()`（返 `AllowedModels.Region`）/ `isManuallyOverridden()` / `detect()` / `detectionBasis()`。判据：`Locale.getDefault().getCountry()=="CN"` **或**时区属大陆集合 → 判大陆（effectiveRegion 返 GLOBAL，只放行区域无关模型）；**港澳台不算大陆**。误判方向刻意偏保守（宁可少给选项，不可给必然 403 的坏选项），所以手动覆盖是一等设置、设置页必须给入口。
- `controller/ai/AiModelCatalogController.java` — `GET /api/ai/models`（鉴权口径同 AiChatController：X-Session-Id → userId，null 则 401）。响应契约：`{networkRegion, networkRegionMode, networkRegionBasis, defaultModel, models:[{id,name,vendor,region,contextLength,inputPricePerM,outputPricePerM,tiered}]}`。models 只含 `AllowedModels.availableIn(effectiveRegion())`；价格取首档，`tiered=true` 表示有分档、UI 要提示「长上下文单价更高」。defaultModel 必须由 `ChatModelFactory.resolveDefaultModel()` 解析（DB `ai.defaultModel` 优先于 yml），前端自己挑「清单第一条」会和实际发出去的模型不一致。刻意不放进 AiChatController——那是被治理过一轮的胖控制器，模型目录与对话没有共享状态。
- **模型相关 system_setting 键**（DB 优先于 yml，改完必须 `chatModelFactory.clearCache()`）：`ai.defaultModel`（空→yml `ai.model.open-router.default-model`）、`ai.auxModel`（起标题/上下文摘要/记忆抽取/memory_search/文件自动打标签；空→yml `ai.aux-model`）、`ai.subagentModel`（空→`ai.auxModel`）、`ai.networkRegion`（auto|domestic|international）。
- `service/ai/OllamaProbeService.java` + `controller/ai/OllamaProbeController.java` — 本地 Ollama 只读探测，`GET /api/ai/ollama/probe?model=<可选>`（鉴权同上）。**为什么需要**：Ollama 档没有密钥可校验，向导无法用「Key 填了没有」判断可用性；改造前全仓零探测代码，用户选完本地档要到发第一条消息才收到 Connection refused。打 `{ollama.baseUrl}/api/tags`，连接与响应各 **2 秒**超时（跑在向导关键路径上）。**永远返回 200**，结论在 `status` 三态：`READY`（服务在跑且目标模型已 pull，`command=null`，nextStep 明说只支持 ASK 模式）/ `MODEL_MISSING`（`command="ollama pull <model>"`）/ `SERVICE_DOWN`（连不上、非 200、响应解析不了一律归这档，`command="ollama serve"`）。完整响应：`{status, baseUrl, targetModel, installedModels[], message, nextStep, command}`。目标模型 = system_setting `ai.ollama.modelName`（空白视为未配置）→ yml `ai.model.ollama.model-name`；query 参数 `model` 再优先于二者（向导里没保存就先试）。地址同理走 `ai.ollama.baseUrl` → yml。**这两个键的字面量定义在 `ChatModelFactory.SETTING_OLLAMA_MODEL / SETTING_OLLAMA_BASE_URL`，探测服务引用它们**：探测读的键必须与真实路由读的键是同一个，各写一份的话用户在设置页换了本机模型后会看到「探测说已就绪、对话却发给 yml 里那个模型」。模型名比对两边都补默认 tag（`llama3` ≡ `llama3:latest`）。**baseUrl 刻意不接受调用方传入**——桌面后端与云后端共用这套代码，放开等于做成 SSRF 跳板。
- **前端消费侧**（改模型选择器前先看这三条）：① `frontend/src/components/ChatInterface.vue` 的模型清单来自 `GET /api/ai/models`（`api.js` 的 `fetchAiModels()`，同一端点只有这一个函数名），下拉按 vendor 分组、`region=INTERNATIONAL` 的组排在最后并标注「需国际网络」、`tiered=true` 显示「长上下文单价更高」、每条显示首档单价；默认选中项取响应里的 `defaultModel`，**不许自己取 `models[0]`**。② 模型选择持久化在 uni storage 键 `ai_selected_model`（全局非按项目）；恢复时必须校验该 id 仍在端点返回集合里，不在则回落 `defaultModel` 并提示一次——AI 面板挂在 `v-if` 上，不落盘会静默复位，而这个选择有计费含义，静默换计价对象是本次要修的老毛病。③ `provider=OLLAMA` 时模式选择器只留 ASK（本地档不支持工具调用），判据取 `GET /api/ai/config` 的 `activeProvider`（模型目录端点不回 provider）；该字段现在由 `ChatModelFactory.resolveProvider()` 透出，与真实路由同源。
- **AI PPT 的模型与密钥**不走上面这套：由 `tools/PptxTools.buildModelConfig` 按 `ai.activeProvider` / `ai.defaultModel` / DB 密钥解析后，随 `model_config` **每次请求**下发给 pptx-service（该字段曾在 re-vendor banana-slides 时被整包替换掉，源码级存活检查在 `pptx-service/compat_smoke_test.sh`）。图像模型是常量 `PptxServiceClient.IMAGE_MODEL`，**刻意不进 AllowedModels**——它按张计费、没有 prompt/completion 单价，进白名单会破坏分档计价的前提。本地 Ollama 档下 AI PPT 在入口即拒（`FeatureNotConfiguredException(feature="ai-ppt")`），因为本地模型没有 OpenAI 兼容的图像生成接口，放行只会跑到图片阶段才失败。

**工具注册与执行**
- `service/ai/ToolRegistry.java`（428 行）— @PostConstruct 扫 AgentToolComponent 的 @Tool；getAllSpecifications / execute（反射+服务端强注入 projectId/conversationId/userId+容错类型转换）/ resolve；别名表 TOOL_NAME_ALIASES/ARG_ALIASES/LEGACY_DEFAULTS。**插件启停过滤也在这三处消费点**。
- `service/ai/XmlToolCallParser.java` — XML <tool_code> 协议兜底（位置参数按签名映射为命名参数，PR#193）。
- tools/：FileTools(12，含 create_folder/rename_project_file/move_project_file/move_file 四个 DB 感知文件树原语——直通 ProjectFileService，与前端右键菜单同路径；move_file 2026-08 由停用复活为路径版移动：按路径经 dbPathIndex 解析 project_file 记录、缺失目标文件夹自动补建，真机实证 txt 类文件拿不到 fileId 时模型会绕道 read_file+write_file 整篇重写；list_files/search_project_files 对 DB 已登记条目附带 fileId/folderId，未登记提示先 scan_files；含 extract_file_text——Tika/PDFBox 全文抽取，Word/Excel/PDF 均可读；write_docx 支持可选 parentFolderId 落指定文件夹)、LegalTools(5)、WebTools(2)、PythonTools(1)、TodoTools(1)、TaskTools(2，dev-board #53：task_create/task_list，项目级「任务/日程」的 AI 接线，落 `ProjectTaskService`。与 TodoTools 的边界是术语表那条——task_* 管跨对话持续存在、日历页可见的截止日/开庭日里程碑，todo_write 管 AI 本轮工作步骤条，本轮结束即失效，别混。task_create 走新增的 `ProjectTaskService.createAiTask`（source 恒 "ai"，与用户手建的 "user" 区分；内部委托同一份校验逻辑，未新增校验分支），projectId/userId 走 `SERVER_CONTEXT_PARAMS` 强制注入，fileId 越权校验复用 `validateFileInProject`。task_list 空结果返回明确中文文案而非空串——空白工具输出会炸 `ToolExecutionResultMessage.ensureNotBlank`，掀翻整轮对话，见下文「已知地雷」)、SubAgentTools(1，**@Lazy 防启动死环** PR#98)、EvidenceTools(2：retrieve_evidence 检索 + evidence_verify 勾稽核查，后者委托 `service/evidence/EvidenceVerifyService`，见 ai-doc-bridge「勾稽核查」)、MemoryTools(8)、DocumentEditTools(32)、CheckpointTools(1)、PptxTools(13，含 pptx_inspect_format/pptx_apply_format 走 pptx-service 自有端点 /api/pptx/*)、PdfTools(7，PDFBox 层：pdf_list_files/pdf_inspect/pdf_highlight/pdf_annotate/pdf_redact/pdf_replace_text/pdf_to_word，实现在 PdfEditService；定位类限文本型未加密 PDF、靠引用原文，fileId 必须从 pdf_list_files 拿——doc_list_project_files 不列 PDF、search_project_files 不带 ID。pdf_to_word 三路由：文本型走 pptx-service /api/pdf/to-docx 版式级(pdf2docx)→失败回退 Java 结构级提取；扫描件走 /api/pdf/ocr-markdown 本地 MinerU OCR，不用第三方云 OCR)。PptxEditTools 已删（7 个工具全走编辑器桥 ppt_* 命令，前端明确拒绝，死路径；pptx_smart_modify/pptx_get_page_screenshot 同因服务端点不存在下线）。

**记忆/证据/MCP/子 Agent**
- memory/：MemoryPipelineService（轮次结束异步触发写侧管线）、MemoryManager（检索）、AgenticRetriever、MemCellExtractor、ProjectMemoryExtractor、MemoryEvidenceFormatter（证据账本：时间锚点/来源/更新信号，PR#155）。记忆五作用域 + 拟人化排序（重要性×衰减×随机）。
- evidence/：evidence.retrieve.v1（PR#186）——EvidenceRetriever SPI + Registry + Memory/Mcp 实现。两大不变式：**缺定位符即丢弃、缺证据≠矛盾**。
- mcp/：McpClientService 门面 + StreamableHttpMcpProvider；配置驱动 mcp.servers（langchain4j-mcp 需 1.0.0+）。
- subagent/：SubAgentService（dispatch_subtask，发 subtask_progress）。内存登记簿 `running`（subtaskId → Future + 所属会话，dispatch 返回前 finally 移除）支撑 `cancel(subtaskId, conversationId)`；被停的子任务走 `CancellationException` 分支，给用户看的进度文案是「子任务已停止」（stage 仍用 `failed`，不新造 stage 值），给模型看的是「用户停的、不要自动重派」。

**SSE**
- `service/ai/SseEmitterService.java` — 连接池（cid→SseEmitter，超时 30 分钟，建连发 connected）。**所有事件唯一出口**。生产者：Orchestrator、StreamHandler、Controller、TodoListService(plan_update)、BackgroundTaskService(background_task_*/heartbeat/task_progress)、SubAgentService、EditorBridgeService。**15s 心跳广播**（@PostConstruct 调度器，穿透代理空闲回收 + 前端判活依据）；同 ID 重连会 complete 旧 emitter，回调移除一律用两参 remove(id, emitter) 防摘掉新连接。

**可靠性层（2026-08 harness 加固，治"跑一半停了"）**
- LLM timeout 600s（application.yml open-router.timeout；0.36 的单值=OkHttp callTimeout 整通墙钟上限，不是空闲超时）。
- **流式模型必须 `logResponses(false)`（`ChatModelFactory.streamingBuilder`，两个流式通道共用的唯一构建口径）**。这不是调优是可靠性契约，改回 true 会让**整个传输层错误处理静默失效**：openai4j 0.23 的 `StreamingRequestExecutor$2.onFailure` 在该开关打开时先调 `ResponseLoggingInterceptor.log(response)` 再走 errorHandler，而 okhttp-sse 的 `RealEventSource.onFailure(call, e)` 在「连接失败/被断、压根没拿到响应」这条路径上传的 response **恒为 null**（另一条 `processResponse` 失败分支才是 t==null/response!=null，两者互斥，所以 response 为 null 时 t 必非 null），于是 `response.code()` 抛 NPE；`onFailure` 只 catch IOException，NPE 掀掉 OkHttp Dispatcher 线程，**紧随其后的 errorHandler 那一行永远走不到**。表现：本轮既不 onComplete 也不 onError，SSE 零字节，只能等看门狗兜底；后端日志里唯一痕迹是 `Exception in thread "OkHttp Dispatcher" ... Cannot invoke "okhttp3.Response.code()" because "response" is null`。**排障陷阱**：真正的 IOException 在这条路上被彻底销毁（`LOGGER.debug("onFailure()", t)` 那行本身也在开关内、且全仓无 logging 级别配置停在 INFO 不打印），所以「日志里只有 NPE、看不到网络错误」不代表网络没问题——修好这个开关才拿得到底层异常。回归守护 `StreamingTransportFailureTest`（连不上必须回调 onError；它走工厂那份真实 builder，用例里自己拼 builder 就永远是绿的）。非流式 `OpenAiChatModel` 走 SyncRequestExecutor 没这条路径，**所以「辅助模型秒回成功」不能用来证明流式通道的网络正常**（不同 executor、不同 OkHttpClient/连接池、且不带工具定义）。
- **「AI 全线连不上」优先怀疑 JVM 里冻住的代理端口，不要先怀疑密钥或网络**（2026-08-16 实证，两个 e2e home + 用户真机三处复现）。macOS 上**任何 JVM 启动时都会把系统代理设置自动灌进** `http(s).proxyHost/Port` 系统属性——**不需要任何 `-D`、不需要 `JAVA_TOOL_OPTIONS`**（裸 `java Foo.java` 就已经有 `https.proxyHost=127.0.0.1`），OkHttp 走 `ProxySelector.getDefault()` 于是全部 AI 流量被送去本地代理端口。桌面后端是**长命 JVM**（开 app 起、连跑数天），启动那刻把端口**冻住**；用户的代理工具换端口或重启后（实测 1235 → 8234），后端仍在拨旧端口，**每一个 AI 请求都 `ConnectException: Connection refused`**。
  - 判定三件套：`jcmd <后端PID> VM.system_properties | grep proxy` 拿 JVM 冻住的端口 → `scutil --proxy` 拿系统当前端口 → `nc -z 127.0.0.1 <旧端口>` 确认旧端口已死。两者不一致就是它。
  - **已自愈**：`service/SystemProxyRefresher.java` 每 60s 对齐一次（`scutil --proxy` → `System.setProperty`），开关 `network.proxy.auto-refresh`（默认 true）。成立前提是 `DefaultProxySelector` 每次 `select()` 都重读系统属性、运行期 `setProperty` 立即生效（由 `SystemProxyRefresherTest` 的端到端用例守住）；**运行期打开 `java.net.useSystemProxies` 无效**（类初始化时固化，返 DIRECT），所以只能自己读 OS 再写属性。启用条件刻意收窄成「macOS + 启动时继承到回环代理」：非回环的企业代理端口稳定，动它只有风险。**启动时系统没开代理的情况不接管**（没有被冻住的旧端口，不存在要治的病），那种情况仍靠重启后端。老版本（≤ v0.16.0）没有这层自愈，临时解仍是重启 app。
  - **表现极具迷惑性，两个假信号**：① 修复前流式路撞上文那个 NPE 被吞、静默 180s，日志里只有 NPE 看不到 ConnectException；② **同步路（辅助模型起标题/记忆/分类器）会「秒回」**——但那是 RetryUtils 重试 3 次约 1.4s 全败后写入的**兜底字面量「新对话」**，不是成功。**排障时先看标题是不是字面量「新对话」**，别拿它当"通道正常"的证据。
  - **找日志别找错地方**：`-Duser.home=` 会整体改写 `~/.aiworkdeck` 的位置，e2e 后端的日志在 `<user.home>/run/backend.log`。在真实 `~/.aiworkdeck/logs/backend.log` 里翻 e2e 的证据只会得出「什么都没有」的错误结论。
- `AgentStreamHandler`：终态幂等（AtomicBoolean terminated）+ **流看门狗** armInactivityWatchdog(**首字节 60s / 停滞 180s**，5s 轮询)——两条时限刻意分开：停滞时限要照顾「生成长工具参数时中途静默几十秒」所以必须给足，而「从头到尾零字节」没有这种正当理由，合成一个值就是让用户干等三分钟。首字节这条只在 `streamedAnyToken == false` 时生效，而这恰好就是编排器判定「可安全重放」的条件，所以误杀代价上限是白跑一轮、不会让用户看到重复或半截内容。守护 `AgentStreamWatchdogTest`。
- `AgentOrchestrator.setOnError`：失败按 `LlmErrorClassifier.Kind` 分类（**七类**：RATE_LIMITED / TRANSIENT / MODEL_UNAVAILABLE / REGION_BLOCKED / **QUOTA_EXHAUSTED** / **CONTEXT_OVERFLOW** / FATAL，OpenAiHttpException 的结构化状态码优先于文本匹配），且**零 token 已流出**才允许重放。限流退避 30/60s ×2（限流窗口按分钟计，用 8/16/32 会在同一窗口连撞三次白烧预算），瞬时 8/16/32s ×3（RunGuard.llmRetries，成功轮与切模型后清零）；用户文案两套，限流说「限流等待中」不说「服务不可用」。
- **QUOTA_EXHAUSTED = 配额/余额耗尽**（2026-08 对标 dsh）：402、或 4xx + 配额语义（insufficient credits/quota/balance、quota exceeded、余额不足…）。**判定先于 429**——余额耗尽很多服务商也回 429，但它是终局：不退避（重试白烧）、不换模型（同一账户换哪个都没钱）。SSE error 载荷带 `AI_QUOTA_EXHAUSTED` 标记（`LlmErrorClassifier.QUOTA_EXHAUSTED_MARKER`），前端 useAgentStream includes 命中换中文引导（自备 Key 去服务商充值 / 平台通道去官网查额度分配）。
- **CONTEXT_OVERFLOW = 上下文超窗**（400 + 上下文语义，先于通用 400→FATAL 判定）：不退避（原样重发必撞同一个 400）、不走故障转移链，走**专用恢复通道**——`RunLoopCompactor.forceCompact`（跳过阈值判断）强制压缩后同 depth 重放一次。**重试凭证 = compact 返回了新实例（确实缩小了）**，压不动直接终态（载荷带 `AI_CONTEXT_OVERFLOW` 标记换中文引导）。预算 `RunGuard.overflowCompactions` 1 次/轮，成功轮清零（长任务「涨→压→涨→压」合法）。存在意义：主动 compaction 靠 chars/token=2 估算，中文语料系统性低估，服务商的 400 是最后的事实来源。
- **finishReason 结构化消费**（2026-08 对标 dsh，此前全链路零消费）：① `isTruncatedToolCallRound`——LENGTH + 工具调用**一律不执行**（参数被砍半后「恰好仍可解析」比解析失败更危险：半截 write_file 覆盖用户文件），且截断轮的 AiMessage **不入栈**（不执行又入栈 = tool_calls 无配对结果 → 通道 400），复用 malformedToolRounds 纠正回路 ≤2 轮，耗尽转 PAUSED（`bubble_end reason=max_tokens`）；② LENGTH + 纯文本 → 「暂停 + 继续」收尾，不装正常完成，刻意不触发记忆管线与版本落档；③ `isEmptyResponse`——正常终止 + 零内容 + **零 token 流出**（三条件缺一不可，有 token 给用户看过就绝不重放）按瞬时错误退避重试，空 AiMessage 不入栈，预算耗尽转终态错误而不是静默 FINISHED。finishReason 为 null 的通道（Ollama / 回放评测的 ScriptedStreamingModel）行为与改造前一致。测试：`AgentOrchestratorFinishReasonTest`。
- **REGION_BLOCKED = 403 的地域子类**：OpenRouter 对国际模型在境内网络返回 403「This model is not available in your region」。**不许整体放宽 403**——key 失效/额度禁用也是 403，放宽会把它们带进换模型重试变成重复扣费探测；判据是「403 + 响应体含地域语义」（多子串择一命中，`looksLikeRegionRejection`）。这是文本匹配，上游改文案会退化成 FATAL，退化方向安全（不换模型、只是文案回英文原文）。不重试（同网络重试永远撞同一个 403）但 failoverable，且 `Kind.requiresRegionAgnosticFailover()` 要求候选收窄成 `AllowedModels.Region.GLOBAL`。终态错误载荷带稳定标记 `LlmErrorClassifier.REGION_BLOCKED_MARKER`（"AI_REGION_BLOCKED"，由 `taggedErrorMessage` 拼），前端 `useAgentStream` 用 includes 命中后换成中文引导（载荷前面还拼着「Stream Error: 」，别写成前缀判断）。
- **故障转移链**（`ai.failover.models`，默认两个区域无关常青模型）：重试预算耗尽仍是限流/瞬时错误、模型下线 404（PR#144 坑的一般化）、或地域拒绝时，`switchToFailoverModel` 换模型同 depth 重放本轮并发 SSE 明示切到了哪个。候选必须在 `AllowedModels` 白名单内——非白名单会被工厂静默回落默认模型，切了等于没切；REGION_BLOCKED 还要再按 `AllowedModels.availableIn(GLOBAL)` 过滤，否则换一个同样是国际档的模型只会再撞一次 403。**计费红线：只换 modelId，通道由 `ChatModelFactory.resolveProvider()` 决定，与 modelId 无关**；平台通道下取不到 key 抛的 AccountException 原样透出并终止，绝不回退 BYOK（会花用户自己的钱）。FATAL（400/401、非地域 403 与未知错误）不换模型。
- **自动 compaction**（`context/RunLoopCompactor` + `ai.context.compaction`）：runLoop 每轮 generate 前估算 token，超「历史可用预算 × 0.8」时把中段折叠成一条摘要，保留 system prompt + 首条用户消息 + 最近 8 条。**结构感知**：保留段绝不以 ToolExecutionResultMessage 打头（拆散 tool_calls 配对会让 OpenAI 兼容通道直接 400），这也是不能直接复用 ContextCompressor 的原因——那套会把消息重建成纯文本、抹掉 toolExecutionRequests。摘要本地生成不调 LLM（交互路径中间插同步 LLM 调用等于新增一处卡死成因），上一版摘要会并进新摘要。压缩失败一律原样继续；中段不足 4 条不压，回放评测用例碰不到阈值。
  - **剪枝先于折叠**（2026-08 对标 dsh tool-result-pruner）：触发后先把中段（keepRecent 尾部**刻意不动**——模型正在引用）超过 8192 字符的工具结果剪成首 4096 + 尾 1024 + 省略标记（`PRUNE_MARKER`，提示模型要全文重调工具），只改正文不动 id/toolName（配对不断）；剪完重估、够了就完全不折叠。**必须变小**：折叠后估算不降反升就放弃折叠退回剪枝版（小中段的摘要头开销会得不偿失，溢出恢复还会拿着更大的栈白撞 400）。
  - `forceCompact(messages, modelId)`：CONTEXT_OVERFLOW 恢复通道专用，跳过阈值判断做剪枝 + 折叠；返回原实例 = 压不动（调用方据此放弃重试）。
- **StuckDetector**（先干预后熔断）：RunGuard 的单槽 lastCallSignature 换成 6 格滑动窗口，识别 A/A/A 与 **A/B/A/B 交替**（旧实现对交替完全无感，一路空转到步数预算耗尽）。首次检出只往 **messages 末位**追加 `[系统提醒]` UserMessage、工具照常执行；二次检出才拒绝执行并回喂 `Error:` 前缀的熔断反馈。末位是硬要求——只写 system prompt 的约束会被弱模型无视（PR#209 实证）。
- 截断 `<tool_code>`（有开无闭）不再静默正常收尾：回喂纠正提示重试，最多 2 轮（RunGuard.malformedToolRounds）。
- **工具执行期可取消**：两条工具循环（原生分支与 XML 兜底分支）都在**每个工具执行前**查一次 `isCancelled`，命中即 `handleCancellation` 并丢弃本轮剩余工具。此前只在 runLoop 入口与 onComplete 开头各查一次，于是「停止」在 `dispatch_subtask`（可跑 630 秒）或 AI PPT（十几分钟）中间完全不生效。已跑完的工具副作用不回滚（取消的固有语义）。**新增工具循环必须带这个检查点**。
- **工具输出的面板展示上限按工具分档**（`AgentOrchestrator.toolOutputDisplayLimit`）：默认 4000 字符，`RESULT_HEAVY_TOOLS`（dispatch_subtask / extract_file_text / pdf_inspect）16000。理由：这三个的输出本身就是要给用户核验的成果，且 dispatch_subtask 是 JSON——截断后前端结构化子任务卡直接解析失败退回裸文本。只影响 SSE 载荷大小，**不进上下文、不影响 token 与计费**（executionLog 落库存的一直是全文）。前端截断提示按 `...(截断)` 后缀判定，文案里不要写死字数。
- `ToolResult.success()` 除 "Error" 前缀外还识别 `{"error"...}` JSON 形态（编辑器桥超时曾被判 SUCCESS 致绿勾空转 30 步）；工具参数 JSON 解析失败返回可行动错误回喂模型，不再静默空参硬跑。
- connect 端点：run_state=RUNNING 时**无条件**发 state_recovery（哪怕快照为空）——前端靠它重建气泡指针，否则终态事件被守卫吞掉、isStreaming 永久锁死。
- 前端 `useAgentStream`：心跳 45s 无字节判死 + 指数退避自动重连（1s→30s 封顶）+ online/visibilitychange 钩子（模块级单例，防页面栈多实例重复订阅）；bubble_end/error/cancelled 在气泡指针为 null 时也解锁 isStreaming；sendMessage 防重入有 toast 提示。
- 线程池：`config/AsyncExecutorConfig.java` 显式 taskExecutor(16/32/队列200) + memoryExecutor(2/4)——MemoryPipelineService 的同步 LLM 调用已隔离，别再挂回 taskExecutor。
- 进程重启续跑（二期）：run 状态持久化 + 启动回收，见上文 AgentRunStateService / AgentRunRecoveryService。只有 RUNNING 跨重启复活（回收成 INTERRUPTED），FINISHED/ERROR/CANCELLED 仍是进程内状态，避免僵尸状态。

**前端消费**
- `frontend/src/composables/useAgentStream.js`（1233 行）— SSE 核心：connectSSE（fetch+ReadableStream，非 EventSource）、sendMessage、abort、handleEvent（~:352 分派）、handleTag/processTextDelta（XML 标签驱动气泡组装）、handleStateRecovery。
- `frontend/src/components/ChatInterface.vue`（3620 行）+ `AgentMessage/`（RootBubble/ProcessCard/ThinkingCard/TodoProgressCard/WalkthroughCard/TitleCard/**QuestionCard**/**SubtaskResultCard**）。
- **反问卡（`AgentMessage/QuestionCard.vue`）**：`<question>`/`<option>` 由 useAgentStream 解析成气泡上的 `question={text,options,answered}`，**反问正文不进 `bubble.content`**——卡片不接这个字段等于正文对用户不可见。`<option>` 只在 question 作用域内当标签（正文里的字面量 `<option>` 不造问题卡）。卡片挂在 RootBubble 的 main-content 之后，正文为空时只渲染选项（兼容正文仍在 content 的旧格式，防显示两遍）；可操作性沿用 `isLatest && !isStreaming && !answered` 那条链；`RootBubble.isReady/hasContent` **必须把「只有 question」也算作有可见产出**，漏了整条气泡会卡在 ghost thinking 态。点选项 = `sendMessage({prompt: 选项原文})`，**不拼「我选择了 X」**（契约 D：选项本来就短、像用户自己打的）。
- **历史回灌的 question 分支**（`ChatInterface.loadMessages`）：剥离时机必须在 `<process>` 之后（否则工具输出里复述的 `<question>` 字样会被误判成真反问）；未闭合也要认（后端 `containsQuestion` 只认起始标签）；`answered` 由「这条助手消息之后还有没有 USER 消息」判定，**刻意不学 artifact 硬写 `status:'draft'`**——那样重开会话会让已答过的问题又长出一排可点按钮。`cleanTitle` 的剥离清单也含 question，否则纯反问收尾那轮会把问题正文当会话标题。
- **工具返回结果折叠区（ProcessCard）**：渲染 `item.output`（此前后端一直下发、前端从不渲染），**默认收起**，按 items 下标记开合（依赖 items 只追加不重排）。`dispatch_subtask` 的输出按 SubAgentResult JSON 结构化成 `SubtaskResultCard`（解析失败退回纯文本，不抛异常）。截断提示的判据是 `AgentOrchestrator.truncate` 拼的 `...(截断)` 后缀——改后缀会让提示静默消失；提示文案**刻意不写具体字数**，因为上限是按工具分档的（见下）。
- **工具载荷的标签中和（两侧契约）**：工具的参数与输出被原样拼进 `<tool_code>` / `<tool_output>` 伪 XML，载荷本身可能含协议标签（读一份讲协议的文档、模型复述自己的输出、子任务 JSON 里带 `<final>`）。后端 `service/ai/AgentTagProtocol.java` 把**已知标签形状**的起始 `<` 换成 `&lt;`（SSE 与 executionLog 两条路径都中和），前端 `composables/agentTagProtocol.mjs` 按同一份清单还原。**清单只此一份**：前端 tagRegex 由 `PROTOCOL_TAGS` 生成，后端 `AgentTagProtocol.TAGS` 与它由 `AgentTagProtocolTest` 逐字对拍，Office 插件的 `KNOWN_TAGS` 必须是其超集（插件不渲染工具载荷，故不需解转义）。**中和范围刻意不是所有 `<`**：合同正文里的 `<甲方>`、`<Party A>` 必须原样呈现，全量转义会让律师在折叠区看到 `&lt;甲方&gt;`。**改动必须三处同步**（后端转义 / 流式解转义 / 历史回灌解转义），少一处的表现是「折叠区内容缺一截、剩下的半截串进正文」或「用户看到裸转义符」——两种坏法都不报错。截断在中和之前（`...(截断)` 判据不受影响）。
- **反问停机的前端状态**：独立的 `agentAwaitingInput` ref，**不复用 `agentPaused`**（那个驱动「继续」按钮，而反问要的是「回答」，塞一起等于给律师一个点了没用的按钮）；`AWAITING_INPUT` 下**不置 `isStreaming`**（后台没东西在跑，输入框必须可用）。bubble_end 两处 + run_state 一处共三处都要认。
- **后台任务可见性**：三个后台任务事件（background_task_start / task_progress / background_task_complete）已提到气泡守卫**之前**并各自 return——挂在守卫后面时，切回会话/重连后 `currentAssistantBubble` 为 null，表现是「重连后进度条再也不动」。完成态**不再 5 秒自动销毁**（改为打 `completedAt`），生命周期由 `resetSSE`（切会话清已结束的）与导出的 `dismissBackgroundTask(taskId)` 管；`BackgroundTaskIndicator` 因此必须给已结束的卡一个关闭入口（`@dismiss` → dismissBackgroundTask），否则那张卡关不掉。建连后 fire-and-forget 补拉 `GET /api/agent/tasks/active` 重建进度条。
- `step_update` 前端分派与 `handleStepUpdate` **已删**（后端零生产者）。`subtask_progress` 改推 `proc.items` 而不是 `proc.steps`——ProcessCard 里 items 与 steps 是 v-if/v-else-if 关系，解析器建的过程卡都有 items，往 steps 推永远不显示（此前子任务状态行就是这么半死的）。
- **计划审批卡（2026-08）**：ArtifactCard 对 task_list/plan/implementation_plan 三类 draft 计划内联渲染正文并给「按此推进 / 修订」按钮（仅最新一条助手消息可操作，RootBubble 的 isLatest→actionable 链）；修订态就地编辑，提交时行级 LCS 统计改动处数，handleArtifactApprove 把「已修订 N 处 + 修订版全文」回喂模型。工具过程卡一律收进可折叠组（无步骤归属的归「执行过程」组），流式中展开最新组、结束后全收起。

## 一条消息的完整链路

ChatInterface.handleSubmit（~:927）→ useAgentStream.sendMessage（确保 SSE 已连 → POST /chat）→ Controller 异步 200 → handleUserMessage（@Async：存 USER 消息→标题→SkillRouter.activateForTurn（手动 skillIds ∪ 自动命中）→发 skill_update→assemble→取流式模型→mark(RUNNING)→runLoop）→ StreamHandler.onNext 逐 token 发 SSE → onComplete 回调检测工具请求（原生 function calling 或 XML 兜底）→ dispatchTool→ToolRegistry.execute→副作用（file_change/refresh_files）→ 结果追加 messages → **递归 runLoop(depth+1)** → 无工具时收尾：artifact 解析（implementation_plan 停机待审批/task_list 继续）→ **反问停机（`<question>` → AWAITING_INPUT）** → 存 ASSISTANT → MemoryPipeline 异步 → mark(FINISHED) → bubble_end → 关 SSE。

**反问停机（`<question>`）的实现契约**（`AgentOrchestrator.containsQuestion` / `stopForUserQuestion`）：
- 判据只认**起始标签** `<question` 后接空白/`/`/`>`（正则 `QUESTION_TAG_START`，忽略大小写）。刻意不要求闭合标签：模型漏掉 `</question>` 时问句已经流给用户看了，按「有问题」停机远好过静默收尾——后者会留下一个没有下文的问句而状态显示已完成。`<questionnaire>` 这类同前缀标签不会误命中（有测试钉住）。
- **三处短路**，一处漏掉就会出现「问完了又自己猜下去」：原生 function calling 分支（递归前）、XML `<tool_code>` 兜底分支（递归前）、收尾段 3.2。截断 `<tool_code>` 的纠正回路（2.5）也加了 `!containsQuestion` 排除——不然模型「既问问题又被截断」时会被催着重发工具调用。
- **优先级契约**：`implementation_plan` 审批 > 反问 > 正常收尾。同一轮既给计划又反问时收尾为 `awaiting_approval`（那条路本来就要用户点头，且要落 artifact 文件），由回放用例 `question-loses-to-implementation-plan-approval` 钉住。
- 收尾形态照抄待审批那 6 行：saveAssistantMessage → mark(AWAITING_INPUT) → `bubble_end {"status":"awaiting_input"}` → close → return，**不递归**。刻意不触发记忆管线与版本落档（本轮未结束，用户答完那轮一并跑）。
- 连续反问不会被守卫误伤：RunGuard（StuckDetector 窗口/步数预算/重试预算）每次 handleUserMessage 新建，用户的回答是**新一轮消息**即新的 run；StuckDetector 只记录工具调用签名，反问根本不进窗口。若哪天把 RunGuard 改成跨轮复用，必须让反问轮不计入打转窗口与步数预算。
- 回放用例 `backend/src/test/resources/ai-eval/cases/cases-question.json`（5 例：带选项/纯开放式/工具后反问/同轮工具+反问不递归/审批优先）+ 单测 `AgentOrchestratorQuestionStopTest`。
- 契约 D（发送内容 ≠ 显示内容，定义见上文「关键文件」一节）在对话主链路上的接线点只有一处：`AgentOrchestrator` 存 USER 消息那一行改调六参 `saveMessage(..., request.getMessage(), request.getDisplayText())`。改动这一行等于让 `displayText` 整条通道失效（不报错，只是气泡里又回到机器口吻长句）。
- 提问时机写在 `prompts/system_prompt.md` 的 Clarification 一节（该问/不该问各四条 + 一次只问一组 + `<option>` 语法）。**刻意不做分发层强制兜底**（决策 3）：先看真机调用率，只写 prompt 的约束对弱模型是概率性的。

## SSE 事件名清单

connected / bubble_start / text_delta / artifact / token_usage / bubble_end（status: finished|paused|awaiting_approval|awaiting_input）/ error / cancelled / file_change / client_action / title_update / doc_stream_data（旧名 wps_stream_data 双轨待摘）/ state_recovery（断线重连快照）/ run_state / plan_update / **skill_update** / background_task_start|complete / task_progress / heartbeat / subtask_progress。前端分派均在 useAgentStream.handleEvent。超限 paused 契约见 PR#172。

**`skill_update`（本轮生效的 skill 清单）**：载荷 `{"skills":[{"id","name","source"}]}`，source ∈ `auto`（触发词自动命中）/ `manual`（用户在面板里主动选的，含旧字段 pinnedSkillId）；`name` 已由 `SkillRouter.displayName` 按应用语言解析（en 优先 name_en）。生产者只有 `AgentOrchestrator`，紧跟 `activateForTurn` 之后发一次。
- **每轮必发、空也发**：前端拿它做整表覆写（`useAgentStream.activeSkills`），漏发一次上一轮的 chip 就一直挂着，用户以为某个技能还生效着。
- **ASK 模式恒发空列表**：该模式不传工具、ContextAssembler 也跳过 skill 注入，手动选择在 ASK 下不参与激活——不让「面板亮着 skill、实际什么都没注入」这种显示与实际不一致的状态出现。
- 前端分派放在**气泡守卫之前**（与 plan_update 同理）：切回会话/重连时 `currentAssistantBubble` 为 null，挂守卫后面就再也收不到。
- 「新出现的自动命中 skill」才触发 chip 闪现 + toast（`skillNotice`），手动选的和连续几轮都命中的同一枚都不闪——那是噪音不是信息。

## ChatInterface.vue 内部地图

template :1-539；script :541-1879（模式/模型选择 :648-766、文件变更 :767-817、PPT 配置 :818-862、回滚 :864-920、**提交主链路 handleSubmit :921-1056**、历史加载 :1057-1226、富文本输入/粘贴 :1227-1387、文件上下文 :1388-1450、上传对话框 :1451-1726、上传实现 :1727-1879）；style :1881-3620。

## 配置

`backend/src/main/resources/application.yml` :84 起 `ai:` 段（model.provider 默认 open-router、ai.failover.*、ai.context.*（含 compaction 子段）、ai.subagent.*、ai.skills.*）；生产覆盖 application-prod.yml、桌面 application-desktop.yml。

## 已知地雷

- **改 AgentOrchestrator 构造器必须同步 EvalHarness**（已踩三次；现构造器末三参是
  TelemetryService/TelemetryTurnTracker/MatterClassifierService）。
- **工具返回空白会掀翻整轮**：`ToolExecutionResultMessage.from(req, text)` 的
  `ensureNotBlank(text, "text")` 对空串直接抛异常，用户看到的是
  「Callback Error: text cannot be null or blank」——一个返回空串的工具就能打掉整轮对话。
  两条入栈点（`AgentOrchestrator` 原生分支、`SubAgentService.executeScoped`）现在都把空白归一成
  `AgentOrchestrator.BLANK_TOOL_OUTPUT` 并**按 FAILURE 处理**（进连续失败纠正回路）。
  **新增任何往 messages 里塞工具结果的路径都要带这条归一**；XML 兜底分支因为有模板包裹不受影响。
  上游诱因是抽取层：`read_document`/`read_file` 对 **Office 格式**必须走
  `DocumentTextService`（Tika/PDFBox，与 `extract_file_text` 同一套）——docx 既不在
  `FileContentExtractorService.ALLOWED_TEXT_EXTENSIONS` 也不在 `ai.context.ocr-extensions`，
  只按那两个白名单分支就恒返回空串。抽不出正文时**返回一句可行动的说明，绝不返回空白**。
  `ContextAssemblerService` 注入文件正文的两处守卫（`<file>` 段与 `<active_document>` 段）
  也一律**判空白而不只判 null**，否则模型看到一段空 CDATA 会转头自己再调一次读取工具。
- **轮次异常终止必须落库**：`onComplete` 的 catch 走 `finishWithError(...)`（与
  `handleStreamErrorTerminal` 共用），把 `executionLog` + 已流出的部分内容 + 「[生成出错，已中断]」
  一并写进 ASSISTANT 消息。只发 SSE 不落库 = 那一轮在历史里一个字都没有（「历史对话吃消息」）。
  同理**执行日志的 `executionLog.append` 必须排在 `ToolExecutionResultMessage.from` 入栈之前**：
  入栈抛异常时排在后面的 append 不会执行，崩溃轮的过程卡整段丢失。
  内部一致性错误的 SSE 载荷带 `LlmErrorClassifier.INTERNAL_ERROR_MARKER`（`AI_INTERNAL_ERROR`），
  前端 `useAgentStream` 据此换成人话（`agentStream.internalErrorNotice`，两套 locale 成对）——
  这个标记**不由 `classify()` 产出**，是编排器直接拼的，别往 `Kind` 枚举里加。
- **XML 兜底路径的工具反馈不许谎报成功**：`<tool_code>` 分支回喂模型的
  「[System Tool Execution Log]」文案里，"The tool executed successfully." 曾是**无条件**拼进去的，
  与同一条消息里的 `Status: FAILURE` 直接打架，紧跟着还催「output `<final>` IMMEDIATELY」。
  XML 兜底是弱模型的主路径，而末位/最强指令会赢（PR#209 实证）——工具失败时模型被引导去宣布任务完成，
  用户看到的就是「AI 说做完了，其实什么都没发生」。现按 `xmlToolSuccess` 二选一：成功给原收敛指令，
  失败给纠错指令。**同一分支还补了原生分支早就有的空输出归一**（空白 → `BLANK_TOOL_OUTPUT` + FAILURE）：
  模板包裹让它不会像原生分支那样抛 `ensureNotBlank`，但「Output: 空 + 断言成功」照样把模型骗去收尾。
  回归用例 `AgentOrchestratorXmlToolFeedbackTest`。
- **读取类工具的正文必须有上限，单一来源是 `ToolFileGuard.capToolText`（80k）**：
  `extract_file_text` 一直有这个上限，`read_file` / `read_document` 没有——一次读一份几 MB 的合同
  就产生几十万字符的单条 `ToolExecutionResultMessage`，下一轮必然被服务商以上下文超限 400 挡回。
  **而且救不回来**：这条超长结果落在 `RunLoopCompactor` 的 keepRecent **尾区**（尾部平时刻意不剪），
  中段又往往不够 `minMiddleMessages` 条数，于是 `forceCompact` 恒返回原实例、编排器判「压不动」终态，
  同一份文档每次重试都必然再撞同一个 400。两道防线都要在：工具侧截断 +
  `forceCompact` 兜底剪尾（**只在 force 下**；非 force 的尾部豁免是刻意设计，别一起改掉）。
  回归用例 `OversizedToolResultRecoveryTest`。
- **文件夹上下文要走 `DocumentTextService`，不是 `FileContentExtractorService.extractText`**：
  后者的白名单（java/js/md/txt/csv…）不含 docx/xlsx/pptx/doc/pdf，恒返回空串，
  `buildFolderContext` 随后 `if (!text.isEmpty())` 把这些文件**静默跳过**——
  上下文里「### Folder Document Contents」标题下一个字都没有。17ca80d7 修的是**单文件**路径
  （`read_document` 改走 Tika）与 `<file>` 段守卫，**文件夹路径当时漏了**。
  抽不出正文的文件现在会在 `[System Note: ...]` 里点名留痕，不再凭空消失。
  回归用例 `FolderContextOfficeFormatTest`。
  （同文件的 `extractFileText` / `collectFolderContent` 有同样的白名单缺陷，但**零生产调用方**，
  本次刻意没动——要用它们之前先照 `buildFolderContext` 改。）
- **工具失败判据只认前缀，中英文各一个**：`ToolRegistry.ToolResult.success()` 认
  `Error` 前缀、`错误` 前缀与 `{"error"` JSON 形态。中文前缀是补的——MemoryTools / TagTools /
  TaskTools / EvidenceTools / PptxTools 共 37 处失败返回写的是「错误：…」，它们**自认为在报错**，
  判据却只认英文，于是全被判成 SUCCESS：过程卡给失败调用打绿勾、`appendFailureNudge` 把
  `consecutiveFailures` 清零（连续失败纠正回路对这些工具永不触发，模型能对着同一个错误
  重试到步数上限）、埋点也记 `success=true`。
  **新增失败返回必须以 `Error` 或「错误」开头**，别写成「XX 失败：…」——判据看不见。
  反过来也别改成按包含匹配：合同正文里出现「失败」「违约」是家常便饭，误判成失败比漏判更糟
  （回归用例 `ToolFailureClassificationTest` 把这条也钉住了）。
  **`GatewayException` 的 `unavailable()` 文案刻意没加标记**：`Kind.BUDGET_EXCEEDED`
  按设计「是可恢复的确认，不是失败」，一并标成失败会误伤它——要动先想清楚这一档。
- **聊天输入框的 Enter 必须先判输入法组合**：中文/日文/韩文输入时，按 Enter「上屏候选词」
  同样会派发 keydown（`isComposing=true`，部分浏览器只给 `keyCode=229`）。
  `ChatInterface.handleEnterKey` 不挡住的话，这一下会把**还没上屏的拼音**直接当消息发出去。
  编辑器侧（`zetaOfficeImeOverlay` / `zetaoffice/editor-main.js`）早就为同一类问题做了
  composing 闩，聊天输入框一直漏着。守卫必须排在 `handleSubmit` 之前。
  回归用例 `frontend/tests/project-home/frontend-audit-batch.test.mjs`。
- **聊天气泡 ID 必须走 `nextBubbleId()`，不许用裸 `Date.now()`**：用户气泡与助手气泡是在
  **同一个同步块**里先后创建的（`useAgentStream` 里 `push(createUserBubble(...))` 紧接着
  `createAssistantBubble()`），同一毫秒 = 同一个 ID。而 `ChatInterface` 的列表是
  `:key="msg.id || index"`——key 撞了之后 Vue 的 diff 会**复用错节点**：一条消息的正文
  渲染进另一条气泡、用户/助手样式串位、旧内容残留，也就是「历史对话记录杂乱无序」的一种成因。
  新增任何气泡创建点都要用 `composables/bubbleId.js` 的 `nextBubbleId()`（单调序号 + 时间戳）。
  回归用例 `frontend/tests/project-home/bubble-id.test.mjs`。
- **埋点体系**（`com.checkba.service.telemetry`，设计 docs/ANALYTICS_TELEMETRY_DESIGN.md）：
  唯一采集入口 TelemetryService.record/recordConv，字段过 TelemetryAttrWhitelist 白名单
  （新事件/字段要同步白名单 + TelemetryServiceTest + 官网仓 lib/telemetry-store.ts 的 EVENT_WHITELIST）。
  ai.turn 由 AgentRunStateService.mark 单点合成（新增终止分支走 mark 即自动覆盖）；
  ai.tool 在 dispatchTool；真实模型分布在 ChatModelFactory 的 getOrCreate* 处（请求 modelId 会被白名单改写，别埋 controller）。
  隐私红线：消息文本/文件名/原始 conversationId 永不入账本，convKey 用 InstallIdentityService 派生。
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
- **外部服务凭证有两个来源，工具侧只读一个就会静默失效**：企查查 / Tushare 的 Key 在设置页写进
  `system_setting`（`external.qichacha.key` / `external.qichacha.secret` / `external.tushare.token`），
  yml 只是兜底。`PythonTools` 注入 Python 子进程的那三个环境变量曾只读 `@Value`，于是用户填了 Key
  脚本照样拿空值——还不报「未配置」，只是查不到数据，AI 据此回答「没有查到该公司的信息」。
  取值统一走 `PythonTools.resolveExternalCredentials()`（库优先、yml 兜底、每次调用现取，
  `PythonToolsCredentialSourceTest` 钉住）。platform 档下这三个变量刻意不注入是另一条口径
  （设计文档 §5.5，随 P4 落地），别与本条混为一谈。
- 排障需要后端日志时注意：桌面端复用已在跑的后端进程时不会重建日志管道，`~/.aiworkdeck/logs/backend.log`
  会停止更新（表现为日志停在几天前）。要拿新日志先彻底退出 app 让后端随之重启。
- 防走神注入/todo_write 进度卡/文档检查点机制见 PR#161/162。
- RunGuard 阈值改动影响回放评测断言。
- 本地 Ollama 的地址与模型名有 DB 覆盖键（`ai.ollama.baseUrl` / `ai.ollama.modelName`，admin 页「本地 Ollama」分区写入）。
  **探测端点与真实路由必须读同一对键**：字面量定义在 `ChatModelFactory.SETTING_OLLAMA_BASE_URL / SETTING_OLLAMA_MODEL`，
  `OllamaProbeService` 引用它们。各写一份字面量的后果是「探测说就绪、对话却发给 yml 里那个模型」——
  这类「显示与实际不一致」正是三档改造要消灭的主线问题。改完写入侧记得 `clearCache()`
  （缓存键含模型名，但不含 baseUrl，只改地址时靠 clearCache 生效）。
- **`project_ai_message` 的索引是 2026-08 随项目概览页 A 期才补上的**：`idx_ai_message_project_created (project_id, created_at)` 与 `idx_ai_message_conversation_created (conversation_id, created_at)`，定义在实体的 `@Table(indexes = {...})` 上，由 `ProjectAiMessageIndexTest` 读 INFORMATION_SCHEMA 钉住。在此之前这张表零 `@Index`、线上只有主键索引，项目级会话汇总是全表扫描套全表扫描。四个 profile 全是 `ddl-auto: update`、无 flyway/liquibase、无 schema.sql，**索引被谁顺手删掉不会报错、只会悄悄变慢**——所以才用测试守着。（配套的「删项目清 AI 数据」级联清理仍属后续批次。）
- **会话列表有两条通道，改一条前先确认改的是哪条**：user-scoped 的 `/api/ai/conversations`（裸数组、内存态 runStatus、AI 面板历史下拉在用）与 project-scoped 的 `/api/projects/{id}/conversations`（信封、表态 runStatus、复合游标、项目概览页在用）。两者的 SQL、鉴权口径、返回形状全都不同，**共用的只有 `ProjectAiMessageService` 那三个 private 清洗方法**。改清洗逻辑会同时影响两条，改 SQL/鉴权只影响一条。

## 辅助模型、子 Agent 与身份作用域（2026-08 供应商三档改造）

- **辅助模型（便宜档）**：`ChatModelFactory.getAuxChatModel()` 是「用户看不见但每轮都在跑」的调用的唯一入口，
  模型 ID 解析链 `system_setting ai.auxModel → yml ai.aux-model`，非白名单抛
  `FeatureNotConfiguredException(feature="ai-aux-model")`（不静默回落）。已接的调用点：
  AgentOrchestrator 起标题、ConversationSummarizer 完整摘要与快速摘要、MemCellExtractor 抽取、
  AgenticRetriever 查询扩展（deep_search）、AutoTaggingService 自动打标签。**新增此类内部调用一律用它**，
  不要写死模型 ID、也不要 `getChatModel(null)`（那会落到主模型/默认模型上）。
- **模型 ID 的解析口径**：`AuxModelResolver`（`service/ai/AuxModelResolver.java`）
  ——`auxModelId()` 与 `subAgentModelId(ymlValue)`。记账必须带模型 ID：传 null 会落成 `"default"`，
  且 `AllowedModels.fromId(null)` 为空会把估算成本算成 0（token 数对、钱是 0）。
  ChatModelFactory 里还有一份同源解析，待收口成调用本类。
- **子 Agent 模型**：`system_setting ai.subagentModel → yml ai.subagent.model → 辅助模型`。
  留空**不再继承父会话**（长程任务的子任务跟着主模型跑最烧钱）；非白名单在 `dispatch` 起跑前
  拒绝派发并返回可读中文提示（不静默回落——failover 链踩过同一个坑）。
- **子 Agent 数值自洽**（SubAgentProperties 默认值，yml 若显式写了值会覆盖它，改默认要同时改 yml）：
  `timeout-seconds=630` 必须 > 单次 LLM 读超时 600s（`ai.model.open-router.timeout`），
  否则等待方先放弃而 `future.cancel(true)` 打不断阻塞的 HTTP 读，子 Agent 照样烧完 token 结果被丢弃；
  `token-budget=60000`（×chars-per-token 2.0 = 120000 字符）要能装下至少两个满额文件
  （单文件上限 50000 字符），原来 30000 会让「读一个文件」吃掉 5/6 预算。
- **记账**：子 Agent 每轮的 `response.tokenUsage()` 与上面那批辅助调用都落 `token_usage`
  （归属主会话的 project/conversation/user；辅助调用的 userId 取 PlatformAiUserScope）。
  此前 sub-agent 一行账都没有，花费被整块折进下一条主循环记录——总额对、逐条归属与逐模型分布错。
- **平台通道身份（PlatformAiUserScope）的跨线程红线**：作用域在 taskExecutor 线程建立，
  而流式回调线程、LLM 重试定时器线程、子 Agent 线程池都不继承它。现有三处重建：
  ① `ToolRegistry.execute` 按 `ctx.userId()` 重建（覆盖所有工具，含 deep_search 内部的 LLM 调用）；
  ② `AgentOrchestrator` 的 `setOnComplete`/`setOnError`/重试定时器；
  ③ `SubAgentService.dispatch` 按 `parentCtx.userId()` 重建。
  漏一处的表现是云多租户下抛 AccountException「本次 AI 调用未携带用户身份」，
  而编排器把它当「平台通道不可用」终止整轮——与真实原因无关的提示。
  护栏：`PlatformScopeCloudMultiTenantTest`（真实 PlatformAiChannel + strictMultiTenant 形态）。
- **地域拒绝的故障转移**：`setOnError` 里 `kind.requiresRegionAgnosticFailover()` 为真时，
  候选经 `nextFailoverModel(..., regionAgnosticOnly=true)` 收窄成 `Region.GLOBAL`
  （境内切到另一个国际档只会再撞一次 403）；收窄后无候选就走终态处置，
  错误载荷经 `LlmErrorClassifier.taggedErrorMessage` 带上 `AI_REGION_BLOCKED` 供前端换中文文案。
  因此 `ai.failover.models` 里至少要有两个 Region.GLOBAL 的模型，否则这条链形同不存在。

## 验证

- `cd backend && mvn test`（JDK 21！默认 25 SIGBUS）——含回放评测 OrchestratorReplayEvalTest（用例 `backend/src/test/resources/ai-eval/cases/cases-*.json`，**13 组**）+ DesktopContextSmokeTest。新增 cases-file-tree（整理文件夹/重命名的 create_folder→move_project_file→rename_project_file 链）、cases-harness-recovery（截断 tool_code 纠正回路 F-10、编辑器桥 `{"error"}` 判 FAILURE F-09）与 **cases-question**（反问停机：awaiting_input / 执行日志随停机落库 / 同轮工具+反问不递归 / 计划审批优先于反问）。`expect.promptContains` 断言编排器回喂的系统提醒确实进了下一轮上下文。
  - **地雷：`eval/RealToolBeans.instantiateAll()` 的清单必须与生产 `AgentToolComponent` 集合同步。** TodoTools 曾长期漏列，于是 `todo_write` 在整个回放评测里根本没注册——`offeredToolsInclude` 永远失败、`offeredToolsExclude` 永远通过，相关可见性断言全是空的（已补 TodoTools）。**目前仍缺 CheckpointTools 与 SlideEditTools**，补时要同时复核各用例的 offeredToolsExclude。
  - **跨类 `public static final` 常量在编译期内联**：只跑 `mvn test` 的增量编译会留下「源码一致、字节码不一致」的假失败，验证阶段一律 `mvn clean test`。
  - **`mvn clean test` 里有 3 条 skip 是常态**（env 门控：AllowedModelsLiveContractTest 要 `RUN_LIVE_MODEL_CHECK=1`、RealLlmSmokeTest 要 `OPENROUTER_API_KEY`、CrossLanguageSignatureTest 要 python），不是回归。
  - **Mockito 陷阱（踩过）**：`String.valueOf(inv.getArgument(n))` 会被 Java 重载决议挑成 `String.valueOf(char[])`（泛型 `<T> T` 推成 `char[]`），运行时抛 ClassCastException；若该 mock 的调用方把异常吞掉只 log（如 `SubAgentService.sendProgress`），表现就是「队列永远空、断言说没收到事件」，看着像生产代码不发事件。写 `inv.getArgument(n, String.class)`。
- 只跑回放：`mvn test -Dtest=OrchestratorReplayEvalTest`；真实 LLM 冒烟：`OPENROUTER_API_KEY=… mvn test -Dtest=RealLlmSmokeTest`（默认模型已换成 deepseek/deepseek-v4-flash，境内可跑）。
- 身份作用域与模型解析：`mvn test -Dtest=PlatformScopeCloudMultiTenantTest,AuxModelResolverTest,SubAgentServiceTest,AgentOrchestratorFailoverTest,AgentOrchestratorFailoverFlowTest`。
- 状态持久化/启动回收：`mvn test -Dtest=AgentRunRecoveryServiceTest`（mark 写透、RUNNING→INTERRUPTED+补标记、幂等、续跑翻回 RUNNING）。
- 工具空输出与崩溃轮落库：`mvn test -Dtest=AgentOrchestratorBlankToolOutputTest,ReadDocumentOfficeFormatTest`
  （空串工具不掀翻整轮 + 按 FAILURE 回喂；onComplete 异常路径把执行日志与错误摘要落库、error 载荷带
  `AI_INTERNAL_ERROR`；read_document 对真实 docx fixture 返回非空正文、空文档给可行动说明）。
- 前端：`npm run check:emits`；标签协议编解码 `npm run test:tag-protocol`（node:test，零依赖）；UI 链路 `npm run test:app-e2e`。
- Office 插件的标签解析：`node --test office-addin/taskpane/lib/sse.test.js`（零依赖，未进 CI）。

- **工具参数太长会把模型输出撑到截断**（实测：一章起草里 4 次）。编排器检测到 `<tool_code>` 未闭合会回喂提示让模型重发，最多两轮；**两轮还截断就把原因写进最终正文**（「参数太长…没有执行完」），不再静默收尾。根治办法不是调 max_tokens，而是别让模型回抄大参数：让工具自己把内容写进文档（尽调插件 `dd_table`/`dd_phrases` 的 `docFileId` 就是这么做的）。截断守卫覆盖 `<tool_code>` / `<todo_write>` / `<final>` 三个「开了必须闭」的标签，且**开标签自己被切断也算**（实测最短一次只输出了 `<todo_write`）；只守 tool_code 的话模型在 todo 清单里被切断就静默收尾，一轮丢四个回合。回归用例 `cases-harness-recovery.json` 的 `truncated-tool-code-persists-tells-the-user` 与 `truncated-todo-write-also-corrected`。
