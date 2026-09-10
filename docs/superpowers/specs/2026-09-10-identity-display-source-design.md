# 身份展示统一：展示名与头像以官网为唯一权威源，用户名退成内部标识（dev-board#564 #565 #566 #567）

日期：2026-09-10。涉及官网仓 `aiworkdeck_website` 与本仓（案件库 server、桌面端后端与前端）。

## 1. 问题

手机号注册的官网账户自动生成：用户名 `u`+随机串（如 `upoxwcdtg`），展示名打码手机号（`185****5325`）。
线上 25 个账户 14 个如此。现状：

- 用户名两边都改不了；展示名只有官网账户页能改；头像官网能改、桌面端也能改但传到本机表，与官网无关。
- 桌面端 `account.json` 与案件库 `awd_` 用户在连接/桥接那一刻抄一份展示名，之后永不刷新。
- 同事看到的字段不一致：加人确认卡用展示名 + 官网头像（对）；参与人列表 `displayName || username`、头像用本机那份（桥接用户没有）；
  **版本时间线作者与文档批注/修订作者直接写用户名**——手机号注册的同事在时间线和修订里就是一串随机字母。

## 2. 维护者裁决（2026-09-10）

1. 官网的展示名与头像是**唯一权威源**，其他地方只读、随官网刷新。
2. 案件库每次桥接、每次名录回查刷新展示名；参与人列表、版本作者、批注/修订作者一律展示名；头像统一按 accountId 取官网。
3. 桌面端「个人设置」可改昵称、传头像，**写到官网**；官网补 Bearer awdk_ 版接口；桌面端本机头像上传只留给自建服务器。
4. 手机号注册默认展示名仍是打码号时，官网与桌面端引导「填写你的姓名」。
5. 用户名按 uid 处理：**不改名**（改名断 `/u/用户名` 与 Skill 归属链接）、**任何界面不再当名字显示**（含 `@用户名` 小字），
   用契约测试守住。用户分不清用户名与展示名，那就只给他看一个。

## 3. 官网侧契约（`aiworkdeck_website`）

| 方法 | 路径 | 鉴权 | 变化 |
|---|---|---|---|
| GET | `/api/account/me` | Bearer awdk_ | 新增 `avatarUpdatedAt: string \| null`、`displayNameIsDefault: boolean` |
| PATCH | `/api/account/profile` | **Cookie 或 Bearer**（`resolveSessionOrKeyUser`） | body `{displayName?, bio?}` 不变；回 `{displayName, bio}` |
| POST | `/api/account/avatar` | Cookie 或 Bearer | multipart `file`，不变；回 `{avatarUpdatedAt}` |
| DELETE | `/api/account/avatar` | Cookie 或 Bearer | 回 `{avatarUpdatedAt: null}` |
| GET | `/api/avatar/{accountId}?v=` | 匿名 | 不变 |

`displayNameIsDefault` 的判定放 `lib/users.ts` 单一函数 `isDefaultDisplayName(user)`：展示名为空、等于 `maskPhoneForName(phone)`、
等于 `maskEmailForName(email)`、或等于 `username`（密码注册留空时的默认）之一。官网账户页 `ProfileCard` 在为真时顶部给一条
「填写你的姓名，同事在案卷里看到的就是它」横幅，点开即现有 `ProfileDialog`。

`doc/desktop-contract.md` 与 `scripts/contract-check.mts` 同步：`/me` 两个新字段；profile / avatar 三个端点进契约并标「Cookie 或 Bearer」。
verify 脚本：`verify-profile.mts` 加 Bearer 路径用例（PATCH 昵称、POST/DELETE 头像、`/me` 两字段、默认名判定的四种形态）。

用户名：官网侧**不新增改名接口**；`ProfileCard` 的 `@username · email` 一行去掉 `@username`（邮箱保留，手机号注册的邮箱为空时整行不显示）；
公开主页 `/u/[username]` 的 `@handle` 小字去掉，URL 不动。

## 4. 案件库 server 侧（本仓 backend，profile `case`）

- `AwdkLoginService.resolveUser`：binding 已存在时，若官网给的 `displayName` 非空且与本地不同，**更新本地 displayName**（username 不动）。
  `login`、`ensureBridgedUser` 同享。`CollaboratorAdmission` 命中本地已有用户走 `lookupByAccountId` 时，也用回包的 `account.displayName` 刷新
  （`DirectoryReply.account` 在 `found:true` 时总带）。
- `ProjectMemberController.getMembers`（含 owner）：`displayName` 照旧，`avatarUrl` 改用 `ProjectMemberService.avatarUrlFor(user)`
  （本机有就本机，否则官网 `/api/avatar/{accountId}`），**`username` 字段保留一版**给老客户端，前端不再读。
- `VersionController.userName(userId)`：改为 `displayName`，空则 `username`。已写入的历史 `authorName` 不回填。
- `CloudSyncService.proxyMembers` 透传不改。

## 5. 桌面端后端（本仓 backend，local-mode）

新增 `AccountProfileController`（或并入既有账户控制器，按 licensing-billing.md 的布局）：

| 方法 | 本机路径 | 出站到官网 | 说明 |
|---|---|---|---|
| GET | `/api/account/profile` | `GET /me` | 回 `{accountId, displayName, avatarUrl, displayNameIsDefault}`；`avatarUrl` = `{base}/api/avatar/{accountId}?v={avatarUpdatedAt}`，无头像为 null。**不回 username** |
| PUT | `/api/account/profile` | `PATCH /api/account/profile` | body `{displayName}`；uni.request 无 PATCH，本机用 PUT（同团队接口先例，`AccountServiceTest.teamHierarchyOutboundShape` 那条纪律） |
| POST | `/api/account/avatar` | `POST /api/account/avatar` | multipart 转发；2MB 上限沿用官网的错误码 |
| DELETE | `/api/account/avatar` | `DELETE /api/account/avatar` | |

每次成功写入后、以及 **连接账户时与每次 `GET /api/account/status`（应用启动会拉）** 时，把官网 `displayName`/`avatarUrl` 同步到本机 `User` 行
（`LocalIdentityService` 的「真实账号 displayName 一个字不动」纪律只针对本机 admin 改名，这里是随权威源刷新，在 licensing-billing.md 改写那条说明）。
`account.json` 里的 `displayName` 一并刷新。未连接账户或官网不可达：不动本机行，不报错。

既有 `POST /api/users/avatar`（本机上传）保留给自建服务器（非 local-mode）；local-mode 下前端不再调它。

## 6. 桌面端前端（本仓 frontend）

- **个人设置**（`PersonalSettingsPanel.vue`「基本信息」）：头像可点上传/删除，昵称可编辑（上限 24，与官网一致）。
  local-mode 且已连接账户 → 走 §5 四个端点；自建服务器 → 昵称不可编辑（服务器侧无接口，本期不做）、头像走既有 `POST /api/users/avatar`。
- **姓名引导**：`displayNameIsDefault` 为真时，个人设置「基本信息」顶部与设置「账户」分区各给一条「填写你的姓名」内联提示，
  点了就聚焦昵称输入；首次连接账户成功后弹一次（`uni.showModal` 级别即可，可「稍后」）。
- **用户名清零**：`AdminPane.vue` 侧栏用户卡去掉 `@username` 一行；`CollabDialog.vue` 参与人行 `m.displayName || m.username` 改为
  `m.displayName || $t(...)`（用既有的兜底文案键，找不到就加 `version.unnamedColleague`「同事」）；`LibreOfficeEditor.currentAuthorName`
  改为 `displayName || nickname || name`，**不再回落 username**（空则回 `''`，让引擎用默认作者）；`VersionTimeline.vue` 不改（吃后端 authorName）。
- **契约测试** `frontend/tests/identity/display-name-only.test.mjs`：扫描 `src/**/*.vue`，凡模板插值或 `||` 回落里出现 `.username`
  即失败；允许名单只放登录表单输入、后台用户管理列表、以及 `AdminPane` 账户分区那一处（如仍需展示登录名给自建服务器用户）——名单写在测试旁的
  `display-name-only.allowlist.json`，每条带理由。

## 7. 顺序与发版

1. 官网 PR 先合（契约先落地、自动部署）。
2. 本仓一个 PR 承载 §4 §5 §6；合并后案件库换 jar（§4 立刻生效）。桌面端改动随集中发版。
3. 复测：手机号注册的同事被加进案卷后，参与人列表、时间线新条目、批注作者都显示展示名；官网改名后重开桌面端两处都跟着变；
   桌面端个人设置改昵称、传头像，官网账户页立刻一致；默认名时两端都看到「填写你的姓名」。

## 8. 非目标

- 不做用户名改名；不改 `/u/[username]` 路由。
- 不回填历史版本条目与既有文档里的作者名。
- 自建服务器（非 local-mode）用户的昵称编辑本期不做。
- 不改手机号注册的默认生成规则本身（先引导填写，够用）。
