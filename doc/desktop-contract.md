# 桌面/插件 ↔ 官网契约：本仓侧待办条目

**权威契约文档在官网仓** `doc/desktop-contract.md`（人读版）+ `scripts/contract-check.mts`
（机器可执行版）。本文件只记录由本仓（桌面/server 后端）一侧先行提出、**官网侧尚未实施**的
契约条目；官网侧落地后应把条目并入权威文档与 contract-check，并从此处删除。

## 待官网侧实施

（当前无待办条目。）

## 已落地条目（留档，便于回溯当初的判断）

### `POST /api/internal/collab-directory` 同事名录（2026-09-10 实施，dev-board#550 #551）

官方案件库按手机号加同事时，本库 `app_users` 只有**在桌面端桥接过案件库的人**——在官网注册过、
也登录过桌面端的同事查不到，界面还引他「去邀请」，照做完回来仍然查不到（线上核实：表里仅 3 个用户，
被查号码匹配 0）。治本的做法是本地查不到时回官网按手机号/邮箱找账户，找到即按 `accountId`
预建桥接用户，与对方日后自己桥接落到同一行。

**为什么这不违反本文件「per-user 平台 AI key」那条否决**（当时否决的是「server 注册为受信客户端、
凭 accountId 换任意用户 key」的宽权限服务端-服务端主凭据）：

- **只回名录事实，不发任何凭据**：账户是否存在、`accountId`、`username`、`displayName`、`phone`、
  双方的 `teamId`/`firmId`。拿到这份答复换不出任何用户的 key、令牌或会话，
  也换不出邮箱、头像、角色、团队名与律所名（官网侧刻意不回）。
- **泄露上界就是发起方本来该看到的东西**：某个手机号在官网注册过、展示名是什么、和自己同不同团队——
  这正是律师在「加同事」确认卡上要核对的信息，而这条路本来就有按项目管理员的限频
  （`AuthAbuseGuard.checkMemberLookupRate`，查人与加人共用一个计数）。
- **单一用途、单一密钥、可单独撤销**：`AWD_COLLAB_DIRECTORY_SECRET` 只开这一个口，
  与 `AWD_TRANSFER_BILLING_SECRET`（dev-board#251）、`AWD_MOBILE_BILLING_SECRET`（#425）
  是三把互不复用的密钥；撤销 = 把官网那一个 env 拿掉，案件库侧立刻退回「只查本库」。
  被否决的那条是**一把长期主凭据换全站任意用户的 key**，泄露即全站沦陷，且收窄半径还要另建授权表。
- **形状与前两条内部口逐条相同**：同机 `127.0.0.1` 直连 Next、头 `X-Internal-Secret`、
  未配置或密钥不匹配一律裸 404、公网 nginx `location ^~ /api/internal/ { return 404; }` 兜底。

因此本条**不进官网仓的权威桌面契约**（内部口不是桌面契约，同 transfer 的处理），
官网侧文档在 `DEPLOY.md §7.7`；本仓侧实现在 `com.checkba.service.collab`
（属性 `collab.directory.base-url` / `collab.directory.secret`，未配置即整条准入短路成今天的本地行为）。
设计文档：`docs/superpowers/specs/2026-09-10-collab-member-directory-design.md`。

### `GET /api/avatar/{accountId}` 公开头像（2026-09-05 核对，dev-board#444）

「按手机号邀请同事」的确认卡片要显示对方的头像，好让律师在加人之前看清自己加的是谁
（号码打错一位就把陌生人加进了案卷）。案件库服务器手上只有 `account_binding.external_account_id`
（也就是官网的稳定 `accountId`），所以头像地址是本仓拼出来的：
`{ai.account.base-url}/api/avatar/{accountId}`，由**浏览器直接去官网取**，不经服务端代理。

对官网侧的要求，只有三条：

- 匿名可访问（这个地址会出现在桌面端的 `<image src>` 里，不带任何凭据）；
- 没设过头像时返回 404 或任意非图片响应即可——前端 `@error` 会降级成首字母方块，
  **不需要**官网准备一张默认头像；
- 只暴露头像本身，不得在响应头或响应体里带姓名、手机号、邮箱等任何其他账户信息。

**已核对官网仓（aiworkdeckweb `app/api/avatar/[userId]/route.ts`）**：匿名 GET，路径参数就是 users.json 的
uuid 主键（= `accountId`），文件恒为 `data/avatars/<id>.webp`，无头像 404，带 `?v=<avatarUpdatedAt>` 时才挂
immutable 缓存。三条要求均已满足，无需官网改动。改动面收敛在 `ProjectMemberService.avatarUrlFor` 一处。

### `GET /api/account/me` 的稳定 `accountId`（2026-08-06 提出，官网侧已实施）

官网已返回 `accountId`（`users.json` 的 uuid 主键），并已进入官网仓 `doc/desktop-contract.md`
与 `scripts/contract-check.mts`。本仓 `AwdkLoginService` 以它作 `account_binding.external_account_id`
的映射键；缺失时按 MALFORMED 拒绝，**不回落 username**（username 可改名，以它为键会在改名后
凭空生出第二个 server 用户）。

### per-user 平台 AI key（2026-08-07 实施，方案 a）

server 模式的平台 AI 通道原为机器级（`~/.aiworkdeck/platform-ai-key.json` 一台机器一把 key），
多租户下所有用户共享同一额度池，且 `PlatformUsageAccountant` 的差分对账会串位。现按用户化：

- **官网侧零改动**：`POST /api/account/ai-key` 现成、幂等、已进契约与 contract-check，
  server 实例在**桥接登录**（`POST /api/auth/awdk-login`）与**显式刷新**
  （`POST /api/platform-ai/key/refresh`）这两个时刻短暂持有该用户的 awdk_，用它代表该用户调用；
- awdk_ 明文仍然**不落库**，落库的是它换回的 OpenRouter runtime key（AES-256-GCM 密文，
  密钥来自 `AWD_PLATFORM_KEY_SECRET`）；额度由 OpenRouter 侧的 per-key limit 强制；
- 吊销：用户在官网禁用/重发 runtime key → OpenRouter 401/403 → server 侧探针立刻删本地行。

**没有采纳「官网新增服务端-服务端凭据」**（server 注册为受信客户端、凭 accountId 换任意用户 key）：
那把长期主凭据泄露即可拉取全站账户的 key，而要把半径收窄回来又必须再引入 per-account 授权记录，
建立授权仍然要用户的 awdk_ 走一次桥接——安全上界与现方案相同，却多背一把主密钥。

**触发重新评估的条件**：出现**第二方托管的 server 实例**（非我方运营的插件后端要接入官网账户体系）。
届时「撤销某台 server 对我账户的授权」成为刚需，服务端-服务端凭据 + per-account 授权表值得单独立项。
本仓侧的升级面被收敛在 `PlatformAiKeyService.provision/refresh` 这一个出口，
上层（`PlatformAiChannel` 路由、`ChatModelFactory`、对账分桶、身份作用域）不需要改动。

设计文档：`docs/superpowers/specs/2026-08-07-per-user-platform-ai-key.md`。
