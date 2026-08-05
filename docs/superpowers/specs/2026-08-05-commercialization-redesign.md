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

---

## 12. 实现与 Spec 的偏差记录（2026-08-06，PR-A 至 PR-E 合并后补写）

本节记录桌面仓实现过程中被修正的设计。**代码是事实来源**，本 Spec 上文保留原样不改写，
以便看清「当初怎么想的」与「最后为什么这么做」。官网侧契约的权威版本在官网仓 `doc/desktop-contract.md`。

### 身份与解锁

1. **正式码简化为账户 Key**（§1 已写明的方向，实现照做）：不做「正式码在线激活绑定设备」，
   正式身份就是 `awdk_` 账户 Key。解锁门里粘 Key 会顺带调 `AccountService.connect()` 一步到位——
   少这一步，用户解锁成正式版后账户仍是「未连接」，得把同一把 Key 在设置页再粘一遍。
2. **`verify-key` 只看 `valid`，不要求 `plan=paid`**。要求 `paid` 会让未付费但已注册的账户被判「Key 无效」，
   且启动复验会把这类账户的本地授权直接清掉。
3. **`verify-key` 的 `plan` 取值是 `paid|free`，不是 §9 写的 `trial|paid`**。「试用」是桌面端本地试用码解锁的状态，
   官网账户没有试用概念，返回 `trial` 会与桌面端语义打架。
4. **`GET /api/account/ledger` 返回 `{entries:[...]}` 而不是 §9 写的裸数组**，以官网契约文档为准。
5. **新增 `GET /api/account/ai-usage`**（§9 里没有）：AI 额度的实时口径。官网 master 已实现但当时未写进契约文档，
   桌面端按「可能不存在」处理——拿不到只降级额度这一段，余额与账本照常。
6. **本机身份解析改为「多候选交给用户选定」**（#250 修正 PR-A）。PR-A 的「优先 local，否则回落 admin」
   建立在「老安装的数据都挂在 admin 名下」这条假设上，真机实测该假设不成立（admin 1 项目 0 文件，
   用户 6 项目 21 文件在另一账号名下），回落 admin 会让老用户看到近乎空的工作区。
   新增 `/api/local-identity/{status,candidates,select}` 与 `pages/identity/identity.vue` 选择页。
7. **`DataInitializer` 不新建 `local` 用户**（§2 写的是「local-mode 建 local 用户取代 admin」）。
   全空库复用已存在的空 admin（零新增行、零迁移），只在 admin 也不存在时才建 `local`；
   改名收窄到 `username=admin`，真实账号的 displayName 一个字不动。
8. **启动链多一跳身份分流**：§2 写的是「license status → wizard status → 直达」，
   实际是 `license status →（未解锁）unlock →（待选定）identity → wizard status → 直达`。

### 权益与额度

9. **`entitlements.json` 不签名**（§5 写的是「签名票据集合」）。两个本地 SKU 的判定本就完全在本地执行，
   没有任何服务端往返能拦住改文件的人，签名只是抬高门槛；真要钱的两条路各有服务端闸门
   （付费 bundle 由官网 402 把关、AI 额度由 OpenRouter 侧 key limit 强制执行）。有意接受，已写进类注释。
10. **账户型权益断网超 30 天回落「未拥有」而不是「保持拥有」**：否则一台永久离线的机器等于永久买断。
    本地票据（试用码离线验签）不吃宽限。官网明确拒绝（401/403 = Key 已吊销）时立刻清缓存，不吃宽限。
11. **`GET /api/entitlements` 的 `features` 只含已拥有项**，目录全集另放 `catalog`。
    「出现在 features 里 = 已拥有」是这个字段名唯一自然的读法，混进未拥有项会让前端把一切判成已解锁。
12. **免费额度只在 local-mode 执行**（§5 未提及部署形态）。`EntitlementService` 是按本机的（无 userId 维度），
    团队案件库服务器上权益恒为空集，照执行会把每个接入成员截到 20 条 / 20 个文件且永远无法解锁。
13. **「付费解锁后自选存储路径」搬的是全局存储根，不是缓存区目录**（§5 字面写的是「本地存储路径」）。
    缓存区文件就是项目文件（物理形态是项目内 `__staging_area__` 目录），没有独立目录可搬。
14. **恢复默认存储位置不需要权益**，`GET /api/storage/location` 同样不设权益闸。
    `applyOnStartup` 是无条件的，权益失效后数据仍在自选路径上照常读写；若连查看路径都要权益，
    用户就再也看不到自己数据在哪、也换不回默认位置。付费闸只留在「改到新的自选位置」这一个动作上。
15. **迁移是「复制 → 校验 → 换指针，原目录保留为备份」**，绝不先删后搬；任一步失败只清掉本次复制出的副本。
16. **存储位置配置落文件不落 DB**（`~/.aiworkdeck/storage-location.json`）：存储根必须在任何文件操作之前确定，
    读 DB 要等 JPA 起来，存在启动期顺序窟窿。
17. **`plan.pro` 只占位不实现**（§6 列为预留）：本轮不做订阅状态机，常量先定名以免后续改名。

### 计费

18. **平台通道成本不读响应体的 `usage.cost`**（§3 写的是直接读）。本仓固定 langchain4j 0.36 / openai4j 0.23，
    `Usage` 没有 cost 字段也拿不到原始响应，OpenRouter 还要请求体带 `usage:{include:true}` 才返回 cost，
    该 client 无法透传。改用 `GET /api/v1/key` 累计消费差分（`PlatformUsageAccountant`）。
    取舍：并发轮次之间归属可能串位，但总额始终精确。
19. **`TokenUsage` 新增 `costSource`** 区分 `platform`（真实扣费）与 `estimate`（BYOK 单价表估算），
    §3 只说「两套数字口径分开标注」，实现落成一个实体字段以便逐行标注。

### 广场付费

20. **registry 价格字段是 `priceCents`（分）而不是 §4 写的 `price`**；缺失/负数/超 ¥100,000 上限一律归一为免费。
    「畸形按免费」不是白嫖口子——真付费项官网仍会 402 兜底；反过来「畸形按付费」会把旧 registry 上的免费项锁死。
21. **价格未确认时照带 Bearer**：安装前那次 registry 列表抖动分不清「真免费」与「付费但价格没查到」，
    不带 Key 的话后者官网必 402，一个真已购的用户会被反过来指控没付费。
22. **未连账户时 402 说「去连接账户」而不是「去购买」**：官网无从查这台机器的购买记录，
    402 只说明没带 Key，不说明用户没买过。
23. **兑换码（gift code）桌面端不接入**（§1 写的是「后续批次可做，本轮只留数据模型位」）。
    官网已实现 `POST /api/account/redeem` 与 `AWDG-` 码格式，桌面解锁门只认 `AWD-T-` 与 `awdk_`，
    兑换在官网完成后靠权益同步生效。

### 安全（Spec 未预见，实现期补）

24. **新增 `LocalModeAccessFilter`**：免登消掉了「必须带自定义头 `X-Session-Id`」这条事实上的 CSRF 防线
    （跨源请求原本会因自定义头触发预检而被拦死），而 `CorsConfig` 只是不回显 ACAO、并不阻断请求本身。
    补三条闸：跨站 Origin 硬拦截（缺 Origin 放行，实测打包态渲染进程不带 Origin）、逐请求回环校验、
    反代痕迹头一律 403。仅 local-mode 生效，server 模式行为一字不变。
25. **凭据文件统一 0600 + 关掉 Jackson 的 `INCLUDE_SOURCE_IN_LOCATION`**：默认 umask 下会落成 0644，
    而解析失败的异常 message 会带原文片段、这些点普遍 `log.warn(..., e.getMessage())`——
    一次半截写入就能把密钥复制进日志。
26. **`ai.account.base-url` 强制 https**（回环 http 例外供本地联调），配成 http 时拒绝启动并给出中文原因。
27. **账户与权益类端点必须要求登录**：`LocalModeAccessFilter` 在 server 模式整体短路，
    而 `deploy/web/nginx.conf.example` 把 `/api/` 整段反代出去，漏检等于把账户隐私暴露给匿名请求。
    两个 `market/list` 因为响应里加了 `purchased`/`accountConnected` 也一并补上登录校验。

### 文案

28. **业务错误文案不许出现「登录」「未授权」「请先」**：`frontend/src/services/api.js` 对 `code:1` 的消息
    做子串匹配识别掉线，命中就清本地会话（浏览器端还跳登录页）。账户未连接、未分配额度、付费项未购买
    全是用户可自行处理的业务状态，不是掉线。已有单测钉住。
29. **协作术语再校一遍**（§7）：「案件管理员」而非「管理人」——后者是《企业破产法》的法定专有名词；
    「协作人」而非「协作律师」——被加进案卷的常是律助/法务/外部顾问。
30. **冲突三选一的粒度是整份文件，界面上不许说「同一处」**（§7 只说「用律师语言解释后果」）。
    JGit 默认 `CONFLICT` 策略 + 二进制 blob 整份判 unmerged，文档类只要两边都动过就整份进裁决清单，
    与改没改在同一条条款无关。写成「同一处」会让律师以为只丢那一处重叠。
