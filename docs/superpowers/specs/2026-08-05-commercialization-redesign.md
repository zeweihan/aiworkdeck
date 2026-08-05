# AI Workdeck 商业化改造总 Spec（2026-08-05）

状态：用户已批准方向（混合激活 / 桌面+官网一起落地 / 协作 UX 大改），并追加三大计费支柱（Token 计费 +20% 毛利、插件创作者七三分成、本地小功能低额解锁）。本文件是本轮所有实现 agent 的唯一事实来源。

涉及两个仓库：
- 桌面仓 `zeweihan/aiworkdeck`，本地工作树：`/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/zealous-rubin-096c11`
- 官网仓 `zeweihan/aiworkdeck_website`，本地：`/Users/zewei/Documents/2024-2044/5-Tech/1-1 aiworkdeckweb`

红线（全局）：
- 全站禁 emoji（代码/UI/文档/commit）。
- 桌面外壳保持浅色体系。
- 官网侧密钥只走 .env，绝不入库；data/*.json 已 gitignore 的模式沿用。
- 本机 mvn 必须 JDK 21；前端 npm 不是 pnpm。
- 桌面仓 docs/ 在 .gitignore，入库要 git add -f。

---

## 1. 身份模型（最终形态）

三层身份，各司其职：

| 层 | 是什么 | 什么时候出现 |
|---|---|---|
| 本机身份 | 桌面单机模式的固定「本机用户」，userId 恒定，schema 零迁移 | 永远隐身，用户无感 |
| AI Workdeck 账户 | 官网账户（官网已有 users.json + awd_session 体系），扩展出：钱包余额、AWD Key、用量账本、已购内容 | 充值/购买/平台 AI 计费时才需要 |
| 团队服务器凭据 | 现有设备令牌体系，与本机登录本就解耦 | 连接团队案件库（协作）时 |

**桌面端永远不需要登录。** 与账户的唯一连接方式：设置页粘贴一个 `awdk_` 前缀的账户 Key（在官网账户页生成）。粘贴后桌面端可以：用平台 AI 计费通道、同步已购插件与功能解锁、显示余额。可随时断开。

**解锁门（首启）**：新解锁页，两条路解锁应用：
1. 试用码（离线）：Ed25519 签名负载，桌面内置公钥验签，不联网。GitHub README 公开一枚通用试用码。解锁后全功能可用 + 常驻「试用版」标识。
2. 账户 Key：粘贴 awdk_ Key，在线校验有效即解锁为正式版（等于连接账户一步到位）。

原方案中的「正式码在线激活绑定设备」**简化掉**：正式身份 = 账户 Key。兑换码（gift code）作为官网侧功能（兑换后给账户充值/入库已购），后续批次可做，本轮只留数据模型位。

码格式：试用码 `AWD-T-XXXX-XXXX-XXXX-XXXX`（base32 分组，payload+签名）；账户 Key `awdk_` + 32B base64url。

## 2. 桌面端去登录（沿用已批准方案）

- 唯一改动点：`AuthController.getUserIdFromSession()` 在 local-mode 下任何请求解析为本机用户 id。90 处调用、31 个 controller 一行不动。desktop profile 加 `security.local-mode: true`。
- 启动强不变式：local-mode 必须绑定 `server.address` 为回环，否则拒绝启动（延续 2026-08 审计不变式）。
- DataInitializer：local-mode 建 `local` 用户（displayName「本机用户」）取代 admin/123 心智；server 模式不变。
- 版本记录署名：本机用户 → git 作者 `本机用户 <local@aiworkdeck.local>`；连接团队服务器后沿用现有 CloudConnection 身份。
- 前端启动链：App 启动 → GET /api/license/status → 未解锁 → unlock 页；已解锁 → wizard status → 直达上次项目。login.vue 保留，仅浏览器访问团队服务器（非 Electron 环境）时走原流程。判定用 `window.checkbaDesktop` 存在性。
- 权限清理：
  - admin 页删「用户管理」面板（后端 GET /api/admin/users 保留给 server 模式）。
  - 修 api.js 401 拦截清错 storage key 的 bug（清 sessionId/userId，实际 key 是 checkba_session_id/checkba_user）；local-mode 下 401 拦截不再跳登录页。
  - 删「修改密码」死链接（userprofile.vue:314-318）；wizard.vue 页脚删 admin/123 提示。
  - userprofile 页改名心智：「个人中心」→「项目主页」，账号信息区改为显示授权状态（试用版/正式版、账户连接状态、积分余额）。
- e2e 影响（PR-A 必须同步改，发版前必跑）：app-e2e 的 J1 登录旅程重写为「解锁→直达」；lowa-e2e 前置登录步骤改为免登直达；desktop-e2e 同理。已知 issue #200 J1 登录抖动将随登录消失一并消亡。

## 3. 计费支柱一：Token 计费（OpenRouter，成本 +20%）

架构已经 2026-08-05 官方文档核实（Management API Keys / Usage Accounting / Limits / ToS）：

- 官网持 OpenRouter **Management API key**（只能管理 key、不能调推理，只存服务器 env，绝不进桌面包）。为每个充值用户 `POST /api/v1/keys` 创建专属 runtime key（明文仅创建响应返回一次，官网存 key_hash + 转交桌面）。
- 额度强制在 OpenRouter 侧执行：key 带 `limit`（美元 credits），超限 402。充值提额用 `PATCH /api/v1/keys/{key_hash}`，**limit 是累计口径，提额语义 = 旧 limit + 本次折算增量**（不可覆盖写）。止损：`disabled: true` 即时禁用。
- 对账：`GET /api/v1/keys/{key_hash}` 返回 `usage`/`limit_remaining`；桌面端单请求成本直接读响应 `usage.cost`（流式在最后一条 SSE），写入本地 TokenUsage。
- 毛利实现：用户充 ¥X 入钱包 → 分配 AI 额度时折算 limit 增量 = X / 汇率 / 1.2（汇率 admin 配置，默认 7.3）。注意实际毛利 ≈ 20% − OpenRouter 充值通道手续费（Stripe ~5.5%）− wxpay 手续费，约 13~14%，定价文案不要承诺「只加 20%」。
- 运营配套：主账户余额是所有 key 的共享池，limit 之和可能大于主账户余额 → 官网 admin 面板显示主账户余额与已分配额度之和的差值，提醒充值（或开 OpenRouter Auto Top-Up）；OpenRouter credits 一年可能过期，不囤积。
- **ToS 红线**：禁止转售裸 API 访问。平台通道只允许在 AI Workdeck 产品内使用（桌面端不暴露任何通用 LLM 转发端点），文档与官网文案也不得宣传为「API 转售」。
- 账本：官网侧 ledger（data/ledgers.json）记：充值（wxpay 订单号）、AI 额度分配、插件购买、功能解锁、创作者分成入账。桌面端「授权与用量」面板拉官网 API 显示余额 + 本地 TokenUsage 明细（两套数字口径分开标注：本地统计 vs 平台结算）。
- BYOK 不受影响：用户自己的 Gemini/OpenRouter key 走原路，零计费。平台通道只是新增选项。
- 桌面端 provisioned key 的获取：POST 官网 /api/account/ai-key（带 awdk_ Key 鉴权）→ 返回 provisioned key；桌面存本地配置。官网可撤销重发。

## 4. 计费支柱二：插件/Skill 付费与创作者分成

- registry 数据模型扩展：skill/plugin 元数据加 `price`（分，0=免费）、`pricingModel: "once"`（预留 "subscription"，本轮不实现订阅——续费/退订/到期停用是另一个量级的状态机，不值得挤进本轮）、`creatorUsername`。
- 购买流：官网详情页「购买」→ wxpay 支付 → 订单成交 → 写入 purchases.json（userId, itemId, price, splitCreator 70%, splitPlatform 30%）→ 创作者分成入 ledger（creator earnings）。
- 桌面安装流：付费项在桌面广场显示价格与「去官网购买」；安装时桌面带 awdk_ Key 调 registry bundle 端点，官网校验该账户已购才发 bundle。免费项流程不变。未连接账户时付费项只展示不可装。
- 分成提现：本轮做到「创作者账户页可见累计分成 + admin 页可标记已打款」，真实打款人工微信/银行转账。
- 签名安全模型不变：付费与否不改变 Ed25519 bundle 验签链路。

## 5. 计费支柱三：本地小功能低额解锁

两个 SKU（价格均为 admin 可配，默认）：
- 无线剪贴板无限版：¥19.9 一次性。免费额度：最多回溯 20 条 且 保留 3 天（两者同时生效）。
- Stage 文件缓存区无限版：¥19.9 一次性。免费额度：最多 20 个文件、总量 500MB。付费解锁后无限，但需用户自行指定本地存储路径（设置里选目录，默认路径仍可用但会提示建议自选）。

实现：
- 桌面 EntitlementService：本地 entitlements.json（签名票据集合）+ 账户同步（连接账户时拉官网 /api/account/entitlements 合并）。免费额度逻辑在桌面端执行（剪贴板裁剪、Stage 容量检查），到达上限时温和提示 + 解锁引导（跳官网购买页）。
- 官网侧：这两个 SKU 走与插件购买同一条 wxpay 订单流，购买后写 entitlements。
- 试用码解锁的「试用版」同样受免费额度约束（试用版 = 应用可用 + 免费额度；解锁 SKU 与试用/正式无关，独立购买）。

## 6. Entitlement 框架（桌面）

- 中央功能目录 FeatureCatalog：`app.unlocked`、`clipboard.unlimited`、`stage.unlimited`、（预留 `plugin.<id>`、`plan.pro`）。
- EntitlementService.isEnabled(feature) 单一出口；来源合并：本地票据（试用码/离线解锁）∪ 账户同步结果。带缓存与离线宽限（账户型 entitlement 断网 30 天宽限）。
- 前端 composable useEntitlement(feature) + 「解锁」引导组件（统一样式，浅色体系）。

## 7. 协作 UX 律师化（机制不动，心智翻译）

- 概念映射：团队服务器→「团队案件库」；共享项目→「共享案卷」；push→「交稿」；pull→「取回最新稿」；members→「案件参与人」（负责人/协作人/只读/客户）。
- 入口上浮：项目界面一级入口「协作」按钮/面板（替代 admin 页深处的表单），常驻同步状态（已同步/有新变更/待交稿），一键同步。
- 邀请流程：生成可发微信的加入邀请文本（服务器地址+项目名+步骤说明）；客户访问码文案重写。
- 冲突引导：分叉三选一（采纳/放弃/另起一稿）对话框用律师语言解释每个选项后果。
- 不连团队案件库时协作 UI 零打扰。

## 8. PR 切分与执行序

桌面仓（依次）：
- PR-A：去登录 + 解锁门（试用码离线验签 + 账户 Key 在线校验）+ 权限清理 + e2e 全量更新。最大风险点：e2e 基线。
- PR-B：EntitlementService + 账户连接（设置页「账户与用量」）+「AI Workdeck 云端」提供商 + 用量面板。
- PR-C：剪贴板/Stage 免费额度与解锁（依赖 PR-B 的 entitlement）。
- PR-D：插件广场付费项展示与已购安装链路。
- PR-E：协作 UX 律师化（依赖 PR-A 合并，改动面在前端项目界面）。
- PR-F：README 试用码 + CLAUDE.md 路由表补「授权与计费」领域文档 + 各领域文档更新。

官网仓（与桌面并行，互不阻塞）：
- PR-W0（新增，前置于一切真钱业务）：支付安全加固 + SQLite 账本基座。见 §11。
- PR-W1：钱包 + AWD Key + wxpay 充值流 + 账户页账本。
- PR-W2：OpenRouter provisioning 集成（建 key/提额/用量拉取）+ /api/account/ai-key。
- PR-W3：插件定价/购买/七三分成账本 + admin 打款标记 + registry 已购校验（bundle 端点对付费项 402）。
- PR-W4：试用码签发脚本 + /api/license/verify-key + entitlements API。

依赖关系：PR-A 与 PR-W4 需对齐试用码格式与公钥；PR-B 依赖 PR-W1/W2 的 API 契约（先定契约后并行）；PR-D 依赖 PR-W3 契约。

## 9. API 契约（官网 → 桌面）

鉴权：桌面所有请求带 `Authorization: Bearer awdk_...`。

- `GET /api/account/me` → { username, displayName, balanceCents, plan: "trial"|"paid", createdAt }
- `GET /api/account/entitlements` → { entitlements: [{ feature, purchasedAt, orderId }] }
- `POST /api/account/ai-key` → { openrouterKey, limitUsd }（幂等：已有则返回现有）
- `GET /api/account/ledger?limit=50` → 流水数组 { ts, kind: recharge|ai_alloc|purchase|earning, amountCents, meta }
- `GET /api/registry/skills/{id}/bundle`：付费项要求 Bearer Key 且已购，否则 402。
- `POST /api/license/verify-key` → { valid, plan }（解锁门用）

试用码离线负载：`{ v:1, type:"trial", iat }` + Ed25519 签名，base32 分组编码为 AWD-T-… 。公钥内置桌面 backend resources（与插件 registry 公钥并列，独立密钥对）。

## 10. 风险与验证

- e2e 三套（app-e2e/lowa-e2e/desktop-e2e）在 PR-A 内同步改，合并前全绿；后续 PR 每个跑受影响套件。
- 官网改动不许破坏现有 skill 广场与 registry 契约（桌面旧版本仍在线上）。registry 元数据新增字段必须向后兼容（旧桌面忽略未知字段）。
- wxpay 回调幂等：订单状态机必须防重复入账（复用现有 orders.ts 模式的坑要先探明）。
- 安全：awdk_ Key 官网侧只存哈希；provisioned OpenRouter key 桌面本地存储即可（泄露止损=官网撤销重发）；local-mode 回环绑定强校验。
- 定价与文案（「试用版」标识、解锁引导）遵守浅色体系与禁 emoji 红线。

## 11. 官网仓勘察结论（2026-08-05）与 PR-W0 内容

勘察确认官网是 Next.js 16 全 JSON 文件持久化、单实例 PM2、1.8G 内存服务器。做真钱业务前有硬伤，PR-W0 先修：

**P0（必须先修，否则钱包就是漏勺）**：
1. wxpay 回调签名验证被短路：`lib/wxpay.ts:147-153` 未传 publicKey 时 `return true`，而 `app/api/payment/notify/route.ts:17` 恰好没传——任何人可伪造「已支付」回调。修法：实现微信平台证书拉取（GET /v3/certificates）+ 缓存 + 严格验签，验签失败一律拒绝（fail closed）。
2. 商户证书材料已提交进 git：`wxpay/apiclient_cert.p12`、`wxpay/wxpay.env`（还有 `data/orders.json`）。PR-W0 内 untrack + 补 .gitignore（`*.p12`、`wxpay.env`、`data/orders.json`、`secrets/`）。**证书轮换与 git 历史清理需要用户在微信商户平台操作，单独列为用户待办，代码侧不阻塞。**
3. `/api/payment/create` 无登录校验、无金额上限、无幂等键；Order 模型无 userId/商品维度（`lib/orders.ts:6-16`）。

**存储决策：钱相关的表全部上 SQLite（better-sqlite3，WAL，单文件 `data/awd.db`）**，不沿用 JSON 全量覆盖写模式（无锁无原子写，账本必坏）。JSON 保留给 skills/plugins 等读多写少内容。SQLite 表：`orders`（新 schema：id, outTradeNo, userId, kind[recharge|purchase|support], skuId?, amountCents, status, idempotencyKey, createdAt, paidAt, transactionId, payerOpenid）、`wallet_ledger`（userId, ts, kind[recharge|ai_alloc|purchase|earning|adjust], amountCents, balanceAfterCents, refOrderId?, meta）、`purchases`（userId, itemType[skill|plugin|feature], itemId, orderId, priceCents, creatorUserId?, creatorShareCents?, platformShareCents?, createdAt）、`entitlements`（userId, feature, orderId, createdAt）、`api_keys`（userId, keyHash, prefix, createdAt, revokedAt?）、`openrouter_keys`（userId, orKeyHash, limitUsd, createdAt, disabledAt?）、`creator_earnings`（creatorUserId, purchaseId, amountCents, settledAt?, settleNote?）。
- awdk_ Key 官网只存 SHA-256 哈希；明文仅生成时展示一次（与桌面 DeviceToken 同模式）。
- 迁移：现有 `data/orders.json` 一次性导入 orders 表（kind=support）。打赏弹窗流程照常但走新表。
- 统一存储层 `lib/db.ts`：初始化、迁移（schema version 表）、事务 helper。所有钱的变更必须在事务里（订单置 paid + 入账本 + 发权益原子完成，回调幂等靠 orders.status 状态机 + unique outTradeNo）。

**admin 后台**：现有单口令 `awd_admin` cookie 存明文口令。本轮容忍（改动大收益小），但支付/提现相关新 admin 页要加二次确认；PR-W3 的打款标记操作记 audit 行到 SQLite。

**部署注意**：服务器 1.8G 内存曾 OOM，构建已关 TS 检查——官网侧每个 PR 本地必跑 `npm run check` + `npm run build`。better-sqlite3 是原生模块，部署命令里的 `npm install` 会自动编译，DEPLOY.md 补一行说明。
