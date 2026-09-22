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
- **契约 D「发送内容 ≠ 显示内容」**：`model/entity/ProjectAiMessage` 的可空列 `displayContent`（TEXT，ddl-auto 自动建列）。**语义红线：模型永远只看 `content`，用户看 `displayContent`、为空回退 `content`**——`ContextAssemblerService` 的历史栈与所有上下文组装一律读 `content`，一个字都不许改成读 `displayContent`（否则模型丢掉计划审批卡回喂的修订版全文、PPT 结果里的 fileId 这类它真正需要的细节）。写入口：`ProjectAiMessageService.saveMessage(...)` 的六参重载（五参版本 = displayContent 传 null；另有七参版本再带回退定位键 clientRequestId，见下文「回退」一节），空白一律归一为 null——「缺省 = 与今天行为完全一致」是存量兼容前提。请求侧：`POST /api/agent/chat` 可选字段 `displayText`；读侧：`GET /api/ai/history` 直接序列化实体，自动带上 `displayContent`，前端渲染 `displayContent || content`。用途是「点一个按钮时用户气泡里不该出现代拟的机器口吻长句」（病灶：计划审批卡把「我已修订计划（共 N 处改动…）」当用户消息发出去）。
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
- `service/ai/AgentOrchestrator.java` — 编排器：持久化 inbox 受理、每会话串行消费、工具边界插入新指令。普通 30 轮/过卷 120 轮/subagent 6 轮固定上限已移除；每 64 轮切出同步调用栈，保留取消、超时、资源并发、压缩及真实无进展暂停。`StuckDetector` 同时比较工具调用与结果，变化中的轮询不按重复失败处理。详见下方 2026-09-10 契约。
- `service/ai/AgentStreamHandler.java`（493 行）— StreamingResponseHandler：token 流→SSE；<bubble_type>/<artifact> 边界解析缓冲、编辑器流过滤、token 用量上报。每次 runLoop 新建实例。
- `service/ai/AgentRunStateService.java` — 每会话运行状态登记簿：RUNNING/PAUSED/AWAITING_APPROVAL/**AWAITING_INPUT**/FINISHED/ERROR/CANCELLED/**INTERRUPTED**。内存 map 是快路径，同时写透 `agent_run_record` 表（entity `model/entity/AgentRunRecord`，ddl-auto 自动建表；DB 写失败只 log 不阻断）。**新增终止分支必打状态点**（PR#173 状态机契约）。
  - **AWAITING_INPUT = 模型反问（`<question>` 标签）等用户回答**，SSE `bubble_end` 的 status 字面量是 `awaiting_input`。刻意不复用 AWAITING_APPROVAL：会话列表要把「待回答」与「待审批」显示成两种文案。停机语义与审批完全一致——答案是**下一轮普通用户消息**，不做阻塞式挂起（工具分发在流式回调线程上，撞 600s callTimeout 与 180s 看门狗；用户关掉 app 明天再来那一轮必死）。
  - **新增停机/终止状态必须同步四处**（漏一处是静默故障，AWAITING_INPUT 那次就漏了第四处）：① `service/telemetry/TelemetryTurnTracker.TERMINAL`（不加则 ai.turn 轮次永不闭合）；② `office-addin/taskpane/lib/chatSession.js` 的状态分档（**不是**单一 stillRunning：`generating` 锁输入 / `awaitingUser` 解锁并给 notice——任务窗格没有「继续」按钮，把等人类的状态并进「仍在跑」会把输入框永久锁死）；③ `frontend/src/composables/useAgentStream.js` 里 bubble_end 的 status 解析（**有两处**：无气泡兜底分支与正常分支）**外加 run_state 分支**，共三处；④ 会话列表的状态文案表与圆点判定，具体是 `pages/project-overview/project-overview.vue` 的 `convStatusLabel`（文案）+ `convDotClass` + `historyBadge`（圆点）与 `ChatInterface.recentDotClass` ——**文案表最容易漏**，只加圆点的话新状态在列表上和「待审批」长得一模一样，等于新状态白加。
  - 启动回收（`AgentRunRecoveryService`）只捞 RUNNING（→INTERRUPTED）与 INTERRUPTED（塞回内存），**AWAITING_APPROVAL/AWAITING_INPUT 跨重启保持 DB 原样、不进内存**：问题卡与审批卡由历史消息渲染，用户回来点选项照样有效；代价是重启后会话列表的「待回答」圆点会消失（`AiChatController` 的 runStatus 只读内存）。要改这条得连 AWAITING_APPROVAL 一起改，别只给一个状态开后门。
- `service/ai/TodoListService.java` — 任务清单（`todo_write` → `plan_update` 事件）。与 run 状态同款「内存 map 快路径 + 写透 DB」：新表 `agent_todo_list`（entity `model/entity/AgentTodoList`，整表 JSON 一行，ddl-auto 自动建表；写失败只 log 不阻断——进度卡坏掉不该让对话中断）。唯一读路径 `currentList()` 未命中时按 conversationId **惰性回填**：此前清单是纯内存的、进程重启即丢，而 run 状态却能回收成 INTERRUPTED 并给用户「继续」按钮，点下去清单已经没了——是个假承诺。空 list 是「查过 DB 确实没有」的**负缓存**占位（`reminder()` 每轮工具执行都调，不占位会每轮打库）；读失败刻意不写负缓存（留恢复窗口）。`purgeStaleLists()` 每日清 30 天未更新的行并摘掉内存条目。**清单刻意不并进 `agent_run_record`**：两条写路径各自 findByConversationId→save 会互相盖字段（lost update），且清理口径不同。`plan_update` 事件形状未变。
- `service/ai/AgentRunRecoveryService.java` — 启动回收（harness 二期）：ApplicationReadyEvent 把 DB 里遗留的 RUNNING 全部翻成 INTERRUPTED 并塞回内存 map（/connect 的 run_state 只读内存），同时给该会话最后一条半截 ASSISTANT 消息追加 `> **[进程中断]** …`（按「含 [进程中断] 即跳过」幂等）。前端 run_state=INTERRUPTED → `agentPaused={reason:'process_interrupted'}` → 复用「继续」按钮（发一条「继续」消息，编排器起跑照常翻回 RUNNING）。**刻意不做 runLoop 快照重放**：工具副作用无法保证幂等；恢复粒度就是「从已持久化的轮次级执行日志继续」，丢失窗口只有最后一个未完成的 LLM 轮。
- `service/ai/ContextAssemblerService.java` — assemble()：prompts/system_prompt.md + enforcement 段 + 模式约束 + Skill 注入 + 记忆 + 文件上下文 + 历史栈。**应用语言二选一（EN 版 PR5）**：注入 AppLanguageService，en-US 时基底 prompt 换 `prompts/system_prompt.en.md`（缺失回退中文版），enforcement/模式约束/系统时间格式（Locale.ENGLISH，时区仍 Asia/Shanghai）/活跃文档指引/readHint/末位提醒全部切英文文本（文件尾部的 EN_* 常量与 *En 方法）；zh-CN 路径代码与文本一字未动。**两版协议面（标签/停机条件/工具规则）必须逐条一致**——改中文版任一硬编码段时必须同步对应英文段与 system_prompt.en.md（en 文件里有 zh § 行号对照注释）。语言切换测试在 ContextAssemblerServiceTest 的「应用语言切换」组。activeContext 正文来源二选一：ContextItem.inlineContent（Office 插件等外部客户端随请求内联携带，200k 截断）优先，否则 read_document(fileId)（dev-board#800 起它走 ProjectFileTextExtractor + 落库缓存，桌面端活跃文档不再每轮整篇重抽/重 OCR）——见 resolveActiveDocumentContent；末位 [系统提醒] 两条路径共用不变。 **工具的失败回执不是正文**（dev-board#779 K8）：`read_document` 读不到时不抛异常，而是把一句英文说明当返回值交回来（`Error: File not found.` / `Error reading document: …` / `Warning: no text extracted from …`），这些串非空，原来直接通过 `content != null && !content.isBlank()` 的守卫写进 `<active_document>` 的 CDATA——模型读到的是「当前打开的文档，正文如下：Error reading document: For input string: "artifact-12"」，而末位 [系统提醒] 还在说「其正文已内联注入」。现在注入前先过 `isToolFailureText(content)`（只看首行、只认那三种前缀），认出来就落到既有的 readHint 分支明说「正文暂不可读」。前端同批在源头挡了一道（虚拟标签不再当活跃文档，见 sidebar-shell.md 的 `activeTabContext.js`）。**`<file>` 段（显式附件）同批也上了这道**：命中即**不进 CDATA**，改成 `<file id=… name=…>该附件内容暂不可读：<失败首行></file>`（英文 `This attachment could not be read: `），让模型能把「这份附件读不出来」转述给用户，而不是「引用」一句 Java 异常文案当合同原文；首行取 `toolFailureHeadline`（首个非空行，超 200 字符截断）。OCR 降级那段（`appendOcrFallbackFile`）也补了同一判据——它的 CDATA 顶着一句「以下正文由 OCR 转写而来」的横幅，失败回执落进去就成了「识别结果是这句英文」，那里保留横幅、正文位置换成 `[Empty or unreadable file]`。护栏 `ContextAssemblerServiceTest` 的五条（活跃文档 Error / Warning / 正文首行恰好以 Error 开头的不误杀，附件 Error 不进 CDATA / 附件正常正文照常注入）。
  - **法域与字形规则（dev-board#375，2026-09-02）**：zh 版基底 prompt 的「Simplified Chinese / Mainland China」是产品语言与人设，**不是法域断言**——身份段之后加了一段「适用法域以文档为准」（繁體 + 台灣法源 = 台灣法；禁止跨法域套概念；`law_*` 只覆盖内地法；**写进文档的文字跟随文档字形与用语**，「简体中文」只约束对用户的回答）。en 版对应段落在 jurisdiction-neutral 之后，措辞刻意避开 "Simplified Chinese" 字样（`ContextAssemblerServiceTest.englishModeAssemblesEnglishSystemPrompt` 断言英文模式不含它）。enforcement 段的 Language 小节两版各加一行同义规则（弱模型对 system prompt 中段视而不见，末位 enforcement 才管用）。病灶：台湾认购合约被按内地法审、简体句子插进繁體正文。
  - **系统提示按客户端能力分段（dev-board#809 / K29，2026-09-22）**：基底 prompt 里**只留与客户端能力无关的通用规则**；doc_*/sheet_*/slide_*/office_*/需要桌面端的 pdf 与 pptx 写入工具，指引全部搬进片段文件，由 `spliceToolGuidance` 在基底 prompt 的占位标记 `ContextAssemblerService.TOOL_GUIDANCE_PLACEHOLDER`（`<!-- awd:tool-guidance -->`）处按会话能力拼进去。
    - **病灶（实测「发现 A」）**：改动前基底 prompt 在 `AGENT+lowa` 与 `AGENT+none` 下**逐字节相同**，整三节（§7 文档编辑 / §8 PPT / §9 PDF）在教 doc_*、pptx 编辑、pdf 编辑，而这些在任务窗格与纯对话会话里 `isToolVisible` 一个都不放行。代价不是报错，是**白烧一轮**：none 会话每次先调 `doc_list_project_files` 拿回 `Tool not found or arguments invalid.` 才开始干活。
    - **片段清单**（每份 zh + `.en.md` 两版，共 10 个文件）：`prompts/tools-lowa.md`（LOWA，原 §7/§8/§9 + 从 §3 搬来的 `doc_start_stream` 流式写入段）/ `tools-office-word.md` / `tools-office-excel.md` / `tools-office-ppt.md`（三个宿主各一份，工具面互不相通）/ `tools-none.md`（纯后端：文件发现与读取、pdf/pptx 只读、`write_docx` 产出、文件整理）。能力→文件名的**唯一映射**是 `ContextAssemblerService.toolGuidanceStem(capability, officeHost)`。
    - **两条硬约束**：① **片段里出现的每个工具名，在那一档能力下必须真的可见**——`SystemPromptToolVisibilityContractTest` 扫真实工具类（含 `@ToolMeta.requiresHost` 声明）逐名对拍，`tools-none.md` 里更是**一个 `doc_`/`sheet_`/`slide_`/`office_` 都不许出现**（连「你没有 doc_xxx」这种反面提法也不行：提到名字本身就会诱发调用）；② **基底 prompt 与 enforcement 段是三档共用的，不许点名任何挑客户端的工具**（`basePromptNamesNoClientGatedTool` 守着；enforcement 的 Language 一行原来写着 `(doc_*/office_* edits)`，已去掉）；③ **基底与片段里反引号包着的名字，必须是真注册、且 `@ToolMeta.offerToModel` 不为 false 的工具**，否则就得进测试里那份 `NON_TOOL_IDENTIFIERS` 点名（`basePromptOnlyNamesRealOfferedTools`）——`add_memory` / `query_knowledge_base` 这两个**从来不存在**的「工具」在基底 §6 里躺了很久，已换成 `query_memory` / `save_memory` / `memory_*` 的真实口径；`delete_file`（DISABLED 那一行）与 `get_conversation_summary` 同批摘掉，它们是 K27/K28 之后「只登记不下发」的。
    - **片段属于稳定段**：内容只随（能力 × 宿主 × 语言）变，拼在 `SYSTEM_VOLATILE_SEPARATOR` 之前，不影响提示缓存命中（`ContextAssemblerServiceTest.splicedToolGuidanceStaysInsideTheCacheableStablePrefix` 钉住）。**往片段里写任何每轮会变的东西都会让缓存永久失效且不报错。**
    - **跨文件 ref_* 硬规则只有一个家**：它在活跃文档指引之后的末位 Java 块里。Office 片段**刻意不重复**它（重复会把一份拷贝放到提示前半段，直接破坏那条「末位」契约，`officeBoundaryRuleAllowsReferencesAndOpenDocEdits*` 会转红），只留一句「按本提示末尾那条跨文件硬规则办」。
    - 体量（字符，基线 origin/master 12c4a05a）：zh 基底 28673 → 14512；合计 LOWA 28928（+0.9%）、Word 17212（-40.0%）、Excel 16913（-41.0%）、PPT 16523（-42.4%）、none 16464（-42.6%）。en 基底 54052 → 27778；LOWA 55899（+3.4%，多了片段抬头与流式小节标题）、其余 -37.3% ~ -41.7%。LOWA 略涨是片段抬头注释的代价，换来的是另外四档各省四成。
    - 回放评测 `cases-capability-prompt.json`（4 例：none/office-word/lowa 三档首轮无 Tool not found + 一例专门记录「none 调 doc_list_project_files 的代价」）。`allowUnresolvedTools` 今天只有两处打开：那一例，与 K27 的 `tool-choice-law-alias-is-corrected-not-silently-rerouted`（它故意写错名字去验 `unknownToolMessage` 的指路）。同批把回放 harness 的 `RecordingToolRegistry.execute` 从单参 `resolve(name)` 改成与生产一致的 `resolve(name, conversationId)`——此前它**整个跳过了会话能力过滤**，「none 会话调 doc_*」在回放里会拿到桩输出 OK，生产里拿到的却是 Tool not found；`EvalCase.Expect.allowUnresolvedTools`（默认 false）让「本轮不许有 Tool not found」成为全部用例的默认断言。
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
- `service/ai/AllowedModels.java` 是**模型目录的唯一事实来源**（14 条：GLOBAL 9 + INTERNATIONAL 5）。枚举带元数据：displayName（中文）/ Vendor（中文厂商名，前端按它分组）/ Region / contextLength / **vision（是否支持图像输入）** / priceTiers。前端**不许再硬编码任何模型清单**——历史上有三份互不同步的副本（本枚举、ChatInterface.vue 硬编码数组、project-overview.vue 死代码），后果是「后端加模型用户看不到、前端加模型被工厂静默回落默认模型」。
- **分档计价**：OpenRouter 对部分模型按输入长度分档涨价，白名单里 4 个模型有档（qwen3.7-flash 三档，seed-2.0-lite / gpt-5.6-terra / grok-4.5 两档）。价格是 `PriceTier(minPromptTokens, inputPricePerM, outputPricePerM)` 列表，按 minPromptTokens 升序、首档下限恒为 0，取档用 `priceTierFor(promptTokens)`（负数/0 回落首档，取档不许抛异常——记账抛异常会把整条流式对话带崩）。`TokenUsageService.calculateCost` 已按档计价；**只读首档会在长上下文下系统性低报**。刻意不建模提示缓存命中价（langchain4j 0.36 的 TokenUsage 只回 input/output，拿不到命中 token 数），因此估算值对命中缓存的轮次偏高——**已知偏差不是 bug**，真花的钱以 PlatformUsageAccountant 对账为准。
- **价格漂移唯一护栏**：`AllowedModelsLiveContractTest`（联网对拍 `GET https://openrouter.ai/api/v1/models`，断言在线 + supported_parameters 含 tools + 单价一致，容差 1%）。门控 `RUN_LIVE_MODEL_CHECK=1`，默认跳过——mvn test 默认离线可跑是硬要求。首次对拍就抓到 5 条价格错，其中 kimi-k2.6 的输入/输出价分别对用户超收 14% 与 40%。**2026-08-10 第二次对拍又抓到两条**：glm-5.2 与 kimi-k2.6 的上游单价分别涨了 3.0 倍与 1.6 倍，而枚举还是旧值——方向是低报，BYOK 估算系统性偏低且没有任何东西会报警（平台通道走真实扣费，看不出来）。**因为这条护栏是 env 门控、不进 CI，漂移只会在有人手动跑的时候被发现**，所以改动模型相关的 PR 顺手跑一次 `RUN_LIVE_MODEL_CHECK=1 mvn test -Dtest=AllowedModelsLiveContractTest`。**测试红了不许放宽容差**，先核对线上再改枚举。结构性前提（首档为 0、严格升序）与区域集合大小由离线的 `AllowedModelsTest` 守。
  - **同一条护栏现在也对拍 vision 位**（2026-08-29）：判据是 `architecture.input_modalities` 含 `"image"`。两个方向严重性不同——我们标 true 而上游没有 = 图片块发给读不了图的模型换来必然的 400，必须红；我们标 false 而上游有 = 只是把用户的图白白降级成 OCR，也断言但错误信息说清是「漏标」。vision 位与单价一样是**人手抄的、会在我们背后变**，而这条护栏同样 env 门控不进 CI。
- `service/ai/NetworkRegionService.java` — 区域判定，**走桌面本地判定（后端 JVM 信号）**，不走官网回传、不走前端 `navigator.language`（渲染进程的 `utils/zetaOfficeBoot.js` 把 navigator.language shim 成 zh-CN，前端读到的语言不可信）。API：`SETTING_KEY="ai.networkRegion"`、`MODE_AUTO/MODE_DOMESTIC/MODE_INTERNATIONAL`、`mode()`（非法值回落 auto）/ `effectiveRegion()`（返 `AllowedModels.Region`）/ `isManuallyOverridden()` / `detect()` / `detectionBasis()`。判据：`Locale.getDefault().getCountry()=="CN"` **或**时区属大陆集合 → 判大陆（effectiveRegion 返 GLOBAL，只放行区域无关模型）；**港澳台不算大陆**。误判方向刻意偏保守（宁可少给选项，不可给必然 403 的坏选项），所以手动覆盖是一等设置、设置页必须给入口。
- `controller/ai/AiModelCatalogController.java` — `GET /api/ai/models`（鉴权口径同 AiChatController：X-Session-Id → userId，null 则 401）。响应契约：`{networkRegion, networkRegionMode, networkRegionBasis, defaultModel, models:[{id,name,vendor,region,contextLength,**vision**,inputPricePerM,outputPricePerM,tiered}]}`。models 只含 `AllowedModels.availableIn(effectiveRegion())`；价格取首档，`tiered=true` 表示有分档、UI 要提示「长上下文单价更高」。defaultModel 必须由 `ChatModelFactory.resolveDefaultModel()` 解析（DB `ai.defaultModel` 优先于 yml），前端自己挑「清单第一条」会和实际发出去的模型不一致。刻意不放进 AiChatController——那是被治理过一轮的胖控制器，模型目录与对话没有共享状态。响应契约由 `AiModelCatalogControllerTest` 守（2026-08-29 新建；此前 `ChatModelFactoryTest` 有一段注释声称「护栏在模型目录端点的测试里」，**而那个测试根本不存在**，端点契约一直零覆盖——别再相信那条注释）。
- **模型相关 system_setting 键**（DB 优先于 yml，改完必须 `chatModelFactory.clearCache()`）：`ai.defaultModel`（空→yml `ai.model.open-router.default-model`）、`ai.auxModel`（起标题/上下文摘要/记忆抽取/memory_search/文件自动打标签；空→yml `ai.aux-model`）、`ai.subagentModel`（空→`ai.auxModel`）、`ai.networkRegion`（auto|domestic|international）。
- `service/ai/OllamaProbeService.java` + `controller/ai/OllamaProbeController.java` — 本地 Ollama 只读探测，`GET /api/ai/ollama/probe?model=<可选>`（鉴权同上）。**为什么需要**：Ollama 档没有密钥可校验，向导无法用「Key 填了没有」判断可用性；改造前全仓零探测代码，用户选完本地档要到发第一条消息才收到 Connection refused。打 `{ollama.baseUrl}/api/tags`，连接与响应各 **2 秒**超时（跑在向导关键路径上）。**永远返回 200**，结论在 `status` 三态：`READY`（服务在跑且目标模型已 pull，`command=null`，nextStep 明说只支持 ASK 模式）/ `MODEL_MISSING`（`command="ollama pull <model>"`）/ `SERVICE_DOWN`（连不上、非 200、响应解析不了一律归这档，`command="ollama serve"`）。完整响应：`{status, baseUrl, targetModel, installedModels[], message, nextStep, command}`。目标模型 = system_setting `ai.ollama.modelName`（空白视为未配置）→ yml `ai.model.ollama.model-name`；query 参数 `model` 再优先于二者（向导里没保存就先试）。地址同理走 `ai.ollama.baseUrl` → yml。**这两个键的字面量定义在 `ChatModelFactory.SETTING_OLLAMA_MODEL / SETTING_OLLAMA_BASE_URL`，探测服务引用它们**：探测读的键必须与真实路由读的键是同一个，各写一份的话用户在设置页换了本机模型后会看到「探测说已就绪、对话却发给 yml 里那个模型」。模型名比对两边都补默认 tag（`llama3` ≡ `llama3:latest`）。**baseUrl 刻意不接受调用方传入**——桌面后端与云后端共用这套代码，放开等于做成 SSRF 跳板。
- **前端消费侧**（改模型选择器前先看这三条）：① `frontend/src/components/ChatInterface.vue` 的模型清单来自 `GET /api/ai/models`（`api.js` 的 `fetchAiModels()`，同一端点只有这一个函数名），下拉按 vendor 分组、`region=INTERNATIONAL` 的组排在最后并标注「需国际网络」、`tiered=true` 显示「长上下文单价更高」、每条显示首档单价；默认选中项取响应里的 `defaultModel`，**不许自己取 `models[0]`**。② 模型选择持久化在 uni storage 键 `ai_selected_model`（全局非按项目）；恢复时必须校验该 id 仍在端点返回集合里，不在则回落 `defaultModel` 并提示一次——AI 面板挂在 `v-if` 上，不落盘会静默复位，而这个选择有计费含义，静默换计价对象是本次要修的老毛病。③ `provider=OLLAMA` 时模式选择器只留 ASK（本地档不支持工具调用），判据取 `GET /api/ai/config` 的 `activeProvider`（模型目录端点不回 provider）；该字段现在由 `ChatModelFactory.resolveProvider()` 透出，与真实路由同源。
- **AI PPT 的模型与密钥**不走上面这套：由 `tools/PptxTools.buildModelConfig` 按 `ai.activeProvider` / `ai.defaultModel` / DB 密钥解析后，随 `model_config` **每次请求**下发给 pptx-service（该字段曾在 re-vendor banana-slides 时被整包替换掉，源码级存活检查在 `pptx-service/compat_smoke_test.sh`）。图像模型是常量 `PptxServiceClient.IMAGE_MODEL`，**刻意不进 AllowedModels**——它按张计费、没有 prompt/completion 单价，进白名单会破坏分档计价的前提。本地 Ollama 档下 AI PPT 在入口即拒（`FeatureNotConfiguredException(feature="ai-ppt")`），因为本地模型没有 OpenAI 兼容的图像生成接口，放行只会跑到图片阶段才失败。

**图片多模态（视觉直送，2026-08-29 dev-board#266）**

- **一句话**：模型支持视觉 → 图片附件作为 `ImageContent` 内容块直送模型；不支持 → 降级走既有 OCR，并在选模型时就告诉用户。降级全自动，前端不做任何拦截。
- **能力判据只有一处**：`AllowedModels.vision` 位（14 条里 11 条 true，纯文本的是 deepseek-v4-flash / deepseek-v4-pro / glm-5.2）。经 `GET /api/ai/models` 下发，**前端不许自建「哪些模型能看图」的表**。境内 GLOBAL 9 条里有 6 条支持视觉，所以这条路在境内是通的。
- **默认模型是纯文本的**（`ai.model.open-router.default-model` = deepseek/deepseek-v4-flash），所以降级是常态而不是边缘情况。前端提示必须覆盖「用户从没手动选过模型」这一档，否则绝大多数用户静默无提示。
- **判定必须落在「真正生效的模型」上**：`ChatModelFactory.resolveTarget(modelId, logFallback)` 是通道+模型 id 的**唯一解析口径**，`getChatModel` / `getStreamingChatModel` 都改成调它再分派（两份会漂移，漂移的表现是同步路与流式路发给不同模型）。能力判定走 `effectiveModelSupportsVision(modelId)`：请求里的 modelId 有三条静默改写路径（非白名单回落默认模型 / 显式本地档忽略云端模型 / 平台通道回落），按请求 id 判就会把 image 块发给读不了图的模型。**OLLAMA 档恒 false**（langchain4j-ollama 是另一套图片编组，本次不接）。
- **组装点**：`ContextAssemblerService`。图片项在 contextItems 循环里分叉——直送时**不调 read_document、不注入 `<file>` 正文**，只在 system 里留一条 `<image id name note=.../>` 标识（告诉模型图随消息发了、别再调读取工具），字节收进 `visionAttachments`，在**末位用户消息**里以 `UserMessage.from(List<Content>)` 组装（`TextContent` 必须排第一——末位提醒的注意力位置是真机日志换来的结论）。**同一张图绝不许既进视觉又进 OCR**：那会既付图像 token 又付 OCR 的钱，还给模型两份可能打架的输入。
- **降级时必须明示**：`<file source="ocr" reason="...">` 段里写清「这是 OCR 转写、你看不到图像本身、识别可能有误」。不写的话模型会把识别误差当成原文事实。
- **`DetailLevel` 必须显式传 HIGH**。langchain4j 0.36 所有不带 DetailLevel 的 `ImageContent` 重载都在构造器里硬塞 **LOW**（字节码实证），LOW 会让上游把图缩到单块低分辨率——扫描件、合同签署页的字直接糊掉，读文书还不如现有 OCR，而且不报错不告警，只是模型开始胡说。
- **PDF 永远不走视觉**：`ai.context.vision.extensions` 刻意不含 pdf（与含 pdf 的 `ocr-extensions` 是两张表，别合并）。langchain4j-open-ai 0.36 的 `InternalOpenAiHelper.toOpenAiContent` 只认 TextContent / ImageContent，`PdfFileContent` 抛 `Unknown content type`（真 jar 探针实测）。
- **判图是双判据**（fileType 优先、退回文件名后缀，同 `lowaDocKind` 的路数）：`ContextItem.fileType` 是客户端自填、原样落库、无校验，而 OCR 那条路判的是文件名扩展名；只认其一会打出「既不走视觉也不走 OCR」的空洞。
- **闸门**：单张 10MB（`vision.max-image-bytes`，与 OCR 那道闸对齐）、单轮 4 张（`max-images-per-turn`）。`ProjectFileService.getFileBytes` 一路 `readAllBytes` **没有任何上限**，今天图片不撑爆堆全靠 OCR 前面那道闸——跳过 OCR 等于绕开它。超限一律降级走 OCR 并明示，不是静默丢弃。按 contextItem.id 读字节**必须过 `ToolFileGuard.rejectIfOutsideProject`**（那个 id 来自 HTTP 请求体，可信度不比 LLM 参数高）。
- **`UserMessage.text()` 就是 `singleText()`，多模态一律抛**（0.36 字节码：text() 只有一条 `invokevirtual singleText()`；`hasSingleText()` 要求 contents 恰好一条且是 TextContent，所以「文本+图片」照抛）。取文本的单一口径收成 `context/ChatMessageText`（`of` / `imageCountOf` / `containsImage`），新增取文本处一律调它。**slf4j 的参数是提前求值的**——`log.debug("...", m.text().length())` 在 INFO 级别也照样执行，这就是接上多模态后第一个炸的点（AgentOrchestrator 那处已改）。
- **压缩层三件事**：① `RunLoopCompactor.estimateTokens` 给每张图折算 `vision.token-estimate-per-image`（1200，方向偏高）——按 0 计的话带大图的栈永远触发不了主动压缩、只能等 400；**绝不能按 base64 长度算**，一张 500KB 的图会估成 33 万 token 让每轮都强制压缩。② `forceCompact` 的最后一层兜底会**摘掉图像块**并留一行说明——剪枝与折叠都碰不到 ImageContent，不摘的话带图的栈一旦超窗就恒返回原实例、判「压不动」终态，每次重试都必然再撞同一个 400。非 force 不摘（无谓的能力降级）。③ `ContextCompressor` 的 `removeRedundancy` / `compressToolResults` 遇到含图的 UserMessage **原样保留不重建**（`UserMessage.from(text)` 只装得下文本，重建 = 静默剥图）。
- **故障转移要按视觉收窄**：`nextFailoverModel(..., regionAgnosticOnly, visionOnly)`，栈里有图时只接受支持视觉的候选。转移**只换 modelId、不换消息栈**，切给读不了图的模型是一个必然的 400，而且这个 400 会被当成新一轮错误继续往下切、一次烧完整条链。生产默认链 `deepseek-v4-flash`（纯文本）+ `qwen3.7-flash`（视觉）恰好是这个形状。
- **图片不跨轮存活（后端侧）**：`ProjectAiMessage` 只有 content 单列，历史回放只重建文本。多轮能继续看图，靠的是**前端每轮重送 contextItems**——桌面端的内联附件标签与插件端的 attachedFiles 现在都跨轮保留（桌面端那一半是 dev-board#793 K14 ③ 才补上的，此前收到 receipt 就把本轮附件过滤掉，第二轮追问模型手上一个字都没有）。`project_ai_message_attachment` 已经把「哪条消息带了哪些附件、哪些是视觉直送」落了库，但那是给历史 chip 与将来用的；**后端自动回放历史图片仍然没做**——那还要把字节重新读出来编组进消息栈，不是改一行。
- **计费**：平台通道走 `PlatformUsageAccountant` 的账户消费差分，图片怎么计价都被差分吃进去，**金额天然精确**。BYOK 估算也无需改：11 条视觉模型里只有 Gemini 3.6 Flash 有 `pricing.image`（$7.5e-7/张，可忽略），其余的图像开销全折进 `prompt_tokens`，既有分档计价天然覆盖。**口径迁移要知道**：图片从 OCR 网关（按页扣 Credits）搬到 OpenRouter key 计量后，用户会看到 OCR 那项归零、AI 花费变多——同一笔支出换了个口袋，两张账目前没有任何地方加总。
- **`logRequests` 三处已全部关掉**：openai4j 0.23 的 `RequestLoggingInterceptor.logDebug` 在调 `Logger.debug` **之前**先执行 `getBody(request)`（字节码实证），而这个版本不截断 base64——日志级别停在 INFO 一行不打印，却每次请求都把整个请求体物化成 String。一张 5MB 的图 base64 后 6.7MB，最多 30 轮。
- **`ProjectContextHolder` 必须在 assemble 最开头设置**（本次顺手修的既有 bug）：它是 ThreadLocal，`ToolFileGuard` 的项目归属从它取，而那三行 set 原来排在附件注入与活跃文档注入**之后**——@Async 线程上拿到 null 就 fail closed，那句 `Error: no project context ...` 被原样当成文件正文注进 `<file>` CDATA；taskExecutor 池化复用、assemble 从不 clear，还可能拿到**上一个项目**的 id。两种坏法都不报错。测试里 LegalTools 是 mock 的，所以这个顺序错误在单测中完全不可见——`ContextAssemblerServiceTest.projectContextIsSetBeforeAnyFileRead` 直接钉住「读文件那一刻 holder 里是什么」。
- 验证：`mvn test -Dtest=ContextAssemblerServiceTest,AllowedModelsTest,AiModelCatalogControllerTest,ChatModelFactoryTest,AgentOrchestratorFailoverTest,RunLoopCompactorTest`；联网对拍 `RUN_LIVE_MODEL_CHECK=1 mvn test -Dtest=AllowedModelsLiveContractTest`。

**上下文层：当前文档 chip / 附件跨轮 / 上限与降级可见（2026-09-22，dev-board#793 K14 + #801 K21）**

- **一句话**：任何进入模型上下文的东西（当前文档、附件、图片）在界面上都有可见表达与移除入口；任何上限在**触发前**拦截并明示；每一次降级都发 `context_notice`。
- **活跃文档与附件不再二选一**（审查 E-4）。前端原判据 `(!hasFiles && !hasImages && props.activeTab)` 只要挂了任何附件就把 activeContext 置 null，后端整个 `# Active Document` 段与末位 `[系统提醒]` 都不生成——「对照这份对方发来的 docx 改一下当前文档第 3 条」这类跨材料工作流整条被切断，模型只能去 `doc_list_project_files` 摸索或干脆新建文件（dev-board#244 同类病灶）。现在**永远带上**，正文给不给由后端一处判据决定：`ContextAssemblerService.injectActiveDocumentBody(contextItems, activeContext)`，有附件 → 只带壳（id/name + readHint，模型自己 `doc_get_document_text`），无附件 → 照旧注入正文。**只带壳不是降级成什么都没有**：模型知道它存在、知道 id、也知道怎么读。
- **`ContextItem.staleBody`**（新增可选布尔，只对 activeContext 有意义）：客户端没能在发送前把文档落盘时置 true，后端据此也只带壳。**有 inlineContent 时忽略它**——那份正文就是客户端刚从编辑器里取的当前正文。
- **发送前 flushSave（K14 ⑤，审查 E-9）**：`frontend/src/pages/project-overview/flushActiveDocument.js`（零依赖纯函数，同目录 `flushDirtyEditors.js` 的路数），工作台以 `flushActiveDocumentForChat` 作为 **prop** `flush-active-document` 传给 ChatInterface。**超时 1.5 秒**（`prepareInsightDocument` 那条是 10 秒，因为用户知道自己在等一个按钮；聊天回车等 10 秒不可接受）。**失败不阻断发送**，只把 `staleBody` 置 true。判据与 `prepareInsightDocument` 同款：`ready && !docLoadFailed && !_reloading && canWrite !== false && file.id === 目标`，保存后再核一遍 `!dirty && !saving`（「调用返回了」不等于「保存完了」）。**文件没开在 LOWA 里时返回 true**——没有要落盘的东西，返 false 只会无谓降级。
- **当前文档 chip（K14 ①，审查 E-8）**：输入框上方 `.active-doc-chip`，带 ×。合格性复用 `activeTabContext.js` 的 `isContextEligibleTab`（#914 K8：浏览器/artifact/设置这些虚拟标签不是文档）。摘除状态 `activeDocDismissed` **只活在组件里、不持久化**，换文档即复位——它是「这一轮别带」而不是一项设置。两处输入卡片（空态与常态）各渲一份。
- **附件跨轮保留（K14 ③，审查 E-2）**：发送后 `contextFiles` **不再被过滤掉**，`restoreCarriedTags` 把标签重新挂成 `is-carried`（淡态 + 虚线边）。**淡态仍然会继续带上**——这才是 E-2 要的；点 × 移除，点标签本体确认沿用（`confirmCarriedFile`，只是摘掉淡态）。粘贴图仍然发后即清（已上传成项目文件，要继续用从文件树拖回来）。配套守卫：**「只剩上一轮带过的附件、没有任何新内容」算空消息**，否则发完一轮误按一次回车就会把同一批材料顶着空 prompt 再发一遍。
  - **真机实测抓到的两个坑，都在 `restoreCarriedTags` 的结构里**：① 它**必须挂在 `draftUnchanged`（即 `shouldClearChatDraft`）那个 if 块之外**——本会话第一条消息发出去时输入卡片整块被 `v-if` 换掉（空态 `empty-flow-container` ⇄ 常态 `input-area-wrapper`，两个 `ref="richInput"`），`editorHtml` 已经变成空串、指纹必然不匹配，挂在里面的表现是「第一条之后附件全没了，第二条之后才正常」；② 换掉之后**必须 `await nextTick()` 再插标签**，同步写只会写进马上被销毁的那个 div。它还**只补缺的、不整段重建**（用户可能在这一小段时间里已经打了新的字），已经在框里的只补 `is-carried` 类。
  - **只在同一段对话里成立**：`clearAttachmentDraft()` 在新对话（`startNewChat`）、切历史会话（`loadMessages`）、换项目（`watch(() => props.projectId)`）三处清干净。不清的话换项目时那个 fileId 属于别的项目，后端 `ToolFileGuard` 会拒，用户看到「该附件内容暂不可读」却不知道自己带了这份东西。
- **`project_ai_message_attachment`（K14 ④，长期原则 3）**：`message_id / file_id(String) / name / file_type / kind(file|image|folder) / vision_used / created_at`，索引 `idx_ai_attachment_message`，ddl-auto 建表。**`file_type` 不是凑数的**：「重新生成」要按这份记录原样重建那一轮的 contextItems，而后端判图是「fileType 优先、缺失退回文件名后缀」的双判据，丢了它，一个 `fileType="image"` 但文件名没有扩展名的条目重建之后两边都不命中——既不走视觉直送也不走 OCR。写入口是编排器的 `TurnContextLedger.persistTo(messageId)`（**两个 USER 落库点都要**：主入口与插话 `applyPendingSteering`），数据来自 `ContextTurnSink`。读侧：`ProjectAiMessageService.listByConversationId` 批量回填到 `ProjectAiMessage.attachments`（**`@Transient`，不是 `@OneToMany`**——历史接口是没有事务边界的 REST 序列化，挂懒加载代理正是本仓 OSIV 那串坑的来源），`GET /api/ai/history` 自动带上，前端 `loadMessages` 重建气泡下的附件 chip。**`file_id` 是字符串**：插件侧可能带非数字客户端标识，存 Long 会当场抛。写/读失败都只 log。
- **用户气泡持有完整附件记录 + 按它重建请求（K14 ④ 补充，配合 K11「重新生成」dev-board#790）**：判据与重建都在 `frontend/src/utils/chatAttachments.js`（零依赖纯函数）——`attachmentRecord`（live 那条路：`contextFiles.value.map(attachmentRecord)`）、`attachmentsFromHistory`（历史那条路：`GET /api/ai/history` 的 attachments）、`fileListFromBubble`（把气泡上的记录还原成 `sendMessage({ fileList })` 的形状）。
  - **病灶**：气泡上的 contextFiles 原来只是 `{id,name,isDir}` 的精简副本，历史回灌出来的气泡一个附件字段都没有，于是 K11 的「重新生成」只能传 `fileList: []`——同一个问题重问一次、材料却没跟着走，模型当然给出不一样的答案，而用户以为这是「换一份回答」的正常波动。
  - **两条路的记录形状必须一致**，否则会变成「刷新前能重新生成、刷新后不能」，而这种差别不会有任何东西报错（`chat-attachments.test.mjs` 对 live 与历史两条路重建出的 contextItems 做逐字对拍）。
  - **回退与重新生成（K11，dev-board#790）共用同一份记录**：`confirmRollback` 在 `rollbackToMessage` 截断**之前**一次性取下 `source` 气泡上的全部东西（`resendPrompt` / `resendDisplay` / `rolledBackAttachments = fileListFromBubble(source)`）——截断会把那条气泡摘掉，取晚了就什么都没有了。之后分两条路：**回退**回填文字 + `restoreAttachmentsToInput` 把 @标签 也还原回输入框（只还原文字的话，用户改一个字重发材料就悄悄少了）；**重新生成**不碰输入框，直接 `sendMessage({ fileList: rolledBackAttachments })`——传空数组的话「重新生成」就成了「换一个问题」，模型手上没有当初那几份材料，而用户以为这是「换一份回答」的正常波动。
  - **附件的 `wpsFileId` 历史里没有**：它从来没有随 contextItems 上送过（只有 activeContext 带它），所以历史还原出来的记录里没有这一项——这与当初真正发出去的请求完全一致，不是丢字段。
- **`saveMessage` 现在返回 `Long`（行 id）**，三个重载（五参 / 六参 displayContent / 七参 + clientRequestId，真正落库的是七参那个）都返回，忽略返回值的既有调用方一行不用改。**USER 行落库同时要 `clientRequestId`（K1 的回退定位键）与返回的行 id（K14 的附件挂载点）**——两个落库点（主入口与插话 `applyPendingSteering`）都得同时带上，少一个就各丢一半。附件仓储走 **field 注入 + `setAttachmentRepositoryForTest`**（本服务是 `@RequiredArgsConstructor`，往构造器里加参数要牵动五处手工 new 的测试，同 `addinConvSyncService` 的理由）。
- **`ContextTurnSink`（账本）**：`attachment(fileId,name,fileType,kind,visionUsed)` + `notice(kind,fileId,name,detail)`，六个 kind 常量。**一个附件只发一条 notice**：OCR 也没读出字时 `unreadable` 盖过「怎么降级的」——两条都发的话，一次贴 6 张图界面上就是 12 行小字，用户反而看不到重点。`assemble(...)` 多了一个**带 sink 的 12 参重载**，11 参的保留给既有测试与回放评测。**mock 本服务时要 stub 12 参那个**——编排器调的是它，只 stub 11 参的话 Mockito 返回 null，表现是「组装出来什么都没有」（这次就修了 `AgentOrchestratorInboxTest` / `AddinRequestLanguageTest` 三处）。
- **配额口径统一（K21 ⑧，审查 verify.missed ①④）**：① `buildFolderContextCounted` 回传 `FolderContext(text, filesRead, unreadableCount)`，编排侧**真的递增 totalFileCount**——老实现只把它传进去算余额、回来从不递增，于是拖 3 个文件夹 = 每个都拿满额 10 份、一次注入最多 30 份正文（「多读了」比「少读了」更贵）；老签名 `buildFolderContext` 保留给三个既有测试。② **视觉直送的图片也占 `maxFilesPerContext`**，原来只有 OCR 降级分支才 `totalFileCount++`，实际可注入 4 张图 + 10 份文件，前端按 10 拦就与后端差出 4 份。
- **活跃文档截断上限解耦（K14 ⑥，审查 E-10）**：新配置 `ai.context.files.max-chars-active-document`（默认 200000）。**内联正文的上限也读它**（原来是硬编码常量 `MAX_INLINE_CONTENT_CHARS = 200_000`，注入处再按 `max-chars-per-file` = 50000 砍第二刀，实际生效 5 万，插件每轮上传的正文有一大半白传）。内联路径截断后留 `INLINE_TRUNCATION_MARKER`，注入处**据它判「已经截过了」不再截第二刀**（否则会把那个更具体的标记本身切掉）——但 `truncated` 提示照发。
- **`GET /api/ai/config` 新增 `contextLimits`**：`maxFilesPerContext / maxCharsPerFile / maxCharsActiveDocument / maxImagesPerTurn / maxImageBytes / visionCountsTowardFileQuota`。前端 `chatContextLimits.js` 的 `DEFAULT_CONTEXT_LIMITS` 只是拉不到时的兜底，**必须与后端默认值一致**；`normalizeContextLimits` 对 0/负数/NaN/缺字段一律退回兜底（拿 0 去拦截会让用户一份都加不进来）。
- **前端前置拦截**：`addFile` 走 `admitFileToContext`（到顶 toast `chat.contextFileCapReached`，重复添加静默），粘贴/拖图走 `admitPastedImage`（张数 / 体积两种理由分开报）。**判据都在 `src/utils/chatContextLimits.js`**，不许在组件里再写一份。
- **「模型看不了图」改成常驻提示（K21 ⑨，审查 E-6）**：`visionNoticeKey` 的判据是 `modelVision === false && (有粘贴图 || contextFiles 里有图)`，图片判据 `isImageAttachment` 取 fileType 与文件名后缀的**并集**（桌面端那张映射表没有 bmp，项目树里是 'other'），与插件 `chatSession.js` 同口径。提示从 `v-if="pastedImages.length > 0"` 的缩略图块里挪到输入卡片底部——原来从文件树拖进来的项目图片（走 contextFiles）完全不触发，用户拖一张现场照片进去、模型读不了图时界面上没有任何线索。**`currentModelVision` 仍是三态，未知一律不提示。**
- **Ollama 档 vision 位在模型目录端点归一为 false**（K21 ⑨，审查 E-15）：`AiModelCatalogController` 按 `chatModelFactory.resolveProvider()` 判，与 `effectiveModelSupportsVision` 对 OLLAMA 恒 false 对齐。provider 探测抛异常时**不改写**能力位（宁可维持白名单原值，也不要凭一次异常把全体云端用户的模型都标成读不了图）。
- **三种图片降级各说各的原因**（真机实测发现）：原来三条降级路共用一句「这一张图未能直送（超出本轮张数或单张体积上限，或读取失败）」，模型据此告诉用户「因超出**单张体积**限制」，而真实原因是这一轮的**张数**上限——用户会去压缩一张其实不大的图。现在 `OCR_FALLBACK_COUNT/SIZE/READ/NO_VISION` 四句各写各的，**写进 prompt 的原因与 notice 的 kind 一一对应**。前端 `contextNoticeOcrFallback` 的文案刻意**不断言原因**（那条通道也覆盖「读盘失败后改走 OCR」），「模型看不了图」由输入框上方的常驻提示负责。
- **OCR 系统提示不再被当正文**（审查 E-7）：`appendOcrFallbackFile` 现在认 `[System:` 开头并按「读不出来」处理，同时回传 readable 供 sink 发 `unreadable`。原来 `[System: 文件超过大小限制]` 非空、也不带 Error/Warning 前缀，顶着「以下正文由 OCR 从图片转写而来」的横幅进 CDATA。
- **读不出正文的单个附件不再注入空 CDATA**：与 K8 的失败回执同款，改成一句 `该附件内容暂不可读：…` 的说明（中英各一份）。
- 验证：`mvn -B test -Dtest='*ContextAssembler*,*ContextLimits*,*Attachment*,*AiConfig*,*Sse*'`；`npm run test:project-home`、`npm run check:locales`、`npm run check:emits`、`node tests/chat-presentation-ui/run.mjs`。**源码断言挡不住这一层的真问题**——「判据对了但没接上去」「接上去了但写进了马上被销毁的 DOM」这两种都只有真浏览器跑一遍才看得见（本批就是这么抓到的）。

**提示缓存（Anthropic 显式断点，2026-09-03）**

- **一句话**：`runLoop` 每轮都是无状态请求，同一段 system prompt 一轮工具循环最多重发 30 遍；Anthropic 系模型**不做自动前缀缓存**，必须我们自己在 content block 上打 `cache_control`。断点打在通道层 `service/ai/OpenRouterStreamingChatModel`；`ContextAssemblerService` 只做一件事：把易变段挪到 system 末尾并用分隔标记隔开（见下文契约），编排器一行未动。
- **供应商分两类，别搞反**（2026-09-03 核对 https://openrouter.ai/docs/features/prompt-caching）：OpenAI / Grok / Moonshot / Groq / DeepSeek / Z.AI / Gemini 2.5 **自动**做前缀缓存，请求体不需要任何标记，加标记反而是无谓的报文变更；**Anthropic 与 Alibaba（Qwen）必须显式开启**（文档原文 "require you to enable it on a per-message basis"），不打标记就一个 token 都不缓存。两家都接了。
- **Qwen 比 Claude 更要紧，别只顾着 Anthropic**：白名单里两条 Claude 都是 `Region.INTERNATIONAL`，**境内直接 403 region**（真机实证），也就是说 Anthropic 那半边只对能走国际网络的用户生效；而 `qwen/qwen3.7-flash` 是 `ai.auxModel` / `ai.subagentModel` 的默认值、子 Agent 拿它跑完整工具循环、且是 `Region.GLOBAL`。
- **判据是双份的**：`requiresExplicitPromptCache(modelId)` = 模型 id 前缀 `anthropic/` / `qwen/` / `alibaba/`（忽略大小写）**或** `AllowedModels` vendor ∈ {ANTHROPIC, ALIBABA}。只认枚举会漏掉 `ai.subagentModel` / `ai.auxModel` / 故障转移链上被配的白名单外 id（`:beta` 变体、新型号），那些请求会静默按全价跑。
- **为什么是「序列化后改写 JSON」而不是构造对象**：openai4j 0.23 的 `SystemMessage.content` 是 `String`、`Content` 只有 type/text/imageUrl 三个字段（字节码实证），都塞不进 `cache_control`。`markSystemForCaching(body)` 在 `Json.toJson` 之后改写**第一条** system 的 content。
- **摘标记必须在序列化之前**：不做显式缓存的通道走 `stripVolatileSeparator(messages)`——在 `toOpenAiMessages` **之前**把标记从 `SystemMessage` 里去掉，标记绝不能漏进报文。护栏是 `OpenRouterPromptCacheTest.nonAnthropicRequestBodyIsSemanticallyIdentical` 与 `nonAnthropicStripsSeparatorAndStaysSemanticallyIdentical`（用 `Json.toJson` 重建等价请求，**解析成树逐字段对比**），任何波及全体模型的报文改动都会让它们红。
- **请求体统一压成紧凑 JSON（`OpenRouterStreamingChatModel.compact`，dev-board#750）**：openai4j 的 `Json` 开着 `INDENT_OUTPUT`，而工具 schema 嵌套很深——**200 个工具的请求体里 23.5% 是纯空白**（213110 → 163026 字节），且工具规格每轮都要重发。直连 openrouter.ai 实测（用不存在的模型让上游读完就 400，计时不含 prefill）：紧凑 197796 字节 836ms / 缩进 262694 字节 925ms，约 **90ms/轮**，链路越慢省得越多。**token 计费不受影响**（实测 promptTokens 52861 → 52855，差值是时间戳噪音）——上游按解析后的结构重新渲染进 prompt，不是按我们的原始字节。**所以旧的「字节级快照」护栏已不成立**，改成了树对比 + `requestBodyCarriesNoIndentation`（请求体里不许出现换行）。
- **只打 1 个断点**：Anthropic 上限 4 个，本次预算全给 system（每轮重发、体量最大，Office 插件会话把最长 20 万字符的正文内联在里面）。历史消息的滚动断点、以及 OpenRouter 的「顶层 `cache_control` 自动推进断点」模式都没接。**改写失败一律原样返回**：打不上标记只是不省钱，绝不能让本轮对话失败。
- **短于最小长度不报错，只是不缓存**：Anthropic 最小可缓存前缀 Sonnet 4.x / Opus 4-4.1 = 1024 token，Haiku 3.5 = 2048，Opus 4.5+ 与 Haiku 4.5 = 4096。刻意**不在代码里判长度**——那要维护一张会腐烂的阈值表，而多打一个标记是空操作。
- **分隔标记契约（跨轮次命中的全部机制）**：`ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR` = `"\n\n<!-- awd:volatile -->\n"`，在 system 里**恰好出现一次**，由 `assemble()` 在最后一步拼上。它把 system 切成两段：
  - **稳定段（标记之前，打断点）**：基底 prompt + enforcement + 模式约束 + skill 注入 + `# User Context Files` + `# Active Document`（含最长 20 万字符的内联正文）。
  - **易变段（标记之后，不打断点）**：`# Current Context`（Current System Time / Agent Mode / Phase / Project ID / Task List ID / Plan ID）+ `## Phase Instructions` + `# 项目记忆` / `# 相关记忆` / `# 用户偏好与习惯`。
  通道层 `markSystemForCaching` 按标记拆成两个 text block，只给第一块打 `cache_control`，**标记本身被吃掉、模型永远看不到**；没有标记时退化成「整段 system 一个断点」（旧行为兜底）。
- **易变段在三种通道下的实际形态（dev-board#750，别只记住上一条）**：上一条只对**显式缓存**的通道（Anthropic / Qwen）成立——那里 system 被拆成两个 content block，只给前一块打断点。自动缓存的通道（DeepSeek / OpenAI / Grok / Moonshot / Groq / Z.AI）原先走 `stripVolatileSeparator`，易变段被原样拼回 system 的尾巴，于是**整段 system 每轮都不一样、前缀缓存零命中**；现在改成按标记拆成**紧随其后的第二条 system 消息**（`splitVolatileSystem`）。直连实测（`deepseek/deepseek-v4-flash`，33KB system + 200 工具，prompt 51477）：
  - 前后两次请求**完全相同** → `cached_tokens` = **51456**（99.9%）；
  - **只把 system 末尾的时间戳改了 7 秒** → `cached_tokens` = **0**（不是「命中到时间戳为止」，是整个前缀全丢）；
  - 把同一段易变文本**搬进用户消息**、system 保持不变 → 预热后 `cached_tokens` = **35584**；
  - 把它**单独做成第二条 system 消息** → 预热后 `cached_tokens` = **40192**，且模型确实读得到（问「当前系统时间是哪一年」答「2031」）。
  TTFT 影响（4 组配对，中位数）：命中 2870ms / 未命中 3547ms，约 **0.7s/轮**；更大的一块是**每轮少付约 4.4 万个全价输入 token**（缓存读价约输入价 1/10）。
  改前/改后 A/B（同一段 system，每轮换时间戳，各 6 轮）：拼成一条 `cached_tokens` = 0/0/0/1792/1792/1792；拆成第二条 = 45568/33792/45568/45568/47360/33792。
  **三条硬约束**：① 模型读到的文字一字不变，变的只是消息边界（第一条 = 标记之前，第二条 = 标记之后，测试里对拼接结果做了逐字断言）；② 适用范围是 `splitsVolatileSystem` 里那份**逐个实测过的名单**（`VERIFIED_MULTI_SYSTEM`），不是推导出来的规则——判错的代价是 400 打掉用户一整轮，收益只是省钱，所以「没有正面证据就不拆」。2026-09-21 探针（每个模型一次请求、不预热，第二条 system 里塞一个只出现在那里的事实「2031年」再问它）：deepseek-v4-flash / deepseek-v4-pro / glm-5.2 / kimi-k2.6 / kimi-k3 / seed-2.0-lite / minimax-m3 / grok-4.5 **全部 200 且答出 2031，放行**；`openai/gpt-5.6-terra` 与 `google/gemini-3.6-flash` 拿到 403 `This model is not available in your region.`——**是本机出口被地域拦，不是拒收多条 system**（单条 system 的最小请求同样 403，换走本机系统代理仍 403），属「未能验证」不是「验证失败」，一律退回拼接。**2026-09-22 复验（dev-board#812 K32 ④）：那两条已经连得上了，接受性探针也都过了（HTTP 200 + 答出 2031），但仍然不加**——因为名单的判据不是「能不能拆」而是「拆了省不省钱」，而 A/B 缓存对拍（56260 字符稳定段 + 每轮变化的易变段，各 4 轮）显示两条都是**零收益**：`openai/gpt-5.6-terra` 两种形态 `cached_tokens` 全是 0；`google/gemini-3.6-flash` 两种形态都是 8170，而且**两种形态的 prompt_tokens 完全相等（13801）**——证实 OpenRouter 把多条 system 合并成了一个 `system_instruction`，拆开在线上是不折不扣的空操作（这也把原来只是推测的那条 Google 理由坐实了）。③ 要新开一个模型，先补一次同样的探针（**HTTP 200 + 答得出第二条里的事实**两条都要，现在有可重跑的 `MultiSystemSplitLiveProbeTest`，`RUN_LIVE_MODEL_CHECK=1` 门控），**再跑一次 A/B 确认 `cached_tokens` 真的变好**，两样都有了才把 id 加进 `VERIFIED_MULTI_SYSTEM` 并在那段 javadoc 的表里补一行；白名单外的 id（自配 aux/subagent 模型、故障转移新型号、`:beta` 变体）恒不放行。
- **地雷一：任何每轮可能变的内容，一律 append 到标记之后**。写进稳定段的话缓存永远不命中，**不报错、只是静默按全价计费**——没有任何东西会告诉你。特别注意：`# 相关记忆` 是按 `userPrompt` 现查的（`ContextAssemblerService` 里 `retrieveMemories(projectId, userPrompt, null, 5)`）且排序带随机项，**同一个问题两次的结果都可能不同**，所以它必须在标记之后（这就是为什么易变段不止 `# Current Context` 那几行）。同理，**`assemble()` 里 `systemText.append(SYSTEM_VOLATILE_SEPARATOR)` 那一行之后再往 `systemText` 追加任何东西，都会掉进被缓存的前缀里**——新增注入段要么放在标记之前（确认它稳定），要么 append 到 `volatileText`。
- **「末位」语义没有被破坏**：本仓真正的末位约束是**挂在用户消息尾部**的 `[系统提醒]`（`activeDocumentReminder`），不是 system 的尾巴；system 里那些「必须/禁止」类指引（#419 的 `office_replace_batch` 批量指引、纯文本约束等）仍在稳定段的末尾，易变段排在它们之后不算把它们从末位挤走——易变段是状态与记忆，不是行为约束。新增行为约束仍按老规矩挂用户消息末位。
- **验证「稳定」这件事本身要靠测试**：`ContextAssemblerServiceTest.volatileFieldsLiveAfterTheSeparator`（标记恰好一次、时间戳/阶段/阶段指引都在标记之后、内联正文在标记之前）与 `stablePrefixIsByteIdenticalAcrossTurns`（连续两次 assemble 的标记前半段逐字节相同）。
- **turn 内本来就命中**：`assemble()` 每条用户消息只调一次（`AgentOrchestrator:512` 是全仓唯一调用点），递归 runLoop 复用同一个 messages 列表，所以一轮工具循环里第 2..N 次往返用的是同一段 system。分隔标记要解决的是**跨轮次**那一档。
- **可观测性是唯一的判据**：`ReasoningStreamingHandler.onCacheUsage(promptTokens, cachedTokens, cacheWriteTokens)`（第三个 default 方法）→ `AgentStreamHandler` 打一条 info `Prompt cache conv=… model=… promptTokens=… cachedTokens=… cacheWriteTokens=…`。**没有它，system 里多一个变动的字节就会让缓存永久失效而无人知晓。** 字段两套名都认：OpenRouter 统一的 `prompt_tokens_details.cached_tokens` / `cache_write_tokens`，以及部分直通时露出的 Anthropic 原生 `cache_read_input_tokens` / `cache_creation_input_tokens`——**openai4j 0.23 的 `Usage` 一个都没有**（只多一个 `completion_tokens_details`），只能从原始 JSON 树读，所以 `Response.tokenUsage()` 这条路是死的。没有缓存字段时不回调（避免日志里全是 `cached=0`）。
- **不改计费**：`TokenUsageService.calculateCost` 与 `AllowedModels` 的定价建模一字未动，BYOK 估算命中缓存的轮次仍偏高（已知偏差，`AllowedModels` javadoc 的「刻意不建模提示缓存命中价」那段仍然成立）；平台通道走 `PlatformUsageAccountant` 真实扣费，天然精确。把缓存读价（约输入价 1/10）与写价（5 分钟 TTL = 输入价 1.25x）建模进单价表是另一张卡。
- 验证：`mvn test -Dtest=OpenRouterPromptCacheTest,ContextAssemblerServiceTest,OpenRouterStreamingChatModelTest,StreamingTransportFailureTest,AgentStreamHandlerReasoningTest`。

**首 token 之前的准备链（dev-board#812 K32，改 assemble / 通道构建前必读）**

- **扇出并行（K32 ①）**：`assemble` 开头把五段彼此独立的读取提交给 `contextExecutor`——记忆索引（`memoryDocumentService.contextIndexes`，共享空间时会走 HTTP）、项目记忆、关键词记忆检索、用户级记忆、全量历史加载——让它们与紧接着最慢的「逐个附件抽取」重叠，到各自注入点再 join。**拼接顺序与文本一字不变**，join 的位置就是原来调用的位置。
  - **池必须是独立的、且是 CallerRunsPolicy**（`AsyncExecutorConfig.contextExecutor`，8/16/队列 64）。不能复用 `taskExecutor`：assemble 本身就跑在它上面，同池提交再阻塞等 = 池内死锁；而且那个池是 **AbortPolicy**，队列满时提交会抛 `RejectedExecutionException`，直接掀翻一轮正常对话。CallerRunsPolicy 的退化方向是「就地串行跑掉」——慢一点，绝不会死也绝不会抛。
  - **ThreadLocal 必须显式重放**（`fanOut` 里统一做）：`ProjectContextHolder`（`ToolFileGuard` 的项目归属）与 `PlatformAiUserScope`（花谁的额度）都不跨线程池传递。今天这几个任务全是纯 DB 读，但裸提交等于埋雷——哪天有人往 `contextIndexes` 里加一条走 `ToolFileGuard` 的路径，它会 fail closed 返回一句 `Error:` 而**不是抛异常**，静默注进上下文。任务跑完必须 `clear`：池是复用的，留着值下一轮可能拿到**上一个项目**的 id。
  - **异常语义不变**：`join` 会包一层 `CompletionException`，`fanOut` 拆掉包装把原异常原样抛回，否则上层按异常类型分支的代码会全部落空。
  - **池为 null 时整条退化成串行**（各单测、回放评测、手工 `new` 的实例走这条），输出逐字节相同——护栏 `ContextAssemblerServiceTest.parallelAssemblyProducesByteIdenticalOutput`（该用例对「并行分支少追加一段」这种改动会转红，已实测）。
- **读路径不许同步写（K32 ②）**：`MemoryManager.touchMemories` 投递到 `memoryExecutor` fire-and-forget。`lastAccessedAt` 纯粹是使用统计、本轮没有任何东西读它，而它挡在首 token 前面，还会对命中的记忆行取写锁、与 `MemoryPipelineService` 的异步写侧管线抢同一批行。池未注入时就地执行（行为与改造前一致）。护栏 `MemoryTouchAsyncTest`。
- **基底 prompt 按语言各缓存一份、项目记忆一轮只读一次（K32 ③）**：原来每轮 new 一个 `ClassPathResource` 读 28926（中）/54095（英）字符，打成 jar 后是每轮一次 zip entry 解压；`getProjectMemory` 则在注入处与压缩处各读一次。**读失败不进缓存**（返回兜底文案但下一轮再试），免得一次偶发 IO 故障把整个进程的 prompt 钉死成「You are a helpful AI Assistant.」。
- **OkHttp Dispatcher（K32 ⑤）**：`ai.model.open-router.max-requests-per-host`，默认 **32**。不配就是 OkHttp 默认的 **5**，而我们所有流量打同一个 host——全进程同时最多 5 轮对话在途，第 6 个并发会话开始排队（请求停在 `readyAsyncCalls` 里根本没发出去，表现是「一直转圈、没有报错」，看门狗的首字节 60s 还会先把它判成死流去重试/切模型）。更要命的是这个槽位在**整轮工具执行期间**都被占着（工具执行与递归 runLoop 都在 OkHttp 回调线程里，编辑器桥 180s / `dispatch_subtask` 630s / AI PPT 十几分钟）。Dispatcher 挂在被缓存的 client 上，所以改这个值要 `clearCache()` 才生效。全局 `maxRequests`（默认 64）没动，是外层上限。
- **请求体一遍序列化（K32 ⑧）**：`compactJson(rb.build())` 取代 `compact(Json.toJson(...))`。原来是「序列化（带缩进）→ 解析 → 再序列化」三遍，对象是约 160KB、工具 schema 嵌套很深的请求体，每轮都做。敢直接换 mapper 的依据：openai4j 0.23 的 `Json.OBJECT_MAPPER` 经字节码确认就是 `new ObjectMapper().enable(INDENT_OUTPUT)`——没有命名策略、没有 mapper 级 NON_NULL，那些约定全在 DTO 注解上。**输出必须逐字节相同**，护栏 `OpenRouterPromptCacheTest.compactSerializationMatchesTheOldThreePassPipeline`。dev-board#750 的紧凑输出契约与 `requestBodyCarriesNoIndentation` 都保留。
- **建连与 POST 并行（K32 / 审查 C-04）**：`useAgentStream.sendMessage` 不再 `await connectSSE` 才发 POST——两件事同时在途，POST 受理之后才 `await connectPromise`（失败仍走同一条错误处置，只是不再挡在前面）。病灶是后端每轮收尾主动关流（`endRunAndDrain`），而 `connectSSE` 只在 `sseAbortController && isConnected` 时短路，于是**第二条及以后的每一条消息**都要先付一次完整建连往返，且整段串在「按下发送 → 首字」里。
  - **后端必须同时有那道护栏，否则这条改动会丢事件**：`SseEmitterService` 新增「已送达水位」`deliveredHighWater`（只在 `emitTo` **写成功后**推进），`replaySince` 在**没有 Last-Event-ID 时**改用它当游标。此前空游标一律返回 0——而**一条会话的第一轮根本没有游标可带**，于是 POST 抢先发出的那几个事件进了缓冲却再也捞不回来。
  - **刻意不是「没游标就把整个缓冲倒出来」**：那样刷新页面会把已经从 `/api/ai/history` 读到的内容再收一遍，变成重复正文。水位保证「送达过的绝不重发」，刷新场景下补发数恒为 0（既有用例 `noLastEventIdReplaysNothing` 的结果因此不变）。水位必须与 `replayBuffers` 一起 purge，单独清掉会让空游标把「其实都送达过」的那段再倒一遍。
  - **实测（隔离后端 9885）**：本机建连本身只要 **4~6ms**（历史 0/3/8 轮分别是 4/4/6ms 中位），所以桌面端本地这条省下的时间在 LLM 首字（2.4~5s）面前可以忽略；它治的是云后端 + 慢网络那一档，**本机量不出来，别拿本机数字说它快**。真正在本机能验到的是护栏：把窗口人为拉到 1.2/2.5 秒后，baseline 丢 `skill_update`（`inbox_updated` 也只收到 1 条），本改动全部补回（3 条）。正文不受影响是因为模型首 token 本来就在 2 秒之后，那时连接早已建立。
- **text_delta 按帧合并（K32 ⑨）**：`AgentStreamHandler.emitText` 合流，**16ms 窗口或累计 200 字符，谁先到**；**本轮第一段文本不进窗口、立即发**（首字节感知不许变慢）。选后端合并而不是前端合并：前端合并只省 Vue 那一半，后端的 JSON 转义、重放缓冲入队/裁剪、HTTP chunk 三笔照付，而且 SSE 重放缓冲按**条数**裁剪，条数降不下来断线重连要补发的量就降不下来。事件语义一字未变（前端仍然只是把 content 追加上去），前端不用改。**实测（隔离后端 9885，同一组 6 轮对话，origin/master vs 本改动）**：`text_delta` 条数纯对话 16/25/20 → 5/7/9，带历史的多轮 61/27/55 → 31/10/23。另一个顺带被治住的问题：`SseEmitterService.REPLAY_MAX_EVENTS = 512`，而**一条 600 token 的回答在合并前就已经把重放缓冲冲爆了**（单测实测留下 511 条、最早的那些被挤掉）——断线重连补发时正文开头是缺的。
  - **每一个会改变事件顺序的地方都必须先 `flushPendingText()`**：artifact、token_usage、bubble_end、error，以及交给编排器回调之前。漏一处的表现是「气泡已经结束了，正文才姗姗来迟」，而且只在合并窗口恰好没到期时偶发。
  - **兜底 flush 刻意不复用 `WATCHDOG` 那条单线程**：它跑的是「判流有没有死」的轻量检查，而 flush 里要 `emitter.send`（写 servlet 输出流，慢客户端会阻塞）——混在一起时一个卡住的浏览器就能把全进程的流看门狗拖停。绝大多数 flush 其实由生产者线程（OkHttp 回调，也就是今天每个 token 发送所在的同一条线程）上的字符数/时间判断触发，调度器只管「停了但缓冲里还剩一小截」那一种尾巴。护栏 `AgentStreamTextCoalescingTest`。

**一轮对话的时间都花在哪（dev-board#750 实测口径）**：`TurnTimings`（`service/ai/TurnTimings.java`）在 DEBUG 下打两条分段行——`[Timing] prep`（落库 / skill 激活 / 组装 / 取模型）与 `[Timing] round`（本地压缩 / 工具规格准备），`[Timing] assemble` 再把组装拆成 prompt/files/activeDoc/memIndex/memory/historyLoad/historyCompress。**默认 DEBUG 关闭时是共享的空操作实例，零分配零输出**，要看就临时 `--logging.level.com.checkba.service.ai=DEBUG`。本机实测结论：**服务端准备段 3~25ms，历史加载恒 0ms（8 轮会话里一路不涨）**，而同期 `Stream TTFT` 是 2.6~5.7s——**「AI 慢」几乎全在上游，不在我们这一侧**；总时长则由**轮数**决定（实测一条会话第 1 轮 2 个 LLM 往返、第 6 轮 7 个）。排「为什么慢」时先看 `[Round]` 的轮数，再看 `Stream TTFT`，最后才怀疑 `[Timing]`。

**工具注册与执行**
- `service/ai/ToolRegistry.java`（428 行）— @PostConstruct 扫 AgentToolComponent 的 @Tool；getAllSpecifications / execute（反射+服务端强注入 projectId/conversationId/userId+容错类型转换）/ resolve；别名表 TOOL_NAME_ALIASES/ARG_ALIASES/LEGACY_DEFAULTS。**插件启停过滤也在这三处消费点**。
  - **`TOOL_NAME_ALIASES` 现在是空表，而且应当一直是空表**（dev-board#807，审计 A11）。别名的代价是**静默改道**：模型以为调了 A、实际跑的是 B，回喂里一个字都没提。最后一条 `search_laws → search_web` 已删——它把「查法条」改道成一次**公网搜索**，而仓里有 law_search / law_search_keyword / law_recognition / get_law_article 四个真法源工具，模型拿到网页摘要却当法条原文引用，在法律场景里是直接的正确性风险。要容错模型写错的工具名，**改 `UNKNOWN_TOOL_HINTS`（not-found 时的指路文案），不要往别名表里加**：那里只多花一次 LLM 往返，而且日志里看得见模型原本想调什么。XML 兜底分支也会把这句指路原样带给模型（只回一句 "Unknown tool" 它无从纠正，下一轮多半换个同样不存在的名字再试）。护栏 `ToolRegistryTest` + `ToolChoiceSurfaceTest`。
- **LEGACY_DEFAULTS 只许给「可选参数」代填，绝不许给必填参数代填**（审计 A4）：`bindArguments` 的顺序是**先补缺省再转换**，所以这里填了值、方法里的 null 守卫就永远走不到——等于把一处写好的防护重新打开。踩过的坑：`doc_get_paragraph.paragraphIndex` 与 `doc_modify_paragraph.paragraphIndex` 曾缺省 1，而 `DocumentEditTools.rejectBadParagraphIndex` 正是为「模型漏传段落号」写的守卫，结果 doc_modify_paragraph 漏传时不报错、而是对**第 2 段**（0 基 index=1）做一次模型从未主张过的整段替换（修订模式下用户还很可能直接接受）。两条已删；`doc_find_replace.replaceAll` 也搬回工具自身（口径不变：不传即替换全部，只想改第一处必须显式 false）。留下的四条都是真·可选参数。回归 `ToolRegistryLegacyDefaultsTest`（走整条 execute→bindArguments→方法 的链路；直接调方法的 `ParagraphIndexBaseTest` 绕过 bindArguments，证明不了这件事）。
- **工具可见性是五层闸，判据分别在五个地方**（改任一层前先分清是哪一层）：
  ① **会话客户端能力**（`ClientCapabilityService.isToolVisible`）：LOWA 会话只见 doc_/sheet_/slide_，
     Office 插件会话只见 office_* 且按宿主 Word/Excel/PowerPoint 再分，none 两者皆无；
  ①b **工具自报的宿主依赖**（dev-board#799，`@ToolMeta.requiresHost = NONE|LOWA|OFFICE`）：
     叠在前缀链**之上**的声明层，**只收窄、绝不放宽**（两层都通过才可见）。前缀链是既有公开契约、
     一行没动；无前缀的工具从此自己声明，不再往 `ClientCapabilityService` 里塞名字清单。
     **判据是收尾经不经 `EditorBridgeService` 的四个文档级 UI 指令**——`sendOpenFileAction` /
     `sendReloadFileAction` / `sendTextReloadFileAction` / `sendPptConfigAction`，这四条都指名
     一份文档、要求桌面前端把它打开或重载。`sendRefreshFilesAction`（刷文件树）与
     `sendComponentRequiredAction`（引导下载组件）**刻意不算**：它们是环境通知不是交付物，
     按它们判会把 `write_docx` / `create_folder` 一并锁进 LOWA，Office 会话里连新建文件都做不了。
     今天声明 LOWA 的 12 个：`pptx_open_file` / `pptx_generate` / `pptx_apply_format` /
     `litigation_render` / `litigation_timeline_render` / `pdf_to_word` / `pdf_highlight` /
     `pdf_annotate` / `pdf_redact` / `pdf_replace_text` / `text_write_file` / `text_find_replace`。
     **代价是真的**：后两族（pdf_* 与 text_*）的写入本身是纯服务端的，声明 LOWA 等于在
     Office/none 会话里一并收走那份能力——这是按审计口径做的取舍（那些会话里用户既没有文件树
     也没有预览，拿不到结果，而工具还在承诺「编辑器会重载」），不是顺手扩大的。
     **只读面没动**：`pdf_list_files` / `pdf_inspect` / `pptx_inspect_format` / `pptx_list_files`
     在任务窗格会话里照常可见——收窄的是「改」不是「读」。
     声明清单与「谁在发那四个 send」的绊线都在 `ToolDeclarationContractTest`（逐名钉住 + 扫源码对拍）。
  ② **活跃文档类型**（dev-board#729 ①，同一个方法的三参重载 + `visibleForDocKind`）：
     docx 隐藏全部 slide_* 与除 `sheet_create_file` 外的 sheet_*；xlsx/pptx 反过来隐藏 doc_*，
     但 `KIND_AGNOSTIC_LOWA_TOOLS` 里的**六个工具永远保留**——判据是
     「这个工具的效果不依赖活跃文档的类型」，两类：
     **纯后端**（不经 `executeEditorCommand`）的 doc_list_project_files / doc_open_file /
     doc_search_related_docs / doc_restore_checkpoint（裁掉它们 xlsx 会话就再也打不开 Word 文档、
     Calc/Impress 也没有后悔药了）；**「新建并打开一份新文档」**的 sheet_create_file 与
     doc_start_stream（作用在新建出来的那份文件上，不是活跃文档）。
     **判不准一律倒向全集**：kind 为 null / 空 / "text" / 未知值都不裁。
     另有一张**按类型逐个放行**的小表 `EXTRA_DOC_KINDS_BY_TOOL`（dev-board#799）：
     `doc_undo` / `doc_redo` **只放给 xlsx，不放给 pptx**。审计（A3/B-03）主张两类都放，
     理由是「Calc 与 Impress 都没有修订痕迹、撤销是仅剩的细粒度安全网，而这两个工具只是
     `executeEditorCommand("undo")`、与文档类型无关」——前半句对，后半句只对一半。
     lowa-e2e 真引擎逐条验过（`frontend/tests/lowa-e2e/undo-redo-kinds.mjs`，**判据是回读
     文档内容而不是返回值**）：Calc 上 `sheet_write_cells` 之后 undo 真的把 A1 改回原值、
     redo 再回到新值；**Impress 上 undo 返回 `{"success":false,"undone":0,"message":"nothing to undo"}`，
     内容一个字没变**——Impress 的写入原语全是 UNO API 直写（`shape.getText().setString()` /
     `XDrawPages.insertNewByIndex()`），这条路在本引擎上一条撤销条目都不记，直接调 `um.undo()`
     抛 `EmptyUndoStackException`。差别在引擎模块，不在 worker 的 `undoStep`。
     更糟的是 `slide_add_page` 内部「insertNewByIndex（不记）+ `.uno:MovePageUp/Down`（记）」
     只有后半段进撤销栈，撤一次可能把插页撤成「新页留在错位置」的半成品。
     引擎哪天记了撤销栈，那条 e2e 用例会先红——它锁的就是今天这个现状。
  ③ **skill 白名单**（`SkillRouter.visibleTools(runId, …)`）+ 记忆工具兜底，见上文 skill 一节。
     **裁不裁是 skill 自愿声明的**（dev-board#799，审计 A2）：skill.yml 的
     `tool_policy: passthrough | restrict`，**缺省 passthrough = 不裁**。改之前裁剪与否只看
     `allowed_tools` 有没有内容，而它的缺省是空 ArrayList——于是「本身不带工具」的 skill
     （`desensitize` / `text-to-speech`，作用是把用户引导去左栏面板）一旦被触发词命中，
     整轮工具从一百多个塌缩成 base-tools ∪ 编排类工具十来个、`doc_*` 全部消失，模型只能回
     「我无法修改文档」；`text-to-speech` 还是 `enabled_by_default: true`。误配置回退救不了它：
     那条判据是「filtered 里是不是只剩编排类工具」，而 base-tools 的三个恰好让它为假。
     **多个 skill 同时生效时，任一 passthrough 就整轮不裁**——收窄必须全体同意，否则裁掉的
     正是那个 skill 没机会用白名单申报的能力。`restrict` 却没写 `allowed_tools` 同样按
     passthrough 兜（加载期 warn）。八个自带 skill 里六个显式写了 `restrict`（= 保持现状），
     `desensitize` / `text-to-speech` 不写（= 本次要修的那两个）。
     **`restrict` 的 skill 白名单必须含编辑面**（dev-board#818，2026-09-22 已修）：
     `meeting-recorder`（默认开、触发词「会议纪要」很宽）与 `listing-pathway`（触发词含
     IPO/VIE/SPAC）的白名单里一个 `doc_*` / `office_*` 都没有，命中即失去全部编辑能力——
     与 A2 同一形态，只是清单非空所以更隐蔽。两件事一起做的：白名单补读写基本面（两族都列，
     `ClientCapabilityService` 按会话只放行一族）+ 触发词收紧（短词换短语，且
     `SkillRouter.containsTrigger` 现在对拉丁串两端要求整词，「VIPO」「IPOS」不再命中 `IPO`）。
     `skill-listing-pathway-trigger-trim-xml` 仍断言裁剪发生，判据换成「`doc_open_file` /
     `doc_start_stream` / `doc_apply_standard_format` 仍在白名单外」；新增
     `skill-meeting-recorder-doc-editing-surface-visible` 与
     `skill-listing-pathway-office-editing-surface-visible` 各守一族（后者靠用例新字段
     `clientCapability: office` 让 `EvalHarness` 登记 Word 任务窗格会话）。详见 plugin-system.md。
  ④ **运行期可用性**（dev-board#750）：账户没连时那些必然回「尚未连接 AI WorkDeck 账户」的工具不下发。
     判据链是 `AgentToolComponent.currentlyUnusableTools()` → `ToolRegistry.unusableToolNames()`
     → 起跑时存进 `RunGuard.unusableTools` → **在编排器里**做最后一道过滤（与 skill 白名单、
     ASK 只读记忆工具同一处；**刻意不放进 ToolRegistry**：那里多一个重载会让只 stub 了旧重载的
     Mockito 测试静默拿到空工具集，本次就踩了两个）。
     与 `isAvailable()` 的分工：那个是**进程级**的（@PostConstruct 探一次，如本机有没有 Docker），
     这个是**运行期**的（账户随时可连可断）；粒度也不同，这个按工具名——同一组件里常常一半要账户、
     一半是纯本地的（`WebTools` 的 search_web 要、browse_url 不要；`LegalTools` 的 law_* 要、
     read_document 不要），**连坐的表现是「模型以为它连文件都读不了」**。
     受影响的只有三个组件共 8 个工具（真机实测：200 → 192）：`search_web`（SEARCH）、`law_search` / `law_search_keyword` /
     `law_recognition` / `get_law_article`（PKULAW）、`qichacha_query` / `qichacha_ipr`（QICHACHA）、
     `tushare_query`（TUSHARE）。判据 `ExternalServiceAvailability.usable(service)`
     **只判「平台档 + 没连账户」这一种**，BYOK/LOCAL 档与判不出来的一律当可用——
     藏掉一个能用的工具比失败一次严重得多。云后端 `resolve()` 恒不返回 PLATFORM，这道闸天然空转。
     **一轮内不变**：与 activeDocKind 同一条契约，而且这一个连中途放宽的口子都没有。
  - **为什么值得做②**：工具规格**每一轮都要重发**，一条消息跑三五个往返就付三五遍。
    本机实测 202 个工具 59045 prompt token / 首轮 26.4s，裁到 16 个 18854 token / 6.1s；
    本仓离线实测（`ToolSchemaBudgetTest`）docx 省 26.7%、xlsx 省 37.8%、pptx 省 38.8% 的 schema 体量。
    ①b 的收益全在**换一类客户端**那一档（同测试的第三个用例，dev-board#799 实测）：
    Word 任务窗格 127 个 / 56435 字符 → 114 个 / 48880 字符；Excel 117 → 104；
    PowerPoint 105 → 92；纯对话（none）87 → 74。LOWA 全集 200 → 198（少的两个是 offerToModel=false）。
  - **一轮内工具集必须不变**：kind 在 `beginRun` 时算一次存进 `RunGuard.activeDocKind`，
    runLoop 每次递归都读同一个值。每轮重算的话，模型上一轮已经宣布要调的工具这一轮可能就没了，
    通道直接 400。**改写点只有 `dispatchTool` 里的两处，且都只放宽不收窄**：
    `doc_open_file` 切到不同类型（`widenDocKindIfTypeChanged`，类型读不出来也算变了）；
    `sheet_create_file` / `doc_start_stream` 成功建了别类型的文档
    （`widenDocKindAfterDocumentSwitch`，表在 `NEW_DOCUMENT_TOOL_KINDS`）。
    第二处是**必须的**：Word 会话里 `sheet_create_file` 建了张表，接下来填内容的
    `sheet_write_cells` 还被裁着——第一步做成了、第二步没有工具可用，是最坏的一种半途而废。
    新增任何「会换掉活跃文档」的工具都要进这两处之一。
  - **只裁 spec，不裁 resolve/execute**（与 `AgentToolComponent.isAvailable()` 同口径）：
    模型看不见即不会试，万一经 XML 兜底路径调到了被裁的工具，拿到的是工具自己那句可行动的
    错误（"当前打开的不是电子表格…"），远好过 "Tool not found"。
  - 判文档类型的**单一判据**是 `ContextAssemblerService.lowaDocKind(ContextItem)` /
    `lowaDocKindOf(fileType, fileName)`（prompt 文案与工具白名单必须同源，各写一份的表现是
    「提醒说这是表格、下发的却是 Writer 工具集」，两边都不报错）。
  - 回放护栏 `cases-tool-visibility.json`（5 例，起跑时的裁剪）+ `cases-tool-visibility-widening.json`
    （3 例，中途放回全集；用 `expect.offeredToolsExcludeFirstCall` / `offeredToolsIncludeLastCall`
    这对**逐轮**断言——全轮次的 `offeredToolsExclude` 在这种形态下必然自相矛盾）
    + `ClientCapabilityDocKindTest` + `ToolSchemaBudgetTest`。
    **`RealToolBeans` 的清单必须齐全**：CheckpointTools 与 SlideEditTools 长期漏列，
    于是针对它们的 `offeredToolsExclude` 全是空断言（工具名没注册，排除断言恒过）——
    已于 dev-board#729 补齐，`EvalToolBeanParityTest.KNOWN_MISSING` 现在是空集，别再往里加名字。
- **组件级可用性闸（dev-board#396）**：`AgentToolComponent.isAvailable()`（default true）。返回 false 的组件**仍然登记进 builtinTools**（resolve/execute 照常命中），但**它的 spec 不进 builtinSpecifications**——模型看不见即不会去试，而万一被 XML 兜底路径调到，拿到的是工具自己那句可行动的错误（远好过 "tool not found"）。探测在 `ToolRegistry.init` 的 @PostConstruct 上跑，所以实现**必须自己缓存且绝不抛异常**（抛了也被 `componentAvailable` 兜成"可用"，最坏多下发一个工具，但不许让后端起不来）。今天唯一的使用者是 `PythonTools`：`run_python` 无条件 `docker run python:3.9-slim`，判据是「docker 可执行文件在 **且** `docker version` 成功」（Docker Desktop 装了没开的机器上 CLI 在、守护进程不在，run 一样起不来），3 秒超时、输出 DISCARD（接了管道又不读会把子进程卡在 write 上）、**进程级**缓存（不是每实例一份：eval 里每个 harness 都会新建一个 PythonTools，逐个 fork docker 子进程会把测试拖慢几分钟）。护栏 `ToolRegistryAvailabilityTest` / `PythonToolsDockerGateTest`。
- **永久不下发的工具（dev-board#799，审计 A15）**：`@ToolMeta(offerToModel = false)`。与
  `isAvailable()`（**进程级**，本机有没有 Docker）和 `currentlyUnusableTools()`（**运行期**，
  账户连没连）同一口径——**只裁 spec、不裁 resolve/execute**，但这一个是**永久的**：工具本身
  已经停用或者压根不该出现在律师面前，与环境无关，所以写成声明而不是每次现算。
  今天两个使用者：`delete_file`（永久停用，实现就是一句拒绝；每轮白付一份 schema，而且用户说
  「把这个文件删掉」时模型会先调一次再转述拒绝，白烧一个往返）与 `doc_debug_revisions`
  （调试工具，与 `doc_list_revisions` 功能重合，不该进用户的过程卡）。护栏
  `ToolDeclarationContractTest`（逐名钉住 + 断言登记仍在）。
- `service/ai/XmlToolCallParser.java` — XML <tool_code> 协议兜底（位置参数按签名映射为命名参数，PR#193）。
- tools/：FileTools(13，含 create_folder/rename_project_file/move_project_file/move_file/**move_files_batch** 五个 DB 感知文件树原语——直通 ProjectFileService，与前端右键菜单同路径；move_file 2026-08 由停用复活为路径版移动：按路径经 dbPathIndex 解析 project_file 记录、缺失目标文件夹自动补建，真机实证 txt 类文件拿不到 fileId 时模型会绕道 read_file+write_file 整篇重写；**move_files_batch(movesJson) 是它的批量形态**（≤50 条，dev-board#466，见下文「步数预算与批量原语」）；list_files/search_project_files 对 DB 已登记条目附带 fileId/folderId，未登记提示先 scan_files；含 extract_file_text——Tika/PDFBox 全文抽取，Word/Excel/PDF 均可读，**图片与无文字层的扫描件自动走云端 OCR**（见下文「读取类工具的 OCR 路由」）；write_docx 支持可选 parentFolderId 落指定文件夹)、LegalTools(5)、WebTools(2)、PythonTools(1)、TodoTools(1)、TaskTools(2，dev-board #53：task_create/task_list，项目级「任务/日程」的 AI 接线，落 `ProjectTaskService`。与 TodoTools 的边界是术语表那条——task_* 管跨对话持续存在、日历页可见的截止日/开庭日里程碑，todo_write 管 AI 本轮工作步骤条，本轮结束即失效，别混。task_create 走新增的 `ProjectTaskService.createAiTask`（source 恒 "ai"，与用户手建的 "user" 区分；内部委托同一份校验逻辑，未新增校验分支），projectId/userId 走 `SERVER_CONTEXT_PARAMS` 强制注入，fileId 越权校验复用 `validateFileInProject`。task_list 空结果返回明确中文文案而非空串——空白工具输出会炸 `ToolExecutionResultMessage.ensureNotBlank`，掀翻整轮对话，见下文「已知地雷」)、SubAgentTools(1，**@Lazy 防启动死环** PR#98)、EvidenceTools(2：retrieve_evidence 检索 + evidence_verify 勾稽核查，后者委托 `service/evidence/EvidenceVerifyService`，见 ai-doc-bridge「勾稽核查」)、MemoryTools(8 个登记，**下发 6 个**——query_memory / search_knowledge_base / deep_search 三个签名雷同、描述不给判据的检索工具已合并成 `query_memory(query, type, scope, sourceFileId, depth, limit)`，depth = quick(关键词，默认，等于旧 query_memory) / hybrid(RRF 融合，旧 search_knowledge_base) / deep(Agentic 多轮召回，旧 deep_search，**会额外起一次辅助模型做查询扩展**)；旧两名保留为 `@ToolMeta(offerToModel = false)` 的兼容入口，只裁 spec 不裁 execute，返回末尾附一句指路。**depth 填错一律回落 quick，绝不让整次调用失败**。dev-board#807，审计 A13)、DocumentEditTools(32)、CheckpointTools(1)、PptxTools(13 个登记，**下发 10 个**——PPTX 的权威编辑面是 `slide_*`，pptx_open_file / pptx_apply_format / pptx_edit_page 三个已标 `offerToModel = false`（dev-board#808，审计 B-09）：它们改的是**磁盘字节**然后强制编辑器 reload，会把编辑器里尚未保存的修改静默丢掉，且索引 0 起而 slide_* 1 起，两套并存时模型混用必然错页、错页既不报错也不会被任何返回值戳穿。留下的是不可替代的那批：生成(pptx_generate/pptx_generate_outline/pptx_refine_outline)、导出(pptx_export_editable)、清单(pptx_list_files/pptx_search_files/list_project_folders)、只读检查(pptx_inspect_format，走 pptx-service 自有端点 /api/pptx/*，不必在编辑器里打开)、服务探活与页面清单)、PdfTools(7，PDFBox 层：pdf_list_files/pdf_inspect/pdf_highlight/pdf_annotate/pdf_redact/pdf_replace_text/pdf_to_word，实现在 PdfEditService；定位类限文本型未加密 PDF、靠引用原文，fileId 从 `doc_list_project_files`（现在一次列全类型，含 PDF）或 pdf_list_files 拿；**「doc_list_project_files 不列 PDF、search_project_files 不带 ID」是已经不成立的旧说法**（dev-board#807，审计 B-11 + 复核补漏）。pdf_to_word 三路由：文本型走 pptx-service /api/pdf/to-docx 版式级(pdf2docx)→失败回退 Java 结构级提取；扫描件走 /api/pdf/ocr-markdown 本地 MinerU OCR，不用第三方云 OCR)。PptxEditTools 已删（7 个工具全走编辑器桥 ppt_* 命令，前端明确拒绝，死路径；pptx_smart_modify/pptx_get_page_screenshot 同因服务端点不存在下线）。**PptxTools / PdfTools / TextFileEditTools / Litigation* 里共 12 个工具已用 `@ToolMeta(requiresHost = LOWA)` 声明桌面前端依赖**（dev-board#799，见上文①b）——「Office 会话看得到 pptx_generate 并被它的『等待用户操作…』卡住整轮」这条地雷**已修**。

**PDF 页级操作（dev-board#805，审计 A17 / B-12 / B-13）**

- **一句话**：证据卷宗的组织动作（合卷 / 拆分 / 提页 / 删页 / 转正 / 编页码与贝茨号）现在有了六个工具，`PdfTools` 里 `// ==================== 页级组织` 那一段，底层在 `PdfEditService` 同名段。全部是 PDFBox，没有新增依赖、没有新增出站请求。
- **契约只有一条，但它是全部**：**产出项目内的新 PDF，原件一个字节都不动**。PDF 没有修订痕迹、项目里也没有针对 PDF 的检查点——页级操作一旦做成原位修改，用户丢的就是证据原件，没有任何东西能把它捞回来。这与「PDF 大范围修改不做原位编辑、统一引导 `pdf_to_word`」那条刻意设计**不冲突**：那条说的是改**内容**，这里做的是组织**页面**。
- **与原位标注组的分界**：`pdf_highlight` / `pdf_annotate` / `pdf_redact` / `pdf_replace_text` 改的是那份 PDF 本身、收尾发 `sendReloadFileAction`，所以声明 `requiresHost=LOWA`；页级组织组收尾只有「建文件 + 刷文件树」（`@ToolMeta(refreshFiles=true)`，**不自己调 `sendRefreshFilesAction`**，由编排器的 `applyToolSideEffects` 发），按 K19 判据标 `Host.NONE`。**返回文案因此一个字都不许承诺「预览会自动刷新」「已在编辑器中打开」**——那正是审计 A9 的病灶形态。`ToolDeclarationContractTest` 里 `PdfTools.java` 的文档级 UI 指令调用点仍是 **4**，新增这六个工具不该让它变。
- **ranges 是 1 基，`pdf_inspect` 的 `pageIndex` 是 0 基**——同一族工具里两套基数，是本卡最容易出错的地方（K27/A12 要统一的就是这类，届时别忘了这里）。今天的缓解是三处都写明：`PdfTools.RANGES_DOC` 常量、`pdf_inspect` 的工具描述、以及越界/0 页的报错文案本身。
- **解析口径只有一处**：`PdfEditService.parsePageRanges`（展平、去重、升序）与 `parsePageRangeSegments`（保留逗号分段，`pdf_split` 一段产出一份）。语法 `N` / `N-M` / `N-`，全角逗号与各种破折号归一。**越界、倒序（5-3）、0 页、空串一律报错，绝不就近夹取**——夹取会静默产出一份看着正常、内容却少一页的卷宗，而工具返回的仍是「成功」。
- **提页/删页走「加载 → 删掉其余页 → 另存」，不是 importPage 重组**：前者把注释、书签、表单域原样带过去。代价是**不支持重排**，产物永远保持原文档页序（与工具名一致；要重排是另一件事）。
- **旋转是相对当前角度叠加的**，不是设绝对值：同一份扫描件里各页当前角度常常不一样，设绝对值会把本来就正的页转歪。当前角度从 `pdf_inspect` 的 `rotation` 字段读。
- **页码跟着页面的显示方向走**：`/Rotate` 非 0 的页（`pdf_rotate_pages` 之后就是这样）如果按未旋转坐标画，页码会横着印在纸的侧边——而「先转正、再编页码」恰恰是这套工具最常见的连用方式。换算在 `PdfEditService.stampPageNumber`，四档旋转各一行。
- **页码模板**：`{n}` 本页号、`{total}` 总页数、`{n:6}` 零填充到 6 位（**贝茨编号要靠它才排得了序**）。模板里没有 `{n}` 直接报错——整册印同一个数不是页码。
- **CJK 字体探测「存在」不等于「能用」**（本卡顺手修的既有 bug）：`resolveCjkFontFile()` 原先只判 `isFile()`，而仓内 `pptx-service/backend/fonts/NotoSansSC-Regular.ttf` 其实是 **CFF 轮廓的 OpenType**（sfnt 标签 `OTTO`，扩展名骗人），PDFBox 只能子集嵌入 glyf 轮廓的 TrueType。于是在没有 LOWA 字体产物的机器上，**带中文的 `pdf_replace_text` 会把 PDFBox 那句英文原文「True Type fonts using CFF outlines are not supported」直接甩给用户**。现在按 sfnt 标签跳过 CFF 候选，继续找下一个。
- **已知取舍**：PDF 文本抽取的 ToUnicode 不保证与显示一致——macOS STHeiti 把「页」的字形映射到康熙部首 U+2EDA，抽回来是「⻚」。**显示是对的，差异只在抽取这一侧**，但想在产物里 grep 页码要当心（`PdfPageOpsTest.chinesePageNumberTemplateWorks` 的注释里记着这条）。
- **`PdfTools.errorOf` 的兜底分支补上了 `Error:` 前缀**（本卡顺手修的既有 bug，与上文「工具失败判据只认前缀」那条地雷同一族）：它原先返回的是「读取 PDF 失败: …」，而 `ToolResult.success()` 只认 `Error` / 「错误」前缀与 `{"error"` JSON——于是 PdfTools 全部工具的**非 PdfEditException 失败**都被判成 SUCCESS：过程卡给失败调用打绿勾、`consecutiveFailures` 被清零（模型能对着同一个错误重试到步数上限）、埋点也记 `success=true`。`PdfToolsPageOpsTest.everyFailurePathStartsWithTheErrorPrefix` 守这条。
- **`getPdfFile` 补上了 `ToolFileGuard.rejectIfOutsideProject`**（此前 `PdfTools` 是少数几个没有这道围栏的组件）。`fileId` 是模型自由填写的普通参数，而 `pdf_merge` 尤其经不起漏——合并会把另一个项目那份 PDF 的全部内容复制进本项目，一次调用就是一次完整的跨项目搬运。这道围栏覆盖全部 13 个 pdf_* 工具。
- **闸**：合并 ≤50 份（`PdfEditService.MAX_MERGE_SOURCES`）、拆分 ≤50 份（`PdfTools.MAX_SPLIT_PARTS`）、不能删光全部页。产出落在**原件所在的文件夹**（证据卷宗都在文件夹里，扔到项目根目录等于让用户再搬一次），同名走 `ConflictPolicy.RENAME` 加 `(n)`、**绝不覆盖**——覆盖掉的可能正是上一次的合卷成果。失败路径会删掉临时文件（留一个不在文件树里的孤儿文件，用户既看不见也删不掉）。
- **`pdf_inspect` 续读**（B-13）：第三个可选参数 `offset`，截断时返回 `next_offset`，原样传回即可接着读同一页。`char_count` 始终是**整页**字数而不是本次返回那一段。每页单次上限仍是 3000（`INSPECT_MAX_CHARS_PER_PAGE`）。`offset > 0` 必须同时给 `pageIndex`（续读位点是按页算的）。
- 验证：`mvn -B test -Dtest='*Pdf*,*ToolMeta*,ToolDeclarationContractTest'`。四个用例文件：`PdfPageRangeTest`（ranges 语法）、`PdfPageOpsTest`（PDFBox 层逐个操作 + 页码 + 续读）、`PdfToolsPageOpsTest`（工具接线：文件树登记、落点、原件不动、围栏、失败不留临时文件）、`PdfToolsToolMetaContractTest`（六个工具 ADDED + refreshFiles + 不许声明 LOWA）。

**记忆/证据/MCP/子 Agent**
- memory/：MemoryPipelineService（轮次结束异步触发写侧管线）、MemoryManager（检索）、AgenticRetriever、MemCellExtractor、ProjectMemoryExtractor、MemoryEvidenceFormatter（证据账本：时间锚点/来源/更新信号，PR#155）。记忆五作用域 + 拟人化排序（重要性×衰减×随机）。
- evidence/：evidence.retrieve.v1（PR#186）——EvidenceRetriever SPI + Registry + Memory/Mcp 实现。两大不变式：**缺定位符即丢弃、缺证据≠矛盾**。
- mcp/：McpClientService 门面 + StreamableHttpMcpProvider；配置驱动 mcp.servers（langchain4j-mcp 需 1.0.0+）。
- subagent/：SubAgentService（dispatch_subtask，发 subtask_progress）。内存登记簿 `running`（subtaskId → Future + 所属会话，dispatch 返回前 finally 移除）支撑 `cancel(subtaskId, conversationId)`；被停的子任务走 `CancellationException` 分支，给用户看的进度文案是「子任务已停止」（stage 仍用 `failed`，不新造 stage 值），给模型看的是「用户停的、不要自动重派」。

**SSE**
- `service/ai/SseEmitterService.java` — 连接池（cid→SseEmitter，超时 30 分钟，建连发 connected）。**所有事件唯一出口**。生产者：Orchestrator、StreamHandler、Controller、TodoListService(plan_update)、BackgroundTaskService(background_task_*/heartbeat/task_progress)、SubAgentService、EditorBridgeService。**15s 心跳广播**（@PostConstruct 调度器，穿透代理空闲回收 + 前端判活依据）；同 ID 重连会 complete 旧 emitter，回调移除一律用两参 remove(id, emitter) 防摘掉新连接。

**可靠性层（2026-08 harness 加固，治"跑一半停了"）**
- LLM timeout 600s（application.yml open-router.timeout；0.36 的单值=OkHttp callTimeout 整通墙钟上限，不是空闲超时）。
- **流式通道是自有的 `service/ai/OpenRouterStreamingChatModel`，不再是 langchain4j 0.36 的 `OpenAiStreamingChatModel`（2026-09-02，dev-board#364）**。唯一构建口径 `ChatModelFactory.streamingModel(apiKey, baseUrl, modelId, timeout)`，平台通道与 BYOK 两个流式路径都走它；Ollama 流式仍是 langchain4j 的。换实现的直接原因是**思考型模型**：OpenRouter 对 Kimi K3（`moonshotai/kimi-k3`）这类模型从第 4 秒起就流式返回 `delta.reasoning`（真机探测：每个思考 chunk 是 `content:""` + `reasoning:"…"` + `reasoning_details:[…]`，前面夹 `: OPENROUTER PROCESSING` 注释保活），而 openai4j 0.23 的 `Delta` 只有 role/content/toolCalls/functionCall 四个字段，reasoning 在反序列化那一刻就丢了、注释行被 okhttp-sse 静默吞掉，langchain4j 只对非 null 的 content 调 `onNext`——于是几百秒的思考期间编排器收到的全是 `onNext("")`（**恰好把看门狗喂活、又一个字节都不往前端发**），用户看到的就是「思考中 281 秒、什么都没有、分不清死机还是在想」。这条流在 langchain4j 那一层没有任何钩子能拿到 reasoning，所以只能自己读 HTTP/SSE。**刻意复用不重写**：`InternalOpenAiHelper.toOpenAiMessages/toTools`（含 ImageContent 编组）、openai4j 的 `Json`（请求体，snake_case + NON_NULL + INDENT_OUTPUT——**断言请求体时先去空白**）、`OpenAiStreamingResponseBuilder`（tool_calls 按 index 拼装、usage、finish_reason），本类只管 HTTP + SSE 行协议 + 多转发两条通道。与旧实现对齐的请求参数：`stream=true`、`stream_options.include_usage=true`、`temperature=0.7`；错误语义对齐：非 2xx 抛 `OpenAiHttpException(code, body)`（`LlmErrorClassifier` 按状态码分类），IOException 原样 onError，**HTTP 200 里用 data 事件送来的 `{"error":{...}}` 也当错误**（旧实现会按空回复静默收尾）。护栏 `OpenRouterStreamingChatModelTest`（假服务端回放真机抓到的片段形状）+ `StreamingTransportFailureTest`（连不上必须 onError；它走工厂那份真实口径）。**旧的 `logResponses(false)` 地雷随之消失**（openai4j 的 `StreamingRequestExecutor$2.onFailure` 在 response==null 时先调 `ResponseLoggingInterceptor.log` 抛 NPE、errorHandler 永远走不到），但非流式 `OpenAiChatModel` 仍必须 `logRequests(false)`（请求体物化，理由在 `streamingModel` 的 javadoc）。**「辅助模型秒回成功」仍不能用来证明流式通道的网络正常**（不同 HTTP 客户端/连接池、且不带工具定义）。
  - **`ReasoningStreamingHandler`**（extends `StreamingResponseHandler<AiMessage>`）多三个 default 方法：`onReasoning(delta)`、`onKeepAlive()` 与 `onCacheUsage(promptTokens, cachedTokens, cacheWriteTokens)`（提示缓存，见上文「提示缓存」一节）。客户端只对 `instanceof` 这个接口的 handler 转发，回放评测与各测试的脚本模型按老接口写不受影响。`AgentStreamHandler` 实现它：reasoning → SSE `reasoning_delta`（**不进 fullContentBuilder、不进编辑器流、不过标签解析**：思考文本不是正文，不落库、不回喂模型——契约 D）；两者都刷新看门狗的 `lastActivityNanos`。**`streamedAnyReasoning` 与 `streamedAnyToken` 刻意分开**：看门狗选时限时任一为真都算「流已开始」（思考几分钟是正常的，改用 180s 停滞时限），而编排器的「可安全重放」判定仍只看正文——思考卡重放一遍无害，正文重放才会让用户看到重复内容。护栏 `AgentStreamHandlerReasoningTest`。
  - **看门狗首字节 60s 保持不变**：真正的零字节死流仍在 60s 被掐；思考型模型靠 reasoning 增量 + OpenRouter 保活注释刷新活动时间，不会再被误杀。**注意 K3 的思考也是按输出单价计费的**（$15/M），思考 281 秒的那一轮反复被掐重放会成倍烧钱——这就是首字节时限不能靠「调大」而必须靠「认得出模型还活着」来解决的原因。
  - **前端**：`useAgentStream.handleEvent` 认 `reasoning_delta` → `appendReasoning()`——没有过程卡时写顶层 `bubble.thinking.content`（ghost 态的 ThinkingCard 实时滚动显示），已有工具过程后挂到最后一个过程卡的 thinking 条目（与 `<thinking>` 标签的落点同口径，否则第二轮起的思考会把首轮顶层卡的时长越算越长）。**不过 `processTextStream`**：思考文本里出现 `<final>` 字样只是模型自言自语。等待首 token 的活性计数本来就有（`sendMessage` 起算 `thinking.startTime`，ThinkingCard 按 `chat.thinkingLive` 读秒）；新增的是 **SSE 链路状态 `linkStatus`**（`{state:'live'|'reconnecting'|'superseded', attempt}`，`scheduleReconnect` 置 reconnecting、建连成功与 `resetSSE` 回 live、收到 `superseded` 事件置 superseded 并停止重连），ChatInterface 输入区据此渲染 `chat.linkReconnecting` / `chat.linkSuperseded` 提示条——之前断线重连只写 console.warn，用户看到的是计时器一直走、分不清模型在想还是连接死了。**前端判死阈值 `HEARTBEAT_STALE_MS=45000` = 后端 `SseEmitterService.HEARTBEAT_INTERVAL_SECONDS=15` 的 3 倍**，两边任一改动都要同步（`reasoning-stream.test.mjs` 与 `SseEmitterServiceTest.heartbeatSweepReachesEveryLiveConnection` 各守一侧）。Office 插件的 `sse.js` 对未知事件名直接忽略，`reasoning_delta` 不影响任务窗格。
- **「AI 全线连不上」优先怀疑 JVM 里冻住的代理端口，不要先怀疑密钥或网络**（2026-08-16 实证，两个 e2e home + 用户真机三处复现）。macOS 上**任何 JVM 启动时都会把系统代理设置自动灌进** `http(s).proxyHost/Port` 系统属性——**不需要任何 `-D`、不需要 `JAVA_TOOL_OPTIONS`**（裸 `java Foo.java` 就已经有 `https.proxyHost=127.0.0.1`），OkHttp 走 `ProxySelector.getDefault()` 于是全部 AI 流量被送去本地代理端口。桌面后端是**长命 JVM**（开 app 起、连跑数天），启动那刻把端口**冻住**；用户的代理工具换端口或重启后（实测 1235 → 8234），后端仍在拨旧端口，**每一个 AI 请求都 `ConnectException: Connection refused`**。
  - 判定三件套：`jcmd <后端PID> VM.system_properties | grep proxy` 拿 JVM 冻住的端口 → `scutil --proxy` 拿系统当前端口 → `nc -z 127.0.0.1 <旧端口>` 确认旧端口已死。两者不一致就是它。
  - **已自愈**：`service/SystemProxyRefresher.java` 每 60s 对齐一次（`scutil --proxy` → `System.setProperty`），开关 `network.proxy.auto-refresh`（默认 true）。成立前提是 `DefaultProxySelector` 每次 `select()` 都重读系统属性、运行期 `setProperty` 立即生效（由 `SystemProxyRefresherTest` 的端到端用例守住）；**运行期打开 `java.net.useSystemProxies` 无效**（类初始化时固化，返 DIRECT），所以只能自己读 OS 再写属性。启用条件刻意收窄成「macOS + 启动时继承到回环代理」：非回环的企业代理端口稳定，动它只有风险。**启动时系统没开代理的情况不接管**（没有被冻住的旧端口，不存在要治的病），那种情况仍靠重启后端。老版本（≤ v0.16.0）没有这层自愈，临时解仍是重启 app。
  - **表现极具迷惑性，两个假信号**：① 修复前流式路撞上文那个 NPE 被吞、静默 180s，日志里只有 NPE 看不到 ConnectException；② **同步路（辅助模型起标题/记忆/分类器）会「秒回」**——但那是 RetryUtils 重试 3 次约 1.4s 全败后写入的**兜底字面量「新对话」**，不是成功。**排障时先看标题是不是字面量「新对话」**，别拿它当"通道正常"的证据。
  - **找日志别找错地方**：`-Duser.home=` 会整体改写 `~/.aiworkdeck` 的位置，e2e 后端的日志在 `<user.home>/run/backend.log`。在真实 `~/.aiworkdeck/logs/backend.log` 里翻 e2e 的证据只会得出「什么都没有」的错误结论。
- `AgentStreamHandler`：终态幂等（AtomicBoolean terminated）+ **流看门狗** armInactivityWatchdog(**首字节 60s / 停滞 180s**，5s 轮询)——两条时限刻意分开：停滞时限要照顾「生成长工具参数时中途静默几十秒」所以必须给足，而「从头到尾零字节」没有这种正当理由，合成一个值就是让用户干等三分钟。首字节这条只在 `streamedAnyToken == false && streamedAnyReasoning == false` 时生效（前者恰好是编排器判定「可安全重放」的条件），所以误杀代价上限是白跑一轮、不会让用户看到重复或半截内容；思考增量与 OpenRouter 保活注释（`onReasoning` / `onKeepAlive`）都刷新活动时间，思考型模型静默几分钟不会被首字节时限掐掉。守护 `AgentStreamWatchdogTest` + `AgentStreamHandlerReasoningTest`。
- `AgentOrchestrator.setOnError`：失败按 `LlmErrorClassifier.Kind` 分类（**七类**：RATE_LIMITED / TRANSIENT / MODEL_UNAVAILABLE / REGION_BLOCKED / **QUOTA_EXHAUSTED** / **CONTEXT_OVERFLOW** / FATAL，OpenAiHttpException 的结构化状态码优先于文本匹配），且**零 token 已流出**才允许重放。限流退避 30/60s ×2（限流窗口按分钟计，用 8/16/32 会在同一窗口连撞三次白烧预算），瞬时 8/16/32s ×3（RunGuard.llmRetries，成功轮与切模型后清零）；用户文案两套，限流说「限流等待中」不说「服务不可用」。
- **QUOTA_EXHAUSTED = 配额/余额耗尽**（2026-08 对标 dsh）：402、或 4xx + 配额语义（insufficient credits/quota/balance、quota exceeded、余额不足…）。**判定先于 429**——余额耗尽很多服务商也回 429，但它是终局：不退避（重试白烧）、不换模型（同一账户换哪个都没钱）。SSE error 载荷带 `AI_QUOTA_EXHAUSTED` 标记（`LlmErrorClassifier.QUOTA_EXHAUSTED_MARKER`），前端 useAgentStream includes 命中换中文引导（自备 Key 去服务商充值 / 平台通道去官网查额度分配）。
- **CONTEXT_OVERFLOW = 上下文超窗**（400 + 上下文语义，先于通用 400→FATAL 判定）：不退避（原样重发必撞同一个 400）、不走故障转移链，走**专用恢复通道**——`RunLoopCompactor.forceCompact`（跳过阈值判断）强制压缩后同 depth 重放一次。**重试凭证 = compact 返回了新实例（确实缩小了）**，压不动直接终态（载荷带 `AI_CONTEXT_OVERFLOW` 标记换中文引导，**并发一条 `context_notice kind=overflow`** 告诉用户唯一的出路是移除部分附件，dev-board#812 K32 ⑥）。预算 `RunGuard.overflowCompactions` 1 次/轮，成功轮清零（长任务「涨→压→涨→压」合法）。存在意义：主动 compaction 靠 chars/token=2 估算，中文语料系统性低估，服务商的 400 是最后的事实来源。
- **finishReason 结构化消费**（2026-08 对标 dsh，此前全链路零消费）：① `isTruncatedToolCallRound`——LENGTH + 工具调用**一律不执行**（参数被砍半后「恰好仍可解析」比解析失败更危险：半截 write_file 覆盖用户文件），且截断轮的 AiMessage **不入栈**（不执行又入栈 = tool_calls 无配对结果 → 通道 400），复用 malformedToolRounds 纠正回路 ≤2 轮，耗尽转 PAUSED（`bubble_end reason=max_tokens`）；② LENGTH + 纯文本 → 「暂停 + 继续」收尾，不装正常完成，刻意不触发记忆管线与版本落档；③ `isEmptyResponse`——正常终止 + 零内容 + **零 token 流出**（三条件缺一不可，有 token 给用户看过就绝不重放）按瞬时错误退避重试，空 AiMessage 不入栈，预算耗尽转终态错误而不是静默 FINISHED。finishReason 为 null 的通道（Ollama / 回放评测的 ScriptedStreamingModel）行为与改造前一致。测试：`AgentOrchestratorFinishReasonTest`。
- **REGION_BLOCKED = 403 的地域子类**：OpenRouter 对国际模型在境内网络返回 403「This model is not available in your region」。**不许整体放宽 403**——key 失效/额度禁用也是 403，放宽会把它们带进换模型重试变成重复扣费探测；判据是「403 + 响应体含地域语义」（多子串择一命中，`looksLikeRegionRejection`）。这是文本匹配，上游改文案会退化成 FATAL，退化方向安全（不换模型、只是文案回英文原文）。不重试（同网络重试永远撞同一个 403）但 failoverable，且 `Kind.requiresRegionAgnosticFailover()` 要求候选收窄成 `AllowedModels.Region.GLOBAL`。终态错误载荷带稳定标记 `LlmErrorClassifier.REGION_BLOCKED_MARKER`（"AI_REGION_BLOCKED"，由 `taggedErrorMessage` 拼），前端 `useAgentStream` 用 includes 命中后换成中文引导（载荷前面还拼着「Stream Error: 」，别写成前缀判断）。
- **故障转移链**（`ai.failover.models`，默认两个区域无关常青模型）：重试预算耗尽仍是限流/瞬时错误、模型下线 404（PR#144 坑的一般化）、或地域拒绝时，`switchToFailoverModel` 换模型同 depth 重放本轮并发 SSE 明示切到了哪个。候选必须在 `AllowedModels` 白名单内——非白名单会被工厂静默回落默认模型，切了等于没切；REGION_BLOCKED 还要再按 `AllowedModels.availableIn(GLOBAL)` 过滤，否则换一个同样是国际档的模型只会再撞一次 403。**计费红线：只换 modelId，通道由 `ChatModelFactory.resolveProvider()` 决定，与 modelId 无关**；平台通道下取不到 key 抛的 AccountException 原样透出并终止，绝不回退 BYOK（会花用户自己的钱）。FATAL（400/401、非地域 403 与未知错误）不换模型。
- **上下文预算的基数（dev-board#729 ④，改压缩相关代码前先读这条）**：「历史可用预算」=
  `maxContextTokensFor(modelId) - systemPromptReserve - memoryReserve - responseReserve`
  （`ContextCompressor.getAvailableTokensForHistory`，`RunLoopCompactor` 也读它）。
  - `maxContextTokensFor` 的解析链是**四级**：显式 `ai.context.model-token-budgets` 精确匹配 →
    子串匹配 → **`AllowedModels.contextLength × model-budget-headroom`（0.85）** → 兜底
    `ai.context.max-context-tokens`。第三级是新加的：改动前 `modelTokenBudgets` 一直是空的，
    于是**所有模型都吃 10 万那个常数**，而生产默认模型 deepseek-v4-flash 的真实上下文是 1,048,576。
  - `system-prompt-reserve` 是 **60000，实测值**（每轮 promptTokens 约 5 万，其中约 2/3 是工具 schema）。
    旧值 8000 与真实固定前缀差了近一个数量级；它与「总预算只有 10 万」两个错误方向相反，
    互相掩盖了很久——**改其中一个必须同时看另一个**。
  - 兜底 `max-context-tokens` 随之从 10 万抬到 **20 万**（白名单里最小的 Claude Haiku 4.5）：
    reserve 抬到 6 万后兜底值若还是 10 万，解析不出上下文长度的模型（白名单外 id / 本地 Ollama）
    可用预算会从 79000 掉到 27000，比改动前更早触发同步摘要。
    **不变式：任何模型的历史可用预算都不得低于旧口径的 79000**，由
    `AiContextPropertiesTest.noModelEverGetsATighterHistoryBudgetThanBefore` 钉住。
  - 为什么这件事要紧：触发压缩会走 `ContextCompressor` → `ConversationSummarizer`，
    那是**首 token 之前的同步 LLM 调用**——用户白等一整个模型往返，上下文还被压掉了。
- **自动 compaction**（`context/RunLoopCompactor` + `ai.context.compaction`）：runLoop 每轮 generate 前估算 token，超「**整栈**可用预算 × 0.8」时把中段折叠成一条摘要（**dev-board#812 K32 ⑥ 修正**：阈值原来用的是 `ContextCompressor.getAvailableTokensForHistory`，那个已经扣掉了 `systemPromptReserve`，而 `estimateTokens` 统计的是**含 system 的整个消息栈**——system 被扣了两遍。带大附件时 tokens 恒大于 threshold，**每一轮都白跑一次剪枝/折叠**，而且历史还很短就开始丢历史。现在 `triggerThreshold = (历史预算 + systemPromptReserve) × 比例`，等价于用 system 的**真实**大小而不是一个 6 万的常数估计），保留 system prompt + 首条用户消息 + 最近 8 条。**结构感知**：保留段绝不以 ToolExecutionResultMessage 打头（拆散 tool_calls 配对会让 OpenAI 兼容通道直接 400），这也是不能直接复用 ContextCompressor 的原因——那套会把消息重建成纯文本、抹掉 toolExecutionRequests。摘要本地生成不调 LLM（交互路径中间插同步 LLM 调用等于新增一处卡死成因），上一版摘要会并进新摘要。压缩失败一律原样继续；中段不足 4 条不压，回放评测用例碰不到阈值。
  - **剪枝先于折叠**（2026-08 对标 dsh tool-result-pruner）：触发后先把中段（keepRecent 尾部**刻意不动**——模型正在引用）超过 8192 字符的工具结果剪成首 4096 + 尾 1024 + 省略标记（`PRUNE_MARKER`，提示模型要全文重调工具），只改正文不动 id/toolName（配对不断）；剪完重估、够了就完全不折叠。**必须变小**：折叠后估算不降反升就放弃折叠退回剪枝版（小中段的摘要头开销会得不偿失，溢出恢复还会拿着更大的栈白撞 400）。
  - `forceCompact(messages, modelId)`：CONTEXT_OVERFLOW 恢复通道专用，跳过阈值判断做剪枝 + 折叠；返回原实例 = 压不动（调用方据此放弃重试）。
- **步数预算与批量原语（改文件类工具前必读，dev-board#419 / #466）**：预算按 **LLM 轮数**计，不是按工具调用数——
  一轮里发 N 个工具调用只花 1 步（原生分支与 XML 兜底分支都是执行完本轮全部调用才 `runLoop(depth+1)`）。
  所以「一次要改/搬很多个对象」的任务，成败取决于**有没有一个批量原语**、以及**有没有在末位逼模型用它**：
  弱模型（Kimi 实证）一轮只发一个调用，十几个对象就把 30 步用光，任务干到一半 `bubble_end reason=max_depth` 暂停。
  今天有两个：`office_replace_batch`（Word 面多处替换，#419）与 `move_files_batch`（项目文件树批量移动，#466）。
  两者的形状必须一致，新增同类原语照抄：**≤50 条 / 形状校验全部前置 / 逐条回报 `moved: N` + FAILED 段 /
  明说「只重试 FAILED、绝不整批重发」**（整批重发会把已成功的做第二遍）/ `@ToolMeta(refreshFiles = true)` 每次调用只刷一次。
  **`move_files_batch` 的索引契约**：`dbPathIndex` 一次建、**每成功一项后重建**——批内先建的目标文件夹、改过的路径
  必须对后续条目可见，拿旧索引接着走会静默解析到过时位置。缺失的目标文件夹由 `ensureFolderPath` 自动补建，
  所以「先 create_folder 再逐个 move」那条链整条不需要。`move_file` 与 `move_files_batch` 共用私有 `moveOnePath`，
  两个入口对同一件事的拒绝理由不能有出入。**批量不是原子的**：物理文件已搬走的回滚不了（`delete_file` 停用、
  移动也没有回收站），所以单条失败只记账不掀翻整批，成功清单逐条写明目的地供用户核对。
  **刻意不抬 MAX_LOOP_DEPTH**：#422 的 `min(30+块数,120)` 是有状态源（`OfficePassStateStore.totalChunks`）兜底的，
  文件整理没有等价状态源，抬平会让所有失控轮次的最坏烧钱翻倍，而暂停本身正是成本闸。
- **StuckDetector**（先干预后熔断）：RunGuard 的单槽 lastCallSignature 换成 6 格滑动窗口，识别 A/A/A 与 **A/B/A/B 交替**（旧实现对交替完全无感，一路空转到步数预算耗尽）。首次检出只往 **messages 末位**追加 `[系统提醒]` UserMessage、工具照常执行；二次检出才拒绝执行并回喂 `Error:` 前缀的熔断反馈。末位是硬要求——只写 system prompt 的约束会被弱模型无视（PR#209 实证）。
- 截断 `<tool_code>`（有开无闭）不再静默正常收尾：回喂纠正提示重试，最多 2 轮（RunGuard.malformedToolRounds）。
- **工具执行期可取消**：两条工具循环（原生分支与 XML 兜底分支）都在**每个工具执行前**查一次 `isCancelled`，命中即 `handleCancellation` 并丢弃本轮剩余工具。此前只在 runLoop 入口与 onComplete 开头各查一次，于是「停止」在 `dispatch_subtask`（可跑 630 秒）或 AI PPT（十几分钟）中间完全不生效。已跑完的工具副作用不回滚（取消的固有语义）。**新增工具循环必须带这个检查点**。
- **取消真正生效（2026-09-22，计划 K4；审计 C-03 / D-01 / D-10）**——改造前「停止」只置一个布尔，真机实测点完还要跑 82 秒（`Cancelling conversation` 与 `Response completed` 相隔 82 秒），卡在桥调用上要等 180 秒。四道闸**缺一条停止就还是「按了要等」**：
  - **① 掐断在途 HTTP 请求**。`OpenRouterStreamingChatModel.generateTracked` 把 okhttp 的 `Call` 交回给调用方（`generate` 现在就是它的 void 包装，两条路一字不差）；编排器经包级可见的 `AgentOrchestrator.startGeneration` 按 `instanceof` 分派，把取消句柄存进 `RunGuard.inflightCanceller`，`setCancelled` 顺手 `cancel()`。**`trackInflight` 登记后必须再查一次取消标志**：「发请求」与「点停止」是两个线程，标志可能恰好落在这两步之间，不补这一下那次请求永远没人去掐。不可取消的通道（本地 Ollama、脚本模型、回放评测）返回 null，行为与改造前完全一致。**不掐断的代价不只是烧 token**：被放弃的 AsyncCall 会一直占着 okhttp Dispatcher 的请求槽位直到上游自己写完。
  - **② handler 层的取消闸**。`AgentStreamHandler.setCancellationCheck(BooleanSupplier)`（默认恒 false，不设闸的调用方行为不变），`onNext` / `onReasoning` / `onKeepAlive` 命中即 return。**光掐 HTTP 不够**：okhttp 的 cancel 不是瞬时的，在途那一段响应体已经在本机缓冲里，取消之后 onNext 还会被回调几次——不加这道闸它们会继续打进气泡、继续进断线恢复快照、继续往文档里流式写。
  - **③ okhttp cancel 抛的 IOException 不是故障**。编排器 `setOnError` 开头的 `guard.isCancelled()` 分支改走 `handleCancellation`（原来走 `handleStreamErrorTerminal`）——按错误处置会打 ERROR 状态点、发 error 事件、还可能白白切一次模型，用户看到的是「点了停止却报错了」。
  - **④ 编辑器桥上的等待立即释放**。`EditorBridgeService.cancelPendingActions(conversationId)` 把该会话所有 pending future `cancel(false)`，`executeEditorCommand` 多一条 `catch (CancellationException)`（**必须排在 `catch (Exception)` 之前**）返回 `cancelledResultJson()`。回执与 `TIMEOUT_RESULT_JSON` 同口径：带 `"error"` 键（让 `ToolResult.success()` 判成失败，否则面板打绿勾）+ `outcomeUnknown`（worker 打不断，内容可能已经写进去了，别让模型原样重发）。
  - **落库**：`handleCancellation` 现在带 `StringBuilder executionLog` 形参（四个调用点都在 runLoop 内，直接传实参），落 `logText + partialContent + "[已中断]"`，条件是**两者任一非空**——与 `finishWithError` 逐字对齐。**同源的 `handleStreamErrorTerminal` 一并补掉**：它原先给 `finishWithError` 硬传 null executionLog，于是「模型先调了几个工具、然后这一轮流式出错」的收尾同样把过程卡整段丢掉；现在它也带 `executionLog` 形参（六个调用点作用域里都有实参，**没有一个需要传 null**）。护栏 `AgentOrchestratorTerminalErrorPersistenceTest.streamErrorPersistsTheExecutionLogOfToolsAlreadyRun`——注意它的两条断言必须打在**同一行**落库内容上：整批工具跑完后那次增量保存会先写下过程卡，分别看两处都「有」是假绿。**真正会丢的是「取消落在工具批次中间」那一档**：整批跑完之后有一次增量保存会捎上执行日志，而逐个工具前的检查点直接 return，那次增量保存整段跳过——形态就是「AI 已经动过文件、用户点了停止、刷新后历史里什么都没有」。
  - **`/cancel` 的响应体多一个 `cancelled` 布尔**（`{"status":"ok","cancelled":true|false,...}`）。**不许改成 404**：没有活跃轮次不是错误，前端的错误分支会写成「未能确认后台停止」，那更吓人。
  - **前端 `abort()` 的顺序与改造前相反**：① 放弃发送中的 POST /chat → ② **就地解锁本地状态**（isStreaming / thinking 归零 / finalizeProcesses）→ ③ 发 cancel（10s 超时）→ ④ **不拆本地 SSE**，等后端的 `cancelled` 事件把文案落定（15s 兜底）。当初先拆流是为了「断网时停止键不失效」（dev-board#211），那件事现在由第 ② 步就地解决；拆流的代价是后端随后发出的 `cancelled` 事件永远到不了前端，于是「停止到底生效没有」前端永远不知道。后端每轮收尾本来就会关流、前端退避重连，留着这条流是轮次之间的常态。
  - **文案口径：收到 `cancelled` 事件之前一律只说「正在停止」**（`stopPending`）——供应商那头会不会继续计费我们承诺不了。终态四档：`stopConfirmed` 已停止生成 / `stopAlreadyFinished` 该轮次已经结束（`cancelled=false`）/ `stopRequested` 指令发了没等到确认（兜底超时）/ `stopUnconfirmed` 请求本身失败。**`noteStopConfirmed()` 要在 `cancelled` 事件的两条分支都调**（气泡指针为 null 的兜底分支也算），缺一条就会一直停在「正在停止」。响应体没有 `cancelled` 字段或读不出来时按「打中了」处理（旧后端兼容）。
  - 护栏：`AgentOrchestratorCancellationTest` / `AgentStreamHandlerCancellationTest` / `EditorBridgeCancelPendingTest` / `OpenRouterStreamingCancelTest` / `tests/project-home/agent-stream-abort.test.mjs`。
- **工具输出的面板展示上限按工具分档**（`AgentOrchestrator.toolOutputDisplayLimit`）：默认 4000 字符，`RESULT_HEAVY_TOOLS`（dispatch_subtask / extract_file_text / pdf_inspect）16000。理由：这三个的输出本身就是要给用户核验的成果，且 dispatch_subtask 是 JSON——截断后前端结构化子任务卡直接解析失败退回裸文本。只影响 SSE 载荷大小，**不进上下文、不影响 token 与计费**（executionLog 落库存的一直是全文）。前端截断提示按 `...(截断)` 后缀判定，文案里不要写死字数。
- `ToolResult.success()` 除 "Error" 前缀外还识别 `{"error"...}` JSON 形态（编辑器桥超时曾被判 SUCCESS 致绿勾空转 30 步）；工具参数 JSON 解析失败返回可行动错误回喂模型，不再静默空参硬跑。
- connect 端点：run_state=RUNNING 时**无条件**发 state_recovery（哪怕快照为空）——前端靠它重建气泡指针，否则终态事件被守卫吞掉、isStreaming 永久锁死。
- 前端 `useAgentStream`：心跳 45s 无字节判死 + 指数退避自动重连（1s→30s 封顶）+ online/visibilitychange 钩子（模块级单例，防页面栈多实例重复订阅）；bubble_end/error/cancelled 在气泡指针为 null 时也解锁 isStreaming；sendMessage 防重入有 toast 提示。
- 线程池：`config/AsyncExecutorConfig.java` 显式 taskExecutor(16/32/队列200) + memoryExecutor(2/4)——MemoryPipelineService 的同步 LLM 调用已隔离，别再挂回 taskExecutor。
- 进程重启续跑（二期）：run 状态持久化 + 启动回收，见上文 AgentRunStateService / AgentRunRecoveryService。只有 RUNNING 跨重启复活（回收成 INTERRUPTED），FINISHED/ERROR/CANCELLED 仍是进程内状态，避免僵尸状态。

**轮次隔离：runId / RunGuard（2026-09-09，dev-board#533；审计「留给维护者拍板」第 2、4 条的落地）**
- **2026-09-22 复核更新：生产路径上 `POST /api/agent/chat` 已不直接开新轮，先落 inbox 再串行化**——`AiAgentController.chat` 只 `agentInboxService.submit` 后调 `AgentOrchestrator.acceptInboxSubmission`，后者 `synchronized (inbox.conversationLock(...))` 里先查 `activeRuns.get(conversationId)`，**有活跃轮次就原样返回那个 runId、不 `beginRun`**（新提交留在 inbox 里 PENDING，等当前轮次 `endRun` 时 `drainNext` 才被取出续跑）。下面这条 RunGuard 机制仍然真实存在、仍然是唯一状态持有者，但它防的是 inbox 串行化之外的入口（各测试与 `EvalHarness` 直接调 `AgentOrchestrator.handleUserMessage`——这个方法**不查 `activeRuns`**、无条件 `beginRun`，只有测试直调，生产控制器从不调它）与理论上的竞态窗口，不是「生产路径上同一会话真的会产生两个并发轮次」。
- **一次 `POST /api/agent/chat` = 一个轮次 = 一个 runId**（`AgentOrchestrator.beginRun` 现签 UUID，进程内唯一、不入库、不上 SSE）。`AgentOrchestrator.RunGuard` 现在持有本轮的**全部**状态：conversationId、runId、SSE 连接代次、取消标志（AtomicBoolean）、流式缓冲（同步的 StringBuilder）、本轮 ASSISTANT 行 id，外加原有的 StuckDetector / triedModels / llmRetries / malformedToolRounds / overflowCompactions / activeFileId。**改造前这四样都是 `conversationId -> 单槽` 的 map**，同一会话两个并发轮次（双击发送 / 两个标签页 / 客户端重试 / 手机端镜像同一会话）必然互相踩：后起一轮把行 id 槽清掉，先起那一轮收尾时拿到**别人的行 id** 去 update（两轮合并成一行、一轮的正文永久消失）；后起一轮开头无条件 `cancelledConversations.remove(cid)`，把上一轮尚未生效的取消标志擦掉（用户点了停止，旧轮次一路跑到底继续烧 token、继续改文档）。
- **`activeRuns`（conversationId → 当前轮次的 RunGuard）是唯一的解析入口**：`POST /cancel/{cid}` → `setCancelled` 解析出当前活跃 runId 再置位；`/connect/{cid}` 的断线恢复快照 `getRecoverySnapshot` 同样按它解析。**没有活跃轮次时 cancel 是 no-op**——这是刻意的语义改变：会话级的粘性取消标志正是要消灭的缺陷（它会误杀「停止后立刻再发」的新一轮）；`setCancelled` 因此返回 boolean 并经 `/cancel` 的 `cancelled` 字段下发，前端据此区分「正在停止」与「该轮次已经结束」（见下文「取消真正生效」）。前端 `useAgentStream.abort()` **先就地解锁本地状态、再 `await` POST /cancel，全程不拆本地 SSE**，用户随后手动发的新消息必然排在后面，顺序天然正确。**轮次登记同步发生在控制器线程上**（2026-09-09 补齐）：`handleUserMessage` 不再整体挂 `@Async`——`beginRun` 在返回给控制器之前完成，之后才把循环本体提交给 `taskExecutor`（线程池经 `setTurnExecutor` 方法注入，**刻意不进构造器**：本类构造器由 `@RequiredArgsConstructor` 生成，加字段就要同步改 EvalHarness 与九个编排器测试；字段为空时就地同步执行，正是各单测与回放评测里 `new AgentOrchestrator(...)` 的既有行为）。此前「chat 已返回 200、池线程还没执行 beginRun」那个空窗里发来的 cancel 会**整个落空**（云端 taskExecutor 打满时窗口会放大到秒级），用户点了停止却眼看着它继续写文档、继续烧 token；现在窗口不存在——起跑后第一件事就是 `runLoop` 顶部的取消检查，模型一次都不会被调用。提交被有界队列的 AbortPolicy 拒绝时先 `endRun` 撤销登记再把异常原样抛回控制器，不留「有活跃轮次但什么都没跑」的幽灵。
- **被取代的旧轮次继续跑完，但对会话级状态全程静默**（`isCurrentRun` 判据 = `activeRuns.get(cid) == guard`）：run_state 状态点（`markRunState`）、`bubble_end` / `cancelled` / `error` / `doc_stream_end` / `text_delta` / `skill_update`（`sendRunEvent`）、`closeSse`、`officePassStateStore.clear` 一律跳过。不这样的话旧轮次的一个 `bubble_end` 就能把新轮次的气泡当场结束掉，一次 `mark(FINISHED)` 就能把新轮次的 RUNNING 盖成终态。**它自己的东西照写**：消息行、执行日志、重试预算——所以两轮的回复在历史里各自完整，这正是回归用例断言的东西。刻意不强杀旧轮次：工具副作用（已写进文档、已发出的请求）回滚不了，跑完不比半路掐更危险。
- **`SkillRouter` 的登记簿改按 runId 索引**（`activeByRun`，原 `activeByConversation`）：`activateForTurn(conversationId, runId, …)`（conversationId 只用于埋点归属）、`activeSkills(runId)` / `activeSkill(runId)` / `visibleTools(runId, …)`、新增 `clearRun(runId)` 由编排器在每条终态路径上调。本类不再持有任何 conversationId 级的可变状态。`ContextAssemblerService.assemble` 因此多了一个 **`runId` 形参（第 2 位）**——prompt 注入与工具白名单必须读同一轮的生效集合，这条「同源」契约在并发下只有按 runId 才成立。
- **流式增量也过轮次闸**（2026-09-09 补齐）：`AgentStreamHandler` 构造器多了第 8 个参数 `BooleanSupplier currentRunGate`，编排器传 `() -> isCurrentRun(guard)`。本类所有会话级 SSE 出口收进私有的 `sendSse`（`text_delta` / `reasoning_delta` / `bubble_start` / `artifact` / `token_usage`，以及无编排器回调时的 `bubble_end` 与 `error`+close），闸关就静默丢弃；`runLoop` 里 `setOnEditorStream` 的回调体整体加了 `isCurrentRun(guard)` 前置判断（`doc_stream_data` 与 `noteStreamContent` 都按 conversationId 寻址，是会话级的）。**闸只管往 emitter 上发什么**：本轮的内容累积、看门狗、终态幂等、`onToken` / `onEditorStream` / `onComplete` / `onError` 回调一概不受影响——被取代的旧轮次照常跑到自己的终态、落自己的库，只是对 SSE 完全静默。七参构造器保留（恒开闸），给无轮次概念的调用方与三个既有 handler 单测用；**编排器必须走带闸的重载**。
- **对外契约零变化**：SSE 事件名与载荷、`/api/agent/chat` 与 `/api/agent/connect/{id}` 的请求响应形态一字未动，runId 不出现在任何载荷里；前端与 Office/WPS 插件不需要改。
- **仍然存在的已知局限（刻意没做）**：① 埋点 `TelemetryTurnTracker` 仍按 conversationId 记，被取代那一轮的 `ai.turn` 不会闭合；② 跨进程重启的 `AgentRunRecoveryService` 仍按 conversationId 回收（重启后进程内一个 RunGuard 都不剩，`agent_run_record` 每会话一行仍然正确，加 runId 列没有消费者）；③ 旧轮次收尾时仍会执行 `editorBridgeService.setStreamingMode(cid, false)`，把新轮次正在进行的编辑器流式写入模式一起关掉。**这里刻意没加闸**：流式模式是工具打开、收尾关闭的会话级开关，给旧轮次加闸只会换成「旧轮次开的流式模式永远不关」这种更难查的泄漏；正确修法是把流式模式本身做成轮次级状态，属 ai-doc-bridge 领域。

**前端消费**
- **活跃文档与拖拽入上下文（dev-board#779 K6/K8）**：`activeTab` prop 来自工作台的 `currentActiveTab`，**虚拟标签（浏览器 / AI 计划 artifact / 设置 / 广场详情 / 版本对比…）在那里就被挡掉了**，判据在 `pages/project-overview/activeTabContext.js`；ChatInterface 这一侧 `id: String(props.activeTab.id || props.activeTab.wpsFileId)` 照旧原样透传，不要在组件里再加一道形态判断（判据只有一个出处）。`activeContext` 仍只在 `!hasFiles && !hasImages` 时才带。拖进对话区的四种来源与落点在 sidebar-shell.md 的拖拽段；组件这边只暴露两个公开方法给宿主调：`addFile(file)` 与 `uploadLocalFilesAndAddContext(fileList)`（后者与上传对话框共用 `uploadFilesAndAttach`，字节没传上去的文件**不并入附件**那条老规矩照旧）。
- **把项目文件挂进上下文的四个入口（dev-board#794 K15）**：文件树拖拽、文件树右键「加入 AI 对话」、输入框 `@` 引用选择器、「+」对话框的「从项目选择」页签。**四者的检索排序、文件夹 10 文件上限、系统文件夹剔除只有一份判据**：`frontend/src/utils/aiContextFiles.js`（`matchProjectFiles` 前缀命中先于包含命中 / `countDescendantFiles` + `AI_CONTEXT_FOLDER_FILE_LIMIT` / `excludeSystemFolders` / `dirLabelOf`）。Cmd+P 快速打开（`QuickOpenPanel.vue`）也改成读这一份——原先那是第二份实现。
  - **`@` 选择器是独立组件 `components/AgentMessage/MentionPicker.vue`**，纯展示 + 过滤：清单由 ChatInterface 加载（`allProjectFiles` → `mentionCandidates`，与「从项目选择」页签同一份）按 props 传入，选中只 `emit('select')`。**行点击必须走 `@mousedown.prevent` 而不是 `@tap`**：点一下先 blur 掉 contenteditable 的话，记着 `@查询` 在哪的那个 Range 就没了，标签会插到文档末尾、而用户打的 `@xxx` 原样留在正文里被当成提问发出去。
  - **触发判据在 `ChatInterface.detectMention()`**：光标前那段 `@xxx` 必须落在**同一个文本节点**里，且 `@` 前面是行首或空白（插完标签补的那个 `&nbsp;` 也算）。内联标签里画出来的那个 `@` 在 `contenteditable="false"` 的 span 里，光标进不去，不会自触发。命中时把 `{node, start, end}` 记进 `mentionAnchor`——**必须提前记**，理由同上条。选中时按这个 Range `deleteContents()` 再让既有的 `insertContextTagToInput` 往当前光标插标签，位置天然对上；锚点失效只退化成「标签追加到末尾」，绝不因为一次定位失败就把选中的文件丢掉。
  - 右键那条链是 `FileTree` 的 `add-to-ai` → `project-overview.onAddFileToAiContext` → **先 `resolveChatInterface()`**（AI 面板此刻可能根本没开，`$refs.chatInterface` 不在就等于点了没反应）→ 既有的 `addDraggedFileToAiContext`。
- **输入框键位（dev-board#795 K16）**：两个输入卡（空态 / 常态）都只有一个 keydown 绑定 `@keydown="handleInputKeydown"`，Enter 仍转交 `handleEnterKey`（isComposing 闩留在那里，别再加第二个 keydown 绑定）。
  - **Esc 只挂在输入框局部，永远不进 `config/commands`**：那条硬规则（`config/commands/ai.js:6`）说 Esc 一旦成为菜单加速键就会吞掉编辑器和所有输入框的 Esc。优先级：引用浮层开着 → 收浮层；流式中 → `handleAbort`；否则**两段式清草稿**（第一次只 toast「再按一次 Esc 清空草稿」并上 3 秒的闩，第二次才清）。清草稿会连内联标签一起清掉（`syncContextFilesWithInlineTags` 随之清空 `contextFiles`），**粘进来的图片刻意不动**——它们各自有 × 可摘。
  - **Cmd/Ctrl+Enter 的分支排在引用浮层之前**：浮层开着时按它，用户要的是发送而不是再选一个文件。这也是这条分支唯一与普通 Enter 不同的地方（普通 Enter 本来就发送），测它必须测这个形态，否则等于没测。
  - 上箭头翻历史：**只在输入框为空时起步**，之后只要草稿还等于刚回填的那条就继续往上，用户一改字就退出（`handleRichInput` 里重置 `historyRecall`）。到顶停住不绕回，下箭头翻过最新一条即清空草稿。取的是 `displayContent || content`（与回退回填输入框同口径）。
  - 发送 / 停止两颗键是真 `<button type="button">` + `@click`（不是 `@tap`：`.attention-locator` / `.back-to-latest` 早有先例），CSS 里 `appearance:none` + `::after{border:0}` 打平 uni-h5 的默认样式。**刻意不绑原生 `disabled`**——只有图片或附件、正文为空时 `handleSubmit` 仍要能发，绑上去会把这条路堵死。三个下拉的选项加了 `role="option"` + `tabindex="0"` + Enter/空格（`onOptionKey`），容器 `role="listbox"`。
- **正文渲染与流式解析的四条性能红线（dev-board#750）**：后端每个模型 token 发一条 `text_delta`，所以「每 token 做一次」的东西都会被放大几千倍。
  ① **markdown-it 实例必须是模块级单例**（`frontend/src/utils/markdownRenderer.js`），**绝不能放进组件的 `data()`**：Vue 3 会把 `data()` 返回的对象整个 `reactive()`，解析过程中对 `md.options`/`md.block`/`md.inline`/`md.renderer` 的每次内部访问都要过 Proxy trap，而 render 跑在 computed getter 里还要登记依赖。实测（markdown-it 14 + @vue/reactivity，8450 字中文法律文书）：普通实例 0.545ms / 响应式实例 2.028ms / 在 computed 里 2.377ms，**慢 3.7~4.4 倍**；整条流累计 313ms → 1972ms。`PlainTextEditor` 用 `this._md`（挂实例 ctx，不进 data）是对的写法。
  ② **`MarkdownPreview` 的渲染按帧合并 + 分段增量**（分段是 dev-board#811 K31 加的）：首屏那一次在 `data()` 里同步渲染（静态预览/历史/计划卡挂载即有内容，不推迟首字），之后走 `scheduleRender()` 每帧至多一次；`requestAnimationFrame` 不存在时回落 `setTimeout(16)`，`beforeUnmount` 取消待执行的帧与定稿定时器。`renderFrame`/`sourceText` 的形状别乱改——护栏在 `frontend/tests/markdown-table/mdStreaming.test.mjs`（两条病灶各自还原都验证过会转红）。分段见下面「长会话的渲染开销」第 ③ 条。
  ③ **工具载荷的解转义必须是增量的**：`decodeProtocolTagsIncremental(chunk, carry)`（`agentTagProtocol.mjs`）
  只解这一段新到的、把「还可能长成完整转义标签」的尾巴留到下一次，闭合时（`</tool_code>` /
  `</tool_output>`）与 `flushRemainingBuffer` 各收一次。原先每个 delta 都对**整段已累加**的文本
  重跑一次全串正则，载荷含被中和标签时是 O(n²)。**尾巴的判据有两半，漏一半就会漏还原**：
  `&lt;/tool_out` 这种标签名被切断的，和 `&l` / `&lt` 这种**连 `&lt;` 本身都被切断**的
  （逐字符喂时就是后者，最容易漏）；另外孤立的 `&lt;` 后面永远不来 `>` 时要按 `MAX_CARRY` 放行，
  否则之后整段输出永久扣在缓冲区里。护栏在 `tests/tag-protocol/protocol.test.mjs`：
  **同一段文本按 1/2/3/5/7/13/64/9999 字符切都必须与整段一次解码逐字相同**。
  ④ **SSE 热路径（`useAgentStream` 的读取循环、`handleEvent`、`text_delta` 分支）不许加 `console.log`**：`vite.config.js` 没有 `drop_console`，这些日志会进生产包；DevTools 打开时每条几十到几百微秒 × 每 token 一次，还会把会话正文写进控制台。
- **长会话的渲染开销（dev-board#811 K31，审查 C-05 / C-10 / C-12 / F12）**：上面那四条管的是「每 token 做一次的事有多贵」，这一条管的是「每 token 要重建多大一棵树」。本机基线（200 轮会话 + 一条 3000 token 的回答，`frontend/tests/chat-presentation-ui/perf.mjs`）：**每 token 14.5ms、墙钟 44.6 秒、最坏一帧 92.2ms、13 个长任务共 826ms、喂进 markdown 解析器 2745 万字符**；改造后 **1.7ms / 5.2~6.4 秒 / 19.8ms / 0 个长任务 / 59 万字符**。成本归属是实测出来的，不要凭直觉改：`buildChatTurns` 单次只占 0.68ms，markdown 解析只占 0.065ms，**剩下约 13.8ms 全是 Vue 重建那 200 轮的用户气泡子树**（仅两颗 SVG 图标按钮就占 11.8ms——它们 `opacity:0`，hover 才看得见，等于每 token 为看不见的东西重建 400 个 SVG）。
  ① **`v-memo` 必须挂在 `v-for="turn in chatTurns"` 那个元素上**。挂到 `v-for` 里层的元素上是无效的：Vue 的 `v-memo` 缓存槽按 `_cache[n]` 取，不在 v-for 元素上时 200 个迭代**共用同一个槽**，每次都互相覆盖——实测这么写完全没有效果（14.5 → 13.8ms），而且不报错。依赖清单 = 这棵子树读到的每一个气泡字段（正文三件套 / timestamp / receiptState / submissionMode / wasPendingInbox / dbMessageId / clientRequestId / 三个数组的身份与长度 / `isStreaming` / `bubbles.length`），**漏一个就是「内容变了界面不动」且一声不吭**；助手那半边不进依赖——`RootBubble` 是子组件，有自己的响应式 effect，父层 memo 跳过不影响它自我重渲。语言切换是整页 reload（`i18n/index.js`），所以 `$t` 不进依赖。
  ② **`buildChatTurns(bubbles, { cache })` 与那条 `v-memo` 是一对，缺一边都等于没做**。`cache` 是 ChatInterface 里一个**普通对象**（`turnCache`，绝不能进响应式数据，它每次求值都要被写一遍），作用是让内容没变的轮次**返回上一次那个 turn 对象**——memo 的第一依赖就是它。判等在 `chatTurns.mjs` 的 `sameTurn`：**只比派生字段与成员清单，不比气泡正文**（正文变化由气泡代理自己驱动子组件重渲）。两个坑：没有清单的轮次要共用 `NO_TODOS` 常量（每次现造 `[]` 会让第一轮永远命中不了复用）；`turn.label` 按气泡走 `labelCache`（WeakMap，按 source 比对）。带 cache 的那一遍**不比不带的慢**（实测 0.636ms vs 0.704ms）。
  ③ **`MarkdownPreview` 分段渲染**：正文切成「已定稿前缀」与「还在长的尾巴」两段各自 `v-html`（`.markdown-stable` / `.markdown-tail`，两者 `display: contents` 以免切点处段落外边距不再合并）。前缀字符串不变 Vue 就不碰它的 DOM——**用户在已输出正文里选中的文字不再被下一个 token 清掉**，也不用重新解析。切点规则在 `frontend/src/utils/markdownStableSplit.js`：空行之后、不在 ``` / ~~~ 围栏内、且下一行明确是新顶层块（不缩进、不是 `>`、不是列表项——列表和引用能跨空行续，切进去会把一张清单变成两张）；正文短于 4000 字符不分段，前缀每次至少前进 2000 字符。挡不住的边角（尾巴里才出现的链接引用定义）由 400ms 的定稿校正兜底：整篇重渲一次，**与分段结果相同就一个字节都不写回**（写回会清掉用户刚选的字）。**挂载时内容就很长的静态消息不分段**（没有 DOM 要保住），分段只发生在「正文变长」这条路上。
  ④ **历史分页**：`GET /api/ai/history` 加了可选的 `limit` / `before`。**不带 `limit` 时原样返回整条会话的裸数组**——Office/WPS 任务窗格与旧桌面端都按裸数组解析，改成信封会当场打断它们；带了才回 `{messages, hasMore, nextBefore}`。游标按 **id** 不按 `createdAt`（同一轮里几条消息可能同毫秒，MySQL 还按秒截断，用时间戳当游标会永久丢条）。前端首屏取 60 条（`ChatInterface.HISTORY_PAGE_SIZE`，工作台 `loadHistoryChat` 传同一个值），`handleHistoryScroll` 在「离顶不到一屏」时补上一页并**把 scrollTop 加回长出来的那一截**（不补偿的话用户每翻一页就被弹到一段不相干的对话上）。`loadMessages` 两种形状都吃（数组 = 旧后端/夹具/整条会话）。
  ⑤ **`assemble` 那一侧只减列、不减行**：`ContextAssemblerService` 改走 `messageService.listHistoryForAssembly(conversationId)`（`ProjectAiMessageRepository.findHistoryForAssembly`，只投影 `role` + `content`，且不查附件）。**刻意不加行数上限**——`ContextCompressor` 的第二到第五层（去冗余 / 压工具结果 / 摘要旧消息 / 激进压缩）读的是整条历史，砍掉前面的行会静默改变摘要内容，等于让模型忘掉会话开头。审查 C-12 提的「投影掉 executionLog」不可执行：`ProjectAiMessage` 根本没有这一列，执行日志是拼进 `content` 的，而 `content` 正是要读的东西。
  ⑥ **虚拟化没做，是实测之后决定不做的**：200 轮夹具下首屏回灌 105~144ms、单次跳转 0.4~0.7ms、滚动跟随最坏一帧 18.5ms，离「首屏 1 秒 / 跳转 100ms」的拐点还差一个数量级。真正的瓶颈是每 token 的重建，已由 ①②③ 解决。`RootBubble` 的历史执行段默认折叠（`isExpanded` 初值 = `isActive`）也在替 DOM 规模兜底，别顺手改成默认展开。
  ⑦ 护栏：`tests/project-home/chat-turns-incremental.test.mjs`（turn 复用契约 + 两条源码级接线断言，防有人把 `v-memo` 或 `cache` 摘掉）、`tests/markdown-table/mdStreaming.test.mjs`（分段结果与整篇渲染逐字相同 / 围栏与松散列表不被切开 / 前缀不再重解析）、`tests/chat-presentation-ui/run.mjs`（200 轮 + 800 token 的宽松性能断言与「流式中选区不被清」）、`backend/.../ProjectAiMessageHistoryPageTest`（分页游标与投影）。**性能阈值刻意宽松**：这里拦的是整片回归，卡死具体毫秒只会换来一条间歇性红线。
- **对话按时间线分层（2026-09-15，dev-board#646，取代 #584 的固定活动面板）**：`ChatInterface` 仍用 `AgentMessage/chatTurns.mjs` 按 USER 分轮（`.conversation-turn` + `.message-row.user/.assistant`，保留原 bubbles 与全局索引），但思考/执行/任务/产物/正文**全部回到消息流里按发生顺序渲染**，顺序由 `AgentMessage/chatTimeline.mjs` 决定。`TurnActivityPanel.vue` / `TitleCard.vue` / `RootBubble` 的 `hideActivity` / 固定活动面板入口 / 28 个 `chat.activity*` 文案键（只留下 ChatInterface 还在用的 `activityBackToLatest`）**已整体删除**；「待回答/审批的常驻定位入口」以浮条形态补回（dev-board#663，见下条）。后台任务取消仍走输入区与 BackgroundTaskIndicator，不混用生命周期。
  - **待处理定位条（dev-board#663）**：长会话里反问卡/审批卡会被滚出视野，用户既看不见也回不去。`ChatInterface` 在 `.return-to-latest` 容器里（新增 `.locator-row` 横排，「回到最新」按钮拿到 `.back-to-latest` 类名）多渲一个 `.attention-locator` 按钮，点击 `navigateToMessage({index, target:'attention'})` 滚到卡片并给它挂 1.6s 的 `.chat-attention-flash`（样式在 **RootBubble 的 scoped style 里**——带 `data-chat-attention` 的两处都由该组件模板渲染，ChatInterface 的 scoped 选择器匹配不到）。三条契约：
    ① **判据只此一处**。`chatTurns.mjs` 导出的 `pendingAttention(turns)` 读 `buildChatTurns` 已经算好的 `attentionIndex` / 新增的 `attentionKind`（`'question'|'approval'`），**不许再写一份判定**——那条判定含 `isLatest && !isStreaming`，与 RootBubble 给卡片 `actionable` 的是同一条链，另起一份的表现是「定位条把用户送到一张点了没反应的卡上」。返回 `{index, kind, count}`，最早一条 + 计数；今天 `attentionIndex` 只在最新一轮设置，所以 count 恒为 1，聚合是给判定放宽留的（`attention-locator.test.mjs` 直接喂合成 turns 钉住这条）。
    ② **只在测量到不可见时出现**。`useChatReadingPosition` 新增 `isMessageOffscreen`（与 `navigateToMessage` 共用私有 `locate`，两者必须解析同一个元素，否则会「测量一张卡、滚到另一张」），**元素没渲染出来时返回 false**——「查不到」不是「不可见」的证据，返 true 会让每张刚到的卡都闪一下浮条。刷新时机是新增的第三个参数 `onViewportChange`（滚动、ResizeObserver、跳转后各调一次），写进一个 ref 而不是让 computed 直接读 DOM：computed 每次滚动都产生新对象会把整个 ChatInterface 模板拖进重渲染。
    ③ 文案键 `chat.attentionLocatorQuestion` / `chat.attentionLocatorApproval`（`{n}` 计数，两语成对）。
  - **钢琴键会话导航（dev-board#791 K12）**：`AgentMessage/ChatTurnRail.vue`，props `turns`（直接收
    `chatTurns`）+ `activeKey`，emits `jump({key, index})`。静息是消息区右缘一列 12px 刻度（每轮一格，
    `turn.status` 定色：running → `--awd-gold`、awaiting_input/awaiting_approval → `--awd-danger`、
    queued → `--awd-gold-line`、其余 → `--awd-text-3`；当前轮加长加粗换 `--awd-accent`，且当前/运行中/
    待处理这三格 `flex: 0 0 auto` 不参与压缩——200 轮时其余格被压到 2.7px，跟着压就糊成一条灰线），
    hover 或键盘聚焦展开 200px 浮层逐轮列出 `turn.label`。**轮数 ≤ 1 不渲染。** 四条契约：
    ① **数据零新造**：`turn.label` / `turn.status` 是 `buildChatTurns` 早就算好的两个字段（在这之前零消费），
      组件不重算任何一轮的状态——另起一份判定就会和定位条、RootBubble 的 `actionable` 链各说各话。
    ② **跳转必须转调 `navigateToMessage({index, target:'turn'})`**，不许自己 scrollTo（同定位条那条
      「跳转和『是否在屏』必须解析同一个元素」）。`index` 取那一轮**第一条用户消息**的全局下标
      （`turn.user.index`，历史里开头就是助手的老会话退到 `turn.assistants[0].index`），因为
      `navigateToMessage` 认的是 `[data-message-index]`。
    ③ **当前轮靠 IntersectionObserver，观察的是轮级元素**（`.conversation-turn` 新加的 `:data-turn-key`，
      几十到两百个），`root` 取 `.message-list`、`rootMargin: '-8% 0px 0px 0px'`，命中的里取 rect.top
      最小的那一轮 = 视口内最靠上的那一轮（顶边内缩 8% 是让只剩一条边挂在上沿的上一轮及时让位）。
      **不许改成 scroll 回调里逐轮量 `getBoundingClientRect`，也不许改成观察每条消息**——那正是长会话
      掉帧的两种写法。重挂 observer 的判据是 `turnSignature`（长度 + 首尾 key）而不是 `chatTurns` 本身：
      后者每个 token 都重算，跟着它重挂 200 个 observer 等于把这条纪律从另一头丢掉。
      回调里现读 rect 而不是用 entry 里那份快照：仍在屏的条目不会再来回调，存下来的坐标滚两下就过期。
    ④ 文案键 `chat.railLabel` / `chat.railTurnIndex` / `chat.railUntitled`（两语成对）。
      容器定位（`.message-area`）与 12px 列不覆盖滚动条的理由见 `sidebar-shell.md` 同名段。
    实测（`tests/chat-presentation-ui`，200 轮夹具 `window.loadManyTurns(200)`）：单次跳转 0.3–0.5ms，
    滚动跟随最坏一帧 17.5ms。
  - **两个函数就是全部契约**。`captureChatTimeline(bubble)` 只在 **useAgentStream 的解析器边界**调用（标签开/闭前后、尾部 flush、flushRemainingBuffer、artifact、plan_update、step、reasoning 增量、以及每次新建助手段），只往 `bubble.timeline` **追加**——记录时机放在渲染期就会让后到的正文跑到先发生的工具上面去。`visibleChatTimeline(bubble)` 派生渲染列表：相邻 process 条目并成一个 `execution` 组（RootBubble 渲成可折叠的 `.activity-summary` / `.activity-details`）、空 thinking 与空 plan 过滤掉、末尾按 `content.length` 补一条 `key:'tail'` 的正文条目（错误提示与编辑器状态行是绕过解析器直接追加到 `content` 的）。`isTimelineEntryActive` 只让「最后一条非 plan/title 条目」有活动态。
  - **text 条目存的是 `bubble.content` 的绝对下标（start/end），所以 `content` 只许 append、不许整段替换**。这是整套机制里唯一的隐式约束，违反它不报错、只是正文错位或整段消失。要清空必须**连 `timeline` 一起清**（`state_recovery` 那条路就是两者一起置空的，改那里时别只清一个）。`visibleChatTimeline` 对 `end > content.length` 做降级截断兜底，但那是兜底不是许可。
  - **兜底分支的判据必须是 `bubble.timeline?.length`**：没经过解析器的气泡（PPT 取消/开始那两条系统确认、更早的内存气泡）走兜底按字段拼一份时间线，而**空数组是 truthy**——写成 `bubble.timeline || [...]` 的话，刚建好还没收到 token 的气泡会渲染成空白。同理，**每一处新建助手段都必须先 `captureChatTimeline(next)` 再 push**（`sendMessage` / `bubble_start` / `input_applied` 续跑三处），漏掉的那一处表现是「插话续跑后首 token 之前什么都不显示」。
  - **围栏剥离与分片不变式**：`processTextStream` 现在每轮都把 `parserBuffer` 抽干到只剩半截标签，所以 ```` ``` ```` 的剥离**必须放过「结尾还可能长成完整围栏」的那截**（`PARTIAL_FENCE` = /`{1,3}[A-Za-z]*$/，与半截标签一样留在缓冲区里；流结束时由 `flushRemainingBuffer` 补剥）。不放过的话跨分片的围栏永远拼不起来，````xml```` 原样漏进正文——**同一段文本按不同分片得到不同正文**，而且不报错。历史回灌（`processTextStream(content, true)`）恒不剥离：历史正文里的代码块是真的。护栏 `tests/project-home/chat-timeline-contract.test.mjs`（同一输入按 1/3/9999 分片必须逐字相同）。
  - **历史回灌把恢复出来的计划卡插进时间线时，判据必须与 `recoverPlanTodos` 同源**——两边各写一份正则时，失败的 `todo_write` 也会命中，而 `findLastIndex` 落空（-1）配上 `splice(planIndex + 1, ...)` 会把计划卡放到整条时间线**最前面**。单一判据是 `chatTurns.mjs` 导出的 `isPlanSnapshotCall(item)`。
  - 任务跨轮继承直到 todo_write 覆写，不能在 sendMessage 清空全局 planTodos；新 assistant bubble 复制当前快照。历史从成功的 JSON todo_write 参数恢复快照，无法解析的旧格式保留原执行记录，不猜造任务。历史/暂停清单的 in_progress 是最后记录状态，TodoProgressCard `live=false` 不显示动态执行文案或转圈。
  - `useChatReadingPosition` 观察实际消息内容高度（ResizeObserver），仅在 followLatest 时贴底。向上阅读/定位历史暂停跟随，点击“回到最新”恢复；禁止重新加 bubbles 全树 deep watcher。导航仅滚动本聊天容器。
  - 历史 root thinking/final 在剥离 process 后全量提取，保留多段回复；嵌套 thinking 留在对应 process。此改动不重写 bubble_start 或服务端消息持久化边界。`input_applied` 切换 assistant 段前必须先 `flushRemainingBuffer()`，否则上一段无标签正文会被 resetParser 静默丢弃（question-stream.test.mjs 定向回归）。
  - 回归：`npm run test:project-home`（定位条判定在 `tests/project-home/attention-locator.test.mjs`，会话恢复在 `last-conversation.test.mjs`）、`npm run test:tag-protocol`、`npm run test:md-table`（代码块复制键改了 `renderMarkdown` 的签名）；浏览器真实 Vue 组件 + 合成历史/SSE 边界夹具：`node tests/chat-presentation-ui/run.mjs`（含定位条的出现/点击/高亮/消失一轮，插话的未读角标 → 引用行跳转 → 已送达 → 删除后两处都消失 → 菜单停止，以及四处复制、运行态工具名与秒数、本轮 token 一行、重新生成走回退通道）（Chrome 路径可用 CHROME_PATH 覆盖，截图落点用 `AWD_SHOTS=<目录>`；无需真实模型或业务数据）。该夹具**没有 uni 的模板编译器**，`@tap` 不会被映射成 click——点待处理区那几个动作、以及确认框的按钮要用 `dispatchEvent(new CustomEvent('tap'))`。**剪贴板在无头 Chrome 里读不回**（异步 API 恒 NotAllowedError，`execCommand('paste')` 也被拒），所以复制用例验的是「组件算出来交给平台的是哪段文字」+「平台确实收下了」，真机粘贴留给走查。
- `frontend/src/composables/useAgentStream.js`（1233 行）— SSE 核心：connectSSE（fetch+ReadableStream，非 EventSource）、sendMessage、abort、handleEvent（~:352 分派）、handleTag/processTextDelta（XML 标签驱动气泡组装）、handleStateRecovery。
- **输入区上方现在有三样东西**（都在两处输入卡片里各渲一份：空态 `centered-style` 与常态）：粘贴图缩略图块、**当前文档 chip**（`.active-doc-chip`）、**「模型看不了图」常驻提示**（`.input-images-note`，已从缩略图块里挪出来）。改其中任何一处都要两边一起改——漏一边的表现是「新会话里有、聊起来就没了」。
- `frontend/src/components/ChatInterface.vue` + `AgentMessage/`（RootBubble/ProcessCard/ThinkingCard/TodoProgressCard/WalkthroughCard/**QuestionCard**/**SubtaskResultCard** + `chatTimeline.mjs`/`chatTurns.mjs`）。
- **反问卡（`AgentMessage/QuestionCard.vue`）**：`<question>`/`<option>` 由 useAgentStream 解析成气泡上的 `question={text,options,answered}`，**反问正文不进 `bubble.content`**——卡片不接这个字段等于正文对用户不可见。`<option>` 只在 question 作用域内当标签（正文里的字面量 `<option>` 不造问题卡）。卡片挂在 RootBubble 的 main-content 之后，正文为空时只渲染选项（兼容正文仍在 content 的旧格式，防显示两遍）；可操作性沿用 `isLatest && !isStreaming && !answered` 那条链；问题卡**刻意挂在时间线循环之外**（`v-if="bubble.question"`），所以「只有 question、没有正文」的那一轮照样渲染得出来——原先靠 `RootBubble.isReady/hasContent` 把 question 算作可见产出的那条地雷已随 #646 消失，别再去找那两个计算属性。点选项 = `sendMessage({prompt: 选项原文})`，**不拼「我选择了 X」**（契约 D：选项本来就短、像用户自己打的）。
- **用户消息的送达状态角标（dev-board#779 K7②）**：判据只有气泡上的 `receiptState`（pending|applied，由 `ensureInboxUserBubble` / receipt 回执写入）与 `submissionMode`（steer|queue），**不许另起一份状态机**。渲染在 `ChatInterface` 的 `.bubble-footer` 里一行 `.bubble-receipt`，pending 时整条 `.user-bubble` 再挂 `.is-unread`（降透明度 + 虚线边）。文案 `chat.receiptPendingSteer` / `receiptPendingQueued` / `receiptApplied`，两语成对。
  - **applied 只对「曾经排过队」的那条报「已送达」**，判据是气泡上的 `wasPendingInbox`（第一次见到 `state==='pending'` 时置位）。普通消息发出去就是 applied，每条下面都挂一行回执是噪音。
  - **这五个字段必须在 `createUserBubble` 里预声明**（`inboxMessageId` / `clientRequestId` / `receiptState` / `submissionMode` / `wasPendingInbox`）——运行时才挂上去的字段 Vue 3 追踪不到，角标会「第一次对、之后再也不变」（同 `createAssistantBubble` 里 `documentEdited` / `status` 的那条老规矩）。
  - 历史回灌出来的气泡没有这些字段，天然无角标；这是有意的，历史里不该再提「送达」。
- **待处理区与对话流的对账（dev-board#779 K7①）**：删掉一条待处理插话时，对话流里同 `inboxMessageId` 的用户气泡必须同时消失——此前删完气泡还在，用户看到的是「删了个寂寞」。实现是 `useAgentStream.pruneRemovedInboxBubbles()`：**凡是整表覆写 inbox 的地方一律走 `syncInboxSnapshot(snapshot)`**（state_recovery / `inbox_updated` / delete 的快照响应三处），别再直接调 `applyInboxSnapshot`，漏一处就是「另一个标签页删了条目，这边气泡还挂着」。
  - **判据成立的前提是后端 `AgentInboxService.snapshot()` 只过滤 DELETED**：applied 的条目仍留在 `items` 里（state='applied'），所以「气泡还标着 pending、id 却已不在快照里」等价于「它被删了」。后端哪天改成「applied 也从快照里摘掉」，这条对账就会在 apply 的瞬间误删气泡。
  - **只摘 `receiptState === 'pending'` 的**：已经 applied 的插话模型真读过，属于这段对话的事实历史，整理待处理区不该把它从记录里抹掉（长期原则 1）。
- **同一条插话在两处的分工（dev-board#779 K7③）**：**正文留在对话流，待处理区退成引用行**（`.agent-inbox-row.is-quote` 左侧竖线 + 单行省略号）并多一个 `chat.inboxInStream`「在对话中查看」入口，`@locate` → `ChatInterface.handleInboxLocate` → `navigateToMessage({index})` + 1.6s 的 `.chat-inbox-flash`。理由：这条被读取后待处理区就消失了，正文只放在那里等于一读即丢；而待处理区是操作台（编辑/排序/立即发送/删除）、就在输入框上方，越矮越好。哪些条目算「流里已有气泡」由 `inboxStreamIds` 告诉 `AgentInbox`（`stream-ids` prop），**AgentInbox 自己不去翻 bubbles**。
  - 闪的类名刻意不是 `chat-attention-flash`：那套样式在 RootBubble 的 scoped style 里，而跳转目标 `.message-row` 由 ChatInterface 渲染，scoped 选择器匹配不到。
- **刷新后回到上次那段对话（dev-board#779 K7④）**：键 `awd_last_conversation_<projectId>`，读写收在 `frontend/src/utils/lastConversation.js`（零依赖纯函数，两边都 import 它，**不许各写一份键名字面量**）。**写在 `ChatInterface`**（会话 id 归它所有——新会话是 `handleSubmit` 现造的，工作台那边的 `currentConversationId` 只在点历史时才更新），`watch(currentConversationId, …)` 且**刻意不加 `immediate`**：挂载那一刻它还是 null，立刻回写会把工作台正要读的记录当场抹掉，恢复永远不发生且毫无报错。**读在 `project-overview.vue` 的 `restoreLastConversation()`**，挂在 `toggleAiPanel` 打开那一支上——AI 面板默认收起、ChatInterface 挂在 `v-if` 上，onLoad 时它还不存在。
  - 三条闸缺一不可：① `restoredLastConversation` 每个页面实例只试一次（关掉面板再打开不再恢复）；② 会话必须还在 `chatHistoryList` 里（已删除/换账号/脏值静默放弃）；③ 等待期间 `currentConversationId` 已经有值就让开，绝不覆盖正在进行的会话。全程不弹提示。
  - **三条「已经指定了要开哪一条」的入口必须先把闸关掉**（`onLoad` 的 `query.conversationId`、`openConversationInPanel`、`resolveChatInterface`），否则两条路会同时调 `loadHistoryChat`。
  - 点「新对话」把 `currentConversationId` 置空，watcher 随即清除记录——刷新后再把旧会话拽回来是违背用户刚表达的意图。
- **菜单「停止当前任务」（dev-board#779 K5）**：`ChatInterface.menuStop` 先 `await handleAbort()`（`isStreaming` 时）再逐个取消 `runningTasks`，返回「AI 轮次算 1 + 后台任务数」。**判据必须与菜单项的置灰判据 `menuState().aiRunning`（`isStreaming || runningTasks.length`）对齐**——此前只遍历 runningTasks，于是最常见的「只有 AI 在生成」点了循环零次、什么都不发生，而模型照样在跑、在改文档、在烧 token。停 AI 复用 `handleAbort`（输入区停止键的同一条路，附带提示；它现在 `return abort()` 以便被 await）。
- **历史回灌的 question 分支**（`ChatInterface.loadMessages`）：剥离时机必须在 `<process>` 之后（否则工具输出里复述的 `<question>` 字样会被误判成真反问）；未闭合也要认（后端 `containsQuestion` 只认起始标签）；`answered` 由「这条助手消息之后还有没有 USER 消息」判定，**刻意不学 artifact 硬写 `status:'draft'`**——那样重开会话会让已答过的问题又长出一排可点按钮。`cleanTitle` 的剥离清单也含 question，否则纯反问收尾那轮会把问题正文当会话标题。
- **工具返回结果折叠区（ProcessCard）**：渲染 `item.output`（此前后端一直下发、前端从不渲染），**默认收起**，按 items 下标记开合（依赖 items 只追加不重排）。`dispatch_subtask` 的输出按 SubAgentResult JSON 结构化成 `SubtaskResultCard`（解析失败退回纯文本，不抛异常）。截断提示的判据是 `AgentOrchestrator.truncate` 拼的 `...(截断)` 后缀——改后缀会让提示静默消失；提示文案**刻意不写具体字数**，因为上限是按工具分档的（见下）。
- **工具载荷的标签中和（两侧契约）**：工具的参数与输出被原样拼进 `<tool_code>` / `<tool_output>` 伪 XML，载荷本身可能含协议标签（读一份讲协议的文档、模型复述自己的输出、子任务 JSON 里带 `<final>`）。后端 `service/ai/AgentTagProtocol.java` 把**已知标签形状**的起始 `<` 换成 `&lt;`（SSE 与 executionLog 两条路径都中和），前端 `composables/agentTagProtocol.mjs` 按同一份清单还原。**清单只此一份**：前端 tagRegex 由 `PROTOCOL_TAGS` 生成，后端 `AgentTagProtocol.TAGS` 与它由 `AgentTagProtocolTest` 逐字对拍，Office 插件的 `KNOWN_TAGS` 必须是其超集（插件不渲染工具载荷，故不需解转义）。**中和范围刻意不是所有 `<`**：合同正文里的 `<甲方>`、`<Party A>` 必须原样呈现，全量转义会让律师在折叠区看到 `&lt;甲方&gt;`。**改动必须三处同步**（后端转义 / 流式解转义 / 历史回灌解转义），少一处的表现是「折叠区内容缺一截、剩下的半截串进正文」或「用户看到裸转义符」——两种坏法都不报错。截断在中和之前（`...(截断)` 判据不受影响）。
- **复制（dev-board#790 / 审查 F2、F6、D-07）**：四处，全部经 `frontend/src/utils/chatClipboard.js` 这一个口（`answerPlainText` 取文本 + `copyToClipboard` 放剪贴板并提示）。
  ① 助手气泡的「复制」（`RootBubble` 的 `.msg-copy-btn`）——**判据只有「有正文、流已结束」，刻意不挂 `showUseInDocument`**：那条判据（`utils/useInDocumentVisibility.js`，dev-board#728）问的是「这段话值不值得放进当前文书」，而复制的去处是邮件、微信、另一份文档；恰恰是 AI 刚改过文档的那一轮（`documentEdited=true`，整组「用到文档」消失）用户最想把修改说明拷走。把它并回那条判据的后果在真渲染夹具里立刻转红。
  ② / ③ 工具卡的「复制调用 / 复制输出」（`ProcessCard` 的 `.tool-copy-btn`，12px）——复制的是**原文**（调用串与工具返回原样），不是 `humanOutput` 那份可读化结果：用户按复制是为了复现或存证。**必须 `@click.stop`**，整行本身是展开/收起输出的开关。
  ④ 正文代码块右上角（`.md-copy-btn`）——按钮由 `utils/markdownRenderer.js` 的 fence/code_block 规则渲染，复制由 `MarkdownPreview` 的**事件委托**做（每帧整段重写 innerHTML，逐按钮绑监听会反复建了又丢）。**按钮文字随 `renderMarkdown(text, { copyLabel })` 传进去，`markdownRenderer.js` 里绝不许 import `@/i18n`**——它被 `node --test`（tests/markdown-table/*）直接 import，而 `@/` 别名只有 vite 认得，加一个 import 那两份用例整个跑不起来；没给 copyLabel 就不渲染按钮（兜底成硬编码英文会在中文界面里露出来）。
  `answerPlainText` 先剥协议标签再 `markdownToPlainText`：`bubble.content` 正常已被解析器剥干净，但流式被打断、旧格式历史、模型自己复述标签三条路会把 `<final>` 之类留在里面，粘进合同里比少复制一段更难发现。
- **重新生成（dev-board#790 / 审查 D-07）**：`RootBubble` 的 `.msg-regen-btn`，**只给最新一条助手消息**（`isLatest && !isStreaming && content 非空`）——回退会连带删掉目标之后的所有对话，对着历史中间某条点下去会静默毁掉后面好几轮。实现上**完全复用「回退到这条消息」那条链路**（同一个确认框、同一次 `rollbackConversation`——后端先把原路径整条存成一条「· 回退前存档」会话再截断，dev-board#780），唯一分叉点是 `ChatInterface.rollbackResend` 这个 ref：为真时 `confirmRollback` 结尾不回填输入框（用户此刻可能已经在里面打了别的），而是用原提问调一次 `sendMessage`，并在**后端截断成功之后**才发（历史没截断就重发 = 同一个问题在库里问两遍）。**重发用的是 `bubble.content` 而不是确认框里那份 `rollbackTargetContent`**：后者是 `displayContent || content`（给用户看、回填输入框用），而模型当初读到的是 `content`——点计划审批卡产生的那类消息两者差一整篇修订稿（契约 D）。两份文本必须在 `rollbackToMessage` 摘掉气泡**之前**取。确认框在重新生成时切换标题/正文/预览标签三处措辞，`rollbackArchiveNote` 那行两种用法共用（存档对两者都发生）。`openRegenerateDialog(assistantIndex)` 往回找最近的 USER 气泡；标志只在对话框真开了之后才置位，`cancelRollback` / `confirmRollback` 两条终态路径都要清它，否则下一次普通回退会莫名其妙地自动重发。**刻意不另起一条「重放这一轮」的后端通道**：截断是会删用户数据的动作，两份实现迟早在存档这件事上漂移。
- **运行状态条的工具名与秒数（dev-board#792 / 审查 F5）**：`RootBubble.activityLabel` 在执行段处于活动态、且段内有 `status==='loading'` 的工具项时，改报 `chat.timelineExecutingTool`（「正在{工具displayName} · {秒} 秒」）；没有工具在跑才退回原来的 `timelineExecuting` / `timelineExecuted` 计数文案，结束态一字未变。工具名取该段**最后一个** loading 项（items 只追加不重排，倒着找即当前那个），秒数来自 `useAgentStream` 在推入工具项时打的 `startTime`（历史回灌的条目没有这个字段，按 0 处理）。秒表是 `RootBubble` 里一个 1s 的 `setInterval`，只在 `bubble.isStreaming` 时走，**`onBeforeUnmount` 必须清**（气泡在切换会话/回退时整片销毁）。这条判据刻意排在 `hasError` **之前**：前面某一步失败、现在又在跑下一个时说「有操作未完成」既不准也没信息量。
  **地雷**：`tests/project-home/root-bubble-step-group-toggle.test.mjs` 用 `new Function` 把 `<script setup>` 剥出来跑、按名字喂依赖，所以 setup 里**新增任何在顶层就执行的调用**（这里是 `watch` / `onBeforeUnmount`）都要同步进那份形参表，否则整组用例 `ReferenceError`。喂的必须是空实现：喂真 `watch` 会让 `immediate` 回调起一个 setInterval，node:test 进程再也退不出去。
- **本轮 token 用量（dev-board#792 / 审查 F3①）**：`ChatInterface` 状态栏右侧一行 `chat.tokenUsageLine`（采集一直都在，此前整块被注释掉）。**只在 `totalTokens > 0` 时出现**——为 0 说明后端还没回 `token_usage`（Ollama 档不回），挂一个 0 像是「这一轮不要钱」。配套把 `tokenUsage` 的清零从「切换会话」提到**每一轮非续跑的 `sendMessage`**（与同处的 `fileChanges` 同口径）：文案说「本轮」，数字就必须是本轮的。
- **反问停机的前端状态**：独立的 `agentAwaitingInput` ref，**不复用 `agentPaused`**（那个驱动「继续」按钮，而反问要的是「回答」，塞一起等于给律师一个点了没用的按钮）；`AWAITING_INPUT` 下**不置 `isStreaming`**（后台没东西在跑，输入框必须可用）。bubble_end 两处 + run_state 一处共三处都要认。
- **后台任务可见性**：三个后台任务事件（background_task_start / task_progress / background_task_complete）已提到气泡守卫**之前**并各自 return——挂在守卫后面时，切回会话/重连后 `currentAssistantBubble` 为 null，表现是「重连后进度条再也不动」。完成态**不再 5 秒自动销毁**（改为打 `completedAt`），生命周期由 `resetSSE`（切会话清已结束的）与导出的 `dismissBackgroundTask(taskId)` 管；`BackgroundTaskIndicator` 因此必须给已结束的卡一个关闭入口（`@dismiss` → dismissBackgroundTask），否则那张卡关不掉。建连后 fire-and-forget 补拉 `GET /api/agent/tasks/active` 重建进度条。
- `step_update` 前端分派与 `handleStepUpdate` **已删**（后端零生产者）。`subtask_progress` 改推 `proc.items` 而不是 `proc.steps`——ProcessCard 里 items 与 steps 是 v-if/v-else-if 关系，解析器建的过程卡都有 items，往 steps 推永远不显示（此前子任务状态行就是这么半死的）。
- **计划审批卡（2026-08）**：ArtifactCard 对 task_list/plan/implementation_plan 三类 draft 计划内联渲染正文并给「按此推进 / 修订」按钮（仅最新一条助手消息可操作，RootBubble 的 isLatest→actionable 链）；修订态就地编辑，提交时行级 LCS 统计改动处数，handleArtifactApprove 把「已修订 N 处 + 修订版全文」回喂模型。工具过程卡一律收进可折叠组（无步骤归属的归「执行过程」组），流式中展开最新组、结束后全收起。

## 2026-09-10 记忆与运行中输入（dev-board #559–563）

- `AgentInboxService` / `AgentInboxController` / `AgentInboxItem` 以 DB 保存输入、完整附件上下文、幂等键及 revision。`POST /api/agent/chat` 返回 receipt，正在运行时默认 steer，可选 queue；编辑/排序/删除只针对 pending。删除保留幂等 tombstone。每会话锁覆盖 claim/register/finalize，模型执行在锁外；目前单后端消费，不能将进程锁当成多实例租约。
- `inbox_updated` 是权威快照；`input_applied` 携带 messageId/runId/sequence。最初 POST 靠 receipt 显示，不重复发 applied；自动接续先发新 run 快照再发 applied。原生/XML 工具批次在安全边界应用新指令，未开始的旧工具回填取消结果；已经执行的副作用保留。取消/错误/待审批/待回答/无进展均暂停队列；只有明确“立即发送”才主动启动暂停队列中的该项。
- **「立即发送」的判据是「目标模式是 steer 且当前无活跃轮次」**（`AgentInboxController.edit`，dev-board#802），不是「模式发生过 queue→steer 的转变」。后者把**本来就是 steer** 的待处理项整个排除在外：那一轮以取消/出错/待审批/待回答/无进展暂停收尾之后（这几种都不 drain 队列），这条 steer 永久卡在 pending 里，界面上只剩编辑/上移/下移/删除。`acceptInboxSubmission` 自身幂等（进去先查 `activeRuns`，有就原样返回），控制器额外判 `activeRunId(conversationId) == null` 只是省一次调用并把意图写在脸上。**纯改正文（不带 `submissionMode`）永远不起跑**——「我改了一下措辞」不是「现在就发」。
- 前端 `AgentInbox.vue` 的 `run-active` prop 决定 steer 项露不露「立即发送」（`canSendNow`：queue 项恒露，steer 项只在没轮次在跑时露），并在没轮次时多渲一行 `chat.inboxIdleNotice`。判据取 `ChatInterface.inboxRunActive` = **`isStreaming` 单一来源**：切回一条后台仍在跑的会话时 `run_state=RUNNING` 会把它置起，所以它不只是「本窗口从头看到尾的那一轮」。**刻意不与 `agentRunStatus === 'RUNNING'` 取或**——用户点停止后 `isStreaming` 立刻 false，而 `agentRunStatus` 要等后端 `cancelled` 事件才落终态，SSE 正好死了就永远停在 RUNNING；用一个可能永不归位的状态去挡救命按钮，等于把病灶换了个地方。护栏在 `tests/chat-presentation-ui/run.mjs`（真组件渲染，停止前后各断一次）。
- 前端 `AgentInbox.vue`、`agentInboxState.mjs` 与 `chatSubmissionState.mjs` 管理队列、事件去重和提交事务。发送与停止分开，执行中可输入；新会话只断开本地视图，旧会话继续。迟到 receipt 不得清空新会话草稿；附件草稿按原始 HTML 快照比较。
- `service/ai/memory/document/*`、`MemoryDocumentController`、`MemoryDocument`/`MemoryDocumentSpace` 是 Markdown 记忆真源。`/api/ai/memory/{spaces,files,file,download}`；个人/项目使用权限校验后的 opaque spaceId，团队/律所由官网共享服务校验成员/管理员。每空间 remember.md 自动维护 topic 链接；UTF-8 128 KiB、路径校验、expectedRevision 冲突及删除墓碑由后端负责。legacy 读写/同步向同一文档服务收敛，不保留可独立写入的副本。
- `MemoryTools` 暴露 memory_list/read/search/write/edit/delete；Agent/Plan 的 skill 白名单不能隐藏这些基础工具，ASK 只允许前三个。ContextAssembler 每轮注入有权限的限量索引，正文按需由模型读取。`MemoryBrowser.vue` 从对话与设置进入，支持索引跳转、编辑、下载及冲突提示。
- 桌面共享记忆使用已连接账户 Bearer；服务器使用专用 `memory.shared.base-url`/`memory.shared.secret` 与绑定账户 ID，不能复用只读协作目录密钥。官网配套契约与 PR 见 `doc/ai-alignment/memory-report.md`；无账户/服务未配置的共享空间显示不可用，不能伪装成本地共享。
- 测试与实际结果：`doc/ai-alignment/validation-report.md`；隔离运行配方：`doc/ai-alignment/test-environment.md`。不可将模拟 provider E2E 称为真实模型测试。

## 一条消息的完整链路

ChatInterface.handleSubmit（~:927）→ useAgentStream.sendMessage（确保 SSE 已连 → POST /chat）→ Controller 异步 200 → handleUserMessage（@Async：存 USER 消息→标题→SkillRouter.activateForTurn（手动 skillIds ∪ 自动命中）→发 skill_update→assemble→取流式模型→mark(RUNNING)→runLoop）→ StreamHandler.onNext 逐 token 发 SSE → onComplete 回调检测工具请求（原生 function calling 或 XML 兜底）→ dispatchTool→ToolRegistry.execute→副作用（file_change/refresh_files）→ 结果追加 messages → **递归 runLoop(depth+1)** → 无工具时收尾：artifact 解析（implementation_plan 停机待审批/task_list 继续）→ **反问停机（`<question>` → AWAITING_INPUT）** → 存 ASSISTANT → MemoryPipeline 异步 → mark(FINISHED) → bubble_end → 关 SSE。

**反问停机（`<question>`）的实现契约**（`AgentOrchestrator.containsQuestion` / `stopForUserQuestion`）：
- 判据只认**起始标签** `<question` 后接空白/`/`/`>`（正则 `QUESTION_TAG_START`，忽略大小写）。刻意不要求闭合标签：模型漏掉 `</question>` 时问句已经流给用户看了，按「有问题」停机远好过静默收尾——后者会留下一个没有下文的问句而状态显示已完成。`<questionnaire>` 这类同前缀标签不会误命中（有测试钉住）。
- **三处短路**，一处漏掉就会出现「问完了又自己猜下去」：原生 function calling 分支（递归前）、XML `<tool_code>` 兜底分支（递归前）、收尾段 3.2。截断 `<tool_code>` 的纠正回路（2.5）也加了 `!containsQuestion` 排除——不然模型「既问问题又被截断」时会被催着重发工具调用。
- **优先级契约**：`implementation_plan` 审批 > 反问 > 正常收尾。同一轮既给计划又反问时收尾为 `awaiting_approval`（那条路本来就要用户点头，且要落 artifact 文件），由回放用例 `question-loses-to-implementation-plan-approval` 钉住。
- 收尾形态照抄待审批那 6 行：saveAssistantMessage → mark(AWAITING_INPUT) → `bubble_end {"status":"awaiting_input"}` → close → return，**不递归**。刻意不触发记忆管线与版本落档（本轮未结束，用户答完那轮一并跑）。
- 连续反问不会被守卫误伤：RunGuard（StuckDetector 窗口/步数预算/重试预算）每次 handleUserMessage 新建，用户的回答是**新一轮消息**即新的 run；StuckDetector 只记录工具调用签名，反问根本不进窗口。若哪天把 RunGuard 改成跨轮复用，必须让反问轮不计入打转窗口与步数预算。
- 回放用例 `backend/src/test/resources/ai-eval/cases/cases-question.json`（5 例：带选项/纯开放式/工具后反问/同轮工具+反问不递归/审批优先）+ 单测 `AgentOrchestratorQuestionStopTest`。
- 契约 D（发送内容 ≠ 显示内容，定义见上文「关键文件」一节）在对话主链路上的接线点只有一处：`AgentOrchestrator` 存 USER 消息那一行调 `saveMessage(..., request.getMessage(), request.getDisplayText(), request.getClientRequestId())`（七参重载，返回行 id 供挂附件）。改动这一行等于让 `displayText` 整条通道失效（不报错，只是气泡里又回到机器口吻长句）。
- 提问时机写在 `prompts/system_prompt.md` 的 Clarification 一节（该问/不该问各四条 + 一次只问一组 + `<option>` 语法）。**刻意不做分发层强制兜底**（决策 3）：先看真机调用率，只写 prompt 的约束对弱模型是概率性的。

## SSE 事件名清单

connected / bubble_start / text_delta / **reasoning_delta**（思考型模型的 reasoning 增量，`{"content":"…"}`，只进思考卡、不进正文与历史；state_recovery 快照不含它，重连后思考文本不回放）/ artifact / token_usage / bubble_end（status: finished|paused|awaiting_approval|awaiting_input；外加 **documentEdited**，见下）/ error / cancelled / file_change / client_action / title_update / doc_stream_data（旧名 wps_stream_data 已于 dev-board#816 摘除，出站单名）/ doc_stream_end（编辑器流式写入收尾，前端据此落盘并报失败）/ state_recovery（断线重连快照）/ run_state / plan_update / **skill_update** / background_task_start / background_task_complete / task_progress / heartbeat / subtask_progress / **pass_progress**（整篇分段过卷进度，dev-board#422）/ **context_notice**（附件降级/截断/丢弃 + 上下文超窗，见下）/ inbox_updated / input_applied / **superseded**（本连接已被同会话的另一个客户端实例接管，见下）。前端分派均在 useAgentStream.handleEvent。超限 paused 契约见 PR#172。

**载荷一律由调用方自己序列化成字符串**：`SseEmitterService.send(cid, event, data)` 里是 `String.valueOf(data)`，**不替谁转 JSON**。传裸 `Map.of(...)` 出来的是 Java 的 `toString()`（`{content=正文}`），客户端 `JSON.parse` 当场抛错——而抛错往往被 catch 吞成一行 console.error，于是表现为「功能整条静默失效、前后端都不报错」。#663（2026-08-30）把 `send` 从 `SseEmitter.event().data(Object)`（Spring 用 Jackson 转换器序列化）改成 `String.valueOf` 时，全仓只有 `doc_stream_data` / `wps_stream_data` 两行传的是裸 Map，AI 流式写入新建文档的正文因此三周多一个字都没到过编辑器（dev-board#465 描述的症状）。现已改用既有的 `AgentOrchestrator.jsonContentEnvelope(token)`（与 `text_delta` 同一个信封，转义交给 Jackson）。护栏 `SseEventPayloadJsonContractTest` 扫源码，任何 `sseEmitterService.send` 传裸容器即红；前端侧 `useAgentStream` 的 `doc_stream_data` 分支**先 parse 成功再置 `isEditorStreaming` 与占位符**（反过来会让坏载荷表现成「看起来正在写」），护栏 `tests/project-home/doc-stream-bad-payload.test.mjs`。

**事件名与本清单的对拍有测试守着**（`SseEventNameDocContractTest`）：后端发出的每个字面量事件名都必须在这一节里出现——「加了事件、文档没加」不会有任何东西报错，下一个照着文档写客户端的人只会认为那个事件不存在。反向不校验（清单里可以留已经摘掉的旧名，如 wps_stream_data 那条双轨）。

**断点续传与窗口移交（`X-Client-Instance` / `Last-Event-ID` / `superseded`，dev-board#803）**：后端 `/connect` 一直支持这两个可选请求头（`AiAgentController.connect` → `SseEmitterService.createConnection`），**桌面端 2026-09-22 起才开始上送**，此前只有 Office 任务窗格带。
- `X-Client-Instance` = 客户端实例身份。换了实例时后端给旧连接发一条 `superseded` 再关（`{"reason":"another_pane"}`），**不带这个头时那段移交逻辑整块不触发**：旧窗口只看到流断了，45 秒心跳判死后退避重连，又把新窗口顶掉，两边无限互顶。桌面端的实例 id 在 `useAgentStream` 模块级的 `clientInstanceId()` 里，存 `sessionStorage`（按标签页/窗口隔离，所以两个窗口天然不同；同一个窗口刷新沿用同一个 id——刷新是同一个窗口重连，不该触发移交）。
- `Last-Event-ID` = SSE 规范的断点续传游标。后端只给**可补发**的事件打 id（`bufferable()`：heartbeat / connected / superseded 除外），重连时补发 `id > since` 的缓冲事件。正文有 `state_recovery` 全量快照兜底，而 **`client_action`（编辑器指令）与 `file_change` 没有任何兜底**——重连正好卡在 AI 往文档里写东西的时候，那条指令就丢了，表现是「AI 说改好了，文档里没动」。
- 前端侧三处：`parseSSELineFull` 认 `id:` 行并在**派发之前**推进游标（handleEvent 抛异常也不该让同一条事件下次重连再来一遍）；**id 不大于已收过的一律丢弃**（补发重了的代价是正文凭空多一段、或一条迟到的 `bubble_end` 把新一轮的气泡当场结束掉，两种都不报错）；`handleEvent` 的 `superseded` 分支置闸 + 停重连 + `linkStatus.state='superseded'`。**游标按 conversationId 计数，`resetSSE` 必须清零**，否则新会话的前几条事件会被去重当成补发丢掉。
- **`superseded` 闸在建连成功时放掉**（`connectSSE` 里与 `linkStatus='live'` 同处）：用户在这个窗口主动发消息把会话抢回来之后，闸还挂着的话此后任何一次断线都不会再重连。
- **地雷：新增任何自定义请求头都要同步 `config/CorsConfig.java` 的 `Access-Control-Allow-Headers`。** 桌面端页面与后端是两个 origin（页面在 dev/打包端口，后端在 127.0.0.1:52xx），带自定义头一定触发预检；漏了名字的表现是**整条 SSE 建连被浏览器拦在预检上**——对话完全不出字，而后端日志里干干净净（请求根本没到服务器）。本次实测就是这么撞上的，护栏 `CorsPreflightHeadersTest`。
- 护栏：`tests/project-home/agent-stream-connect.test.mjs`（请求头）、`tests/project-home/agent-stream-replay.test.mjs`（游标推进与去重）、`CorsPreflightHeadersTest`。

**`bubble_end.documentEdited`（本轮动过文档没有，dev-board#728）**：布尔，**六个发送点全部带上**，载荷形如 `{"status":"finished","documentEdited":true}`（paused 那两条仍是 `{"status":"paused","reason":"…","documentEdited":…}`，字段顺序 status → reason → documentEdited）。
- **语义**：本轮**成功**调用过 `doc_`/`sheet_`/`slide_` 里的**写入类**工具。判据 `ClientCapabilityService.isDocumentWritingTool`，记在 `AgentOrchestrator.dispatchTool` 这一个 funnel 上（原生与 XML 两条工具循环都经过它），状态挂 `RunGuard.documentEdited`（轮次级，置位后不清零）。
- **两个判据必须分开，别合并**：`isLowaTool`（宽前缀，回答「要不要 LOWA 编辑器」）给 `isToolVisible` 用——Office 会话里连 `doc_get_document_text` 都执行不了，读取工具必须仍算 LOWA 工具；`isDocumentWritingTool`（前缀 **且** 不匹配只读名模式）给 `documentEdited` 用。合过一次就出过事：按宽前缀判，「先读文档、再起草一条条款」那一轮因为调过 `doc_get_document_text` 被判成「改过文档」，回复下方的按钮被误藏，而那恰恰是最该出按钮的场景。
- **判据是工具名而不是 `@ToolMeta.fileEffect`**：这条不因注解补齐而改。历史上最常用的写入原语（doc_insert_at_cursor / doc_replace_selection / doc_delete_text …）压根没声明 fileEffect，按它判会把真正的编辑漏成「没动过」；审计 B-02 后 DocumentEditTools 已全部补齐（doc_/sheet_/slide_ 共 113 个，现只剩 SlideEditTools 的 5 个**只读**工具没有注解，对副作用无影响），但判据仍**不许**改成读 fileEffect——① 分类不能依赖注解纪律，漏标一个就静默错判；② 两者语义本就不重合：`doc_collapse_cursor` 按名算写入侧（拿不准算写入）却不该建检查点，`doc_start_stream` 反过来要 MODIFIED 但真正落字的是流。
- **只读名模式里有两个真实的坑，改之前先看这两条**：① `doc_find_replace` 以 `find_` 开头却是全仓最常用的**写入**原语，所以 `find` 不能做词头，只有 `find_text` 进精确名单；② `doc_set_selection` 只挪选区（只读）而 `doc_replace_selection` / `doc_delete_selection` / `doc_format_selection` 都是写入，所以 `selection` 不能做子串匹配，只有 `set_selection` 进精确名单。词头一律 `(?:_|$)` 收尾（`inspect` 松绑会咬到 `insert_*`、`list` 会咬到 `link_`）。**拿不准的一律算写入**（doc_undo / doc_redo / doc_restore_checkpoint / doc_collapse_cursor 都在写入侧）。
- **归类的权威清单在 `DocumentWritingToolClassificationTest.EXPECTED_READ_ONLY`（31 条只读 / 113 条总计）**，那条测试扫真实工具类的方法名逐名对拍——**新增 doc_/sheet_/slide_ 工具而没人归类就会当场红**，逼着加卡的人自己决定它改不改文档。
- **新增 bubble_end 发送点必须走 `bubbleEndPayload(guard, status[, reason])`**，不要再手写 JSON。漏一个的表现是前端在那条路径上读到 undefined、按「没编辑过」把按钮放出来，而那恰恰是编辑最多的几条路径之一。
- **消费者**：前端 `useAgentStream` 把它与 `status` 一起落到气泡（`bubble.documentEdited` / `bubble.status`，两者都在 `createAssistantBubble` 里预声明——不预声明就不是响应式的），`RootBubble` 的 `showUseInDocument` 据此决定「用到文档」那组操作出不出。判定全在纯函数 `frontend/src/utils/useInDocumentVisibility.js`：正文非空且流已结束、未动过文档、不是反问态（`bubble.question` 或 `status === 'awaiting_input'`）、且正文纯文本化后 ≥ `USE_IN_DOCUMENT_MIN_CHARS`(50) 或含列表/标题结构。**「多段」刻意不算结构信号**——`bubble.content` 是「调工具前随口说的那句」与 `<final>` 正文拼起来的、中间由解析器补了空行，按段落数放行等于架空长度门槛。
- **历史回灌另有一份镜像判据**：`GET /api/ai/history` 回的是原始协议正文、没有这个字段，`parseAssistantHistory` 用 `documentEditedFromProcesses` 从执行日志里的 `<tool_code>` 工具名 + `<tool_output status="SUCCESS">` 复原。**那份只读名模式是 Java 侧的镜像**，由 `tests/project-home/use-in-document-visibility.test.mjs` 把仓库里全部 113 个工具名喂进前端规则、与后端那份权威清单逐名比对（清单只有一份，两侧各自对着它验），改后端判据要一起改；不补这一份的表现是「刷新前不显示、刷新后按钮全冒出来」。
- Office/WPS 任务窗格（`sse.js` / `chatSession.js`）忽略未知字段，不受影响；缺字段一律按 false（旧后端 / 其它客户端兼容）。

**`context_notice`（本轮附件被怎么处理了，dev-board#801 K21 ⑦ / #812 K32 ⑥⑦）**：载荷 `{"kind":"truncated|dropped|ocr_fallback|image_limit|image_too_large|unreadable|budget_exhausted|overflow","fileId":"9","name":"合同.docx","detail":"50000"}`（`name`/`detail` 可缺省）。前六个 kind 的生产者只有 `ContextAssemblerService` 经 `ContextTurnSink`，发生在 assemble 期间（首 token 之前）。
- **`budget_exhausted`（K32 ⑦）**：本轮附件正文的**合计**字符额度用完了（`ai.context.files.max-total-attachment-chars`，默认 120000），这一份一个字的正文都没进去（`<file>` 壳仍然保留，模型可以按需 `read_document`）。`detail` 是**字符数**。**刻意不复用 `dropped`**：那句文案写死是「一轮最多带 N 份材料」、detail 是份数，套进去会渲染成「一轮最多带 120000 份材料」。为什么需要这道总闸：单文件 50000 × 文件数 10 乘起来没有总闸，十份长合同 = 50 万字符全进 system，而 system 在压缩的保护区里，一旦它自己超窗，**这个会话此后每发一条消息都必死**。
- **`overflow`（K32 ⑥）**：唯一**不描述某一个附件**的 kind，也是唯一由 `AgentOrchestrator` （不是 assemble）发的——服务商已用 400 证实装不下、`forceCompact` 又返回了原实例（压不动）。载荷只有 `{"kind":"overflow"}`。原来这条路只有一行 warn 日志，用户看到「又失败了」，而唯一的出路（去掉几份附件）没有任何东西指向它。文案必须给出可执行的下一步。
- **八个 kind 不许合并**：它们对用户是八句不同的话，处置也不同——该换模型的（ocr_fallback）、该少贴几张的（image_limit）、该压缩图片的（image_too_large）、该删掉几份材料的（dropped）、该知道只读了前 N 字的（truncated）、该知道这份根本读不出字的（unreadable）、该删掉几份材料的另一种（budget_exhausted：字数而不是份数）、以及整轮装不下的（overflow）。合成一句「未能处理」等于什么都没说，那正是改造前的状态（三条降级路共用一句「超出本轮张数或单张体积上限，或读取失败」）。
- **前端落在最后一条用户气泡上**（`bubble.contextNotices`，`createUserBubble` 里预声明成 `[]`），渲染成气泡下方一行小字，**不弹 toast**——它说的是既成事实，不需要用户点确认。分派放在气泡守卫**之前**（与 plan_update 同理：重连/切回会话时助手气泡指针为 null）。
- **历史回灌不重放**：`GET /api/ai/history` 不带它，`loadMessages` 里 `contextNotices` 恒为 `[]`。降级是「那一轮发生的事」，刷新之后再说一遍只是噪音。
- 认不出的 kind 回退成一句通用说明，**绝不整条吞掉**——那就又变回静默降级了。Office/WPS 任务窗格忽略未知事件名，不受影响。

**`pass_progress`（整篇分段过卷进度）**：载荷 `{"chunk":3,"total":12,"replaced":7,"done":false}`——`chunk`/`total` 是块序号与总块数（1 起），`replaced` 是**全程累计**成功改动处数（不是本块的），`done=true` 表示过卷结束（收尾或 stop）。生产者只有 `OfficeEditTools.office_pass_step`，每次返回后发一条。消费者目前只有 Office/WPS 任务窗格（`office-addin/taskpane/lib/chatSession.js` 的 `passProgress`），主前端 useAgentStream 未消费（桌面端走 LOWA，没有这个工具）。纯展示：发不出去不影响工具结果，客户端收到坏载荷也只是静默忽略。**`done` 之后客户端必须归位**——挂着「12/12 段」比不显示更误导。

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

- **工具描述是模型唯一的判据——描述与实现不一致 = 能力被自己的文案藏起来**（dev-board#807，审计 A14/B-10/B-11 + 复核补漏）。
  `list_files` 的描述曾写着「returns paths only, **NO database fileId**」，而它的实现逐条附
  `(fileId=N)`、末尾还提示这些 id 能喂给 move/rename——模型只读描述，于是纯文本文件
  「看起来拿不到 fileId」，审计员据此都推出了错误结论。同一类问题还有：`read_document` 只有一句
  79 字的英文（「Read document content…Provide fileId」），既没说支持哪些格式、会 OCR、会截断，
  也没说 id 是文件夹时该转 `extract_file_text`——而它恰好是 `ai.skills.base-tools` 三个兜底工具之一，
  无 skill 时模型大概率挑它，于是「把一个卷宗文件夹当材料范围交进来」这条能力永远不会被发现。
  **改工具行为时必须同时改描述**，反过来也一样；护栏是 `ToolChoiceSurfaceTest`
  （逐条断言互指还在：read_document↔extract_file_text↔read_file、
  pdf_list_files / pptx_list_files → doc_list_project_files、list_files 里必须出现 fileId）。
- **同一个意图摆几个同义工具 = 模型选哪个是随机变量，而选错不会被任何返回值戳穿**
  （dev-board#807/#808）。今天收敛掉的三组，判据与代价各不相同：
  ① 记忆检索三合一（A13）——选错的代价是多花一次辅助模型调用（deep 那档要做查询扩展），
  现在是 `query_memory` 的一个 `depth` 参数，「要不要多花这一次」变成模型能明确判断的事；
  ② PPTX 编辑面二选一（B-09）——选错的代价是**一份被 reload 丢掉的未保存修改**，加上
  0 基/1 基混用导致的错页；③ 法规检索的假名字（A11）——选错的代价是**网页摘要被当成法条原文引用**。
  再加同义工具前先问：选错时用户看得见吗？看不见的，就只能留一个。
- **`doc_list_project_files` 现在是全类型清单**（dev-board#807，审计 B-11）：docx/doc/xlsx/xls/pptx/ppt
  之外，PDF、txt/md/csv、图片、其它文件也一并列出，每条带 `[可编辑文档]/[PDF]/[纯文本]/[图片]/[其他]`
  标注与该用哪组原语的说明，并过滤 `isDeleted`。改动前 **txt / md / 图片没有任何工具能给出它们的
  fileId**，而「看看我项目里有什么」要连调三四个专用清单才拼得齐。
  分桶判据**只看扩展名**，不看 `ProjectFile.fileType`——那一列是客户端自填、原样落库、无校验的，
  拿它分桶等于让「用户随手填的一个 doc」变成模型的行动依据。
  `pdf_list_files` / `pptx_list_files` 保留（旧会话回放 + 结果太多时按类型过滤），
  但描述已改成「等价于 doc_list_project_files 过滤后的子集」。清单一次列全之后，模型更容易拿一个
  PDF/图片的 fileId 去调 `doc_open_file`，所以那里的拒绝文案也补了指路（PDF → pdf_inspect / pdf_*，
  其它 → extract_file_text），**别让它以为这份文件整个读不了**。

- **SSE 长连接端点不许持有 JPA EntityManager（v0.38.3 走查 D1）**：`spring.jpa.open-in-view` 从未配置、走默认 true，
  OSIV 会把 connect 里归属校验拿到的 JDBC 连接一直占到流结束（Spring 下 Hibernate 是 DELAYED_ACQUISITION_AND_HOLD），
  客户端断开走 onError 时 OSIV 甚至不关 EntityManager，连接永久泄漏；池子 10 条，切几次会话后端整体卡死。
  现由 `config/OpenEntityManagerInViewConfig` 替换 Boot 自动配置的拦截器并排除 `/api/agent/connect/**`（其余路径 OSIV 不变，
  **不要全局关 OSIV**）。新增任何 SseEmitter / 长轮询端点必须加进 `LONG_LIVED_STREAM_PATHS`。护栏 `SseConnectPoolReleaseTest`
  （真 Tomcat + Hikari 活跃数）。另：Spring 6.1 的 `ResponseBodyEmitter.complete()` 在 I/O 发送失败后是空操作，
  断线收尾靠容器 onError 分派，`SseEmitterService.emitTo` 里的 complete() 只兜非 I/O 失败。
- **uni-h5 的 `<textarea>`/`<input>` 缺省 maxlength=140，且 v-model 有 100ms 节流**：新增输入控件必须显式 `:maxlength`
  （记忆编辑器 -1 + 保存时按 128 KiB UTF-8 校验）；「打完字立刻点按钮」的提交路径要读 confirm 事件值或控件 DOM 值
  （`MemoryBrowser.liveFieldValue`，同 `utils/identityProfile.js` 的 submittedInputValue）。e2e 必须真按键输入，
  原生 value setter 会绕过这两类缺陷。
- **「立即发送」会恢复停住的队列**：停止时本地 SSE 已断开，`useAgentStream.updateInbox` 对 `submissionMode=steer`
  的补丁先 `connectSSE` 再发 PATCH（前端不带 Last-Event-ID，连晚了会丢 input_applied）。
- **队列自动接续不许先关流**：正常收尾由 `AgentOrchestrator.endRunAndDrain` 在 inbox 锁内判断——还有下一条待处理就
  **不关流**，接续那一轮沿用同一条连接（`beginRun` 记的是当前代次，它自己收尾时再关）；队列跑空才 `closeSse`。
  曾经是「bubble_end → closeSse → drain」，接续那一轮的事件全部发进没有 emitter 的会话，前端看不到、条目一直挂在待处理。
  护栏 `AgentOrchestratorInboxTest.finishingWithQueuedFollowUpKeepsTheStreamOpenForTheContinuedRun`。

- **改 AgentOrchestrator 构造器必须同步 EvalHarness**（已踩三次；现构造器末三参是
  TelemetryService/TelemetryTurnTracker/MatterClassifierService）。
- **编排器里凡是「会话级」的写操作，一律走 `markRunState` / `sendRunEvent` / `closeSse(guard)`，不要直接调
  `agentRunStateService.mark` 或 `sseEmitterService.send(conversationId, …)`**（dev-board#533）。直接调等于
  绕过 `isCurrentRun` 判据，被新一轮取代的旧轮次会把新轮次的气泡与状态点一起终结掉——而这类 bug 只在
  并发轮次下才现形，单轮跑一百遍都是绿的。工具副作用类通知（`client_action` refresh_files / `file_change`）
  是例外：它们描述的是已经发生的文件变化，与哪一轮在前台无关。
- **新增终态分支必须调 `endRun(guard)`**：漏了会在 `activeRuns` 里留一条僵尸轮次，此后这个会话的
  「停止」会打到一个已经死掉的 run 上（用户看到点了没反应），断线重连还会拿到过期快照。
- **`SkillRouter` / `ContextAssemblerService` 拿的是 runId，不是 conversationId**：两处签名里它们都是
  `String`，传错了编译照过、单轮也照跑，只有并发轮次才会露出「A 轮的 prompt 配 B 轮的工具」。
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
- **读取类工具的 OCR 路由：一条路由、一份判据、一份缓存**（dev-board#396 + #800）。
  `read_file`（按路径）、`read_document`（按 fileId，LegalTools）、`extract_file_text`（按 fileId，FileTools）
  与**文件夹上下文**（`FileContextLoader.extractForFolder`）全部收敛到
  `service/file/ProjectFileTextExtractor`：扩展名表 `ai.context.ocr-extensions`
  （jpg/jpeg/png/gif/bmp/webp/pdf），**图片直接 OCR、PDF 先抽文字层抽不出（扫描件）才 OCR**——
  图片抽 Tika 是纯浪费，文本型 PDF 走 OCR 是白花钱。
  「文字层够不够用」的判据**全仓只有 `service/file/PdfTextLayer.isUsable`** 一份
  （实义字符 ≥16；只判 `hasText` 会把扫描件残留的几个页码当成整份判决书的全文）。
  - **#800 之前 pdf 在 ocr-extensions 里就等于「一律 OCR」**：`read_document` / `read_file` 把每一份
    PDF 都逐页 150DPI 渲染送云端识别、只看前 20 页，而 `extract_file_text` 走文字层不设页数上限，
    **同一份 PDF 走不同入口得到的正文与账单完全不同**，文件夹里的那份又是第三种。现已统一。
  - **页数上限 `ai.context.ocr-max-pdf-pages`（默认 20）只约束 OCR 路径**；文字层整篇抽取不设限，
    触发上限时正文末尾明写「这是扫描件、以上由文字识别得到、共 N 页仅识别前 M 页、不要当作全文」。
  - **扣费点全仓只有 `OcrService.recognizeGeneral`（按次，PDF 每页一次）**，所以
    `verify(ocr, never()).recognizeGeneral(...)` 就是「这份文件没花钱」的断言。文本型 PDF 零调用。
  - **抽取结果落库缓存**：表 `project_file_text_cache`（实体 `ProjectFileTextCache`，ddl-auto 建表），
    键 fileId，**失效判据是物理文件的 mtime+size**（不是 project_file.updatedAt——编辑器保存、
    版本回退、插件写回都可能只动磁盘不动那一行），配置 `ai.context.text-cache.*`
    （enabled / max-text-chars 100 万 / max-entries 2000）。写入与查询失败一律只 log、按未命中处理。
    与 `DocumentTextService` 那份 32 条内存 LRU **并存不替代**：内存那层吃「同一轮里的重复抽取」，
    落库这层吃「跨轮次、跨重启」——扫描件的 OCR 结果没有内存缓存兜底，只有它救得了重复扣费。
    读回来时还会校验 `text_chars` 与实际长度是否一致（MySQL 档的 TEXT 只有 64KB），对不上当未命中重抽。
  - **参考材料入口（`extract` / `extractBytes`）仍然不走 OCR**（`NEEDS_OCR`，PRIVACY.md 承诺参考材料
    不产生 Credits 扣费）；但**命中缓存照样返回正文**——那正是 NEEDS_OCR 那句话许诺的结果，且不花钱。
  - **文件夹上下文刻意不做 OCR**（批量路径，拖一个照片文件夹进来不该默默花一笔钱），
    但从此在 unreadable 名单里**逐条写明原因**（「请先做一次文字识别」等），不再只给一个光秃秃的文件名。
  - 实测（dev-board#800，本机隔离后端 + nda.pdf 附件连问两轮）：第一轮 `[Timing] prep total=274ms`
    （assemble 254ms，其中 files=223ms）；第二轮 `prep total=25ms`（assemble 16ms，files=3ms），
    `project_file_text_cache` 落一行 `source=text`，OCR 网关零调用。
  - **音频走第三条分支，且它排在缓存之前**（dev-board#814）。mp3/m4a/wav/aac/flac/ogg/opus/amr/wma/webm
    既不在 `ocr-extensions` 也不在纯文本白名单，改动前落进 Tika 抽出空串或 ID3 标签里的艺术家/专辑，
    `read_document` 于是回一句「the file may be empty, or an image whose OCR recognised nothing …
    try extract_file_text」——对音频这三条建议**没有一条成立**，模型据此告诉用户「我看不到这个文件」，
    而这个产品自己就带着会议录音、听悟转写与文件树右键「转写音频」。现在：
    - **扩展名表全仓只有 `MeetingRecordingService.AUDIO_EXTENSIONS` 一份**（`isAudioFileName` 是它的
      唯一判据），抽取器直接引用它；前端那份在 `frontend/src/utils/audioAttachment.js`，由
      `tests/project-home/audio-attachment.test.mjs` 读 Java 源码逐项对拍——两边判错都不报错，
      前端漏判 = 右键没有「转写音频」也不提示，前端多判 = 点了转写后端回「该文件不是音频文件」。
    - **音频的「正文」就是它的转写稿**。关联用既有的 `meeting_recording.audio_file_id`（面板录音建档与
      右键「转写音频」两条路径都写这一列），**没有在 project_file 上新开字段**；查询是
      `MeetingRecordingRepository.findByProjectIdAndAudioFileIdOrderByCreatedAtDesc`
      → `MeetingRecordingService.findByAudioFile(projectId, fileId)`，带 projectId 是防越界。
      注入时顶一句横幅（口径同 OCR 降级的「明示」）：这是机器语音识别、可能有误差、你听不到音频本身——
      不写的话模型会把识别误差当成庭审原话来引用。
    - 没有转写稿时抛 `ProjectFileTextExtractor.AudioNotTranscribedException`（`extends IOException`，
      与 `OcrFailedException` 同一套路数），message 按会议状态分档（尚未转写 / 转写进行中 /
      上次失败可重试 / 未识别到人声 / 录音未结束 / 已转写但转写稿是空的）并指向真实入口。
      `read_document` 与 `extract_file_text` 把它转成 **`Warning: ` 前缀**而不是 `Error: `——
      文件本身好好的，只是这一步还没做；两种前缀都会被 `isToolFailureText` 认出来、
      **不进 `<file>` 的 CDATA**（K8 守卫路径，ContextAssembler 一行没改）。
    - **必须排在 `textCache.find` 之前**：转写稿是会后才出现的，而音频字节一个都没变、
      mtime+size 指纹也就一个字节都没变。把「请先转写」写进 `project_file_text_cache`，
      用户转写完成之后这份文件在本机**永远**读不到转写稿——而且不报错。
      护栏 `ProjectFileTextExtractorTest.audioNeverTouchesTheTextCacheInEitherDirection`。
    - 参考入口（`extract` / `extractBytes`）走同一条分支：裸字节查不到转写稿，但同样不交给 Tika。
    - **`read_file`（按路径）另有一份说法 `audioNoticeByPath`**，别套用上面那句。它拿不到 fileId
      也就查不到会议记录，说「尚未转写」会在音频其实早就转写完时直接说反；它只陈述事实
      （这是音频、正文是转写稿）再指向 `extract_file_text` + fileId。**这条不是可选的**：
      Tika 对 mp3 抽回来的是 ID3 标签里的标题/艺术家/专辑，**非空**，会被当成「文件正文」
      原样喂给模型——比那句误导性 Warning 更坏。
    - 前端在**发送之前**就说：`ChatInterface` 的 `.audio-transcribe-bar`（输入框正上方，
      判据 `audioNeedingTranscription`，已转写的不提示），「转写」按钮 `emit('transcribe-audio')`
      → 工作台 `onTranscribeAudio`，**与文件树右键是同一条动作**（register-file + 打开录音面板）。
      已转写集合来自既有的 `GET /api/meetings/projects/{id}`，只在真挂了音频附件时拉一次，
      **不新增任何出站请求**；拉不到就按「都没转写」提示（多说一次好过让用户以为 AI 听过录音）。
  `extract_file_text` 此前<b>没有</b>这条分支（只有 Tika），项目里的 jpg 恒抽不出正文，
  返回的提示又只说「try read_file with OCR for **image PDFs**」——模型据此认定图片读不了，
  转头调 `run_python` 想自己跑 OCR，撞上 "Cannot run program docker" 后**自己下结论**
  「OCR 环境（docker）不可用」并这样告诉用户。三处修复缺一不可：路由（工具真能读）、
  @Tool 描述与 system prompt（模型知道它能读，不去另找路子）、run_python 的可用性闸
  （没有 Docker 就别摆出这条死路）。
  **OCR 失败一律 `Error:` 开头并把底层原因原样带出**：`extractTextWithOcr` 的失败是
  `[System: ...]` 形态（非空、无 Error 前缀），直接透传会被 `ToolResult.success()` 判成成功、
  当作正文喂给模型。Credits 不足、OCR 未开通、上游报错长得都不一样，模型要转述真实原因而不是自己编。
  护栏 `ExtractFileTextOcrTest`。
- **读取类工具的正文必须有上限，单一来源是 `ToolFileGuard.capToolText`（80k）**：
  `extract_file_text` 一直有这个上限，`read_file` / `read_document` 没有——一次读一份几 MB 的合同
  就产生几十万字符的单条 `ToolExecutionResultMessage`，下一轮必然被服务商以上下文超限 400 挡回。
  **而且救不回来**：这条超长结果落在 `RunLoopCompactor` 的 keepRecent **尾区**（尾部平时刻意不剪），
  中段又往往不够 `minMiddleMessages` 条数，于是 `forceCompact` 恒返回原实例、编排器判「压不动」终态，
  同一份文档每次重试都必然再撞同一个 400。两道防线都要在：工具侧截断 +
  `forceCompact` 兜底剪尾（**只在 force 下**；非 force 的尾部豁免是刻意设计，别一起改掉）。
  回归用例 `OversizedToolResultRecoveryTest`。
- **截断说明里点名的工具必须真的注册着**（审计 A5/B-01）：这条说明每次超长读取都会回给模型，
  它曾点名一个注册表里从来没有过的分段读取工具（`doc_read_paragraphs`，全仓 5 处引用、0 处定义），
  模型照做只会拿到「Tool not found」，白烧一整个 LLM 往返，弱模型还会据此判定「这份文档读不完」而放弃。
  现在写的是 `doc_get_document_text(startParagraph=…, maxParagraphs=…)`（返回值的 `nextStartParagraph`
  就是下一段起点），并保留「否则先检索定位再读该段」那半句——`capToolText` 服务的是
  `extract_file_text / read_file / read_document` 这类读**项目文件**的工具，而 doc_* 读的是编辑器里那一份，
  PDF/xlsx 这类还没有分页读取原语的类型只能走后半句。`OversizedToolResultRecoveryTest` 现在反射
  DocumentEditTools 的真实 @Tool 名单，核对文案点名的工具确实存在。
- **文件夹上下文要走 `ProjectFileTextExtractor.extract`，不是 `FileContentExtractorService.extractText`**：
  后者的白名单（java/js/md/txt/csv…）不含 docx/xlsx/pptx/doc/pdf，恒返回空串，
  `buildFolderContext` 随后 `if (!text.isEmpty())` 把这些文件**静默跳过**——
  上下文里「### Folder Document Contents」标题下一个字都没有。17ca80d7 修的是**单文件**路径
  （`read_document` 改走 Tika）与 `<file>` 段守卫，**文件夹路径当时漏了**；
  dev-board#800 起两条路径收敛到同一个抽取器（同一份文字层判据、同一份落库缓存），
  所以「直接拖一份 PDF」与「把它放在文件夹里拖」现在逐字得到同一份正文。
  抽不出正文的文件在 `[System Note: ...]` 里**带原因**点名留痕，不再凭空消失、也不再只给文件名。
  回归用例 `FolderContextOfficeFormatTest` + `PdfExtractionParityTest`。
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
  **ai.turn 的 `rounds` 与 `promptTokensFirstRound`（dev-board#729 ⑥）**：前者由
  `TelemetryTurnTracker.noteRound`（runLoop 每次 generate 前）累计，后者由 `notePromptTokens`
  只记第一次（后续轮次叠着工具结果，混在一起就看不出固定前缀体量了）；两者都只由
  **当前轮次**记账（`isCurrentRun` 闸），拿不到 usage 时 `promptTokensFirstRound` **整个字段不写**
  （写 0 会让「拿不到」与「真的很小」在账本里长得一模一样）。有了这两个数才分得清
  「一轮很慢」与「跑了八轮」——此前账本里只有总时长。官网仓 lib/telemetry-store.ts 的
  EVENT_WHITELIST 只按事件名过滤（ai.turn 已在内），attrs 原样落库，字段级不需跨仓同步（2026-09-20 核对）。
  配套还有两条 INFO 日志：`[Round] conv=… depth=… round=… tools=… messages=…`（每轮工具数与栈深）
  与 `Stream TTFT conv=… model=… kind=token|reasoning ms=…`（首字节耗时，零点是看门狗上弦那一刻，
  即工具准备与本地压缩都做完、马上要发请求；kind 区分正文与思考增量，混看会把「思考了 4 秒」
  误读成「正文 4 秒就出来了」）。
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
- **会话列表有两条通道，改一条前先确认改的是哪条**：user-scoped 的 `/api/ai/conversations`（裸数组、内存态 runStatus、AI 面板历史下拉在用）与 project-scoped 的 `/api/projects/{id}/conversations`（信封、表态 runStatus、复合游标、项目概览页在用）。两者的 SQL、鉴权口径、返回形状全都不同，**共用的只有 `ProjectAiMessageService` 那三个 private 清洗方法**。改清洗逻辑会同时影响两条，改 SQL/鉴权只影响一条。`pinned`（dev-board#796）只加在 user-scoped 那条上（`findConversationSummaries` 的第 7 列 + `listConversations` 的稳定排序），project-scoped 那条一行没动。
- **会话置顶挂在首条消息上**（`ProjectAiMessage.conversationPinned`，`conversation_pinned` 列，ddl-auto 自动建列），与 `conversationTitle` 同一存储位、同一取值口径（「首条非空值」）。写入 `updateConversationPinned` / 单查 `isConversationPinned`，端点 `POST /api/ai/conversation/{id}/pin`（缺字段按取消置顶——这条只改排序，没有破坏性，为缺字段回 400 是无谓的摩擦）。**读取要过 `truthy()`**：JPQL 标量子查询的返回类型按方言而异（H2 回 Boolean，部分 MySQL 驱动把 BIT(1) 回成 Number），按 Boolean 强转会在某一端静默失败，表现是「置顶点了没反应」而没有任何地方报错。

- **插件对话镜像与只读会话（dev-board#298，2026-08-30）**：`project_ai_message` 新增可空列
  `sourceChannel`（office-word / wps-excel 等六值）与 `sourceMessageId`（云端消息 id，导入幂等键）。
  云后端上，绑定项目（addin_project_link 有映射）的每条消息落库后经
  `AddinConvSyncService.record` 进 outbox（挂在 `ProjectAiMessageService` 三个落库口的
  `mirror()`，**可选 field 注入**，桌面端 link 表恒空零成本）；桌面端 `MobileRelayClientService.
  pollConversationSync` 拉取并经 `importExternalMessage` 导入（幂等 upsert、content 空白/坏 role
  拒收、**createdAt 严格递增钳制**——历史回放只按 created_at 排序无 tiebreaker）。
  两条会话列表通道的 summary 都多了 `sourceChannel` 尾列（JPQL 各加一个标量子查询）。
  **镜像会话在桌面端只读**：`/api/agent/chat` 对 `isMirroredConversation` 的会话回 409
  （插件那头还在续写，双头写会交错）；续聊走 `POST /api/ai/conversation/{id}/fork`
  （`forkConversation`：整条复制成新会话，标题加「（分支）」、来源字段清空、
  userId 改发起者、原始时间保留；新 id 是 `conv-<毫秒>-<8位随机>`，随机尾巴是
  dev-board#779 K1 加的——纯 conv-<毫秒> 在同一毫秒里 fork 两次会落进同一条会话、
  后一份把前一份吞掉，而回退存档正是为了不丢数据）。前端只读态在 ChatInterface 的 `externalReadOnly` prop
  （值=来源文案，`utils/conversationSource.js` 是 sourceChannel→文案的唯一映射）。
  `officeFamily`（office/wps）随 chat 请求上送，ClientCapabilityService 内存登记，
  只用于镜像来源标注、不参与工具过滤。护栏：`ProjectAiMessageImportForkTest` /
  `AddinConvSyncServiceTest` / `MobileRelayClientHttpTest` 的对话镜像组。

## 回退与分叉（dev-board#779 K1/K18，审查 D-02/D-03/D-06/F4）

同一条历史上的两个动作，**共用一套定位键与同一个 fork 实现**，差别只有三处：复制到哪里为止、
标题后缀、原会话截不截断。

| | 回退（edit-and-resend） | 从此分叉 |
|---|---|---|
| 端点 | `POST /api/agent/history/rollback` | `POST /api/ai/conversation/{id}/fork`（带 body） |
| 服务 | `rollbackWithArchive` | `forkFromMessage` |
| 原会话 | 目标及其后**被删** | **一个字不动** |
| 产物 | 「… · 回退前存档」（整条） | 「… · 分支」（只到分叉点） |
| 确认框 | 有 | **没有**（非破坏，没有要用户承担的后果） |
| 收尾 | 回填输入框让用户改了重发 | 宿主切到新会话 |

### 回退 = edit-and-resend + 自动存档（K1）

`POST /api/agent/history/rollback`。一句话：**目标消息连同其后一起删，但删之前先把原路径整条存档**。

- **语义只有一种，两边同源**：前端把目标正文回填输入框让用户改了重发，所以后端也必须连目标一起删。
  改造前后端只删「严格晚于目标」的行、把目标留着（DTO 注释原文 `keep this one, delete newer`），
  于是库里留下「原始提问 + 改过的提问」两条连着的 USER 行——刷新页面那条本以为撤销掉的提问会复活，
  而 `ContextAssemblerService` 的历史栈直接读库，**模型会把旧要求也一起执行**。
  `api.js` 的 JSDoc 当时写的又是与后端字面相反的定义；三处（控制器注释 / api.js 注释 / 实现）现在同源，改一处要改三处。
- **删除判据是显示顺序 (createdAt, id) 的字典序**，不是单独的 createdAt：
  `ProjectAiMessageRepository.deleteFromMessageOnwards`。只按 `createdAt >` 会让与目标**同刻**落库的
  那条助手回复幸存（同毫秒、MySQL 秒级截断都造得出）；只按 `id >=` 则假设 id 与时间同序，
  fork / 镜像导入的行不保证。`@Modifying` 带 `flushAutomatically + clearAutomatically`——
  批量 JPQL 绕过一级缓存，同事务里前面刚 insert 的存档必须先落地，删完也不许有人从缓存读到已删行。
- **定位键有两个，`project_ai_message.clientRequestId` 是主角**（新增可空列，ddl-auto 自动建）。
  **主键在这里用不了**：`POST /api/agent/chat` 的回执由 `AgentInboxService.receipt` 在**控制器线程**上拼出，
  而 USER 行要等 `AgentOrchestrator.handleUserMessageInScope` 在 **turnExecutor 线程**上跑起来才落库——
  回执序列化的那一刻它还不存在；`input_applied` 同理（在 `claim` 里发，且发起的那条 POST 还显式压掉了它）。
  clientRequestId 反过来是**发之前就有**的，气泡从出生那一刻起就握着它。
  写入口是 `saveMessage(...)` 的**七参**重载（六参 = clientRequestId 传 null），
  编排器两处 USER 落库点都要带（`handleUserMessageInScope` 与 `applyPendingSteering`，
  后者取 `input.getClientRequestId()`）——漏了哪一处，那条路径发出的消息就回退不了。
  读侧 `GET /api/ai/history` 直接序列化实体，自动带上它，所以 live 与 replay 两种气泡共用同一个定位键。
- **`RollbackRequest.messageId` 是 String 不是 Long**：声明成 Long 时，前端自造的气泡 id
  （`msg-<毫秒>-<序号>`，`bubbleId.js`）会让 Jackson 在**进 handler 之前**就把请求拒掉，
  用户看到「回退失败: 服务器内部错误」，而前端的界面回退、回填输入框、刷新历史三步全部跳过。
  现在非数字走到 handler 里拿一句可读的 400；定位不到也是 400（`resolveRollbackTarget` 抛可读文案）。
  **跨会话的 id 一律当「定位不到」**，不回显它属于谁。
- **身份解析交给 `AuthController.getUserIdFromSession(sessionId)`，不许在端点里短路**：
  这里原本是 `sessionId != null ? … : null`，而 local-mode（整个桌面端）根本不发这个头，
  于是回退恒 403。这是与 D-02/D-03 彼此独立的第三个缺陷，同一个端点上。
- **存档在服务端、与截断同一个事务**（`ProjectAiMessageService.rollbackWithArchive`）：
  刻意不做成「前端先调 fork 再调回退」——那样存档失败时前端若照样截断，数据就真没了；
  服务端做，任何客户端（Office 插件等）走这个端点都有同样的保护。
  端点回 `{"status":"ok","archivedConversationId":"conv-…"}`，前端据此把 toast 换成「原对话已存为分支」。
- **`ai_conversation` 表不存在**，别去找：会话是隐式的（一组共享 conversation_id 的 `project_ai_message` 行），
  会话级元数据一律挂**首条消息**（`conversationTitle` / `sourceChannel` 就是这么存的）。
  K1 新增的 `parentConversationId` / `branchFromMessageId` 同样写在 fork 产物的首行，
  本批 UI 不展示，先落库是给 K18「从此分叉」把分支串成树用。
- **前端**：气泡上是 `dbMessageId`（history 回灌才有）+ `clientRequestId`（live 才有），
  两者都在 `createUserBubble` 里**预声明**（不预声明按仓里惯例不是响应式的）；
  `ChatInterface.rollbackLocator(msg)` 是唯一判据，返回 null 时回退按钮置灰并用
  `chat.rollbackUnavailable` 说明原因——**别让用户点一个注定失败的按钮**。
  **回填输入框必须 `await nextTick()`**：模板里有**两个** `ref="richInput"` 的 contenteditable
  （空状态的欢迎输入框、有对话时的底部输入框），回退到第一条时 bubbles 变空、两者互换，
  紧接着同步写 innerHTML 只会写进马上被销毁的那一个（表现：回退了但输入框是空的，原文没了）。
- 护栏：`ProjectAiMessageRollbackTest`（语义 / 定位键 / 存档先于截断 / 存档失败不截断 / 两次存档不撞号 /
  父子字段）、`AiAgentControllerRollbackTest`（字符串 messageId 的 400、两种定位键、无 session 头、403）、
  `AgentOrchestratorQuestionStopTest` 与 `AgentOrchestratorInboxTest` 各一条钉住两处 USER 落库点带上
  clientRequestId、`frontend/tests/chat-presentation-ui/run.mjs` 的按钮可用性一段。

### 从此分叉（K18）

`POST /api/ai/conversation/{id}/fork`，**可选 body** `{untilMessageId, untilClientRequestId}`。
一句话：**把「到这条为止」复制成一条新会话，原会话一个字不动**。

- **改造前普通对话里唯一的改写入口就是破坏式回退**（审查 D-06/F4）。fork 的服务端能力早就在了，
  但前端唯一的调用点是插件镜像只读会话顶部那条 `externalReadOnly` 说明栏里的「另起分支继续」。
  律师常要对同一份合同试两种改法再比较——试第二种就等于销毁第一种的全部过程与产物。
- **两个键都不给 = 老行为整条复制**，插件镜像那条路一行不改（`AiChatControllerForkTest` 钉住）。
  **body 整个可空**（`@RequestBody(required = false)`）：老客户端连 `Content-Type` 都不发。
- **截断判据是显示顺序 (createdAt, id) 的字典序 ≤ 分叉点**（`ProjectAiMessageService.cutAt`），
  与回退的删除判据互为镜像。只按 `createdAt <=` 会让与分叉点**同刻**落库的那条助手回复也跟过来——
  那恰恰是用户想岔开的那一答，而同毫秒落库、MySQL 秒级截断都造得出这种行；只按 `id` 则假设
  id 与时间同序，fork / 镜像导入的行不保证。
- **非数字 `untilMessageId` 回可读 400，与回退端点同口径**（前端自造的 `msg-<毫秒>-<序号>`
  说明这条消息还没落库）。定位不到（服务层抛 `IllegalArgumentException`）也是 400 —— 这个端点
  **不能让异常漏给 `GlobalExceptionHandler`**：那里对 `IllegalArgumentException` 回的是
  HTTP 200 + `code=1`，而 `api.js` 的 `request` 只在 HTTP≠200 时取 `res.data.message`，
  用户会看到一句无关的通用报错。
- **列表角标**：`findConversationSummaries` 尾部加第七列 `parentConversationId`（取**首条**消息的，
  与 `sourceChannel` 同款——本仓没有 `ai_conversation` 表，会话级元数据一律挂首行）。
  父标题由新增的 `findConversationTitleCandidates(ids)` **一次批量查回**（父会话不一定在这一页里，
  逐条查是 N+1），取标题口径与 fork 一致：storedTitle 优先，没有就用用户第一问。
  服务层落成 `parentConversationId` / `parentTitle` 两个字段，`project-overview.vue` 的
  `convBranchLabel` 渲染「分支自 …」；**前端不许再清洗一次**（仓里已有两套并行漂移的正则）。
  父会话查不到标题时只说「分支」，**绝不拿父会话的正文当标题显示**。
  **只加在 `/api/ai/conversations`（AI 面板历史下拉）这一条通道上**，项目概览页那条
  `findProjectConversationSummaries` 未动——两条通道的 SQL / 鉴权 / 返回形状本来就不同。
- **前端**：`ChatInterface` 用户气泡 footer 里 `.branch-btn` 排在 `.rollback-btn` **左边**
  （不销毁任何东西的动作应该先被读到），可用性判据同为 `rollbackLocator(msg)` ——
  两者用的是同一套定位键，**置灰必须同步**，否则会出现「回退不可用、分叉可用，点了照样失败」。
  点击**不弹确认框**，只 `emit('fork-from-message', {conversationId, messageId, clientRequestId})`；
  宿主 `project-overview.vue` 的 `branchConversationFromMessage` 走的是
  `forkPluginConversation` 同一条切换路（先 `fetchChatHistory(true)` 刷列表，再 `loadHistoryChat`
  ——列表里要先有这条新会话，`loadHistoryChat` 才查得到它的 `sourceChannel`，本地分支恒 null 即可写），
  并带同一条竞态防护（等 fork 的间隙用户切走了会话就不抢占）。
- **两个按钮的区别写在 `title` 里**：`chat.rollbackBtnTitle` 说「这条及之后的内容会从当前对话移除」，
  `chat.branchBtnTitle` 说「当前对话保持不变」。回退确认框里那句
  `chat.rollbackArchiveNote`（存档会出现在「近期对话」）保留不动。
- 护栏：`ForkConversationBranchTest`（截断到分叉点 / 原会话不动 / 父子字段 / 标题后缀 /
  两种定位键 / 连点不撞号 / 不给截断点仍整条复制 / 列表角标两条）、
  `AiChatControllerForkTest`（无 body 兼容、两种定位键、非数字 400、定位不到 400、403）、
  `ConversationBranchSummaryQueryTest`（真 H2 跑那两条 JPQL）、
  `frontend/tests/chat-presentation-ui/run.mjs` 的分叉按钮一段。

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

- `cd backend && mvn test`（JDK 21！默认 25 SIGBUS）——含回放评测 OrchestratorReplayEvalTest（用例 `backend/src/test/resources/ai-eval/cases/cases-*.json`，**16 组**）+ DesktopContextSmokeTest。新增 cases-file-tree（整理文件夹/重命名的 create_folder→move_project_file→rename_project_file 链）、cases-harness-recovery（截断 tool_code 纠正回路 F-10、编辑器桥 `{"error"}` 判 FAILURE F-09）与 **cases-question**（反问停机：awaiting_input / 执行日志随停机落库 / 同轮工具+反问不递归 / 计划审批优先于反问）。**cases-tool-choice**（9 条，dev-board#807）是「工具选择」这一面的基线组：法规名写错要收到指向 law_search 的可行动错误而不是被静默改道、记忆检索一个意图只 offer 一个工具、depth 是参数不是第三个工具、改幻灯片只走 slide_*、读 docx 走 list→extract、清单一次答完、闲聊不触发检索。**改工具面之前先跑它拿基线**（改动前 5/9 通过，改动后 9/9；那 4 条红的正是本卡要修的病灶）；它的上游——「一个意图摆着几个同义工具」——由 `ToolChoiceSurfaceTest` 量化钉住。`expect.promptContains` 断言编排器回喂的系统提醒确实进了下一轮上下文；`expect.checkpointForFileId` 断言本轮为活跃文档建过检查点——判据是 `@ToolMeta(fileEffect="MODIFIED")`，所以漏标注解的写入原语会在这里现形（`cases-revision.json` 的 `insert-at-cursor-creates-checkpoint-xml` 就是一轮只用 doc_insert_at_cursor 的最小复现，审计 B-02）。
  - **地雷：`eval/RealToolBeans.instantiateAll()` 的清单必须与生产 `AgentToolComponent` 集合同步。** TodoTools 曾长期漏列，于是 `todo_write` 在整个回放评测里根本没注册——`offeredToolsInclude` 永远失败、`offeredToolsExclude` 永远通过，相关可见性断言全是空的（已补 TodoTools）。CheckpointTools 与 SlideEditTools 已于 dev-board#729 补齐，`EvalToolBeanParityTest.KNOWN_MISSING` 现在是空集，**别再往里加名字**；新增工具组件时同步这份清单，否则针对它的 offeredToolsExclude 会变成空断言。
  - **跨类 `public static final` 常量在编译期内联**：只跑 `mvn test` 的增量编译会留下「源码一致、字节码不一致」的假失败，验证阶段一律 `mvn clean test`。
  - **`mvn clean test` 里有 16 条 skip 是常态**（2026-09-22 K32 后实测：Tests run 4870 / Skipped 16；同日 K31 时是 4758 / 15，更早 4676 / 15，2026-09-20 是 4321 / 15，2026-09-09 是 3410 / 14），不是回归。逐条门控：ProjectProfileFieldMysqlSchemaTest **3** 条与 ProjectAiMessageIndexMysqlTest **1** 条要 `AWD_MYSQL_SCHEMA_CHECK=1`（真 MySQL）；LitigationPngServiceTest **4** 条要本机有随包字体与已生成的示例 SVG（`node desktop/scripts/fetch-lowa-assets.js`）；RealVisionSmokeTest **3** 条与 RealLlmSmokeTest **1** 条要 `OPENROUTER_API_KEY`；WritingLiveEvaluationTest **1** 条同样要 key；AllowedModelsLiveContractTest **1** 条与 MultiSystemSplitLiveProbeTest **1** 条要 `RUN_LIVE_MODEL_CHECK=1`（后者还要 `OPENROUTER_API_KEY`）；CrossLanguageSignatureTest **1** 条要 python。数字对不上再查，别默认「skip 反正是常态」。
  - **Mockito 陷阱（踩过）**：`String.valueOf(inv.getArgument(n))` 会被 Java 重载决议挑成 `String.valueOf(char[])`（泛型 `<T> T` 推成 `char[]`），运行时抛 ClassCastException；若该 mock 的调用方把异常吞掉只 log（如 `SubAgentService.sendProgress`），表现就是「队列永远空、断言说没收到事件」，看着像生产代码不发事件。写 `inv.getArgument(n, String.class)`。
- 只跑回放：`mvn test -Dtest=OrchestratorReplayEvalTest`；真实 LLM 冒烟：`OPENROUTER_API_KEY=… mvn test -Dtest=RealLlmSmokeTest`（默认模型已换成 deepseek/deepseek-v4-flash，境内可跑）。
- 工具选择面（dev-board#807/#808）：`mvn test -Dtest=ToolChoiceSurfaceTest,FormatTableColumnWidthTest,MemoryToolsScopeTest,ToolRegistryTest,ToolDeclarationContractTest,OrchestratorReplayEvalTest`。
- 身份作用域与模型解析：`mvn test -Dtest=PlatformScopeCloudMultiTenantTest,AuxModelResolverTest,SubAgentServiceTest,AgentOrchestratorFailoverTest,AgentOrchestratorFailoverFlowTest`。
- 工具可见性声明化 / skill tool_policy（dev-board#799）：
  `mvn test -Dtest=ToolDeclarationContractTest,ClientCapabilityServiceTest,ClientCapabilityDocKindTest,SkillRouterTest,BuiltinSkillsTest,ToolSchemaBudgetTest,OrchestratorReplayEvalTest`。
  Calc/Impress 的 undo 真机判据：`cd frontend && npm run test:lowa-undo-redo`
  （借 `/Applications/AI WorkDeck.app` 的引擎载荷，本树 `npm run build:zetaoffice` 出 glue，无头）。
  **写新 skill 或改 skill.yml 的 tool_policy 会撞到两处**：`BuiltinSkillsTest.toolPolicyOfEveryBuiltinSkillIsPinned`
  （八个自带 skill 的 policy 逐个钉住）与那些**自己造 skill 夹具**的测试——夹具不写
  `tool_policy: restrict` 就根本没有裁剪可言，针对裁剪的断言会变成空断言
  （`SkillRouterTest` 与 `AgentOrchestratorConcurrentTurnsTest` 的 writeSkill 都已补上）。
- 并发轮次隔离：`mvn test -Dtest=AgentOrchestratorConcurrentTurnsTest`（五条：两轮并发各占一行消息、「停止后立刻再发」只停旧轮、两轮各自命中各自的 skill、旧轮次被取代后 `text_delta` / `reasoning_delta` / `doc_stream_data` 全部静默而新轮次照常、chat 返回后循环起跑前发来的 cancel 不落空；交错点全用 CountDownLatch 钉死，不靠 sleep 赌时序——最后一条用一个只在放行后才跑任务的假 executor 卡住「已提交未起跑」这个窗口）。
- 状态持久化/启动回收：`mvn test -Dtest=AgentRunRecoveryServiceTest`（mark 写透、RUNNING→INTERRUPTED+补标记、幂等、续跑翻回 RUNNING）。
- 工具空输出与崩溃轮落库：`mvn test -Dtest=AgentOrchestratorBlankToolOutputTest,ReadDocumentOfficeFormatTest`
  （空串工具不掀翻整轮 + 按 FAILURE 回喂；onComplete 异常路径把执行日志与错误摘要落库、error 载荷带
  `AI_INTERNAL_ERROR`；read_document 对真实 docx fixture 返回非空正文、空文档给可行动说明）。
- 前端：`npm run check:emits`；标签协议编解码 `npm run test:tag-protocol`（node:test，零依赖）；UI 链路 `npm run test:app-e2e`。
- Office 插件的标签解析：`node --test office-addin/taskpane/lib/sse.test.js`（零依赖，未进 CI）。

- **工具参数太长会把模型输出撑到截断**（实测：一章起草里 4 次）。编排器检测到 `<tool_code>` 未闭合会回喂提示让模型重发，最多两轮；**两轮还截断就把原因写进最终正文**（「参数太长…没有执行完」，`truncatedToolNoticeDelta()`），不再静默收尾。**那条说明必须 `sendTextDelta` 发一遍、而且必须包在 `<final>` 里**（dev-board#768）：截断恰恰发生在 `<tool_code>` 里、标签没闭合，裸接在 content 后面的文字对两端解析器而言就落在工具载荷作用域内，插件端与桌面端**都整块丢弃**——用户看到的是一个空白气泡加一行「已完成 · N 秒」，任务其实做了一半。`<final>` 在两端的路由里都优先于未闭合的工具作用域，是唯一能把它捞回正文的包裹。根治办法不是调 max_tokens，而是别让模型回抄大参数：让工具自己把内容写进文档（尽调插件 `dd_table`/`dd_phrases` 的 `docFileId` 就是这么做的）。截断守卫覆盖 `<tool_code>` / `<todo_write>` / `<final>` 三个「开了必须闭」的标签，且**开标签自己被切断也算**（实测最短一次只输出了 `<todo_write`）；只守 tool_code 的话模型在 todo 清单里被切断就静默收尾，一轮丢四个回合。回归用例 `cases-harness-recovery.json` 的 `truncated-tool-code-persists-tells-the-user` 与 `truncated-todo-write-also-corrected`。

## 2026-09-07 实测回归（B4/B7）

- 活跃文档提醒在中英两路明确要求新表整表 `doc_insert_table(rowsJson)` 一次提交，再做合并/格式，避免逐行写表耗尽 30 步；不放大全局步数限制。
- 回答菜单「插入当前文档」复用 LOWA `stream_insert({text, complete:true})` 富文本路径，单命令冲出尾表；拒绝并行 Agent 写入，失败不显示成功。覆盖 `tests/project-home/ai-message-insert.test.mjs`；worker 契约见 ai-doc-bridge。

## 超时与失败收尾补充（2026-09-15，dev-board#650）

SSE 建连只等待响应头15秒，已有长流不套这个上限；发送中初次连接失败也必须reject。停止先结束本地等待，再独立用10秒请求确认后台取消，失败不能写“已停止”。模型generate同步抛错走handler.onError同一终态闸，工具准备/本地压缩完成才启动首字看门狗。验证见 `agent-stream-connect/abort` 和 `AgentOrchestratorFailoverFlowTest`；完整矩阵与成本流程见 `doc/ai-timeout-cost-audit.md`。

## 参考来源工具 ref_*（2026-09-18，dev-board#717-720）

spec `docs/superpowers/specs/2026-09-18-addin-cross-file-design.md`。Office/WPS 任务窗格的会话多了四个工具：读别的文件、改**另一个打开着的**文档。插件那一侧（心跳、`read_for_reference`、修订记录）见 office-addin.md「跨文件读写」节；桌面端那一侧见 mobile-sync.md「桌面端常连与参考读取」节。

### 关键文件

- `service/ai/tools/ReferenceTools.java` — 四个 `@Tool`，唯一后端入口。
- `service/ai/ref/ReferenceSourceService.java` — 按 ref 前缀分派 + 合并清单 + 统一截断与错误包装。
- `service/ai/ref/{RefSource,RefQuery,RefEntry,RefSourceException}.java` — 来源接口与数据形状。
- `service/ai/ref/{OpenDocSource,DesktopSource,CloudProjectSource,CaseLibrarySource,GitProviderSource}.java` — 五个来源。
- `service/ai/ref/CaseRefClient.java` — 案件库内部口的出站客户端；`controller/internal/InternalRefController.java` 是案件库那一侧的入站端点（见 version-control.md）。
- `service/addin/PaneRegistry.java` + `controller/addin/AddinPaneController.java` — 窗格登记簿与心跳/告别端点。
- `service/addin/{GitProviderClient,GitTokenCipher}.java` + `controller/addin/AddinGitLinkController.java` + `model/entity/AddinGitRepoLink.java` — 关联 GitHub/Gitee 仓库。
- `service/file/ProjectFileTextExtractor.java` — 从 `FileTools.extract_file_text` 抽出的抽文字路由，现在**五个**使用方共用（`extract_file_text` / `read_document`(dev-board#800) / 文件夹上下文 / 云端项目来源 / 桌面端参考读取），并在这里挂着 `ProjectFileTextCacheService` 落库缓存。
- 改：`OfficeBridgeService.executeOnPane`、`ClientCapabilityService.isToolVisible`、`ContextAssemblerService` 的 Office 末位硬规则、`ProjectFileService.{findByRelativePath,listRelativePaths}`。

### 工具契约

| 工具 | 参数 | 说明 |
|---|---|---|
| `ref_list` | `query`（文件名关键字，可空）、`source`（`open`/`desk`/`cloud`/`case`/`git`，可空 = 全部） | 每行一条 `ref \| source \| path[ \| host][ \| openable]`，总数 ≤100 |
| `ref_read` | `ref`、`locator`（可空） | 纯文本，≤200,000 字符，截断标注 `...(截断)` |
| `ref_edit` | `ref`（必须 `open:`）、`command`、`argsJson` | 把 office_* 命令下发到那个文档自己的窗格 |
| `ref_open` | `ref`（必须 `desk:` 且 LIST 标了 `openable`） | 请桌面端用系统默认程序打开该文件，**只限文档类扩展名**（见 mobile-sync.md 地雷 12b） |

- **ref 是不透明串，模型只复制不构造**：`open:<paneId>` / `desk:<deviceId>:<projectKey>:<path>` / `cloud:<fileId>` / `case:<remoteProjectId>:<path>` / `git:<repoLinkId>:<path>`。`desk:` 的 path 可含 `:`，**只按前两个 `:` 切**（`DesktopSource.split`）；`case:` / `git:` 只按第一个切。
- **userId / projectId / conversationId 由 ToolRegistry 的 `SERVER_CONTEXT_PARAMS` 强制注入**，模型传的同名值一律被覆盖——否则拿别人的会话或项目当参考来源就是一行参数的事。
- **`ref_*` 只对 OFFICE 会话可见，且这是显式写出来的**：`ref_` 不属于 `doc_/sheet_/slide_/office_`，按 `isToolVisible` 的前缀规则会落进「所有会话可见」，所以那里加了一条 `toolName.startsWith("ref_") → capabilityOf == OFFICE`。LOWA 会话已有项目文件工具，不引入。护栏 `ClientCapabilityServiceTest`。
- **`RealToolBeans.instantiateAll()` 已补上 `ReferenceTools`**（那份清单必须与生产 `AgentToolComponent` 集合同步，见上文 TodoTools 的旧坑）。回放用例默认 LOWA 会话，既有可见工具集不变。

### 分派与合并（ReferenceSourceService）

- **顺序固定 `open → desk → cloud → case → git`**（`ORDER`，构造时按它把来源装进 `LinkedHashMap`）：模型读到的清单顺序就是「离用户最近的先来」。
- **一个来源失败只并列一行 `[scheme] 不可用：…`，不让整份清单失败**。非 `RefSourceException` 的运行时异常**不把 message 带给模型**（可能夹带上游响应片段），只记异常类名、回一句「暂时无法访问」。
- **`available(q)` 为假的来源整块跳过**：国际站没配案件库、这个人没绑官网账号、这个项目没关联仓库，都属于「这个来源在此上下文不存在」，不是错误，清单里不添噪音行。
- **`read` 的统一收尾**：空白正文换成「该文件没有可读取的文字。」；超上限走 `cap()`——**不把代理对切成半个字符**（生僻字/emoji 落在边界上会变乱码）。`edit`/`open` 的空白结果换成一句「无法确认操作是否完成」。**工具输出永不为空串**（`ToolExecutionResultMessage.ensureNotBlank` 那条地雷）。
- **只有 `open:` 可写**（D 决策）：`RefSource.edit` 的默认实现直接抛「该文件没有打开，不能直接修改」，其余四个来源一个字都不用写。
- **未打开的来源带 `locator` 时**走 `RefSource.withLocatorNote`：在全文前面加一句「未打开的文件无法按页定位，以下为全文。」——**悄悄忽略 locator 会让模型把全文当成「第 3 页」来引用**。正文为空时原样返回空白（给空白加抬头等于告诉模型「这份文件的全文就是空的」）。

### 跨窗格下发（OpenDocSource + OfficeBridgeService）

- `executeOnPane(target, command, args, origin)` 复用既有 pendingRequests/CompletableFuture，**发往目标窗格当前的 conversationId**，载荷多一个 `origin: {paneId, docName, conversationId}`。`OfficeResultController` 的归属校验一行未改。
- **「窗格还在不在」只看心跳，不看 SSE 有没有 emitter**（2026-09-20 修）。后端每轮结束都主动关掉 SSE 流（`AgentOrchestrator` / `AgentStreamHandler`），窗格要退避 1~30 秒才重连——一个开得好好的窗格在这段空档里就是没有 emitter 的。按 emitter 判活会把这段空档诬成「请在该文档里打开 AI WorkDeck 窗格」，而用户正看着那个窗格。本窗格的 `executeOfficeCommand` 从来不这么做：`client_action` 进补发缓冲，重连时按 Last-Event-ID 补回去（dev-board#287）。真正关掉的窗格由 `PaneRegistry` 的 90 秒心跳过期兜住，`OpenDocSource.target` 在这之前就报「窗格已经关闭」。
- **origin 必须有**：B 窗格靠它判定「这是跨文档写入」并强制标修订，缺了它的写入到了 B 那边会被当成本窗格自己的操作，痕迹保证就落空——所以发起方窗格还没登记上（心跳未到）时也照样给 origin，`docName` 兜底成「另一个文档」。
- **读取超时的文案要换一句**：通用超时文案开头是 `OfficeBridgeService.TIMEOUT_PREFIX`（`操作超时`）、后半句写的是「请不要直接重试这条写入命令」，那是给写入的；`OpenDocSource.read` 认这个前缀并换成读取版文案。**改超时文案时开头必须仍是这个常量。**
- **撞会话的窗格既不列也不下发**（`OpenDocSource.requireAddressable` / `unaddressable`，读写两条路都过）。下发是按 conversationId 推 SSE 的，「两个窗格但同一条会话」在通道上分不开——命令落到当时占着 emitter 的那个窗格，读回来的是**另一份文档**的正文却顶着目标文档的名字，沿途无人报错。三种成因都要挡：目标就是自己（回「这是当前文档本身，请直接用 office_* 工具」）、目标与发起方撞会话、**目标与另一个第三方窗格撞会话**（与发起方无关，只看 `PaneRegistry.panesOfConversation(target.conversationId).size() > 1`；两份**未保存**的新文档就是这个形状）。后两种点名目标文档、让用户去那个窗格点「新对话」。插件侧的会话键已按「项目+宿主+文档」分（见 office-addin.md），这里是兜底。
- **发起方认不准就不署名**：`PaneRegistry.paneOfConversation` 在一条会话挂着多个窗格时返回 empty（不再 `findFirst` 随便挑一个），`origin` 于是退回通称「另一个文档」。挑错的代价是 B 的修订记录与横幅把这次修改署到**另一份文档**头上。护栏 `OpenDocSourceTest.{twinPanesOnAThirdConversationAreNeitherListedNorAddressable,ambiguousCallerIsNeverAttributedToTheWrongDocument}`、`PaneRegistryTest.twoPanesCanShareOneConversationAndThenNeitherIsTheOne`。

### 末位硬规则改写（ContextAssemblerService）

dev-board#285 那条「**本会话能直接编辑的只有上面这一份打开的文档**」（病灶：模型说「PPT 不在可编辑列表中」却把内容写进了当前 Word）现在改写为三句，仍挂 Office 分支**末位**（约束放前面会被弱模型无视）：可读任何参考来源（`ref_list` → `ref_read`）；可经 `ref_edit` 改其他**打开着**的文档，且只改用户要求的那一份、**绝不把本该写进那个文件的内容改写进当前这份**（原病灶的禁令原样保留）；**未打开的文件一律不改**，桌面端项目里的可用 `ref_open` 代为打开，然后停下来等用户。**中英两版逐条对应**（中文版内联在 `assemble()` 的 OFFICE 分支里，英文版在 `activeDocumentGuidanceEn`），护栏 `ContextAssemblerServiceTest` 的「跨文件读写的硬边界」两条（三类宿主 × 中英，且断言它仍排在纯文本约束之后、活跃文档正文之前）。

### 已知地雷（参考来源面）

1. **登记簿全是进程内存**（`PaneRegistry` / `ReferenceRequestStore` / `DesktopStreamService`），前提是**单区域单 JVM**（北京、新加坡各一个实例）。横向扩容要先做粘性路由 + 共享登记簿，本设计没做——加实例会让「B 窗格连在另一台上」表现成「窗格没连着」。
2. **心跳登记要在写入时顺带清过期**（`PaneRegistry.heartbeat` 里的 `purgeExpired`）：窗格每次载入换一个 paneId，只在 `list()` 里清的话，从不发起跨文档读取的账号下会一直堆着没来得及告别的旧窗格。
2b. **心跳里的 `conversationId` 不信任请求体**（`AddinPaneController` 的 `canUseConversation`，与 `OfficeResultController` 同一条线）。登记簿是跨窗格下发链路上**唯一**一处 conversationId 来自客户端的地方——`executeOnPane` 直接往 `target.conversationId()` 那条连接推 `client_action`，`OpenDocSource.target` 只校验 paneId 在**调用者自己**名下（那是调用者自己造的）。不校验的话，任何登录用户把别人的 conversationId 登记到自己名下，`ref_read`/`ref_edit ref=open:<自己的paneId>` 就落到别人开着的文档上；结果回传那一闸也拦不住，受害者自己的窗格是合法投递者。护栏 `AddinPaneControllerTest.heartbeatWithSomeoneElsesConversationIsRejectedAndNotRegistered`。没带会话 id 的心跳照常登记（窗格只是暂时不可被下发）。
3. **参考材料的正文一个字都不许进日志**：五个来源 + service 的日志只记 scheme / 长度 / 耗时 / 异常类名。git 那条尤其严——**Gitee v5 的令牌在查询串里**，把 URL 或上游响应体拼进 message 就等于写进一次日志（`GitProviderClient` 的类注释把这条写死了）。
4. **参考材料不计费**：不走 `TransferBillingClient`，与整份文件的 PULL/PUSH 账目完全分离。**OCR 也算计费**（2026-09-20 修）：平台代采档的 OCR 按页扣 Credits，所以参考入口（`ProjectFileTextExtractor.extract` / `extractBytes`）走到 OCR 分支时改为回一句「参考读取不做文字识别，请先在工作台里识别」，而不是默默扣钱——`legal/PRIVACY.md` 中英两版都写着参考材料「不产生 Credits 扣费」。
5. **`ProjectFileTextExtractor` 是从 `FileTools.extract_file_text` 抽出来的同一条路由**（图片直接 OCR / PDF 先抽文字层抽不出才 OCR / 其余 Tika，dev-board#396 口径；文字层判据 `PdfTextLayer.isUsable`），dev-board#800 起 `read_document` 与文件夹上下文也走它；**但只有工具入口 `extractText(pf)` 真走 OCR**，两个参考入口按上一条拒绝（**缓存命中除外**——返回一条早先由 OCR 得到的正文不花钱，正是 NEEDS_OCR 那句话许诺的结果）。改它等于同时改 `extract_file_text`，`ExtractFileText*Test` 与 `ProjectFileTextExtractorTest` 要一起跑。OCR 的「[System: …]」形态失败一律转成 `OcrFailedException`，**绝不能被当成正文**。
6. **`cloud:` 的 fileId 是模型抄来的参数**：读之前一律 `hasReadPermission`，判不过与「不存在」回**同一句话**，不回显别人项目的文件名。`case:` / `git:` 同理。

### 验证（参考来源面）

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest='ReferenceSourceServiceTest,ReferenceToolsTest,OpenDocSourceTest,DesktopSourceTest,CloudProjectSourceTest,CaseLibrarySourceTest,CaseRefClientTest,GitProviderSourceTest,PaneRegistryTest,AddinPaneControllerTest,AddinGitLinkControllerTest,GitProviderClientTest,GitTokenCipherTest,OfficeBridgeCrossPaneTest,ClientCapabilityServiceTest,ContextAssemblerServiceTest,ProjectFileTextExtractorTest,ProjectFileServicePathTest'
```
