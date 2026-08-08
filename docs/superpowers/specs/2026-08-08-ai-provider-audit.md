# AI 供应商体系排查报告（2026-08-08）

> 产出方式：7 路并行只读排查（88 条结论）+ 对 37 条缺陷主张/低置信结论做反驳式复核（23 条成立，1 条被推翻）。
> 全部结论带 file:line；标「未确认」的是静态排查到不了、需真机或跨仓确认的。

# AI 供应商体系排查报告与改造清单

排查范围：backend/src/main/java/com/checkba/、frontend/src/、office-addin/、pptx-service/、desktop/main/，外加 OpenRouter 官方 OpenAPI 与 2026-08-08 当日 `/api/v1/models` 真实返回。所有结论带 file:line；标「未确认」的都是静态排查到不了的地方。

---

## 一、现状全貌（四条通道今天到底能不能跑通）

| 通道 | 主对话（AI 面板，流式） | 一句话结论 |
|---|---|---|
| **AWD_CLOUD** | 能 | 桌面/local 形态完整可跑（`ChatModelFactory.java:321-323` 短路 → `:195-201` platformApiKey），但云多租户实例（`application-cloud.yml:36`）下凡是**工具内部再调 LLM** 的场景会因身份丢失而失败；且可用性判据仍是 `hasKey`，官网 Credits 重构后可能把已充值用户挡在向导里。 |
| **OPENROUTER** | 能 | 唯一一条从向导到主对话完整闭环的自备 Key 通道（`wizard.vue:361-363` 写 `external.openrouter.apiKey` → `ChatModelFactory.java:358` 读到）。 |
| **GEMINI** | **不能** | 流式侧从来没有实现（`ChatModelFactory.java:338-346` 是 TODO，末行 return Ollama），而且连这个回落都到不了——白名单短路排在 provider 判定之前（`:325`），用户第一条消息被打到一把空 key 的 OpenRouter。只有非流式辅助调用（记忆抽取/自动打标签/memory_search）真会打 Google。 |
| **OLLAMA** | **不能** | 桌面 AI 面板同样被 `:325` 短路到空 key 的 OpenRouter，报一条指向 openai.com 的英文错误；唯一真落到 Ollama 的入口是不传 model 的 Office 插件，而插件硬编码 `mode:'AGENT'`（`office-addin/taskpane/lib/chatSession.js:483`），必抛 langchain4j 0.36 的 `Tools are currently not supported by this model`（`AgentOrchestrator.java:962` 三参 generate）。 |
| 附属：**AI PPT** | 不能（与选哪个供应商无关） | 三层同时断：Java 只读 yml 的 OpenRouter key（`PptxTools.java:904-926`）、pptx-service 全仓不解析 `model_config`（0 命中，PR#129 / commit 86a88142 re-vendor 时删掉了消费端）、桌面 spawn 不注入任何 AI key 且写设置接口恒 403（`desktop/main/services/pptx-service.js:25-43`、`pptx-service/backend/controllers/settings_controller.py:36-46`）。 |

一句话总括：**四条通道里今天真正可用的是两条（AWD_CLOUD、OPENROUTER），而它们恰好是白名单模型 + OpenRouter 后端这同一条物理链路。**向导给用户的四选一里有一半是摆设。

---

## 二、确认的缺陷与死路径（按严重度排序）

### P0-1 向导里选的供应商对主对话无效（本次排查的根缺陷）

`ChatModelFactory` 两个入口的判定顺序都是：AWD_CLOUD 短路 → **白名单短路** → provider 分流（流式 `:321 / :325 / :330 / :338`；非流式 `:111 / :122 / :129 / :139`）。而 AI 面板的模型下拉是硬编码的 8 个 OpenRouter 白名单 ID（`ChatInterface.vue:693-701`，默认 `:703` = `deepseek/deepseek-v4-flash`），组件全文不读 `activeProvider`。

用户会遇到什么：在向导里选「本地 Ollama」或「Google Gemini」，发第一条消息拿到 `Internal Error: openAiApiKey cannot be null or empty. API keys can be generated here: https://platform.openai.com/account/api-keys`（`AgentOrchestrator.java:459-463` → `useAgentStream.js:659-661` 拼成「执行中断：…」）。选了 Ollama 却看到 OpenAI 官网链接。

注意这个短路是**刻意设计**并被测试固化（`ChatModelFactoryTest.java:85-92`），所以修法不是删短路，而是让前端模型集随 provider 变（见批次 1）。连带影响：`AgentOrchestrator.java:380`（起标题）与 `ConversationSummarizer.java:86/126`（上下文压缩）硬编码 `deepseek/deepseek-v4-flash`，在 Ollama/Gemini 下这两条后台链路也一起废。

### P0-2 GEMINI 通道流式无实现

`ChatModelFactory.java:338-346`：`// TODO: Implement Gemini Streaming` + `return getOrCreateOllamaStreamingModel(...)`，与 `:348` 的兜底完全同一句。非流式反而有实现（`:139-141 → :295-311`）。所以 Gemini 的能力只在辅助链路上活着，主对话结构上不可用。`GeminiChatLanguageModel.generate(messages, tools)` 还会静默丢弃 toolSpecifications（`GeminiChatLanguageModel.java:38-44`），对本产品（重度依赖 doc_*/sheet_* 原语）等于不可用。

### P0-3 AI PPT 整条模型/密钥下发链路断裂

`PptxServiceClient.java:125 / 161 / 199 / 920` 四处发 `model_config`，`grep -rn model_config pptx-service/` **零命中**；`project_controller.py:349-376` 的 generate_outline 只取 `language` 与 `idea_prompt`。git log -S 显示这套消费逻辑是 checkba 在 0.1.0 上的定制，在 commit 86a88142（PR#129，2026-07-09 re-vendor banana-slides 0.4.0）里被整体删除，而 `pptx-service/UPGRADE_CHECKBA.md:6` 的定制清单没列它，两道防线都照不到。

用户会遇到什么：桌面版任意供应商下调 `pptx_generate*`，pptx-service 侧在大纲阶段抛 `GOOGLE_API_KEY ... is required`（`pptx-service/backend/services/ai_providers/__init__.py:161-167`，默认 provider=gemini 见 `config.py:49`），且产品内**无处可配**（写设置需 `PPTX_SETTINGS_TOKEN`，全仓只有该 controller 出现这个变量名）。**未确认**：未真机复现报错文案。

顺带两条文案错误：`wizard.vue:225`「同一 Key 也用于 AI PPT 的图像生成」是假的（PptxTools 全文无 `getGemini`）；`wizard.vue:90-96` 对律师承诺「AI PPT 在本机完成、数据不出本机」，而设计上主题/大纲/描述是要交给云端模型的（`PptxTools.java:922-923` 传 `https://openrouter.ai/api/v1`）。

### P0-4 云多租户下工具内部的 LLM 调用拿不到用户身份

`PlatformAiUserScope` 在 taskExecutor 线程建立（`AgentOrchestrator.java:337`），而工具分发跑在流式回调线程上（`AgentOrchestrator.java:555-556` 与 `ToolRegistry.java:287` 的注释自己写明这条边界会丢 ThreadLocal，ToolContextHolder 就是为此补的），`dispatchTool`（`:595`，实现 `:202-254`）**不重建**该作用域。于是 `SubAgentService.java:116` 的 `PlatformAiUserScope.wrap` 捕获到 null。

后果分形态：桌面/local 回落机器级 key 侥幸无事；`application-cloud.yml:36`（local-mode=false）下 `strictMultiTenant()` 为真，抛「本次 AI 调用未携带用户身份…」。同根因还波及 `AgenticRetriever.java:167`（query_memory 工具内）与 `AgentOrchestrator.java:983`（onError 回调线程里换模型），后者会把这句中文错误当成「平台通道不可用」直接终止整轮。最小修法：在 `dispatchTool` 或 `ToolRegistry.execute` 里按 `ctx.userId()` 重建作用域（那里 userId 本来就有）。**未确认**：addin.aiworkdeck.com 上 DB 里 `ai.activeProvider` 是否已是 AWD_CLOUD——若是，这条在生产上已经在发生。

### P0-5 平台通道可用性判据是 hasKey，可能挡住已充值用户

`AccountController.java:244` 把 `hasAiQuota` 直接等同于官网返回的 `hasKey`；向导 `wizard.vue:294` + `:410-413` 据此**硬拦提交**，admin `:1282/:1247/:1745` 据此置灰。官网 Credits 重构后 OpenRouter key 改按需签发，充值完到首次调用之间 `hasKey` 恒 false——等于「下一步在向导里做不完」。修复已存在但**不在 master 上**：commit 866f10a4 只在未合并分支 `claude/website-signup-flow-089407`（`git merge-base --is-ancestor` 为否）。**未确认**：官网 `/api/account/ai-usage` 今天的实际返回。

### P1-1 403 region 被判 FATAL：不重试、不换模型、英文原文进气泡

`LlmErrorClassifier.java:99`（结构化）、`:113-115`（文本）、`:91`（默认兜底）三条路都归 FATAL；`Kind.failoverable()` 显式排除 FATAL（`:51-53`）→ `AgentOrchestrator.java:934` 不触发 → `:1020` 直接 `Stream Error: ` + 原始响应体 → 前端拼成「> **执行中断**：…」（`useAgentStream.js:659-661`），错误态只设 `agentRunStatus='ERROR'`，「继续」条只对 paused 渲染（`ChatInterface.vue:449`），没有重试入口。

严重度要说准：默认模型与 failover 链两个候选都是区域无关的（`application.yml:143 / :155-157`），三个国际模型在下拉里已带「(国际网络)」后缀并排末位（`ChatInterface.vue:699-701`）。所以这不是默认必踩，而是「事前一句括号提示、事后零兜底」。附带一条同向问题：403 在平台通道语义二义——对账探针把 401/403 判成「官网已吊销 key」并作废本地密钥（`PlatformUsageAccountant.java:198-205` → `PlatformAiChannel.java:124-131`），流式路径的 403 却不区分，两种完全不同的故障糊成同一句英文。

### P1-2 单价表漂移 + 长上下文翻倍计价未建模（今天就在错账）

`AllowedModels.java:15-40` 的双单价直接喂 `TokenUsageService.java:104-118`。以 2026-08-08 真实 `/api/v1/models` 比对，20 个 id 全部在线（没有下线风险），但 5 个价格错：`z-ai/glm-5` 0.6/1.92 vs 线上 0.95/2.55（输入低报 37%）、`deepseek/deepseek-v4-flash` 0.09/0.18 vs 0.14/0.28（照旧快照抄的，浮动 id 已涨价）、`deepseek/deepseek-v3.2` 低报约 18%、`moonshotai/kimi-k2.6` 0.66/3.41 vs 0.5795/2.44（**高报 14%/40%，对用户超收**）、`deepseek/deepseek-v4-pro` 差 1%。

两个结构性漏项：`google/gemini-2.5-pro` 与 `google/gemini-3.1-pro-preview` 有 `pricing.overrides`（prompt 超 200k 时输入翻倍、输出 1.5x），而 `application.yml:169-170` 的 model-token-budgets 示例正是把 gemini 放大到 500000，一放大就踩；20 个模型里 17 个有 `input_cache_read` 价（约 prompt 的 1/10），我们一律按未命中缓存计价。

### P1-3 sub-agent 与 11 处辅助 LLM 调用完全不记账

`SubAgentService.java:191` 拿到 Response 后只取 `:197` 的 content，`tokenUsage()` 从未读；`SubAgentResult.java` 无 token 字段。全仓 `recordUsage` 只有两个调用点：`AgentStreamHandler.java:493`（主循环）与 `AiChatService.java:118`（已死的 v1）。

用户会遇到什么：`/api/account/usage`（`AccountController.java:156-185`）的 token 数在两种通道下都偏低；平台通道的 cost 是 observed-previous 差分（`PlatformUsageAccountant.java:178`），这些未记账的花费会被整块折进下一条主循环记录——总额对、逐条归属和逐模型分布错。跑满 6 轮的子任务在 token_usage 里一行都没有。

### P1-4 模型选择不持久化

`ChatInterface.vue:703` 初始化为 `availableModels[0].id`，`:707-711` 的 selectModel 只写 ref，全文件零 storage 调用。而 AI 面板挂在 `v-if="showAiPanel"`（`project-overview.vue:1014`），**关掉右侧面板再打开就复位**成 DeepSeek V4 Flash，界面无任何提示。这是有计费含义的选择。

### P1-5 向导「只填一个字段」会清掉同组 baseUrl

`AdminConfigController.java:462-465` 对同组其余字段一律 `safe(null)→""` 落库，`SystemSettingService.java:41-51` 的 getOrDefault 对「存在但为空」返回空串不回退默认值。只有 `ChatModelFactory.java:80-83` 做了空白兜底（注释明写就是为这个坑），三个消费方没有：`QichachaService.java:48→57`（url 变 `/ECIInfoVerify/GetInfo`）、`TushareService.java:254→263`（`HttpRequest.post("")`）、`TtsService.java:114→117 / 185→195`（url 变 `/voices`）。阿里云 OCR 靠自己的 hasText 逃过（`AliyunOcrClientFactory.java:14`）。

准确口径：对纯新装用户是「填了也用不了，且失败模式从鉴权失败退化成 URL 非法，更难自查」；真正**从可用变不可用**的是两种场景——baseUrl/secret 由 env 提供的部署，以及管理员走 `/api/admin/wizard/reset`（`WizardController.java:112-128`）重跑向导把原本正确的 baseUrl 清空。ElevenLabs 那条**有条件**：桌面端 Kokoro 已下载时会注入 `EXTERNAL_TTS_PROVIDER=local`（`desktop/main/services/backend-service.js:132-136`），此时短路走本地不受影响。

### P1-6 两个自带 skill 一命中，dispatch_subtask 就从工具规格里消失

`AgentOrchestrator.java:961` 过 `skillRouter.visibleTools()`，可见工具 = skill.allowed_tools ∪ base-tools，而 base-tools 只有三个（`application.yml:237`）。`backend/skills/shareholder-meeting-verification/skill.yml:21` 与 `backend/skills/listing-pathway/skill.yml:33` 都没有 `dispatch_subtask`——这两个恰恰是 `prompts/system_prompt.md:395`（§6.5）教模型要委派的长程任务。裁剪只影响可见性不拦分发（`SkillRouter.java:25-26`），所以「能不能用 sub-agent」变成了「模型走原生 function calling 还是 XML 兜底协议」的函数，这不是有意设计。

### P2 死路径清单（清理时不会坏东西，但今天严重污染排查）

- **整条 v1 `/api/ai/chat`**：端点仍映射（`AiChatController.java:65`），但前端唯一调用方 `project-overview.vue:4531 handleAiSend` 在模板里已无任何绑定（模板止于 `:1397`，AI 面板换成 `<ChatInterface>`，`:1028`）；且 `api.js:375-380` 的 payload 还丢了 `contexts` 与 `assistantId` 两个字段——双重死。连带死掉的能力：Gemini PDF 直传与全部多模态（`MultiModalContentService.java:93/111/147`，唯一消费者 `AiChatService.java:103`）、`ai.systemPrompt.OLLAMA/GEMINI` 两个 key（唯一读者 `AiChatService.java:180-188`，注意它按模型名字符串而非 provider 选 key，所以 admin 那两个提示词 tab 对**四条通道全部失效**，不是「OpenRouter/AWD_CLOUD 缺入口」的不对称问题）。
- `GeminiCacheService.java`：零调用方，`GEMINI_CACHE_ID:` 协议只有消费端没有生产端。
- `AiConfiguration.java:71 projectAssistant` bean：全仓零注入。
- `project-overview.vue:1606-1611` 的第二份模型清单（`gemini-1.5-pro` / `ollama`，都不在白名单）+ `initAiModel`（`:4462-4485`，在 onLoad 里真的会跑）+ `switchModel`（`:4491`，模板零引用，所以 `activeAiProvider` 这个 storage 键永远不会被写入）。
- `AiAssistantService.java:76-83` 的 assistantCache 键只有 modelId + assistantId，不含密钥指纹（对比 `ChatModelFactory.java:245/265` 刻意带了 `keyFingerprint()`），多租户下会跨用户复用别人的平台 key——**标 likely**，取决于该端点是否还可达（今天 UI 不可达，但鉴权后 curl 可打）。
- 小口子：admin 页 Google/OpenRouter API Key 输入框无 `password` 属性（`admin.vue:63 / :94`），同页阿里云 Secret 有（`:179`）。
- 子 Agent 两个数值不匹配：超时 180s vs LLM 600s 且 `cancel(true)` 打不断阻塞 HTTP（`SubAgentService.java:129/180/191`）；charBudget = 30000×2.0 = 60000 字符，而单文件上下文上限 50000（`application.yml:191`），给了读文件工具的子任务一次读取就吃掉 5/6 预算。

---

## 三、逐条回答你的四个方向

### 方向 1：向导里的 Google AI Key — 建议直接干掉

**现有用途**：只有两处真实用途。一是 admin 的三个字段（`admin.vue:61-84`），二是 provider=GEMINI 时三个非流式辅助调用真会打 `generativelanguage.googleapis.com`：`MemCellExtractor.java:184`（记忆抽取）、`AgenticRetriever.java:167`（memory_search）、`AutoTaggingService.java:56`（文件自动打标签）。向导声称的第三个用途（AI PPT 图像生成）是错的。

**建议：把供应商从四档收敛成三档——「AI Workdeck 云端 / 自备 OpenRouter Key / 本地 Ollama（实验）」，删掉 GEMINI 枚举与 Google key 全部字段。** 理由：(a) Gemini 主对话在架构上不可用（无流式实现 + 不支持 tool calling），补齐它等于重写一个 provider 适配层，而它的全部模型都能通过 OpenRouter 的 `google/*` 拿到（`AllowedModels.java:26-30` 已有 5 个，线上还有 `google/gemini-3.6-flash` 等更新的）；(b) 保留一个「选了就坏」的选项比没有这个选项更糟；(c) 它还会被 `demotePlatformProvider`（`ChatModelFactory.java:225-226`）主动推给用户。

删除时必须一并做的四件事（其余都是死代码，删完不会坏）：三处 `getChatModel(null)` 改指统一的 aux 出口；`demotePlatformProvider` 的 GEMINI 落点改 OLLAMA；存量 DB `ai.activeProvider=GEMINI` 加一条启动期迁移（`ChatModelFactory.java:66-70` 有 catch 回退，只 warn 不崩，但会静默改变用户设置）；向导与 admin 摘选项、改文案。

**关于「Ollama 无多模态能力时报错并建议改用官网通道」**：这条今天的前提不成立——**主链路根本没有图像通道**。AI 面板粘贴的图片只用于气泡展示（`ChatInterface.vue:1445-1452` 存 File，`:1105` 只映射 dataURL，`file` 从此无人引用），`/api/agent/chat` 的 payload（`useAgentStream.js:335-357`）与后端 DTO（`AiAgentController.java:299-330`）都没有任何图像字段；`ContextAssemblerService.java:435/478` 与 `AgentOrchestrator.java:620/627/703/726/748` 全是纯文本 UserMessage。产品里所有「图片进 AI」都是 OCR 转文本（阿里云 OCR / 本地 MinerU）。

所以我的建议是把这条拆成两件事，且顺序相反：
1. **先修一个用户今天就在踩的坑**：`ChatInterface.vue:1074-1078` 允许「只有图片、没有文字」就发送，此时 `message: ""` 照样 POST——用户看到自己的图片气泡在等回答，模型收到一条空消息。这个必须立刻拦住并提示「图片会以 OCR 文字形式加入上下文，请补充说明」。
2. **真要做原生图像输入再谈能力探测**。届时能力判据不能用现有的 `MultiModalContentService.java:49-52` 子串启发式（`contains("gemini")||contains("gpt-4")||contains("claude-3")`，已过期到认不出 claude-sonnet-5 / gpt-5.2），应改成从 OpenRouter 的 `architecture.input_modalities` 取（见方向 2）。
3. **Ollama 更急的问题不是多模态而是 tool calling**：`OllamaStreamingChatModel`（langchain4j 0.36）没有三参 generate，AGENT/PLAN 模式必抛英文异常。建议在 provider=OLLAMA 时前端只允许 ASK 模式并显式说明，同时向导选中 Ollama 时补一次探测（`GET /api/tags` 查是否在跑 + 目标模型是否已 pull）——全仓今天**零探测代码**（`11434` 只出现在 5 处配置常量，desktop 目录对 ollama 零命中，`model-manager.js:46-76` 的组件清单里也没有 Ollama）。

### 方向 2：官网通道推荐与模型选择 — 核心是「模型目录服务化」，不是加个下拉

今天的三份不同步事实来源：后端白名单 20 条（`AllowedModels.java:15-40`）、AI 面板硬编码 8 条（`ChatInterface.vue:693-701`）、死代码 2 条（`project-overview.vue:1608-1611`）。**没有任何端点把清单下发给前端**（`/api/ai/config` 只回 activeProvider 与 platformAiAvailable，`AiChatController.java:188-203`），所以后端加模型用户看不到、前端加模型会被工厂静默回落默认模型；防漂移只有一个单向断言（`ChatModelFactoryTest.java:139-153` 只断言白名单包含这 8 个，反向漏）。白名单里 12 条今天在 UI 里选不到。

**建议：**

1. **给 AllowedModels 补元数据 + 加一个 `GET /api/ai/models` 下发端点。** 现在只有 id + 双单价，缺六个字段：`vendor`、`displayName`（中文名今天只存在于前端硬编码）、`regionClass`（GLOBAL / INTL_ONLY）、`toolCalling`、`inputModalities`、`contextLength`。前端下拉改成从端点取，按 provider + region 过滤。这是方向 2 和方向 3 的共同前置。

2. **不拉全量，维持人工精选白名单，但按你给的分组重排。** OpenRouter 服务端筛选完全可用（`supported_parameters=tools` → 319 个、`input_modalities=image` → 237、`min_tool_success_rate=0.9` → 207、`max_price`、`sort` 12 值、`category=legal` 也有），但把 400 个模型丢给律师用户选是负价值。建议白名单改成（全部经 2026-08-08 实测在线且支持 tools）：
   - 国内：`deepseek/deepseek-v4-flash`（0.14/0.28，日常默认）、`deepseek/deepseek-v4-pro`、`z-ai/glm-5.2`（0.252/0.792，1M 上下文——**比我们现在用的 `z-ai/glm-5` 更便宜且上下文大 5 倍，应直接替换** `ChatInterface.vue:698`）、`moonshotai/kimi-k2.6` 或 `kimi-k3`、`qwen/qwen3.7-flash`（0.03/0.13，1M，支持 tools+图片，极便宜）、`bytedance-seed/seed-2.0-lite`（**注意：OpenRouter 上没有任何含 `doubao` 的 id，字节的模型在 `bytedance-seed/` 下**）、`minimax/minimax-m3`。
   - 国外：`anthropic/claude-sonnet-5`、`google/gemini-3.6-flash`、`openai/gpt-5.6-terra`（1/6，比我们现在的 gpt-5.2 1.75/14 更新更便宜）、`x-ai/grok-4.5`（**白名单里今天一个 Grok 都没有**）。
   - 三类要避开：`openrouter/auto` 等动态路由 id（pricing 返 -1，静态价格表无法计价）、`:free` 后缀（平台限流 20 RPM）、`*-image-preview`（不在白名单且无单价）。

3. **境内屏蔽境外御三家：只能靠自己的信号，OpenRouter 给不了。** 这是本次调研唯一的坏消息且已确证：`/models` 的 `region` 参数枚举只有 `"eu"` 且语义是 EU 数据驻留；官方文档全文搜 "not available in your region" 零命中；地理 403 只在面向供应商的 uptime 文档里以「按 endpoint 单独统计、不计入 uptime」出现。而 `anthropic/claude-sonnet-5` 的 8 个 endpoint 全在 US/EU/global，没有亚太友好 endpoint，所以「换 provider 绕开」缺乏事实基础。桌面端自己也**零区域判定能力**：JVM 不读 Locale/user.country（只取 os.name/os.arch），Electron 主进程对 locale/timezone/country 零命中，前端不读 navigator.language——反而在 `zetaOfficeBoot.js:232-236` 主动 shim 覆盖成 zh-CN（把唯一现成的信号关掉了，而且当年是当 bug 关的）。

   **推荐方案：让官网在 `verify-key` / `ai-usage` 响应里回传一个按源 IP 判定的 `region` 字段，桌面端缓存并据此过滤模型集 + 决定文案。** 理由：(a) 现成通道，扩一个字段即可；(b) 官网 ingest 端本来就能看到源 IP，是全链路唯一准确的判据；(c) 时区/系统语言都能被用户改且 `Asia/Shanghai` 不等于境内网络；(d) 首次调用探测慢且对离线装机不友好。兜底：admin 页给一个「网络区域：境内/境外/自动」的手动开关，覆盖自动判定。

4. **同时把 403 region 从 FATAL 里摘出来**（按 message 细分出 region 子类，不要整体放宽 403——否则 key 失效/额度禁用会被带进换模型重试，重复扣费探测），并给一句中文引导「该模型在当前网络不可用，已切换到 X / 建议改用官网通道」。

5. **默认模型可选可持久化**：admin 页今天完全没有「默认模型」字段，默认值只能改 yml 重启（`application.yml:143`）。建议加 `ai.defaultModel` 到 system_setting（走现成的 `getSetting` 模式，DB > yml，改完 `clearCache()` 生效），前端 selectModel 落 storage。**自由度留给客户这一点我同意**：不做「平台通道只让用便宜模型」的收窄（今天也没有，`ChatModelFactory.java:176-185` 平台通道与 BYOK 共用同一份白名单），但要在下拉里显示单价档位标签，让用户知道自己在花什么钱——现在产品里连 ¥→$ 折算率和加价倍率都不披露（`AccountController.java:239-251` 刻意不透传官网的 marginMultiplier/exchangeRate，唯一书面口径在 `docs/superpowers/specs/2026-08-05-commercialization-redesign.md:59`），而 admin 把「余额 xx 元」和「额度 $xx」并排显示（`admin.vue:464-478`）。

### 方向 3：sub-agent 与 workflow 的额度调用 — 机制在，但有三处硬伤，且省钱这件事已半程达成

**长程任务能否用 sub-agent：能，机制是完整的。** `dispatch_subtask` 是内置工具、默认注册（`ToolRegistry.java:123-130`）、对 LOWA/OFFICE/NONE 三档客户端都可见（`ClientCapabilityService.java:108-122` 只过滤 doc_/sheet_/slide_/office_ 前缀）、system prompt §6.5 有使用时机、限值全部配置外置（轮数 6 / 并行 3 / 超时 180s / 预算 30k token / 模型可独立配，`SubAgentProperties.java` + `application.yml:198-211`）、防递归三道闸。

但今天有三处硬伤（前两条见 P0-4 / P1-6，第三条见 P1-3），其中 **P0-4 是前置依赖**：多租户下子 Agent 连模型都建不出来，换什么模型都无意义。另外**没有任何实测数据支撑「模型会自发委派」**：`cases-subagent.json` 的 3 个用例是写死的 turns 脚本，`RealToolBeans.java:28` 明确说评测里只记录分发从不 invoke，所以 SubAgentService 在评测里一行都没跑过；`SubAgentServiceTest` 的 7 个用例全是 mock 模型且没有一条覆盖 PlatformAiUserScope 传播。

**「用更便宜的额度/模型省钱」：现状其实已经半程达成，缺的是统一出口与可配置性，不是能力。** 11 处非主对话 LLM 调用里，3 处硬编码就是最便宜档 `deepseek/deepseek-v4-flash`（`AgentOrchestrator.java:380`、`ConversationSummarizer.java:86/126`），3 处 `getChatModel(null)` 在 OPENROUTER/AWD_CLOUD 下也落到同一个默认模型，1 处已有独立配置（`MatterClassifierService.java:80`，`telemetry.classifier-model`），1 处可独立配（`ai.subagent.model`）。

**推荐三步（最小改动）：**
1. `ChatModelFactory` 加 `getAuxChatModel()`：读 `ai.aux-model`（缺省沿用 open-router.default-model），内部仍走现有 provider 判定（平台通道短路在 `:111` 之前，所以便宜模型照样计在平台额度里，省钱有效）。把上述 6 处硬编码/null 全部改指它。
2. 把 `ai.aux-model` 与 `ai.subagent.model` 按现成的 `getSetting("external.*")` 模式提升为 system_setting 键（DB > yml，改完 `clearCache()` 即生效，不必发版），并补一条「非白名单即拒绝并提示」的校验——今天填了非白名单模型会 `log.warn` 后静默回落（`ChatModelFactory.java:129-135`），跟故障转移链踩过的同一个坑，且没有护栏测试。
3. 补记账（P1-3）。不做这一步，换便宜模型省了多少钱在面板上看不出来。

**关于「用不一样的额度/受限 key」**：OpenRouter 侧我查清了——`POST /api/v1/keys` 只有 name/limit/limit_reset/expires_at/include_byok_in_limit/workspace_id，**没有 allowed_models，也没有 per-key 限速**（官方还明说多开 key 不增配额）。要按模型限制单把 key 必须用 **Guardrails**：`POST /api/v1/guardrails` 支持 `allowed_models` / `ignored_models` / `allowed_providers` / `limit_usd` + `reset_interval`，再 `POST /guardrails/{id}/assignments/keys` 按 key hash 挂载（一把 key 最多挂一个），并可用 `GET /api/v1/models/user` 反查「这把 key 实际能用哪些模型」。

**但我不建议为此上 Guardrail。** 对 AWD_CLOUD 我们自己就是发 key 方，在我们后端按调用类型选模型（步骤 1）就够了，成本为零；上 Guardrail 会引入新的错误码歧义（guardrail 拦截也是 403，会和地域 403、key 吊销 403 撞在一起，把 failover 链搅乱），且个人账号能否创建 guardrail 官方两处口径不一致（OpenAPI 写「for the authenticated user」，文档页写「组织账号需 admin」）——**未确认**。Guardrail 值得留作后手：如果将来要给企业客户发「只能用国产便宜模型」的子 key，它是唯一手段。

### 方向 4：整体一致性与死路径清理

一致性缺口集中在三处：(a) `AiConfiguration.java:34` 的遗留 bean 读 yml 的 `ai.model.provider` 而不是 DB 的 `ai.activeProvider`，且 switch 把 OPENROUTER/AWD_CLOUD 一起落进 `case OLLAMA: default:`（`:50-51`），打包态（provider=open-router）启动时其实构造的是 OllamaChatModel（懒连接才没炸）；(b) `PptxTools` 完全绕开 ChatModelFactory（不读 DB、不看 activeProvider、没有平台通道分支）；(c) `application.yml:125` 的 yml 默认 provider 是 `open-router`，而 `application-prod.yml:39` 是 `ollama`，desktop 打包用 desktop profile（`backend-service.js:123`）且 `application-desktop.yml` 无 ai 段——即打包版兜底 OpenRouter、dev 版兜底 Ollama，两种形态默认行为不同。死路径清单见 P2。

---

## 四、改造清单

### 批次 0：止血（无契约变更，可独立合并，建议一个 PR 打包）

| 项 | 改哪些文件 | 契约影响 | 验证方式 |
|---|---|---|---|
| 0.1 修 `dispatchTool` 的 PlatformAiUserScope 缺口 | `AgentOrchestrator.java:202-254`（或 `ToolRegistry.execute`） | 无（内部线程作用域） | 新增单测：cloud profile + strictMultiTenant，断言 sub-agent 与 query_memory 不抛 AccountException；跑一轮真对话在 dispatchTool 里 log 线程名与 `PlatformAiUserScope.current()` 确认非 null |
| 0.2 单价表校正 5 条 + 补 `pricing.overrides` 与 cache-read 建模 | `AllowedModels.java:15-40`、`TokenUsageService.java:104-118` | TokenUsage.cost 数值变化（estimate 口径，非账单） | 新增单测：200k+ prompt 的 gemini-pro 走翻倍档；对拍一次真实 `/api/v1/models` 价格 |
| 0.3 sub-agent 与 6 处辅助调用补 `recordUsage` | `SubAgentService.java:191-197`、`SubAgentResult.java`、六个辅助调用点 | token_usage 表新增行（model 字段会出现 aux 模型名）；`/api/account/usage` 数字上升 | 单测断言 6 轮子任务产生 token_usage 行；真机看面板 token 数是否与 OpenRouter 后台接近 |
| 0.4 两个自带 skill 加 `dispatch_subtask` | `backend/skills/shareholder-meeting-verification/skill.yml:21`、`backend/skills/listing-pathway/skill.yml:33` | skill 可见工具集变化 | 评测加一条真模型用例（长程检索任务），断言 `dispatch_subtask` 出现在工具序列里 |
| 0.5 空图片消息拦截 + OCR 说明 | `ChatInterface.vue:1074-1078` | 无 | app-e2e 加一步：只粘图不打字点发送，断言被拦并有提示 |
| 0.6 `toSettingsUpdates` 不写 null 字段 | `AdminConfigController.java:462-465`（`safe()` 改为跳过 null） | **改变 admin 保存语义**：admin 是整表回传所以字段本来有值，不受影响；但「清空某字段」这个操作会失效，需要显式空串 | 单测：向导只填企查查 key，断言 `external.qichacha.baseUrl` 不被写入；跑一次 wizard reset + 重填回归 |
| 0.7 admin 两个 key 输入框加 `password` | `admin.vue:63 / :94` | 无 | 目视 |
| 0.8 `ai.subagent.model` 非白名单启动期校验 | `SubAgentProperties`（对齐 `AiFailoverProperties` 口径） | 非法配置从静默回落变启动告警 | 单测 |

批次 0 无依赖，可立即做。**0.1 是批次 3 的前置。**

### 批次 1：provider 收敛为三档 + 默认模型可选可持久化（依赖批次 0.6）

| 项 | 改哪些文件 | 契约影响 | 验证方式 |
|---|---|---|---|
| 1.1 删 GEMINI 枚举与 Google key 全部字段 | `AiModelProperties.java:26/113`、`ChatModelFactory.java:139-141/225-226/295-311/338-346`、`GeminiChatLanguageModel.java`、`GeminiCacheService.java`、`AiConfiguration.java:50-51/71`、`application.yml:126-131`、`application-prod.yml:40-42`、`wizard.vue:221-230`、`admin.vue:61-84/1241/331-364`、`AdminConfigController.java:131-132/169/383-385/423-428` | **契约变更**：`ai.activeProvider` 枚举减一；`external.google.*` 与 `ai.systemPrompt.*` 四个 system_setting 键废弃；`/api/ai/config` 返回值不变 | 启动期迁移单测（DB 里 GEMINI → OLLAMA/OPENROUTER）；向导 + admin e2e；跑 `mvn test` 全量（`ChatModelFactoryTest` 需删 GEMINI 用例） |
| 1.2 前端模型集随 provider 变 + Ollama 只允许 ASK | `ChatInterface.vue:693-711`（改为从端点取）、新增 provider→模型集映射 | 依赖 2.1 的端点 | app-e2e：选 Ollama 后断言下拉只有本地模型、模式选择器无 AGENT |
| 1.3 `ai.defaultModel` 提升为 system_setting + 前端持久化 | `ChatModelFactory`（getSetting 模式）、`AdminConfigController`（新键）、`admin.vue`（新字段）、`ChatInterface.vue:707-711`（落 storage） | 新增 system_setting 键 `ai.defaultModel` | app-e2e：改默认模型 → 关面板 → 重开 → 断言仍是所选模型 |
| 1.4 向导选 Ollama 时探测（服务在跑 + 模型已 pull） | 新增后端 `GET /api/ai/ollama/probe`（调 `/api/tags`）、`wizard.vue:391-413` 加校验 | 新增端点 | 手测：不开 Ollama 点下一步应被拦 |
| 1.5 合规文案与 AI PPT 文案修正 | `wizard.vue:90-96 / :225` | 无 | 目视 + 法务口径复核 |

**1.2 依赖 2.1。1.1 与 2.x 可并行。**

### 批次 2：模型目录服务化 + 区域（依赖批次 1.1 决定枚举形态）

| 项 | 改哪些文件 | 契约影响 | 验证方式 |
|---|---|---|---|
| 2.1 AllowedModels 补六个元数据字段 + `GET /api/ai/models` | `AllowedModels.java`、`AiChatController.java`、`api.js` | **新增端点**；前端不再硬编码清单 | 新增双向护栏测试（端点返回集 == 白名单，替换今天 `ChatModelFactoryTest.java:139-153` 的单向断言） |
| 2.2 白名单换代（glm-5→glm-5.2、加 Grok、加豆包 bytedance-seed、gpt-5.2→gpt-5.6-terra 等） | `AllowedModels.java`、`application.yml:143/155-157`（默认与 failover 链） | 用户可见模型集变化 | 用真实 `/api/v1/models` 对拍每个 id 在线且 `supported_parameters` 含 tools |
| 2.3 官网回传 region → 桌面按区域过滤 + admin 手动开关 | 官网仓 `verify-key`/`ai-usage` 响应扩字段、`AccountController.java:209-251`、`admin.vue`、前端过滤 | **跨仓契约变更**（官网 + 桌面同步发） | 真机：境内网络断言境外模型不出现在下拉；手动开关覆盖生效 |
| 2.4 403 region 从 FATAL 摘出 + 中文引导 | `LlmErrorClassifier.java:91/99/113-115/51-53`、`AgentOrchestrator.java:934/1020`、`useAgentStream.js:659-661` | **改变 failover 语义**（`.claude/agents/ai-chat.md:44` 的契约要同步更新） | 单测（region 403 → failoverable；401/额度禁用仍 FATAL）；真机在境内网络选国际模型验证降级 |
| 2.5 单价档位标签 + ¥/$ 折算披露 | `admin.vue:464-490`、模型下拉 | 无 | 目视 |

**2.3 需要官网仓配套 PR，是唯一跨仓项，排期要留同步窗口。2.4 依赖 2.1 的 regionClass 字段做「换到区域无关模型」的候选选择。**

### 批次 3：额度分层与省钱（依赖 0.1、0.3）

| 项 | 改哪些文件 | 契约影响 | 验证方式 |
|---|---|---|---|
| 3.1 `getAuxChatModel()` 统一出口 | `ChatModelFactory`（新方法）、`AgentOrchestrator.java:380`、`ConversationSummarizer.java:86/126`、`MemCellExtractor.java:184`、`AgenticRetriever.java:167`、`AutoTaggingService.java:56` | 新增 system_setting 键 `ai.aux-model` | 单测断言六处都走 aux 模型；回放评测不回归 |
| 3.2 `ai.subagent.model` 提升为 system_setting + admin 入口 | `SubAgentProperties`、`AdminConfigController`、`admin.vue` | 新增键 | 单测 + 手测改完不重启即生效 |
| 3.3 子 Agent 超时/预算调参 | `application.yml:198-211`（timeout 180→与 LLM 超时对齐、charBudget 与单文件上限对齐） | 配置默认值变化 | `SubAgentServiceTest` 调整断言 |

### 批次 4：AI PPT（独立，需决策 3）

选项 A（修）：pptx-service 恢复 `model_config` 消费（四个 controller）+ 写进 `UPGRADE_CHECKBA.md` 定制清单 + `PptxTools.buildModelConfig` 改走 ChatModelFactory 口径（读 DB key、支持 AWD_CLOUD 平台密钥）+ 图像模型纳入白名单与单价表。验证：真机 `pptx_generate_presentation` 端到端 + 看 token_usage 是否入账。
选项 B（暂下线）：`PptxTools` 三个 @Tool 下线并改文案，issue 记账。

### 批次 5：死路径清理（最后做，避免与上面冲突）

`/api/ai/chat` 整条（`AiChatController.java:65`、`AiChatService`、`AiAssistantService.getAssistant`、`ProjectAssistant`、`MultiModalContentService`、`api.js:371-386`）、`project-overview.vue` 的旧 AI 面板残留（`:1605-1611`、`:4297-4310`、`:4462-4496`、`:4506-4537`、`:4741-4765`、`:1935-1938`）、`GeminiCacheService`、`AiConfiguration.java:33-71` 两个无消费者 bean。契约影响：删除 `POST /api/ai/chat` 端点（注意 `AiAssistantService.loadAssistants()` 与 `GET /api/ai/assistants` 是**活的**，`AiChatController.java:181-183` → `project-overview.vue:4423`，不能一起删）。验证：`npm run test:app-e2e` + `mvn test` 全绿，`check:emits` 通过。

---

## 五、需要你拍板的决策

**决策 1：GEMINI 供应商与 Google Key —— 下线，还是补齐流式实现？**
- A（推荐）下线，收敛成三档。理由：Gemini 缺的不只是流式，还有 tool calling（`GeminiChatLanguageModel.java:38-44` 静默丢工具规格），而本产品的价值全在 doc_*/sheet_* 原语上；它的全部模型都能通过 OpenRouter 的 `google/*` 拿到，用户体验不降级。成本：一个批次的删除 + 存量迁移。
- B 补齐。成本：写一个完整 provider 适配层（流式 + tool calling + 图像），换来的能力与 OpenRouter 路线重合。

**决策 2：本地 Ollama 保留什么形态？**
- A（推荐）保留但降级为「离线/实验」档：明确只支持 ASK 模式、向导内做连通性与模型探测、模型名/地址给 admin 入口（今天全产品无处可改，`application.yml:136` 是硬编码字面量）。理由：「数据不出本机」对律师是真需求，是差异化卖点，不该因为技术限制悄悄坏掉。
- B 直接下线，只留云端两档。理由：langchain4j 0.36 的流式 Ollama 不支持工具，而不带工具的 Ollama 在本产品里几乎没有用处；保留一个功能残缺的档位会持续产生支持成本。
（这条决定批次 1.2 与 1.4 是否要做。）

**决策 3：AI PPT 是修还是先下线？**
- A（推荐）**先下线 + 改文案，同期排修复**。理由：它现在对所有桌面用户都是坏的（P0-3），而且是一条绕过 Credits 的自带 key 通道——修它必须同时移植 pptx-service 侧的 `model_config` 消费、把图像模型纳入白名单与单价表、并接上记账，工作量不小；留着一个必然报错的工具在工具集里，会消耗模型的轮次和用户的信任。
- B 直接修（批次 4 选项 A）。若走这条，必须把「恢复 model_config」写进 `pptx-service/UPGRADE_CHECKBA.md` 的定制清单，否则下一次 re-vendor 会再丢一次。

**决策 4：区域判据从哪来？**
- A（推荐）官网在 `verify-key`/`ai-usage` 响应里回传按源 IP 判定的 `region`，桌面缓存 + admin 手动开关兜底。最准、有现成通道，代价是一次跨仓契约变更。
- B 桌面本地判（时区 + 系统语言）。零跨仓改动，但 `Asia/Shanghai` 不等于境内网络，且系统语言信号已被 `zetaOfficeBoot.js:232-236` 主动覆盖，要先解开那段 shim（它当年是为修 en-GB 系统拿到英文编辑器加的）。
- C 向导里让用户自己选。最简单、离线可用，但多一步且用户会选错。
（这条决定批次 2.3 的形态与是否需要官网仓同步排期。）

---

## 六、被推翻的假设（避免后续重复踩）

1. **「选了 Gemini 会回落到本机 Ollama」** —— 主路径不会。白名单短路（`ChatModelFactory.java:325`）排在 GEMINI 判定之前，主 AI 面板永远带白名单模型，实际被打到空 key 的 OpenRouter。回落 Ollama 只在**不传 model** 的入口（Office 插件）上真实发生。
2. **「AI PPT 拿着空 OpenRouter key 去 openrouter.ai 401」** —— 这条路径上根本没有请求发往 OpenRouter。pptx-service 全仓不解析 `model_config`，Java 传的 api_key/api_base/text_model/image_model 全是死负载；真实失败点在本机 5001 缺 `GOOGLE_API_KEY`。「改成读 DB key 就能修」是错的，必须两头一起改。
3. **「只有 GeminiChatLanguageModel 能编码图片」** —— 它只是仓内唯一的手写实现。langchain4j 的 OpenAI 与 Ollama 通道库层同样能编 ImageContent。图片进不了模型的原因**纯粹是前端**：`ChatInterface.vue` 的 pastedImages 从不上传，`/api/agent/chat` 的 DTO（`AiAgentController.java:299-330`）连字段都没有；v1 那条路上 `api.js:375-380` 还把 `contexts` 与 `assistantId` 一起丢了。
4. **「Ollama 没视觉能力时发图会降级去 OCR 所以不会送出去」** —— 因果错了。`AiChatService.java:103` 无条件调 `buildMediaContents`，图片分支只看 isImageType 不看模型；「OCR 降级」与「原图直传」是并存的。今天没有图片被送出去，是因为前端丢字段，不是因为能力判定。
5. **「admin 的 Ollama/Gemini 提示词 tab 是不对称缺口」** —— 不是不对称，是这两个 key 对**四条通道全部失效**：唯一读者 `AiChatService.java:180-188` 挂在已死的 v1 上；今天真正生效的 system prompt 由 `ContextAssemblerService.java:426` 拼装，provider 无关、admin 完全无入口。而且那段代码按模型名字符串选 key，`google/gemini-*` 走 OpenRouter 时反而会命中 GEMINI 提示词。
6. **「打包桌面版连环境变量都改不了 Ollama 模型名」** —— 机制上能改：`AiModelProperties` 是 `@ConfigurationProperties(prefix="ai.model")`，desktop 全量传 `process.env`（`backend-service.js:121`），`AI_MODEL_OLLAMA_MODEL_NAME` 走 relaxed binding 生效——这套「无占位符 + env 覆盖」正是 pptx 动态端口在打包态天天在用的现役机制（`backend-service.js:126-128` + `application.yml:65`）。对终端用户仍等于改不了（没有界面能设 env），但绝对化表述不成立。名字是 `AI_MODEL_OLLAMA_MODEL_NAME`，不是 `OLLAMA_MODEL_NAME`（后者只在 prod profile 有效）。
7. **「ElevenLabs 会被向导填 key 搞坏」** —— 有条件。桌面端 Kokoro 已下载时注入 `EXTERNAL_TTS_PROVIDER=local`（`backend-service.js:132-136`），`TtsService.isLocalProvider()` 在入口短路，空 baseUrl 不生效。
8. **「Ollama 非流式支持 tool calling 所以那条路可用」** —— 这个清单漏掉了唯一真会炸的位置。`AgentOrchestrator.java:962` 的三参 `generate` 是 langchain4j core 的 interface default，字节码直接 athrow；而清单里当成缺陷上报的 `AiConfiguration.java:85-87`（provider==OLLAMA 才挂 tools）落在一个**零注入的 bean** 上（`projectAssistant` 全仓无消费者），且打包态 provider=open-router 该分支永不触发。
9. **「AllowedModels 里有模型下线风险」** —— 20 个 id 2026-08-08 全部在线、全部支持 tools、全部不是滚动别名。真实问题是价格漂移与代次落后，不是 404。
10. **「本次联网数据能证明国内直连可用」** —— 不能。本机走了系统代理（`scutil --proxy` HTTPEnable:1，`openrouter.ai` 解析到 198.18.15.199 即 fake-IP 段），拿到的 400 个模型清单不构成「国内直连能列出/调用 Claude/GPT/Gemini」的证据，也无法复现或证伪 403 region。任何国内直连结论都需要在未走代理的国内网络（如北京 ECS 8.137.95.63）用真 key 打一次 `/chat/completions`。
11. **`workdeck.ai` 的 geo 分流不在任何仓库里** —— 全仓 grep `workdeck.ai`、`geo`、`china_ip` 零命中；只存在于新加坡 ECS 的文件系统与你的记忆里。deploy/ 下只有插件云与自建 Web 两份模板。这套逻辑一旦被误改或机器重装就无从恢复，且改它没有 review 门——建议单独立项纳入 `deploy/`。

---

### 还需要真机/跨仓确认的五件事（本次静态排查到不了）

1. 官网 `/api/account/ai-usage` 今天的实际返回（`hasKey` 是否仍存在、是否新增 `creditsCents`）—— 决定 P0-5 在生产上的真实严重度。
2. addin.aiworkdeck.com 上 DB 里 `ai.activeProvider` 是否已是 AWD_CLOUD —— 决定 P0-4 是已发生还是潜在。
3. 桌面正式包里 AI PPT 的确切报错（预期 pptx-service 侧 `ValueError: GOOGLE_API_KEY is required`）。
4. OpenRouter 的区域拒绝实际状态码与响应体文本（决定 2.4 是改分类还是加 message 子类），以及 403 是否会被 OpenRouter 自动 fallback 到其他 provider（文档只说 429/5xx 会）。
5. 个人账号能否创建 Guardrail（OpenAPI 与文档页口径不一致）—— 只在决定走 Guardrail 路线时才需要。