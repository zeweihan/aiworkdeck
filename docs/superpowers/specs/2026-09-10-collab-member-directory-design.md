# 协作加人：回官网找账户 + 律所/团队资格门（dev-board#550 #551）

日期：2026-09-10。涉及两个仓：本仓（案件库 server 侧 + 桌面端弹窗）与官网仓 `aiworkdeck_website`。

## 1. 问题

官方案件库（backend.jar 第二实例，profile `case`）的「把同事加进案卷」按手机号查人时只查本库
`app_users` 表。这张表只有**桌面端桥接过案件库**的人（`AwdkLoginService.resolveUser` 建 `awd_` 用户
并 `claimPhoneFromWebsite`），普通登录桌面端不触发桥接。于是在官网注册过、也登录过桌面端的同事
被提示「没有这个用户 / 对方还没用这个手机号登录过 AI WorkDeck」，「去邀请」链到官网下载页，
照着做完回来仍然查不到。线上核实（2026-09-10）：表中仅 3 个用户，被查号码匹配 0。

## 2. 维护者裁决

1. 治本：案件库本地查不到时**回官网按手机号/邮箱找账户**，找到即按 `accountId` 预建桥接用户
   （与日后对方自己桥接落到同一行）并允许加入。
2. 资格门：当前是「律所 → 团队 → 律师」三级结构，加人双方**至少同一律所；没有律所的，同一团队**。
   判定单独成层、可整体替换——将来律师之间只按项目连接时把这层换成「放开」即可，上下游不动。
3. 文案说实话：区分「还没注册」「不在你的律所/团队」「你自己还没加入团队」，落点各给对的动作。
4. 查人（lookup）与加人（POST members）同一道门。
5. 先不发客户端版本；服务端（官网 + 案件库）可先上线，老客户端照样能显示新文案。

## 3. 安全边界（沿用已有先例，不开新口子）

官网早已否决「服务端凭 accountId 换任意用户 key」这类宽权限服务间凭据
（本仓 `doc/desktop-contract.md`「per-user 平台 AI key」）。本次**不违反**那条裁决：新口只回
名录信息（账户是否存在、展示名、手机号、团队/律所归属），**不发任何凭据**，形状照
`POST /api/internal/transfer`（dev-board#251）与 `/api/internal/account`（#425）：
同机直连 `127.0.0.1:<next 端口>`、头 `X-Internal-Secret`、专用密钥、未配置或不匹配一律裸 404、
公网 nginx `location ^~ /api/internal/ { return 404; }` 已在线上。

## 4. 官网侧：`POST /api/internal/collab-directory`

文件 `app/api/internal/collab-directory/route.ts`，纯逻辑抽到 `lib/collab-directory.ts` 便于 verify 脚本直调。

鉴权：`process.env.AWD_COLLAB_DIRECTORY_SECRET`，`timingSafeEqual`；未配置 / 头缺失 / 不匹配 → `404` 空体。

请求体（JSON，≤ 64KB，`readJsonBody`）：

```json
{ "requesterAccountId": "uuid | null", "identifier": "手机号或邮箱", "candidateAccountId": "uuid" }
```

`identifier` 与 `candidateAccountId` 恰好给一个，否则 `400 {error:"bad_request"}`。

解析：
- `identifier` 含 `@` → `normalizeEmail`（无效 → `found:false`），按 `email` 精确匹配未注销、**且已验证**
  （`emailVerifiedAt` 有值）的用户——密码注册时填的邮箱谁都能填别人的（新增只读 `findUserByEmail`，
  不用会建号的 `findOrCreateByEmail`；与桌面仓 `findByVerifiedEmail` 同口径）；
- 否则 `normalizePhone`（无效 → `found:false`），`findUserByPhone`；
- `candidateAccountId` → `findUserById`（注销视为不存在）。
- `requesterAccountId` 为空或查无 → `requester` 归属回 `{teamId:null, firmId:null}`（**不** 404：
  案件库上未桥接的操作账号也要拿到可解释的拒绝理由）。

归属：`getMembership(accountId)` → `getTeam(teamId)` → `{teamId, firmId: team.firmId ?? null}`。

响应 `200`：

```json
{ "found": false, "requester": { "teamId": null, "firmId": null } }
```

```json
{ "found": true,
  "account":   { "accountId": "…", "username": "…", "displayName": "…", "phone": "1xxxxxxxxxx | null" },
  "requester": { "teamId": "… | null", "firmId": "… | null" },
  "candidate": { "teamId": "… | null", "firmId": "… | null" } }
```

**不返回** 邮箱、头像、角色、团队名、律所名——案件库那侧只需要 `phone` 做认领，其余是泄露。

测试：`scripts/verify-collab-directory.mts`（照 `verify-team.mts` 空目录直调路由的写法），覆盖：
未配置 404、错密钥 404、两个定位键同时给 400、手机号命中、邮箱命中、无效手机号 `found:false`、
注销用户 `found:false`、requester 无团队、同团队、同律所不同团队、`candidateAccountId` 路径。
加 `package.json` 脚本 `test:collab-directory` 并进 `.github/workflows/ci.yml` contracts 循环。

文档：`DEPLOY.md` 新增 §7.7，格式照 §7.4/§7.5；env 名 `AWD_COLLAB_DIRECTORY_SECRET`（仅国内站需要，
国际站未配置即 404）。**不进** `doc/desktop-contract.md`（内部口不是桌面契约，同 transfer 的处理）。

## 5. 案件库 server 侧（本仓 backend）

新包 `com.checkba.service.collab`：

```java
public interface AccountDirectoryClient {
    boolean configured();
    DirectoryReply lookupByIdentifier(String requesterAccountId, String identifier);
    DirectoryReply lookupByAccountId(String requesterAccountId, String candidateAccountId);
}
public record DirectoryReply(boolean found, DirectoryAccount account, OrgMembership requester, OrgMembership candidate) {}
public record DirectoryAccount(String accountId, String username, String displayName, String phone) {}
public record OrgMembership(String teamId, String firmId) { public static final OrgMembership NONE = new OrgMembership(null, null); }

/** 可整体替换的资格判定层。 */
public interface CollaborationPolicy {
    Verdict check(OrgMembership requester, OrgMembership candidate);
}
public enum Denial { NOT_REGISTERED, NOT_IN_ORG, REQUESTER_NO_TEAM }
public record Verdict(boolean allowed, Denial denial) {}
```

实现：
- `HttpAccountDirectoryClient`：照 `HttpTransferBillingClient`（JDK HttpClient、15s/10s 超时、
  `X-Internal-Secret`、`POST {base}/api/internal/collab-directory`）。属性
  `collab.directory.base-url`（env `COLLAB_DIRECTORY_BASE_URL`）、`collab.directory.secret`
  （env `COLLAB_DIRECTORY_SECRET`），任一为空 → `configured()=false`，不发请求。
  网络失败 / 非 200 → 抛 `DirectoryUnavailableException`，上层译成「暂时没能核对同事身份，请稍后再试」
  （这是故障，不是「没找到」，前端红字）。
- `SameFirmOrTeamPolicy`（当前）：requester.teamId 为空 → `REQUESTER_NO_TEAM`；candidate.teamId 为空 →
  `NOT_IN_ORG`；两边 firmId 都非空且相等 → 通过；teamId 相等 → 通过；否则 `NOT_IN_ORG`。
- `OpenPolicy`：一律通过（将来「只按项目连接」时切到它；自建服务器也用它）。
- 选择：属性 `collab.eligibility.policy`（env `COLLAB_ELIGIBILITY_POLICY`），取值 `firm-or-team | open`，
  `application.yml` 默认 `open`，`application-case.yml` 显式 `firm-or-team`。用 `@Bean` 按值挑实现。

`AwdkLoginService` 新增公开方法 `User ensureBridgedUser(String accountId, String username, String displayName, String phone)`
= 既有私有 `resolveUser` + `userService.claimPhoneFromWebsite`，受 `requireEnabled()` 约束；不签发令牌、不碰平台 key。

新服务 `CollaboratorAdmission`（同包）封装整条流程，`ProjectMemberService` 以
`@Autowired(required = false)` **字段注入**（不改构造器，`version-control.md` 地雷 47），加
`setCollaboratorAdmissionForTest(...)`：

```
admit(Optional<User> local, String identifier, Long requesterId) -> Admission { User user | Denial denial }
  if admission 为 null 或 !directory.configured(): 维持今天的本地行为（local 有则用，无则 NOT_REGISTERED 语义）
  requesterAccountId = accountBinding(requesterId) 或 null
  local 有且有绑定  -> directory.lookupByAccountId(requesterAccountId, 该绑定 accountId)
  local 有但无绑定  -> 不出网；双方归属按 NONE 交给 policy，拒绝时统一报 NOT_IN_ORG（没有官网身份的人
                      不可能在任何团队里，理由落在对方身上；case 库里这种只有 admin）
  local 无          -> directory.lookupByIdentifier(requesterAccountId, identifier)
  reply.found 为假且 local 无 -> Denial.NOT_REGISTERED
  verdict = policy.check(reply.requester, reply.candidate)；不通过 -> 该 Denial
  user = local 或 awdkLoginService.ensureBridgedUser(reply.account...)
```

`lookupMember` 与 `resolveMemberUser`（addMember 用）都改走 `admit`。`MemberLookup` 记录新增字段
`String reason`（`Denial` 名或 null），`ProjectMemberController` 回包 `data.reason`；
`CloudSyncService.proxyMemberLookup` 原样透传 map，不用改。**拒绝时不回 displayName / avatar / maskedContact**
（存在与否可以说，是谁不能说）。

文案（`LangText.of(zh, en)`，集中在 `ProjectMemberService.notFoundMessage(identifier, denial)`）：

| Denial | zh | en |
|---|---|---|
| NOT_REGISTERED（手机号） | 还没有人用这个手机号注册 AI WorkDeck 账户，请让对方先注册并登录一次桌面端 | Nobody has registered an AI WorkDeck account with that phone number yet. Ask them to register and sign in to the desktop app once |
| NOT_REGISTERED（邮箱） | 同上，把「手机号」换成「邮箱」 | same with "email" |
| NOT_IN_ORG | 对方已有 AI WorkDeck 账户，但不在你的律所或团队里。先在设置「团队」里把对方邀请进团队，再把人加进案卷 | This person has an AI WorkDeck account but is not in your law firm or team. Invite them to your team under Settings > Team first, then add them to the case |
| REQUESTER_NO_TEAM | 你还没有加入团队。协作对象要和你在同一律所或同一团队，先在设置「团队」里创建或加入团队 | You have not joined a team yet. Collaborators must share your law firm or team. Create or join a team under Settings > Team first |

既有测试只断言消息含「手机号」/「邮箱」且不以「用户不存在」开头，新文案满足。
案件库全服一个语言（`AppLanguageService`），不是按请求，本次不改。

## 6. 桌面端（本仓 frontend）

两个加人弹窗——项目列表页的 `InviteMemberDialog.vue` 与工作台的 `components/collab/CollabDialog.vue`
（用户截图的那个）——未找到块都按 `reason` 分三态；`reason` 缺失（老服务端）落回 NOT_REGISTERED 的呈现：

| reason | 标题 | 正文 | 动作 |
|---|---|---|---|
| NOT_REGISTERED / 缺失 | 还没有这个账户 | 服务端 message | 「去邀请」→ 现有邀请链接 `{site}/{zh|en}/start` |
| NOT_IN_ORG | 对方不在你的律所或团队 | 服务端 message | 「去团队设置」→ 打开设置页「团队」分区 |
| REQUESTER_NO_TEAM | 你还没有加入团队 | 服务端 message | 「去团队设置」→ 同上 |

纯逻辑放 `utils/memberLookup.js`（不许 import 别名）：`notFoundPresentation(reason) -> { titleKey, action: 'INVITE_LINK' | 'TEAM_SETTINGS' }`，
`tests/member-invite/member-lookup.test.mjs` 加用例。i18n 键 zh-CN / en-US `version.js` 同步加。
「去团队设置」的跳转复用设置页「账户」分区已有的「团队：… 跳到团队分区」那条路径（`sidebar-shell.md`），
工作台内跳转按导航总规则走 `reLaunch`。

## 7. 部署顺序（维护者亲手做）

1. 官网 PR 合并 → CI 自动部署两站；国内站 `.env.local` 加 `AWD_COLLAB_DIRECTORY_SECRET`
   （`openssl rand -base64 32`），`pm2 restart aiworkdeck-website --update-env`。
2. 案件库 `/opt/aiworkdeck/case/env` 加 `COLLAB_DIRECTORY_BASE_URL=http://127.0.0.1:3000`、
   `COLLAB_DIRECTORY_SECRET=<同上>`，按既有配方换 jar 重启 `aiworkdeck-case`。
3. 用被查号码从服务器上 curl 内部口核对 `found` 与归属；再从桌面端（现版本）走一次弹窗看新文案。
4. 桌面端改动随下次集中发版。

## 8. 非目标

- 不改 `AuthAbuseGuard` 限频（回官网这一跳计入既有查人计数）。
- 不做邮箱预填到桥接用户的 `verifiedEmail`（下次按邮箱查会再走一次名录并按 accountId 命中同一行）。
- 不给国际站配名录口（案件库只在国内站派生）。
- 不改 `CloudSyncService.proxyMembers` 的 `username` 键（服务端已按 identifier 解析）。
