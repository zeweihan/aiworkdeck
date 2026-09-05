# per-user 平台 AI key 设计（server 模式多租户）

- 日期：2026-08-07
- 领域：licensing-billing（编排侧落点见 ai-chat）
- 前置：`2026-08-06-office-addin-and-memory-sync.md` §「后续」第 4 条，本仓 `doc/desktop-contract.md` 待办 2
- 状态：设计待确认 → 确认后实施

## 1. 问题

官方托管的 server 模式实例（插件云后端）里，平台 AI 通道（`Provider.AWD_CLOUD`）仍是**机器级**的：

- `PlatformAiChannel` 把一把 provisioned OpenRouter runtime key 缓存在
  `~/.aiworkdeck/platform-ai-key.json`，整台服务器所有租户共用；
- OpenRouter 的额度上限是 **per-key** 的，一把 key 就是一个额度池——A 用户可以把 B 用户的额度花光；
- `PlatformUsageAccountant` 用「同一把 key 的累计消费差分」记账，多租户下并发轮次的
  cost 会记到别人的 `token_usage` 行上（现有注释里承认的「串位」在单用户下只是明细不准，
  多租户下变成把 A 的钱记在 B 头上）。

目标：每个（已桥接的）用户一把 provisioned key，按用户取、按用户缓存、按 key 隔离对账。

## 2. 现状盘点（代码事实）

| 事实 | 位置 |
|---|---|
| key 缓存是单文件、无用户维度 | `service/ai/PlatformAiChannel.java:45,106-139` |
| 对账 baseline 是**单个** volatile 字段 | `service/ai/PlatformUsageAccountant.java:64` |
| 对账探针用 `platformAiChannel.apiKey()` 拿「那把」key | 同上 `:155-175` |
| 模型实例缓存 key 已含 `keyFingerprint()` | `service/ai/ChatModelFactory.java:224,244` |
| 取不到 key 绝不回落 BYOK（红线） | 同上 `:171-186`、`AgentOrchestrator.java:963-980` |
| `TokenUsageService.recordUsage` 手里**已有 userId** | `service/ai/TokenUsageService.java:36` |
| awdk 桥每次重验官网，awdk_ 明文不落库 | `service/account/AwdkLoginService.java:22-43` |
| 映射键是官网稳定 `accountId` | `model/entity/AccountBinding.java` |
| 官网 `GET /api/account/me` **已返回** `accountId` | 官网仓 `app/api/account/me/route.ts:18`、`doc/desktop-contract.md:58`、`scripts/contract-check.mts:156-159` |
| 官网 `POST /api/account/ai-key` 幂等、已进契约与 contract-check | 官网仓 `app/api/account/ai-key/route.ts`、`scripts/contract-check.mts:221-241` |
| 官网侧 key 明文是 AES-256-GCM 加密入库（`v1:iv:tag:cipher`） | 官网仓 `lib/openrouter-keys.ts` |

顺带发现（本设计的副产物）：本仓 `doc/desktop-contract.md` 的待办条目 1
（「`/api/account/me` 需增加 accountId」）**官网侧已实施并进了权威契约**，按该文件自己的规则应当删除。

## 3. 三个候选方案

共同前提：server 实例不存 awdk_ 明文（PR#268 的既定决策，本设计不推翻）。
差别在于「用什么凭据、在什么时机代表用户去官网取他的 platform key」。

### a) 桥接时即取即存（server 库存 per-user OpenRouter key，awdk_ 仍不落库）

awdk-login 成功的那一刻，server 手里短暂持有该用户的 awdk_，顺手调
`POST /api/account/ai-key` 取到这把用户的 runtime key，加密落 server 库；awdk_ 用完即弃。

- **泄露半径**：server 库里多了 N 把 OpenRouter runtime key。拿到它们能做的**只有**花掉各自
  key 上剩余的额度（上限由 OpenRouter 侧 `limitUsd` 强制），**不能**读官网账户、不能动余额、
  不能签发新 key、不能同步权益、不能买广场付费项——这些都要 awdk_，而 awdk_ 不在库里。
- **吊销路径**：用户在官网账户页禁用/重发 key（`openrouter_keys.disabledAt`）→ 该 key 立即在
  OpenRouter 侧失效 → server 侧的探针（见 §4.6）拿到 401 即删本地行。**不依赖 server 配合**，
  这一点很关键：吊销的权力留在官网侧，被攻破的 server 无法阻止用户止损。
- **离线/过期**：key 在库里，官网不可达也能继续用（与桌面端今天一致）。上界由「30 天未成功验证
  即停用」封顶（§4.6），与 `EntitlementService.OFFLINE_GRACE` 同值同口径。
- **两仓工作量**：官网侧 **零改动**（端点现成、幂等、已进 contract-check）。桌面/server 侧一份改动。
- **弱点**：awdk_ 被吊销 ≠ platform key 被吊销，二者是官网侧两条独立的吊销开关。用户以为
  「我把 Key 撤了就断干净了」但 AI 额度还在被那台 server 用。必须在官网账户页文案上说清，
  或（更好）在官网侧把「吊销 awdk_」与「禁用该账户的 runtime key」做成可勾选的联动——
  这属于官网侧后续优化，不阻塞本次。

### b) 官网新增服务端-服务端凭据（server 注册为受信客户端，凭 accountId 换用户 key）

server 实例持一把长期 `awds_` 服务器凭据（env 注入），调新端点
`POST /api/account/ai-key/for-account {accountId}` 取任意账户的 key。

- **泄露半径**：**最大**。一把 `awds_` 泄露 = 可拉取官网上**任何**账户的 runtime key，包括
  从未用过这台 server 的账户。要收窄就必须再引入「per-account 授权记录」——而建立这条授权记录
  本身仍然需要用户的 awdk_ 走一次桥接，于是安全上界回到与 (a) 相同，却多背了一把长期主密钥。
- **吊销路径**：这是它唯一真正的优势——官网侧可以做「撤销某台 server 对我账户的授权」按钮，
  一键切断，比 (a) 的「去禁用 runtime key」语义更贴用户心智。
- **离线/过期**：server 不必存 key，可每次会话现取、只存内存——库被拖走时零密钥泄露。
  代价是官网不可达时平台通道整体不可用（无宽限可言，除非再把取到的 key 缓存起来，
  那就退化成 (a) 的存储形态）。
- **两仓工作量**：官网侧新表（server 注册 + per-account 授权）、新端点、授权与撤销 UI、
  契约文档 + contract-check 条目、`awds_` 的生成与轮换流程。是 (a) 的数倍。
- **结论**：为「一台自营 server」引入一把可拉取全站密钥的主凭据，是拿更大的爆炸半径换一个
  更好看的吊销按钮。**当出现第二方托管的 server 实例时**（别人部署的插件后端要接我们的账户体系），
  它才成为必需——那时 per-account 授权与撤销是刚需，值得单独立项。

### c) 插件端持 awdk_ 直连官网取 key，server 只透传使用

- **泄露半径**：**把凭据挪到了最不可信的一层**。Office 任务窗格的持久化（localStorage /
  roamingSettings）比 server 库更暴露：roamingSettings 会同步到微软云，任务窗格里一个 XSS
  就能同时拿到 awdk_（不只是 runtime key）。而且 key 最终仍然要进 server 内存才能发请求，
  server 的暴露面一点没减少，只是多了一层。
- **与既有红线冲突**：等于把「account 级凭据不落客户端持久层」这条线在插件侧破了；
  持有 awdk_ 的插件还能直接调官网的余额、权益、断开连接。
- **致命的工程问题**：server 侧的 AI 调用有一半发生在**请求生命周期之外**——编排循环是
  `@Async`、标题生成是 `runAsync`、记忆抽取是 `@Async("memoryExecutor")`、子 Agent 是独立
  executor、PPT 生成是后台任务。「每请求透传」的 key 覆盖不到这些，要覆盖就必须在 server 侧缓存，
  于是又退化成 (a)，只是取 key 的路径更差。
- **结论**：不采纳。

### 比较小结

| | a 桥接即取即存 | b 服务端凭据 | c 插件直连 |
|---|---|---|---|
| server 被攻破的爆炸半径 | N 把 runtime key 的**剩余额度** | 同左（有授权表）/ **全站 key**（无授权表） | 同左，另加插件侧 awdk_ |
| 凭据在最弱一层 | 否 | 否 | **是**（任务窗格存储） |
| 吊销路径 | 官网禁用 runtime key（不依赖 server 配合） | 官网撤销 server 授权（更贴心智） | 官网吊销 awdk_ |
| 离线可用 | 可用，30 天封顶 | 不可用（除非缓存 → 退化为 a） | 不可用 |
| 与 30 天宽限一致 | 一致（复用同口径） | 无宽限概念 | 无 |
| 官网侧工作量 | **零** | 新表 + 新端点 + 撤销 UI + 契约 | 中 |
| 后台线程可用 | 是 | 是 | **否** |

**推荐 (a)**，并在设计里把 (b) 的升级路径留出来：per-user key 的取用被收敛到一个
`PlatformAiKeyService.keyFor(userId)` 出口，将来换成 (b) 只换这个出口的实现，
上层（工厂、对账、身份作用域）一行不动。

## 4. 详细设计（方案 a）

### 4.1 生效范围：只有「已桥接用户」走 per-user，其余一字不动

| 形态 | 平台通道取 key 的路径 |
|---|---|
| local-mode（桌面单机） | **完全不变**：`~/.aiworkdeck/platform-ai-key.json` |
| server 模式 + 当前用户有 `account_binding` | per-user 库记录（本设计新增） |
| server 模式 + 当前用户无 `account_binding`（团队服务器的普通成员） | **不变**：机器级文件 |

不引入新开关：「有没有绑定」本身就是判据，天然是加法改动。团队服务器（没人桥接）行为逐字不变；
官方云后端（注册关闭 + 桥接开启，人人都是桥接用户）自然全部走 per-user。

### 4.2 数据模型

新实体 `PlatformAiKey` → 表 `platform_ai_key`：

| 列 | 说明 |
|---|---|
| `userId` | 唯一约束，指向 server 用户 |
| `keyEnc` | runtime key 明文的 AES-256-GCM 密文，格式 `v1:<ivB64>:<tagB64>:<cipherB64>` |
| `keyFingerprint` | SHA-256(明文) 前 12 位十六进制。模型缓存 key、对账 baseline 的键 |
| `limitUsd` | 官网返回的额度上限，展示用 |
| `fetchedAt` / `lastVerifiedAt` | 取得时间 / 最近一次向 OpenRouter 验证成功的时间 |

不挂在 `account_binding` 上：那张表是纯身份映射、每次桥接登录都要读，
把密文塞进去会改变它的安全等级；分表还让「吊销即删行」不碰身份数据。

**加密**：新增 `service/ai/PlatformAiKeyCipher`，AES-256-GCM，密钥 = SHA-256(secret)，
密文格式与官网 `lib/openrouter-keys.ts` **逐字对齐**（同一形态两侧都好排查）。
secret 取自 `security.platform-key-secret`（env `AWD_PLATFORM_KEY_SECRET`）。

secret 缺失时的行为见 §7 决策点 D1。

### 4.3 取 key 的时机与刷新路径

**主路径（桥接时）**：`AwdkLoginService.login()` 在解析出 user 之后、返回 awdt_ 之前，
用同一枚 in-memory awdk_ 调 `POST /api/account/ai-key`，成功即加密入库（覆盖旧行）。

**取 key 失败一律不拖垮桥接登录**：

| 官网回应 | 处理 |
|---|---|
| 409 `no_allocation` | 正常态（该账户还没从余额分配 AI 额度）。不落库，桥接照常成功 |
| 409 `key_missing` / 503 `not_configured` | 记 warn，不落库，桥接照常成功 |
| 网络不可达 / 5xx | 记 warn，不落库，桥接照常成功 |
| 2xx 但 `openrouterKey` 空 | 记 warn，不落库 |

理由：否则「还没分配额度」的用户连插件都登不进去，而插件的绝大多数能力与 AI 额度无关。

**刷新路径**：用户在官网分配额度/重发 key 之后，server 侧没有 awdk_ 可用来重取。
新增两个**会话级**（不是机器级，不走 `MachineAccountGuard`）端点：

- `GET /api/platform-ai/key/status` → `{available, limitUsd, keyMasked, lastVerifiedAt, stale, bound}`
  —— 供插件/前端展示与引导；
- `POST /api/platform-ai/key/refresh`，body `{accountKey: "awdk_..."}` ——
  用它调官网 `/api/account/me`，**校验返回的 accountId 与当前会话用户的
  `account_binding.external_account_id` 一致**（不一致直接拒绝：否则 A 能把 B 的 Key 贴进来，
  把 B 的额度装到自己名下用），一致则取 key 覆盖入库。awdk_ 用完即弃，不落库。

不复用 `awdk-login` 做刷新：那条路每次都会多签发一枚 awdt_ 设备令牌，令牌会越积越多。

### 4.4 身份作用域：模型工厂怎么知道「这是谁的钱」

`ChatModelFactory.getChatModel(modelId)` 在全仓有 ~12 个调用点，其中
`MemCellExtractor` / `ConversationSummarizer` / `AgenticRetriever` / `AiAssistantService`
的方法签名里根本没有 userId，把 userId 一路穿进去要改动整个 ai-chat 领域的调用链——
与「外科手术式改动」冲突，也会把 AI 编排领域拖进这次计费改造。

采用**显式作用域 + 缺身份即拒绝**：

新增 `service/ai/PlatformAiUserScope`：`ThreadLocal<Long>` + `run(userId, Supplier)` +
`capture()`（跨线程时显式重放）。设置点（每个都在手边就有 userId）：

| 设置点 | userId 来源 |
|---|---|
| `AgentOrchestrator.handleUserMessage(request, userId)` | 方法参数，try/finally 包住整个方法体 |
| 同方法内标题生成的 `CompletableFuture.runAsync`（:368） | 闭包捕获后显式重放 |
| `AiChatService.chat(request, userId)` | 方法参数 |
| `MemoryPipelineService.onConversationTurnCompleted(..., userId, ...)`（`@Async`） | 方法参数 |
| `SubAgentService.dispatch` 的 `executor.submit`（:115） | `parentCtx.userId()`（工具上下文不变式 3） |
| `MatterClassifierService.classifyAsync`（单线程 executor） | 新增 userId 参数，调用点在编排器内 |
| `AutoTaggingService.autoTagFile(..., userId)` | 方法参数 |
| `AiAgentController` PPT 生成的 `runAsync`（:256） | 已有的 `effectiveUserId` |

其余调用点（`AgenticRetriever`、`ConversationSummarizer`、`MemCellExtractor`、
`AiAssistantService`）都是被上面这些同步调用的，继承线程即可。

**不变式（本设计最硬的一条）**：server 模式 + 当前 provider 是 `AWD_CLOUD` + 作用域为空
→ 抛业务错误（中文文案，不含「登录/未授权/请先」三个子串），**绝不回落机器级 key、
绝不回落 BYOK**。漏掉一个传播点的后果是「这条路报错」而不是「这条路记错账」——
错账是静默的，报错是能被测试和冒烟抓到的。

### 4.5 对账按 key 隔离

- `PlatformUsageAccountant.baseline`（单字段）→ `Map<fingerprint, BigDecimal>`。
  用 **key 指纹**而不是 userId 做键：key 轮换后指纹变化，baseline 自动重建，
  不会把两把 key 的累计值之差整个记到下一条消息头上。
- `reconcileAsync(tokenUsageId)` → `reconcileAsync(tokenUsageId, userId)`；
  `TokenUsageService.recordUsage` 手里已经有 userId，调用点零成本。
- `ensureBaselineAsync()` → `ensureBaselineAsync(userId)`；探针 `probeCumulativeUsage(apiKey)`
  用该用户的 key。
- 单线程 worker → **固定 4 线程池 + 按指纹分片**（`Math.floorMod(fingerprint.hashCode(), 4)`），
  同一把 key 仍严格串行（差分正确性不变），不同用户并行。
  现状单线程 + 每轮最多 4×1.5s 轮询，在多租户下会把「待结算」拖成分钟级。
- `resetBaseline()` 保留为「全清」（机器级账户 connect/disconnect 时用），
  新增 `forget(fingerprint)` 供吊销/轮换时精确清除。

现有注释里那条「并发轮次归属可能串位、但总额精确」的取舍**保留**，但作用域从
「整台机器」收敛到「同一个用户自己的并发轮次」——这正是本次要达成的目标。

### 4.6 吊销与过期

对账探针 `GET https://openrouter.ai/api/v1/key` 每轮都会跑，让它兼做验证：

| 探针结果 | 处理 | 口径来源 |
|---|---|---|
| 2xx | 刷新 `lastVerifiedAt` | |
| **401 / 403** | **立即删除该用户的 `platform_ai_key` 行** + `forget(fingerprint)` + 清模型缓存 | 与「官网明确拒绝 → 立刻清缓存、不吃宽限」同源 |
| 网络不可达 / 5xx | 保留，什么都不做 | 与「服务器故障 ≠ 凭据失效」同源 |

外层封顶：`lastVerifiedAt` 超过 **30 天** → 该用户的平台通道判为过期不可用，
提示走刷新端点。常数与 `EntitlementService.OFFLINE_GRACE` / `LicenseService.OFFLINE_GRACE`
同值（30 天），注释里互相指认。理由与权益缓存完全相同：永久离线不能等于永久可用。

取 key 本身**不发网络请求**（沿用现状：库记录命中即用），验证只发生在探针里。

### 4.7 模型实例缓存

`getOrCreatePlatformModel` 的缓存键已经是 `awd_cloud:<fingerprint>:<modelId>`，
per-user 之后指纹天然按用户分叉，**不存在串用风险**。但 `modelCache` / `streamingModelCache`
是无界 `ConcurrentHashMap`，N 用户 × M 模型会无界增长——见 §7 决策点 D2。

`demotePlatformProvider()` 目前在断开机器级账户时把 `ai.activeProvider` 摘下来。
多租户下 `ai.activeProvider` 是全局设置，若还有 per-user key 在用，摘掉会把所有桥接用户一起打断。
改为：**server 模式下存在任一有效 per-user key 时不降级**。

### 4.8 契约与护栏

方案 a 下**官网仓零代码改动**。契约侧要做的：

- 本仓 `doc/desktop-contract.md`：删除待办条目 1（accountId 官网已实施并已进权威契约与
  contract-check，按该文件自己的规则应当移除）；把条目 2 改写为「已实施，方案 a，官网无新增端点」
  并说明 (b) 的触发条件（出现第二方托管 server 实例时）。
- 官网仓 `doc/desktop-contract.md` 的 `POST /api/account/ai-key` 一节补一段**使用方说明**：
  该端点除桌面端外，还被 server 模式实例在桥接登录时代表用户调用；
  `scripts/contract-check.mts` 已覆盖字段与幂等性，无需新增断言。
- 领域文档 `.claude/agents/licensing-billing.md` 同 PR 更新（关键文件地图 + 地雷）。

## 5. 不变式清单（实施与后续改动都不许破）

1. server 模式 + AWD_CLOUD + 无用户作用域 → 报业务错误，**绝不回落机器级 key 或 BYOK**。
2. awdk_ 明文**永不落 server 库**，也永不回给前端/插件。
3. local-mode 的取 key 路径**一字不动**（文件缓存、`isAvailable()` 语义、现有用例全部不改）。
4. 无 `account_binding` 的 server 用户走机器级路径，与今天完全一致。
5. 对账 baseline 只能按 **key 指纹**分桶；换 key 必须换桶。
6. 所有新增用户可见文案不得含「登录」「未授权」「请先」子串。
7. OpenRouter 401/403 立即清 key；网络不可达一律保留。
8. 刷新端点必须校验 awdk_ 对应的 accountId 与会话用户的绑定一致。

## 6. 测试计划（全量，本机 `mvn` 必须 JDK 21）

新增：

- `service/ai/PlatformAiKeyCipherTest`：加解密往返、密文篡改必失败、格式不符必抛、secret 换过必失败。
- `service/ai/PlatformAiKeyServiceTest`：取 key 覆盖入库幂等；409 `no_allocation` / 网络失败
  **不抛异常**；stale 判定（29 天可用 / 31 天不可用）；401 立即删行；文案护栏。
- `service/account/AwdkLoginServiceTest` 增补：取 key 的四种失败都不影响桥接返回 awdt_。
- `service/ai/PlatformUsageAccountantTest` 增补（**本次的核心回归**）：
  两个用户交替消费，各自差分独立、互不串位；换 key 后 baseline 重建。
- `service/ai/ChatModelFactoryTest` 增补：server 模式无作用域 → 抛业务错误且不落到 BYOK 分支；
  两个用户拿到不同实例；有 per-user key 时 `demotePlatformProvider` 不降级。
- `service/ai/PlatformAiUserScopeTest`：嵌套/清理/跨线程显式重放。
- `controller/PlatformAiKeyControllerTest`：status/refresh 的会话鉴权；accountId 不一致必拒。

回归（必须全绿，不得改断言）：`AccountServiceTest`、`EntitlementServiceTest`、
`MachineAccountGuardTest`、`AccountControllerMachineScopeTest`、`LicenseServiceTest`、
`LocalIdentity*`、`LocalModeAccessFilterTest`，以及编排侧 `EvalHarness` 相关用例
（改 `ChatModelFactory`/`PlatformUsageAccountant` 构造器要同步 `EvalHarness`，这条已经踩过两次）。

前端/端到端：`npm run check:emits`、`npm run build:h5`；
若本次动到设置页展示（见 D3），补 `npm run test:app-e2e`。

## 7. 已确认的决策（2026-08-07）

**方案选型**：走 (a)。(b) 的触发条件（出现第二方托管的 server 实例）写进契约文档备查。

**D1：`AWD_PLATFORM_KEY_SECRET` 缺失 → 启动即失败**，仅在
`security.awdk-login-enabled=true`（即官方云后端形态）时生效，给出精确配置提示。
明文兜底是典型的「潜伏逃生门」（`cors.allow-all` 那类），不留。
local-mode 与未开桥接的团队服务器不受影响——它们根本不走 per-user 路径。

**D2：模型实例缓存本次一并有界化**。access-order `LinkedHashMap`，上限 64 条。
per-user 化正是把它从 O(模型数) 变成 O(用户数×模型数) 的那次改动。

**D3：额度面板一起做**。后端 `status` / `refresh` 两个端点 + 插件设置页的「AI 额度」卡片
（上限 / 已用 / 剩余 / 最近验证时间 / 刷新入口）。已用与剩余取自 OpenRouter
`GET /api/v1/key`（server 侧代查，key 明文不出后端）。

**D4：本仓 `doc/desktop-contract.md` 待办条目 1 本 PR 顺手删除**（accountId 官网已实施
并已进权威契约与 contract-check）。

## 8. 实施记录（2026-08-07）

新增：`service/ai/PlatformAiKeyService`、`PlatformAiKeyCipher`、`PlatformAiUserScope`、
`model/entity/PlatformAiKey` + repository、`controller/PlatformAiKeyController`；
插件设置页「AI 额度」卡片 + `taskpane/lib/api.js` 两个函数。

改动：`PlatformAiChannel`（四分支路由 + `availableFor/resolveFor/onKeyRejected/onKeyVerified/hasPerUserKeys`）、
`PlatformUsageAccountant`（指纹分桶 + 4 分片 worker + 401 作废 + `probeUsageForDisplay`）、
`ChatModelFactory`（作用域取 key、缓存有界化、降级守卫）、`TokenUsageService`（对账带 userId）、
`AccountService`（`fetchProfileWith` / `fetchAiKeyWith` 两个显式给 Key 的重载）、
`AwdkLoginService`（桥接后 `tryProvision`）、`AiChatController#getAiConfig`（可用性按人算）、
以及 8 处身份作用域设置点。

与设计的偏差：无。两处实现细节值得记：

1. `provision/evict/markVerified` 上**没有** `@Transactional`——它们都是单仓储操作，
   而 `tryProvision`/`refresh` 是同类自调用（代理不生效），标了反而给出假保证。
2. 「多租户下缺身份即拒绝」的判据用 `multiTenant()`（本实例存在任一绑定）而不是「非 local-mode」。
   后者会让一台谁都没桥接过的团队服务器因为某处漏传身份就被打断；前者把严格判据
   只落在真正的多租户实例上，团队服务器的机器级路径逐字不变。

验证：`mvn test` 1142 项全绿（新增 51 项）；官网仓 `contract-check` 26 项全绿（该侧仅文档改动）；
`office-addin` 构建通过。`frontend/` 一行未改，故未跑该目录的 e2e。

**部署动作（必做，否则官方云后端起不来）**：给插件云后端加环境变量
`AWD_PLATFORM_KEY_SECRET`（任意高熵字符串，与官网的 `AWD_KEY_ENCRYPTION_SECRET` 是两把无关的密钥）。
这是 D1 有意选择的启动强不变式：`security.awdk-login-enabled=true` 且缺该变量时拒绝启动。

## 9. 明确不做

- 不引入官网服务端-服务端凭据（方案 b）。触发条件写进契约文档：**出现第二方托管的 server 实例时**再立项。
- 不改 `langchain4j` 版本、不改 `usage.cost` 的读取路径（对账口径沿用差分法）。
- 不动 local-mode 的任何行为。
- 不动 `MachineAccountGuard` 的 admin 口径：`account.json` / `entitlements.json` 仍是机器级状态，
  本设计新增的是**并行**的一条 per-user 通道，不是替换。
- 不做 per-user 的 entitlement（权益仍是机器级；插件云后端的付费闸门在官网侧）。
