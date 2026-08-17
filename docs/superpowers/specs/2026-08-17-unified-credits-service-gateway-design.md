# 外部服务统一 Credits 计费（平台服务网关）设计

日期：2026-08-17（初稿）／2026-08-17 修订一（四视角审阅后）
状态：设计已确认，待实施
涉及仓库：`checkba_cloud`（桌面端 Spring + uni-app）、`aiworkdeckweb`（官网 Next.js，Credits 账本所在）
相关领域文档：`.claude/agents/licensing-billing.md`（授权与计费）、`.claude/agents/utility-tools.md`（OCR/搜索/语音）、`.claude/agents/ai-chat.md`（AI 通道）

---

## 1. 背景与目标

今天用户要用全功能，得自己去 8 家供应商开账号、把 23 个字段填进产品：

| 服务 | 字段数 | 配置位置 |
|---|---|---|
| 会议录音转写（通义听悟 + 阿里云 OSS） | 5（AK / SK / AppKey / Bucket / Endpoint） | 系统管理，首启向导里根本没有 |
| OCR（阿里云） | 5（AK / SK / Endpoint / RegionId / PublicBaseUrl） | 首启向导 步骤 2 |
| 企查查 | 2（key / secret） | 首启向导 步骤 2 |
| 北大法宝 | 1（token，同时喂 3 个 MCP server） | 首启向导 步骤 2 |
| 博查搜索 | 1（apiKey） | 系统管理 |
| ElevenLabs TTS | 4（apiKey / baseUrl / modelId / voiceId） | 首启向导 步骤 2 |
| Tushare | 1（token） | 首启向导 步骤 2 |
| AI（OpenRouter） | 2（apiKey / baseUrl） | 已有平台通道可免配 |

八家里**只有 AI 一条已经解决**：官网按 Credits 余额派生额度、自动签发 per-user OpenRouter key、
`PlatformUsageAccountant` 事后差分对账。其余七家全是纯 BYOK，与 Credits 完全无关。

**目标**：用户只需要在官网拿一把 `awdk_` Key 填进产品，其余全部由我们配好；
所有外部服务产生的成本折算成 Credits，从同一个余额里扣。

**成功判据**（修订一改写）：全新安装 → 解锁 → **连接账户且账户有 Credits 余额** →
首启向导只需选 AI 通道 → 语音转写、OCR、搜索、企业数据全部可用，用户没有填过任何第三方 Key；
账户页能看到每项服务花了多少 Credits。

初稿写的是「解锁 → 全部可用」，那是错的：解锁有试用码与账户 Key 两条路，
试用码那条不产生任何 `awdk_`；而且 `lib/users.ts` 的 `createUser` 不发放任何 Credits，
`grantCredits` 只出现在充值、兑换码、AI 分配、购买退款四条路——**新注册账户余额恒为 0**。
「未连账户」与「零余额」是两种必须有明确产品形态的常态，见 §7.1。

---

## 2. 已确认决策

| # | 决策 | Why |
|---|---|---|
| D1 | **8 家全部由公司统一代采**，含企查查与北大法宝 | 维护者的商业选择。风险见 §9——这两家是机构订阅制，按量分销需要单独授权，**合同没谈成之前 `enabled=false`** |
| D2 | **会议录音：云端听悟为主 + 本地模型作隐私选项** | 云端质量好、有说话人分离；本地档给敏感案件一条「录音不出本机」的出路。两条都零配置 |
| D3 | **本轮全量交付**：网关框架 + 8 家全接 + 本地 ASR，分六批 PR | 只做一条链路解决不了「配置太夸张」这个用户可感知的问题 |
| D4 | **加价率按服务分档**，不是全局一档 | 各家成本结构差异大（企查查按次昂贵、听悟按秒便宜），一档会让某些服务亏本或贵得离谱 |
| **D5** | **平台网关只在 local-mode 开放**；非 local-mode（团队自建服务器、`addin.aiworkdeck.com` 云实例）恒 `byok` | 修订一新增，理由见 §5.3。这是本轮的明确**非目标**，不是遗漏 |
| **D6** | **新用户赠额待维护者决定**：代码支持（admin 可配 `signupGrantCents`，落 `reward` 批次），**默认 0 = 不赠** | 修订一新增。花钱的决定不由实现方替维护者做；但没有它「解锁即可用」就不成立，所以能力先留好 |

---

## 3. 架构：三条通路

八家的技术约束不同，硬塞进一条通路会坏事。

### 通路 A — 凭证下发，桌面直连（仅 AI/OpenRouter，已存在，本轮不动）

官网按 Credits 余额派生 limit → 签发 per-user OpenRouter key → **桌面从用户本机直连 openrouter.ai**。

**不能改成网关代理。** 领域文档里那条红线是硬的：桌面端所有 OpenRouter 请求必须从用户本机出口发出，
能用哪些模型由用户的出口 IP 决定、由 OpenRouter 运行时返 403。改成从北京 ECS 代理，
第一步就连不上，而且会把全体用户的模型可用性锁死在我们机房的地理位置上。

计费沿用 `PlatformUsageAccountant` 的事后差分对账，一字不改。

### 通路 B — 网关代理（其余 7 家）

```
桌面（Bearer awdk_） → POST {site}/api/gateway/{service}/{op} → 官网持真实凭证调上游
                     ← 结果 + 本次扣了多少 Credits
```

**为什么不能照抄通路 A**：这 7 家没有一家支持「给每个终端用户签发带限额的子密钥」。
凭证一旦落到用户机器上就是明文可提取的共享密钥——等于把公司的企查查账号发给所有人，
一个人写脚本就能把年度调用额度刷干。OpenRouter 能走 A，是因为它的 per-key limit 由上游强制执行。

### 通路 C — 本地模型（本地 ASR 新增；本地 TTS Kokoro 已存在）

零成本、零出网。作为隐私档，不是默认档。

### 网关落在官网 Next.js，不落 addin 的 Spring

**修订一：初稿写的理由「扣费必须与 Credits 同事务」是错的。**
`lib/db.ts` 的 `withTransaction` 注释逐字写着「better-sqlite3 事务是同步的，fn 里不要 await」，
并且用 `.immediate()` 直接拿写锁——异步的上游 HTTP 调用永远进不了这个事务。
真实序列必然是 `await 上游()` → `withTransaction(spendCredits)`。

**正确的理由是窗口大小的量级差**：网关在 Next.js 里，「上游返回 → 同进程同步事务扣费」之间
只有进程崩溃能打断；放在 Spring 里则多一次跨机 HTTP 调用官网的扣费端点，而网络失败是常态事件。
两者都不是原子的，但前者的失败窗口是「进程崩溃」，后者是「一次网络往返」，差好几个数量级。
加上 §4.7 的幂等键，前者的残留窗口可以收敛到可接受；后者做不到。

**代价：大对象不能进 Next.js 进程。** 语音这条因此做控制面 / 数据面分离，见 §6.1。
其余服务的请求体上限 **10 MB**（OCR 逐页 PNG 单页远小于此；超限直接 413，不静默截断）。

### 已知的规模上限

`withTransaction` 用 `.immediate()`，是排他写锁——网关每次扣费都要串行地拿一次。
对当前规模（数百用户、人均日调用个位数）没有问题。
**触发重新设计的阈值**：`data/awd.db` 的写事务出现可观测的排队，或网关 P99 延迟中数据库等待占比超过 20%。
届时的出路是把 ledger 写入改成追加日志 + 异步归并，不在本轮范围。

---

## 4. 官网侧：平台服务网关

### 4.1 端点形态

| 端点 | 鉴权 | 幂等键 | 说明 |
|---|---|---|---|
| `POST /api/gateway/{service}/{op}` | Bearer `awdk_` | **必需** | 通用代理入口。`service` ∈ {ocr, search, tts, qichacha, tushare, pkulaw} |
| `POST /api/gateway/asr/ticket` | Bearer `awdk_` | 不需要（不扣费） | 查余额、签发 OSS 直传凭证 |
| `POST /api/gateway/asr/submit` | Bearer `awdk_` | **必需** | 预扣 + 建听悟任务 |
| `GET /api/gateway/asr/task/{id}` | Bearer `awdk_` | 不适用 | 轮询 + 完成时结算 + 删 OSS 对象 |
| `GET /api/gateway/pricing` | Bearer `awdk_` | 不适用 | 单价表（桌面端展示用；**不作为扣费依据**）。桌面端加 10 分钟 TTL 缓存 |

鉴权复用 `lib/account-auth.ts` 的 `resolveKeyUser(request)`，与 `ai-key` / `ai-usage` 同一条路。

### 4.2 单价表：服务端权威，客户端从不传价格也不传计量

新增表 `service_pricing`，admin 可在线调，改价不发版：

| 列 | 说明 |
|---|---|
| `service` | `asr` / `ocr` / `search` / `tts` / `qichacha` / `tushare` / `pkulaw` |
| `op` | 同一服务的分档（企查查的贵接口单列一行）；`*` 表示该服务全部操作 |
| `unit` | `minute` / `page` / `call` / `kchar` |
| `costCentsPerUnit` | 我们的采购成本（对账用，不直接计价） |
| `marginMultiplier` | **按服务分档的加价率**（D4）。沿用 `ai-usage` route 已有的字段名 |
| `creditsPerUnit` | 实际计价 = `round(costCentsPerUnit × marginMultiplier)`，落库冗余一份避免每次现算 |
| `maxUnitsPerCall` | **单次调用的计量硬上限**（修订一新增）。客户端申报值超过它直接 400，不放行了再兜底 |
| `enabled` | 供应商未开通/合同未谈成时置 false，网关回 `service_disabled` |

**两条并列的红线：**

- **客户端永远不传价格。** 同广场付费项那条：不信前端传来的 `priceCents`，
  等于让客户端决定闸门何时生效。`GET /api/gateway/pricing` 只供 UI 展示。
- **客户端申报的计量数量同样不可信**（修订一新增）。
  `/asr/ticket` 与 `/asr/submit` 的 `durationSec` 都是桌面端算出来的，
  转码取时长失败会回落、也可以被直接改。**计价数量一律以上游返回的真实计量为准**，
  客户端申报值只用于预扣估算与余额闸，且受 `maxUnitsPerCall` 约束。

### 4.3 扣费规则

**两种时机，按单次估算金额分：**

- **事后扣**（默认）：调用成功后 `spendCredits` 一笔。失败不扣。
- **预扣 → 结算**（单次估算成本超阈值）：见 §4.4。
  阈值 `HOLD_THRESHOLD_CENTS` 是 `service_pricing` 的同级配置项，初值取「一次典型 AI 对话」的量级；
  今天只有 `asr`（按分钟）与超长文本 `tts`（按千字符）会越过它，但**判据是金额不是服务名**——
  写死服务名的话，以后单价一调就会出现「一笔很贵的调用没预扣」。

**六条硬规则：**

1. **失败必退，且必须原批次原额。**
   上游 5xx / 超时 / 我们自己崩了 → `refundSpend(spendLedgerId)`。
   **绝不「简单再发一笔」**——那会把 reward 洗成可退现的 topup，
   整套「创作者收益不可提现」的合规设计直接落空。护栏测试必须覆盖。
2. **余额不足绝不用 401/403。**
   沿用 `ai-key` 的既有形态：`409 + {error:'no_credits', message:...}`。
   桌面端把 4xx 判为凭据失效，401/403 会立刻清空已购权益缓存。
3. **文案不许含「登录」「未授权」「请先」三个子串。**
   `frontend/src/services/api.js` 拿它们判掉线并清会话，
   「余额不足请先充值」正好三个全踩中。护栏抄 `AccountServiceTest` 的写法。
4. **上游查不到用量时回 `200 + usageAvailable:false`**，桌面端显示「—」不是 0。
5. **（修订一）任何扣费成功之后必须调 `syncAiQuota(userId)` 降额；任何退款之后必须再调一次提回去。**
   形态照 `app/api/account/purchase/route.ts:53` 的既有写法（`void syncAiQuota(user.id).catch(() => undefined)`）。
   理由见 §4.6。
6. **（修订一）错误码族必须分成三类**，桌面端据此给不同文案，见 §4.8。

### 4.4 预扣的三行账法与结算的三条路径

`lib/credits.ts` 的 `refundSpend` 按原流水行的 `meta.lots` 明细**全额**退回，没有部分退款能力。
**不改它**——那是核心记账，动它的风险远大于多两行流水。

于是预扣走三行，全部带同一个 `meta.taskId`：

```
① hold    spendCredits(估算额, kind='service_spend', meta={service, phase:'hold',    taskId})
② release refundSpend(①的 ledgerId, {ledgerKind:'adjust', meta:{phase:'hold_release', taskId}})
③ settle  spendCredits(真实额, kind='service_spend', meta={service, phase:'settle',  taskId})
```

**②③ 必须在同一个 `withTransaction` 里**（修订一明确）。分开写的话，② 成功而 ③ 失败
就是「退了钱、服务白干、分文未收」。

账户页按 `meta.taskId` 折叠成一行展示（「会议转写 42 分钟 −N Credits」），展开才看到三行明细。

**结算的三条路径**（修订一：初稿只有前两条）：

| 情形 | 动作 |
|---|---|
| 真实计量 ≤ 预扣估算 | ②③ 同事务，用户按真实量付费 |
| 任务中途失败 | 只做 ②。用户不花一分钱 |
| **真实计量 > 预扣估算，且余额不足补差** | 沿用 `lib/ai-quota.ts` 的 `settleUsage` 既有口径：**扣到 0，绝不把余额扣成负数，绝不回滚整笔结算**；差额写一条 `admin_audit` 的 `gateway_spend_shortfall`；该用户后续网关调用置为拒绝直到补齐 |

第三条不需要恶意也会触发：hold 90 分钟之后，用户在转写期间把余额花在别处，结算 95 分钟即撞上。

### 4.5 hold 的服务端超时回收（修订一新增）

初稿把 ②③ 全部挂在 `GET /api/gateway/asr/task/{id}` 这条**桌面主动轮询**上。
用户合盖笔记本、退出产品、换台机器办公——这三个动作都会让 hold 永久挂账：
余额凭空少一块，账户页只显示一行「会议转写 −N Credits」却没有转写结果，
客服除了手工 `adjust` 没有任何自愈手段。

对照之下，同一份文档给 OSS 对象设计了「代码删 + 生命周期规则」两道兜底，
理由正是「靠代码记得删是会漏的」——对真金白银的 hold 反而只有一道客户端路径，说不通。

**补：**
- hold 流水的 meta 带 `holdExpiresAt` 与 `lastPolledAt`。
- 官网侧挂扫描任务（与 `reconcileAllQuotas` 同一条 cron）：对超过 `holdExpiresAt` 仍未结算的 hold
  主动查听悟任务终态——已完成按真实时长走 ②③，失败或查不到则只走 ② 释放。
- 桌面端必须把 `taskId` 落库（`MeetingRecording` 实体加列），**重启后恢复轮询**。

### 4.6 Credits 与 AI 额度共池：双向同步（修订一新增）

官网的 AI 额度是 `limit = 远端已用量 + 折算(当前余额)`，**只在 `syncAiQuota()` 被调用时才重设**。
既有的非 AI 扣费路径已经建立了配套动作：`purchase/route.ts` 在 `spendCredits` 之后紧跟
`void syncAiQuota(user.id).catch(() => undefined)`，注释原文是「花掉 Credits 后立刻把 AI 上限降下来」。

不调这个钩子，**同一笔余额可以被网关和 AI 各花一遍**：
用户余额 100，AI limit 已按 100 派生；跑一场转写扣掉 90，余额剩 10 但 key 的 limit 一动不动；
他接着把 AI 额度花光，回抄时 `takeCents = Math.min(owedCents, available)` 只能扣到 10，
剩下 80 写进 `ai_usage_shortfall` 由平台吃掉。
语音按分钟计价意味着单笔可以很大且高频，这个窗口会从偶发抖动变成一条稳定的白嫖路径。

**hold 期间对 AI 额度的口径（必须表态）**：hold 直接压低余额 → `syncAiQuota` 下调 OpenRouter limit →
桌面端 `PlatformCreditsGate` 确知为 0 就拦住 AI 对话。
**这是接受的行为**（钱确实被占住了，不该允许再花一遍），但**必须可解释**：
账户页与 AI 面板在有未结算 hold 时显示「N Credits 正被一项转写任务占用，完成后按实际用量结算」。
否则用户同时发现转写和对话都坏了，不知道是转写吃掉的。

### 4.7 幂等（修订一新增）

会扣费的 POST **必须带 `Idempotency-Key`**（桌面端生成 UUID，同一次用户动作的全部重试沿用同一个），
缺失直接 400。

官网侧建 `gateway_request` 表，`(userId, idempotencyKey)` 唯一索引，记结果摘要与 `spendLedgerId`：
- 命中**已完成**的键 → 直接回放原响应，不重调上游、不重扣费。
- 命中**在途**的键 → 409 `in_flight`。

形态直接沿用 `lib/orders.ts` 已有的 `idempotencyKey` / `findOrderByIdempotencyKey` /
`markOrderPaid` 的 `alreadyPaid` 语义，不发明新的。

**为什么必须有**：§4.3 规则 1 只覆盖「网关自己观测到的上游失败」。
「客户端超时而服务端已扣费成功」是另一侧，而 OCR / TTS / 听悟建任务超过 5 秒是常态
（见 §5.1 的超时口径）。用户点一次重试就是再扣一次钱、再向上游付一次费，两笔在日志里都是成功。

### 4.8 错误码族（修订一新增）

三类必须分开，桌面端据此给不同文案与不同的下一步：

| code | 含义 | 用户侧文案要点 |
|---|---|---|
| `service_disabled` | `service_pricing.enabled=false`，产品尚未提供 | 「未开放」，不是故障；给「改用自己的 Key」入口 |
| `upstream_failed` | 上游供应商挂了/超时 | 只说该服务暂时不可用，其余服务不受影响 |
| `gateway_unreachable` | 我们的服务器不可达 | **明确说这不是用户的网络问题**；给「改用自己的 Key」入口 |
| `no_credits` | 余额不足 | 指向充值；不含三个禁用子串 |
| `not_connected` | 本机未连接账户 | 指向设置页连接账户 |

`AccountException.Kind` 今天把 5xx 一律归 `NETWORK`，文案是「无法连接 AI Workdeck 服务器，
请检查网络后重试」——把我们的故障说成用户的网络问题。网关**必须能表达这三类**，
新增 `Kind.SERVICE_DISABLED` / `Kind.UPSTREAM_FAILED` / `Kind.NO_CREDITS`
（`NOT_CONNECTED` 已有，直接复用）。

### 4.9 任务级花费上限（修订一新增）

网关按 `meta.taskId` 累计已花费，超过用户可配阈值时回 `budget_exceeded`——
这是一个**可恢复的**信封，桌面端弹「本次任务已花费 N Credits，是否继续」，而不是直接失败。

这是与记账时机无关的**用户闸**：`HOLD_THRESHOLD_CENTS` 管的是预扣与否，不是用户闸门。
刻意**不做「每次调用前弹确认」**——与「零配置、少打扰」的产品目标冲突。

### 4.10 ledger 只加一个 kind

新增 `service_spend`，**服务名进 `meta.service`**。

不给每家开一个 kind：kind 是开放枚举，但每加一个都要同步改三处——
`scripts/contract-check.mts` 的 `KINDS`、`doc/desktop-contract.md` 的表、词典的 `accountPage.kinds`。
服务会一直加下去，用 meta 区分才不会每接一家就动一次契约。

`meta` 约定字段：`{service, op, taskId?, phase?, units, unit}`。

### 4.11 契约同步（每批必做）

改端点必须同时改 `doc/desktop-contract.md`（人读版）与 `scripts/contract-check.mts`（机器可执行版）。
`ai-usage` 就是反例：它是唯一一条两处都没收录的端点，改它两侧至今没有任何护栏提醒。
网关是全新端点族，**从第一批就把两处建起来**。

---

## 5. 桌面侧：一个客户端，八个双档开关

### 5.1 唯一出站缝

新增 `backend/src/main/java/com/checkba/service/platform/PlatformGatewayClient.java`：

- 照 `service/account/AccountTransport` + `HttpAccountTransport` 的模式做成接口 + 实现，
  单测打桩不依赖网络。
- **超时不得沿用 `AccountTransport` 的 5 秒**（那个类 `TIMEOUT = Duration.ofSeconds(5)`，
  连接与响应共用）。网关按服务给上限：`search` 30s、`ocr` 60s、`tts` 60s、
  `qichacha`/`tushare`/`pkulaw` 30s、`asr/submit` 30s、`asr/task` 15s。
  超时后**只能带同一幂等键重发**。
- 固定 HTTP/1.1（JDK HttpClient 默认 HTTP_2，对明文回环地址会先发 h2c 升级，
  Next 开发服务器收到后不回字节，上层只看到「无法连接服务器」——本地联调必踩）。
- 地址复用 `SiteProfileService.baseUrl()`（双主站按站点走），协议校验复用 `AccountEndpoint`。
- **凭据来源：机器级 `AccountService.currentKeyOrNull()`，且只在 local-mode 使用**（见 §5.3）。
  为 null 时**不发出请求**，直接回 `Kind.NOT_CONNECTED`（区别于网络故障）。
- 错误映射见 §4.8。

### 5.2 每服务双档

与 `TtsService` 现有的 `elevenlabs | local` **完全同构**，不发明新形态：

```
external.<service>.provider = platform | byok      （asr / tts 多一档 local）
```

新装默认 `platform`。**BYOK 保留但从向导挪走**——团队自建服务器、离线部署、
以及自有企查查/法宝订阅的律所都需要它，删掉是回归。

写入口仍是 `AdminConfigController.toSettingsUpdates` 一处，取值非法直接 400（不静默回落）。
注意该方法**跳过 null 字段**是有意的：曾经把 env 提供的 baseUrl 清成空串，
受害者正是 QichachaService / TushareService / TtsService 这三个。

#### 5.2.1 存量迁移：必须显式回填（修订一新增，与档位框架同批合入）

`SystemSettingService.get(key, default)` 只在 `system_setting` 行不存在时回落 yml 默认值。
存量库里根本没有 `external.<service>.provider` 这一行，**升级后一律静默取新默认值 `platform`**——
而用户填过的 `external.qichacha.key` / `external.tushare.token` / `external.aliyunOcr.*` /
`external.pkulaw.token` / `external.bocha.apiKey` / `external.elevenlabs.*` 一个都没丢，
就在库里躺着却不再被用。

这与 §5.2 和红线 6 自己写的「保留 BYOK 是给自有订阅的律所用的」直接冲突，两类用户都被搞坏：

- 自带阿里云 OCR / Tushare 订阅的律所：同一次调用从「花已经付过的订阅」变成「扣 Credits」，
  **为同一项服务付两遍钱**，且没有任何提示。
- 从未连过账户的存量用户：OCR 与搜索从「昨天好好的」变成「余额不足」，
  他的结论是新版本弄坏了功能。

**回填规则**（照 `DataInitializer` 写 `system.wizard.completed` 的做法，仅在标记不存在时执行一次）：

```
对每个服务 s：
  若 s 的 BYOK 关键字段已有非空有效值 → 显式写入 external.s.provider = byok（写库，不靠默认值）
  否则                               → 写入 external.s.provider = platform
```

「平台服务」面板对被回填成 `byok` 的服务显示一次性可关闭的提示
「可切换到平台代采，无需自备 Key」，把选择权交回用户。

### 5.3 平台网关只在 local-mode 开放（D5）

**初稿写「直接搬 `PlatformAiChannel.resolveOrThrow` 四分支」是搬不过来的。**
`PlatformAiKeyService` 的类注释是硬约束：`awdk_` 明文永不落库，server 只在桥接/刷新那一刻
短暂持有它，换成一把 OpenRouter runtime key 存下来。`PlatformAiUserScope` 装的是 `Long userId`，
它能告诉你「记谁的账」，但给不出一个能打通网关的 Bearer 凭据。
通路 A 能做 per-user 是因为上游 OpenRouter 支持签发子密钥，而 §3 已论证其余 7 家没有这个能力。

**本轮的网关路由表（不是 AI 那张）：**

| 形态 | 网关 |
|---|---|
| local-mode（个人桌面版） | 机器级 `awdk_`，平台档可用 |
| server + 团队自建服务器 | **恒 `byok`**，平台档在 UI 上不可选，提示「团队服务器请使用自备 Key」 |
| server + `addin.aiworkdeck.com` 云实例（多租户） | **恒 `byok`**，与今天完全一致（该实例的 bocha 等 key 本来就是机器级配置） |

**为什么选这条而不是「官网签发网关子 Key」**：后者要新建一套与 `platform-ai-key` 同规格的
加密落库 + owner 指纹 + 吊销 + 宽限机制，是独立一期的工作量；而多租户实例今天的外部服务
本来就是机器级共账，选 `byok` 是**零变化**，不引入任何新的白嫖路径。
个人桌面版（local-mode）覆盖了本轮要解决的全部目标用户。

Office 插件（`office-addin/`）走的正是 `addin.aiworkdeck.com`，因此它的搜索/企业数据能力
**与今天一字不变**，不因本轮改造增减。这是有意的非目标，写进 §8。

### 5.4 余额闸

复用 `PlatformCreditsGate` 的三条判据，不另起炉灶：
① 只管机器级路径（在 D5 之下，网关路径恒为机器级，这条自然满足）；
② **确知为 0 才拦**（网络失败 / 端点缺失 / 字段缺失一律放行，且不保留上一次的 0）；
③ 首次同步、之后后台刷新（60 秒保鲜）。

这道闸放行后请求照打、官网自己回 409 `no_credits` 兜底——它是省一次往返的优化，不是承重结构。

### 5.5 platform 档下的非 Java 出站路径（修订一新增）

企查查与 Tushare **不只有 Java service 一条出站路径**：`PythonTools` 把
`TUSHARE_TOKEN` / `QICHACHA_KEY` / `QICHACHA_SECRET` 作为环境变量注入 Python 子进程，
由 AI 写的脚本从用户本机直连上游。platform 档下没有可注入的凭证。

**口径**：platform 档下 `PythonTools` **不注入**这三个变量；
同时在 P4 为企查查与 Tushare 增加 **Java 侧一等工具**（`qichacha_query` / `tushare_query`），
走 `PlatformGatewayClient`。AI 不再需要写脚本去查这两家。

不这么做的后果：新用户在 platform 档问「查一下这家公司的财务数据」，
脚本拿到空 token，失败会表现成「查不到数据」而不是「未配置」——正好打在 §1 的成功判据上。
这条路径也是唯一一条 AI 能循环打出几百次上游调用的口子，收进 Java 侧后才受 §4.9 的任务级上限约束。

---

## 6. 语音转写

### 6.1 云端路：控制面 / 数据面分离

```
桌面 → POST /api/gateway/asr/ticket   {durationSec, format}
     ← 官网查余额（不够则 409 no_credits，此时用户还没上传）
     ← 限路径 / 限大小 / 限有效期的 OSS 直传凭证 + estimatedCredits + balanceAfterCents
桌面 → 直传我们的 OSS                  （几百 MB 不经过 Next.js）
桌面 → POST /api/gateway/asr/submit   {objectKey, durationSec, Idempotency-Key}
     ← 官网按估算时长预扣（§4.4 的 ①）、建听悟任务、返回 taskId
桌面 → GET /api/gateway/asr/task/{id} 轮询（taskId 已落库，重启后恢复）
     ← 完成：按真实时长结算（②③ 同事务）、删除 OSS 对象、回转写结果
```

**余额闸放在 ticket 那一步**，不让用户白传两小时录音才被拒。
`ticket` 响应带 `estimatedCredits`，超过 `HOLD_THRESHOLD_CENTS` 时桌面端在提交前显示确认。

`durationSec` 受 `maxUnitsPerCall` 约束（见 §4.2）；OSS 直传凭证的大小上限必须与它挂钩，
否则申报 1 分钟传两小时的音频就绕过了余额闸。

桌面端保留现有的转码（`MeetingAudioTranscoder`）与 poll-on-read 节流。
`MeetingTranscriptionService` 在 `platform` 档下走 `PlatformGatewayClient`，
**完全不调用 `MeetingOssClient` / `TingwuClient`**（那两个接口的实现留给 `byok` 档，一字不动）——
分档发生在编排层，不是在这两个接口内部改实现，否则两条路的失败语义会纠缠在一起。

**OSS 对象生命周期**：转写完成或任务失败即删；另配 bucket 生命周期规则兜底（24 小时自动清）。
两道都要，靠代码记得删是会漏的。

### 6.2 本地路

新增根级 `asr-service/`（与 `kokoro-service/`、`mineru-service/` 同级），
whisper.cpp 或 faster-whisper，OpenAI 兼容 `POST /v1/audio/transcriptions` 接口位——
这样 `VoiceTranscriptionService` 现有的接口位可以直接复用。

打包：进 pysvc 单包，与 Kokoro / MinerU 同一条路（**新服务定位一律走 `pysvcPath()`**）。
模型走「组件管理」按需下载，不塞进安装包。

#### 6.2.1 开关的就绪判定（修订一新增，P3 内视为阻塞项）

「录音不出本机」开关照**地雷 15 的 Ollama 探测范式**：
切换时就地探一次（asr-service 起没起、模型在不在），未就绪时**开关不允许留在打开态**，
就地给「下载模型（约 N MB）」按钮与进度，下载完成才真正生效。

会议面板在**录音开始前**就显示当前档位与就绪状态，不要拖到转写那一刻才暴露。

**为什么这条是阻塞项**：律师打开这个开关的唯一理由是这场谈话绝对不能出网。
录完两小时才发现要下一个几百 MB 的模型、而现场没网——他只剩两个选择：
放弃这份录音的转写，或者关掉开关把刚才特意保护的内容传上云。
后者与他打开开关的目的完全相反，这不是体验差，是把用户推回他主动规避掉的合规风险里。

**已知取舍**：本地档没有说话人分离。会议纪要里「谁说了什么」要靠 AI 事后推测，
会见笔录场景质量明显下降。UI 上必须写明，不能让用户以为两档等价。

---

## 7. 配置面收敛（用户可感知的交付物）

- **首启向导步骤 2 换成「连接账户 + 平台服务总览」**，**不是净删除**（修订一改）。
  原来的 OCR / 语音 / 企业数据三组配置字段全部撤走；新的步骤 2 显示 8 项服务的状态卡片，
  未连账户时每张卡片显示「需要连接账户」并**就地**给连接入口
  （照地雷 15：向导里每条「下一步」都必须能在向导里做完）。
- **系统管理新增「平台服务」一块**：8 项服务各自的状态（平台代采 / 自备 Key / 本地 / 未开放）、
  本月各项 Credits 消耗；原来那 23 个字段全部收进「使用自己的 Key（高级）」折叠区，默认收起。
  **每项旁边放「改用自己的 Key」按钮**；§4.8 的所有错误提示都带一个直达该按钮的入口
  （就地展开那一项），有 local 档的 asr/tts 另给「一键切本地」。
- **会议录音面板加「录音不出本机」开关**，切本地 ASR 档，就绪判定见 §6.2.1。
- 账户页用量按 `meta.service` 分组展示；有未结算 hold 时显示占用提示（§4.6）。
- 「平台服务」面板补两个设置项：**单次任务花费上限**（喂 §4.9）、**余额低于 N 时提醒**。

### 7.1 「未连账户」与「零余额」的形态（修订一新增）

这两种不是异常，是新用户的**必经状态**（`createUser` 不发 Credits，余额恒为 0）。

| 状态 | 平台服务卡片 | 调用时 |
|---|---|---|
| 未连账户 | 「需要连接账户」+ 就地连接入口 | 不发请求，回 `not_connected` |
| 已连账户、零余额 | 「需要充值」+ 官网充值链接 | 回 `no_credits`，文案指向充值 |
| 已连账户、有余额、`enabled=false` | 「未开放」 | 回 `service_disabled` + 「改用自己的 Key」 |

如果 D6 决定赠额，第二行在赠额用完之前不会出现。

---

## 8. 分期

| 批 | 内容 | 独立可验证 |
|---|---|---|
| **P0** | 官网网关地基：路由框架 + `service_pricing` 表 + `gateway_request` 幂等表 + 预扣三行账/结算三路径/退款 + `syncAiQuota` 双向钩子 + hold 超时回收 cron + `service_spend` kind + 错误码族 + `desktop-contract.md` + `contract-check.mts` | 是（无桌面改动） |
| **P1** | 桌面地基：`PlatformGatewayClient` + 双档框架 + 存量回填 + 余额闸 + 错误信封与逃生门指路；接**博查搜索**贯通验证 | 是（一次调用最简单，用来验证扣费/退款/幂等/余额闸整套账） |
| **P2** | 语音云端路：OSS 直传 ticket + 听悟建任务 + 按时长预扣结算 + taskId 落库与恢复轮询 + 删对象 | 是 |
| **P3** | 本地 ASR：`asr-service` 进 pysvc 单包 + 组件管理下载 + `local` 档 + 开关就绪判定（阻塞项） | 是 |
| **P4** | 其余五家：OCR / TTS / 企查查 / Tushare / 法宝；企查查与 Tushare 增 Java 侧一等工具、`PythonTools` 停止注入其凭证 | 是 |
| **P5** | 配置面收敛：向导步骤 2 改造、「平台服务」面板、用量展示与 hold 占用提示、任务级上限设置项、README / 隐私政策 / 跨境告知同步 | 是 |

**P1 排在 P2 之前**是有意的：博查搜索是最简单的一次调用，
用它把「预扣、扣费、失败退款、幂等、余额闸、错误信封」这整套账验完，
比拿语音这条最复杂的链路当第一个试验品稳。

**存量回填（§5.2.1）必须与档位框架同批（P1）合入**，不能拖到 P5——
档位框架一合入，存量用户就已经被静默切走了。

**P4 里企查查与法宝的 `service_pricing.enabled` 默认 false**，
合同谈成后改一个开关即可，不阻塞其余批次。

**本轮明确的非目标**：server 模式（团队自建服务器、`addin.aiworkdeck.com` 云实例、Office 插件）
的平台网关支持。那三处恒 `byok`，行为与今天一字不变（D5 / §5.3）。

每批 PR 都要顺手更新 `.claude/agents/licensing-billing.md`（新契约、新地雷），
按 CLAUDE.md 的维护规则。

---

## 9. 上线前置条件（代码写完也不能上线）

这些卡在维护者侧，不是工程任务：

| 供应商 | 需要 |
|---|---|
| 阿里云听悟 + OSS | 企业账号开通、充值、RAM 子用户（最小权限）、OSS bucket + 生命周期规则 |
| 阿里云 OCR | 同上，可复用同一账号不同 RAM 子用户 |
| 博查搜索 | 商用账号 + 充值 |
| ElevenLabs | 商用套餐（注意其条款对「转售」的约定） |
| **企查查** | **按量分销授权**——机构订阅制，共用一个 token 服务全部终端用户可能违约 |
| **北大法宝** | **同上**，且其 MCP token 通常绑定订阅主体 |

**运维前置**：官网今天发版是 `npm install && npm run build && pm2 restart`，**无 draining**。
网关上线后，一次发版重启的几分钟里 7 家服务同时挂掉。
上线前需要零停机部署（pm2 reload 或蓝绿双进程）。

**待维护者决定（D6）**：新用户注册是否赠一笔小额 `reward` Credits。
不赠的话，「解锁即可用」不成立，用户必须先充值才能用任何一项平台服务。

**合规文档**必须在 P5 之前更新：
- 隐私政策：新增「哪些数据会经过我们的服务器 / 存放多久 / 何时删除」
- 会议录音上传前的**单独告知**（律师保密义务场景，参照跨境同意那条的做法：不预勾选、不打包进服务条款）
- README 的数据流向说明（onboarding 词典里那段 `complianceNote` 要重写）

---

## 10. 红线汇总（实施时逐条核对）

1. **AI 通道不改成代理**——桌面必须从用户本机直连 OpenRouter（§3 通路 A）。
2. **客户端从不传价格**，单价一律服务端自查。
3. **客户端申报的计量数量同样不可信**——计价以上游真实计量为准，申报值只用于预扣估算，且受 `maxUnitsPerCall` 约束。
4. **余额不足用 409 `no_credits`，绝不 401/403**；文案不含「登录」「未授权」「请先」。
5. **失败退款必须 `refundSpend` 原批次原额**，绝不「再发一笔」。
6. **结算超出可用余额时扣到 0 + 写 shortfall 审计**，绝不回滚整笔结算，绝不把余额扣成负数。
7. **`topup` 批次不许带有效期**（表级 CHECK 已兜住，别绕过它写入）。
8. **会扣费的 POST 必须有幂等键**，服务端去重回放。
9. **扣费后必调 `syncAiQuota` 降额，退款后必调一次提回**——否则同一笔余额被网关和 AI 各花一遍。
10. **预扣必须有服务端侧的超时回收**，不能只靠客户端轮询（与第 11 条同理）。
11. **OSS 对象删除要代码 + 生命周期规则两道。**
12. **BYOK 通道保留**，只是从向导挪进高级设置；**存量用户必须显式回填 `byok`**，不能靠默认值。
13. **平台网关只在 local-mode 开放**；非 local-mode 恒 `byok`，不回落、不共账（D5）。
14. **契约两处同步**：`desktop-contract.md` + `contract-check.mts`。

---

## 11. 验证

- 官网：`verify-credits` / `contract-check` 现有 102 项 + 网关新增用例：
  - 预扣三行账（②③ 同事务）
  - 失败全额退、原批次原额
  - **真实计量 2 倍于申报且余额不足** → 扣到 0 的结算行 + 一条 `gateway_spend_shortfall` 审计
  - 同一幂等键连发两次 → 上游只被调一次、只产生一行 `service_spend`
  - 扣一笔 `service_spend` 后 `openrouter_keys.limitUsd` 必须随余额下降；退款后提回
  - hold 超时后余额自动恢复
  - 余额不足信封不含三个禁用子串
  - 单价服务端权威（客户端传的 `priceCents` 被忽略）、`enabled=false` 直接拒
  - `maxUnitsPerCall` 超限直接 400

  **脚本必须用 nvm v22 全路径跑**，`/usr/bin/node` v20 碰库会段错误。

- 桌面：`cd backend && mvn test`（**JDK 21，系统默认 25 会 SIGBUS**）。
  新增 `service/platform/PlatformGatewayClientTest`、各服务双档路由测试、
  **存量回填测试**（带存量 Key 的真实形态种子库升级后跑一遍原有功能，
  账本不产生任何 `service_spend` 流水；参照 `LocalIdentityRealShapeIntegrationTest` 的做法）、
  **网关路由四形态测试**（local-mode 走平台、两种 server 形态恒 byok；
  口径参照 `PlatformAiChannelRoutingTest`）。
- 前端：`cd frontend && npm run check:emits` + `npm run build:h5`。
- 端到端：`npm run test:app-e2e`（向导步骤 2 改造后 J1 旅程要同步改）、
  `npm run test:desktop-e2e`。
- 真机：全新安装 → 试用码解锁 → 连账户 → **充值或赠额** → 不填任何第三方 Key → 依次验证
  搜索 / OCR / 语音转写（云端与本地两档）/ 企业数据，账户页能看到分服务的 Credits 消耗。
  另跑一遍**存量形态**：带 23 个字段的旧库升级后，全部功能行为不变、账本无 `service_spend`。
