# 外部服务统一 Credits 计费（平台服务网关）设计

日期：2026-08-17
状态：设计已确认，待实施
涉及仓库：`checkba_cloud`（桌面端 Spring + uni-app）、`aiworkdeckweb`（官网 Next.js，Credits 账本所在）
相关领域文档：`.claude/agents/licensing-billing.md`（授权与计费）、`.claude/agents/utility-tools.md`（OCR/搜索/语音）、`.claude/agents/ai-chat.md`（AI 通道）

---

## 1. 背景

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

**成功判据**：全新安装 → 解锁 → 首启向导只需选 AI 通道 → 语音转写、OCR、搜索、企业数据全部可用，
用户没有填过任何第三方 Key；账户页能看到每项服务花了多少 Credits。

---

## 2. 已确认决策

| # | 决策 | Why |
|---|---|---|
| D1 | **8 家全部由公司统一代采**，含企查查与北大法宝 | 维护者的商业选择。风险见 §9 上线前置条件——这两家是机构订阅制，按量分销需要单独授权，**合同没谈成之前对应开关默认关** |
| D2 | **会议录音：云端听悟为主 + 本地模型作隐私选项** | 云端质量好、有说话人分离；本地档给敏感案件一条「录音不出本机」的出路。两条都零配置 |
| D3 | **本轮全量交付**：网关框架 + 8 家全接 + 本地 ASR，分六批 PR | 只做一条链路解决不了「配置太夸张」这个用户可感知的问题 |
| D4 | **加价率按服务分档**，不是全局一档 | 各家成本结构差异大（企查查按次昂贵、听悟按秒便宜），一档会让某些服务亏本或贵得离谱 |

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

**唯一但决定性的理由：扣费必须与 Credits 同事务。**
`spendCredits` 是官网 `better-sqlite3` 的本地事务（`assertTx` 强制）。
挪到 Spring 就变成跨服务两阶段提交，「扣了钱没调成」与「调成了没扣钱」两种事故都会出现，
而这是真金白银的账。

**代价：大对象不能进 Next.js 进程。** 语音这条因此做控制面 / 数据面分离，见 §6。

---

## 4. 官网侧：平台服务网关

### 4.1 端点形态

| 端点 | 鉴权 | 说明 |
|---|---|---|
| `POST /api/gateway/{service}/{op}` | Bearer `awdk_` | 通用代理入口。`service` ∈ {ocr, search, tts, qichacha, tushare, pkulaw}；`op` 由各 adapter 定义 |
| `POST /api/gateway/asr/ticket` | Bearer `awdk_` | 语音专用：查余额、签发 OSS 直传凭证 |
| `POST /api/gateway/asr/submit` | Bearer `awdk_` | 语音专用：预扣 + 建听悟任务 |
| `GET /api/gateway/asr/task/{id}` | Bearer `awdk_` | 语音专用：轮询 + 完成时结算 + 删 OSS 对象 |
| `GET /api/gateway/pricing` | Bearer `awdk_` | 单价表（桌面端展示用；**不作为扣费依据**） |

鉴权复用 `lib/account-auth.ts` 的 `resolveKeyUser(request)`，与 `ai-key` / `ai-usage` 同一条路。

### 4.2 单价表：服务端权威，客户端从不传价格

新增表 `service_pricing`，admin 可在线调，改价不发版：

| 列 | 说明 |
|---|---|
| `service` | `asr` / `ocr` / `search` / `tts` / `qichacha` / `tushare` / `pkulaw` |
| `op` | 同一服务的分档（企查查的贵接口单列一行）；`*` 表示该服务全部操作 |
| `unit` | `minute` / `page` / `call` / `kchar` |
| `costCentsPerUnit` | 我们的采购成本（对账用，不直接计价） |
| `marginMultiplier` | **按服务分档的加价率**（D4）。沿用 `ai-usage` route 已有的字段名 |
| `creditsPerUnit` | 实际计价 = `round(costCentsPerUnit × marginMultiplier)`，落库冗余一份避免每次现算 |
| `enabled` | 供应商未开通/合同未谈成时置 false，网关直接回 `service_disabled` |

**客户端永远不传价格。** 同广场付费项那条红线：不信前端传来的 `priceCents`，
等于让客户端决定闸门何时生效。`GET /api/gateway/pricing` 只供 UI 展示。

### 4.3 扣费规则

**两种时机，按单次金额分：**

- **事后扣**（默认，用于 search / qichacha / tushare / pkulaw / ocr 单页）：
  调用成功后 `spendCredits` 一笔。失败不扣。
- **预扣 → 结算**（用于单次估算成本超阈值的调用）：见 4.4 的三行账法。
  阈值 `HOLD_THRESHOLD_CENTS` 是 `service_pricing` 的同级配置项，初值取「一次典型 AI 对话」的量级；
  今天只有 `asr`（按分钟）与超长文本 `tts`（按千字符）会越过它，但**判据是金额不是服务名**——
  写死服务名的话，以后单价一调就会出现「一笔很贵的调用没预扣」。

**四条硬规则：**

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

### 4.4 预扣的三行账法（因为 `refundSpend` 不支持部分退款）

`lib/credits.ts` 的 `refundSpend` 按原流水行的 `meta.lots` 明细**全额**退回，没有部分退款能力。
**不改它**——那是核心记账，动它的风险远大于多两行流水。

于是预扣走三行，全部带同一个 `meta.taskId`：

```
① hold    spendCredits(估算额, kind='service_spend', meta={service, phase:'hold',    taskId})
② release refundSpend(①的 ledgerId, {ledgerKind:'adjust', meta:{phase:'hold_release', taskId}})
③ settle  spendCredits(真实额, kind='service_spend', meta={service, phase:'settle',  taskId})
```

账户页按 `meta.taskId` 折叠成一行展示（「会议转写 42 分钟 −N Credits」），展开才看到三行明细。

**任务中途失败**：只做 ②，不做 ③。用户不花一分钱。

### 4.5 ledger 只加一个 kind

新增 `service_spend`，**服务名进 `meta.service`**。

不给每家开一个 kind：kind 是开放枚举，但每加一个都要同步改三处——
`scripts/contract-check.mts` 的 `KINDS`、`doc/desktop-contract.md` 的表、词典的 `accountPage.kinds`。
服务会一直加下去，用 meta 区分才不会每接一家就动一次契约。

`meta` 约定字段：`{service, op, taskId?, phase?, units, unit}`。

### 4.6 契约同步（每批必做）

改端点必须同时改 `doc/desktop-contract.md`（人读版）与 `scripts/contract-check.mts`（机器可执行版）。
`ai-usage` 就是反例：它是唯一一条两处都没收录的端点，改它两侧至今没有任何护栏提醒。
网关是全新端点族，**从第一批就把两处建起来**，不要重蹈覆辙。

---

## 5. 桌面侧：一个客户端，八个双档开关

### 5.1 唯一出站缝

新增 `backend/src/main/java/com/checkba/service/platform/PlatformGatewayClient.java`：

- 照 `service/account/AccountTransport` + `HttpAccountTransport` 的模式做成接口 + 实现，
  单测打桩不依赖网络。
- 固定 HTTP/1.1（JDK HttpClient 默认 HTTP_2，对明文回环地址会先发 h2c 升级，
  Next 开发服务器收到后不回字节，上层只看到「无法连接服务器」——本地联调必踩）。
- 地址复用 `SiteProfileService.baseUrl()`（双主站按站点走），协议校验复用 `AccountEndpoint`。
- 鉴权取 `AccountService.currentKeyOrNull()`。
- 错误映射复用 `AccountException.Kind`，另加 `NO_CREDITS` 一类。

### 5.2 每服务双档

与 `TtsService` 现有的 `elevenlabs | local` **完全同构**，不发明新形态：

```
external.<service>.provider = platform | byok      （asr / tts 多一档 local）
```

默认 `platform`。**BYOK 保留但从向导挪走**——团队自建服务器、离线部署、
以及自有企查查/法宝订阅的律所都需要它，删掉是回归。

写入口仍是 `AdminConfigController.toSettingsUpdates` 一处，取值非法直接 400（不静默回落）。
注意该方法**跳过 null 字段**是有意的：曾经把 env 提供的 baseUrl 清成空串，
受害者正是 QichachaService / TushareService / TtsService 这三个。

### 5.3 「这次调用花谁的额度」——不新发明判据

直接搬 `PlatformAiChannel.resolveOrThrow` 的四分支（licensing-billing 地雷 17）：

| 形态 | 走哪把 key |
|---|---|
| local-mode | 机器级 `awdk_`，一字不动 |
| server + 已桥接（`isBound()`） | per-user，复用现成的 `PlatformAiUserScope` 线程作用域 |
| server + `multiTenant()` + 未桥接 | **拒绝**（不回落，回落等于拿别人的钱花，且对账会把 A 的消费记到 B 头上） |
| server + 无任何绑定（团队服务器） | 机器级，与改动前逐字一致 |

**复用 `PlatformAiUserScope` 而不是新造一个作用域**：那 10 个设置点
（含 2026-08 补的 `ToolRegistry.execute` 那个 P0）已经踩过一遍跨线程丢作用域的坑，
再造一个就要再踩一遍。新增的网关调用点如果发生在 `@Async` / `executor.submit` / `runAsync` 里，
**必须 `wrap`**。

### 5.4 余额闸

复用 `PlatformCreditsGate` 的三条判据，不另起炉灶：
① 只管机器级路径；② **确知为 0 才拦**（网络失败 / 端点缺失 / 字段缺失一律放行，且不保留上一次的 0）；
③ 首次同步、之后后台刷新（60 秒保鲜）。

---

## 6. 语音转写

### 6.1 云端路：控制面 / 数据面分离

```
桌面 → POST /api/gateway/asr/ticket   {durationSec, format}
     ← 官网查余额（不够则 409 no_credits，此时用户还没上传）
     ← 限路径 / 限大小 / 限有效期的 OSS 直传凭证（阿里云 PostObject 签名或 STS）
桌面 → 直传我们的 OSS                  （几百 MB 不经过 Next.js）
桌面 → POST /api/gateway/asr/submit   {objectKey, durationSec}
     ← 官网按估算时长预扣（4.4 的 ①）、建听悟任务、返回 taskId
桌面 → GET /api/gateway/asr/task/{id} 轮询
     ← 完成：按真实时长结算（4.4 的 ②③）、删除 OSS 对象、回转写结果
```

**余额闸放在 ticket 那一步**，不让用户白传两小时录音才被拒。

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

**已知取舍**：本地档没有说话人分离。会议纪要里「谁说了什么」要靠 AI 事后推测，
会见笔录场景质量明显下降。UI 上必须写明，不能让用户以为两档等价。

---

## 7. 配置面收敛（用户可感知的交付物）

- **首启向导步骤 2 整个删掉**（`step2Title` / `ocrGroup` / `ttsGroup` / `dataGroup` 四组全消失）。
  向导只剩「选 AI 通道」一步。
- **系统管理新增「平台服务」一块**：8 项服务各自的状态（平台代采 / 自备 Key / 本地）、
  本月各项 Credits 消耗；原来那 23 个字段全部收进「使用自己的 Key（高级）」折叠区，默认收起。
- **会议录音面板加「录音不出本机」开关**，切本地 ASR 档。
- 账户页用量按 `meta.service` 分组展示。

---

## 8. 分期

| 批 | 内容 | 独立可验证 |
|---|---|---|
| **P0** | 官网网关地基：路由框架 + `service_pricing` 表 + 预扣/结算/退款 + `service_spend` kind + `desktop-contract.md` + `contract-check.mts` | 是（无桌面改动） |
| **P1** | 桌面地基：`PlatformGatewayClient` + 双档框架 + 余额闸 + 错误信封；接**博查搜索**贯通验证 | 是（一次调用最简单，用来验证扣费/退款/余额闸整套账） |
| **P2** | 语音云端路：OSS 直传 ticket + 听悟建任务 + 按时长预扣结算 + 删对象 | 是 |
| **P3** | 本地 ASR：`asr-service` 进 pysvc 单包 + 组件管理下载 + `local` 档 | 是 |
| **P4** | 其余五家：OCR / TTS / 企查查 / Tushare / 法宝，同模板批量迁 | 是 |
| **P5** | 配置面收敛：删向导步骤 2、「平台服务」面板、用量展示、README / 隐私政策 / 跨境告知同步 | 是 |

**P1 排在 P2 之前**是有意的：博查搜索是最简单的一次调用，
用它把「预扣、扣费、失败退款、余额闸、错误信封」这整套账验完，
比拿语音这条最复杂的链路当第一个试验品稳。

**P4 里企查查与法宝的代码先合、`service_pricing.enabled` 默认 false**，
合同谈成后改一个开关即可，不阻塞其余批次。

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

**合规文档**必须在 P5 之前更新：
- 隐私政策：新增「哪些数据会经过我们的服务器 / 存放多久 / 何时删除」
- 会议录音上传前的**单独告知**（律师保密义务场景，参照跨境同意那条的做法：不预勾选、不打包进服务条款）
- README 的数据流向说明（onboarding 词典里那段 `complianceNote` 要重写）

---

## 10. 红线汇总（实施时逐条核对）

1. **AI 通道不改成代理**——桌面必须从用户本机直连 OpenRouter（§3 通路 A）。
2. **客户端从不传价格**，单价一律服务端自查。
3. **余额不足用 409 `no_credits`，绝不 401/403**；文案不含「登录」「未授权」「请先」。
4. **失败退款必须 `refundSpend` 原批次原额**，绝不「再发一笔」。
5. **`topup` 批次不许带有效期**（表级 CHECK 已兜住，别绕过它写入）。
6. **BYOK 通道保留**，只是从向导挪进高级设置——团队服务器与离线部署靠它。
7. **多租户下缺身份必须报错，不回落机器级 key**（拿别人的钱花 + 记错账）。
8. **跨线程提交必须 `wrap` `PlatformAiUserScope`**。
9. **OSS 对象删除要代码 + 生命周期规则两道**。
10. **契约两处同步**：`desktop-contract.md` + `contract-check.mts`。

---

## 11. 验证

- 官网：`verify-credits` / `contract-check` 现有 102 项 + 网关新增用例
  （预扣三行账、失败全额退、余额不足信封、单价服务端权威、`enabled=false` 直接拒）。
  **脚本必须用 nvm v22 全路径跑**，`/usr/bin/node` v20 碰库会段错误。
- 桌面：`cd backend && mvn test`（**JDK 21，系统默认 25 会 SIGBUS**）。
  新增 `service/platform/PlatformGatewayClientTest`、各服务双档路由测试、
  沿用 `PlatformAiChannelRoutingTest` 的四形态口径。
- 前端：`cd frontend && npm run check:emits` + `npm run build:h5`。
- 端到端：`npm run test:app-e2e`（向导步骤 2 删除后 J1 旅程要同步改）、
  `npm run test:desktop-e2e`。
- 真机：全新安装 → 试用码解锁 → 连账户 → 不填任何第三方 Key → 依次验证
  搜索 / OCR / 语音转写（云端与本地两档）/ 企业数据，账户页能看到分服务的 Credits 消耗。
