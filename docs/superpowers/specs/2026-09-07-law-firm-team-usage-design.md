# 律所管理与团队使用统计（初稿）

dev-board#496。状态：初稿，待维护者拍板两处决策（见第 9 节）。

## 1. 问题

IDE 目前是单点工具。律所有知识沉淀、管理和团队调度的需求：管理层要看板与使用统计
（本周使用人数、新建项目数、登录/打开次数、节约时间、每个项目投入时间与人数），
并能从主账号向下看到各子账号。产品逻辑：每个人先注册为个人用户，之后组建团队/律所；
管理者配好团队结构后，在 IDE 内查看团队工作情况。

## 2. 调研结论（证据已核对，细节见 scratch 报告）

1. 官网和桌面后端**都没有**任何团队/组织/子账号概念。所有资源直接挂 `userId`。纯新建。
2. 匿名 telemetry 账本**不能**承担团队统计：事件表无 userId/projectId，上报无鉴权，
   且 `legal/PRIVACY.md`、README、官网建表注释三处公开承诺「安装标识与账户无关」。
   团队统计必须走另一条带鉴权、显式开启的通道，端点、表、开关全部分离。
3. 真正可用的原料在桌面端本机：`work_session`（projectId + userId + 起止，版本记录默认开启，
   排除 DRAFT，WORK 段含最多 30 分钟空闲尾巴）、telemetry 本地账本的日计数（ai.turn / ai.tool /
   editor.action / project.created / app.start）、`token_usage`。「登录/打开次数」目前无数据源，
   以后端进程启动次数（本机 app.start）近似。
4. 「节约时间」零实现，只能是带系数的估算。
5. 权限：`User.role` 是装饰字段，从不用于鉴权；官网 admin 是单一全站口令。团队角色不能挂在
   这两处任何一处上。
6. 一个人有三个 id（官网 accountId / 云实例 user.id / 本机 user.id），团队只能挂在 accountId 上。
7. 桌面前端从不直连官网，一律经本地后端 `AccountController` 透传，鉴权由 `Bearer awdk_` 承担。

## 3. 设计目标与非目标

目标：
- 官网新增团队实体：创建团队、按手机号邀请、三档角色、退出/移除。
- 桌面端每日出一条**带鉴权**的个人使用日聚合，上报到团队通道；官网按团队加总。
- IDE 设置页新增「团队」分区：无团队时可创建或接受邀请；有团队时看统计看板与成员表；
  管理者可邀请、改角色、移除。
- 隐私：上云的只有计数、枚举、哈希；项目名默认不上云。

非目标（本期不做）：
- 席位计费、团队钱包、团队级权益。
- 团队知识库/文档共享（那是 case.aiworkdeck.com 案件库的事）。
- 精确工时计费（律师工时是另一条产品线，`user_activity_log` 手动录制保持不动）。

## 4. 概念模型

```
team            id / name / ownerAccountId / shareProjectNames(bool, 默认 false) / createdAt
team_member     teamId / accountId / role(OWNER|ADMIN|MEMBER) / joinedAt / invitedBy   唯一 (teamId, accountId)
team_invite     id / teamId / phone / role / createdBy / createdAt / expiresAt / acceptedAt / acceptedAccountId
team_usage_daily    teamId / accountId / date / payload(JSON) / receivedAt      主键 (accountId, date)
team_project_daily  teamId / accountId / date / projectKey / projectLabel(nullable) / minutes / aiTurns / editActions
                    主键 (accountId, date, projectKey)
```

- 一个账号同一时间只属于一个团队（首期约束，简化权限）。团队之上还有一层「律所」，见第 10 节。
- `projectKey`：项目若已绑定团队案件库（有远端仓标识）用 `HMAC-SHA256(teamId, remoteId)` 前 16 位，
  这样同一案件在多人机器上聚成一行；否则用 `HMAC-SHA256(installSecret, localProjectId)` 前 16 位。
- `projectLabel` 只在团队开了 `shareProjectNames` 时随上报携带项目名，否则为 null，
  面板显示 `projectKey` 短码，管理者可在面板里给短码起别名（别名存官网 `team_project_alias`
  表：teamId / projectKey / label）。

## 5. 数据流

```
桌面本机                                        官网
work_session + 本地 telemetry 计数 + token_usage
   │ 每日 TeamUsageRollupService 聚合（按 userId=本机用户，按 projectId 分组）
   ▼
TeamUsageUploadService（启动 + 24h，Bearer awdk_，开关 team.usage.enabled）
   │ POST /api/account/team/usage
   ▼                                            team_usage_daily / team_project_daily upsert
IDE「团队」分区 ──GET /api/account/team/summary──▶ 按 teamId 汇总（OWNER/ADMIN 全员，MEMBER 只看自己）
      └── 本地后端 AccountController 透传（照 membership 模板）
```

日聚合 payload（全部计数或枚举，无文本）：

```json
{
  "date": "2026-09-07",
  "appStarts": 2,
  "activeMinutes": 213,
  "aiTurns": 41, "aiToolCalls": 88, "agentEditActions": 57, "manualEditActions": 120,
  "projectsCreated": 1,
  "tokens": { "platform": 182000, "estimate": 0 },
  "projects": [
    { "projectKey": "9f2c...", "label": null, "minutes": 95, "aiTurns": 20, "editActions": 31 }
  ]
}
```

- `activeMinutes`：当日 work_session（WORK、非 ACTIVE）时长之和，上限 16 小时。
- `appStarts`：本机 telemetry 账本当日 `app.start` 条数。
- 节约时间（官网汇总时算，不上报）：`savedMinutes = agentEditActions * 3 + aiTurns * 2`，
  系数写在一个常量表里，UI 上明写「按每次 AI 编辑 3 分钟、每轮对话 2 分钟估算」。

## 6. 官网 API 契约（Bearer awdk_，`resolveKeyUser`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | /api/account/team | 任意 | 我的团队：`{team, myRole, members[], pendingInvites[]}`；无团队返回 `{team:null, invites[]}`（我手机号收到的邀请） |
| POST | /api/account/team | 无团队者 | `{name}` 创建团队，创建者为 OWNER |
| PATCH | /api/account/team | OWNER/ADMIN | `{name?, shareProjectNames?}` |
| POST | /api/account/team/invites | OWNER/ADMIN | `{phone, role}` 生成邀请（7 天有效）；被邀请人无需已注册 |
| DELETE | /api/account/team/invites/{id} | OWNER/ADMIN | 撤销邀请 |
| POST | /api/account/team/invites/{id}/accept | 被邀请手机号本人 | 接受邀请，加入团队 |
| PATCH | /api/account/team/members/{accountId} | OWNER（改 ADMIN）/ ADMIN（改 MEMBER） | `{role}` |
| DELETE | /api/account/team/members/{accountId} | OWNER/ADMIN，或本人退出 | OWNER 不可退出 |
| POST | /api/account/team/usage | 团队成员 | 上报日聚合（幂等 upsert，body ≤ 64KB，同一 accountId 每分钟 ≤ 10 次） |
| GET | /api/account/team/summary?range=7\|30\|90 | 团队成员 | 见下 |
| PUT | /api/account/team/projects/{projectKey}/alias | OWNER/ADMIN | `{label}` |

`summary` 返回：

```json
{
  "range": 7,
  "kpi": { "activeMembers": 6, "memberCount": 9, "appStarts": 41, "projectsCreated": 4,
           "activeMinutes": 3120, "aiTurns": 380, "savedMinutes": 1120 },
  "savedMinutesFormula": { "perAgentEdit": 3, "perAiTurn": 2 },
  "daily": [ { "date": "2026-09-01", "activeMembers": 4, "aiTurns": 50, "activeMinutes": 410 } ],
  "members": [ { "accountId": "...", "displayName": "...", "role": "MEMBER", "activeDays": 5,
                 "activeMinutes": 620, "aiTurns": 70, "projectsCreated": 1, "lastActiveDate": "2026-09-06" } ],
  "projects": [ { "projectKey": "9f2c...", "label": "别名或 null", "minutes": 900, "members": 3, "aiTurns": 88 } ]
}
```

MEMBER 调用时 `members` 只含自己，`projects` 只含自己参与的。

错误码：401 未登录；403 无权限；409 已有团队 / 邀请已接受；404 团队或邀请不存在。

## 7. 桌面端改动

后端（`com.checkba.service.team` 新包）：
- `TeamUsageRollupService.rollupFor(LocalDate)`：从 `WorkSessionRepository`（新增按 userId+日期的
  finder）、本地 `TelemetryEvent` 计数、`TokenUsage` 组装第 5 节 payload。projectKey 的 HMAC
  用 `InstallIdentityService` 的 install-secret；远端仓标识从版本记录模块取（若无该概念，
  首期全部按本机 id 哈希，写明）。
- `TeamUsageUploadService`：启动后延迟 + 每 24h，只在 `team.usage.enabled=true`（system_setting，
  默认 false）且账户已连接且 `/api/account/team` 返回有团队时上报；补传最近 30 天未确认的日期。
  节奏与静默失败照 `TelemetryUploadService`。
- `AccountController` 新增透传：`GET /team`、`POST /team`、`PATCH /team`、`POST /team/invites`、
  `DELETE /team/invites/{id}`、`POST /team/invites/{id}/accept`、`PATCH /team/members/{id}`、
  `DELETE /team/members/{id}`、`GET /team/summary`、`PUT /team/projects/{key}/alias`、
  `GET/PUT /team/usage-sharing`（本机开关 + 「立即上报」）。`AccountService` 加对应
  `getJson/postJson/patchJson/deleteJson`（缺的方法补齐，HTTP 缝仍在 `AccountTransport`）。

前端：设置页 `AdminPane.vue` 新增 `personal` 组分区 `team`（「团队」），内容三态：
1. 未连接账户：提示先连接账户。
2. 无团队：「创建团队」表单 + 「收到的邀请」列表（接受按钮）。
3. 有团队：
   - 顶部 KPI 磁贴（复用 `OverviewStatsBar` 的 `.stat-tile` 写法）：本周使用人数 / 新建项目 /
     打开次数 / 投入时长 / 节约时间（估算，带脚注）；7/30/90 天切换（照 telemetry 分区）。
   - 成员表（`.telemetry-list` 写法）：姓名、角色、活跃天数、投入时长、AI 轮次、最近活跃；
     管理者行尾有「改角色 / 移除」。
   - 项目表：短码或别名、投入时长、参与人数、AI 轮次；管理者可点短码起别名。
   - 管理区：邀请（手机号 + 角色）、待接受邀请列表、团队设置（改名、是否共享项目名）。
   - 数据共享开关（`AwdSwitch`，`team.usage.enabled`，默认关）+ 「立即上报」+ 上次上报时间。
   - 空态：有团队但无数据时提示「成员开启数据共享后次日可见」。

隐私文案：分区顶部一句「团队统计只上传计数与匿名项目编号，不含文档内容、文件名与对话」，
并同 PR 在 `legal/PRIVACY.md` 加「团队使用统计（可选）」一节。

## 8. 测试

- 官网：`lib/team.ts` 纯函数（角色判定、summary 聚合、savedMinutes）用 node 脚本测试；
  `npm run typecheck && npm run lint && npm run build` 通过。
- 桌面后端：`TeamUsageRollupServiceTest`（DRAFT 排除、16 小时上限、projectKey 哈希稳定）、
  `TeamUsageUploadServiceTest`（开关关不发、无团队不发、补传窗口）、`AccountController` 透传测试
  照 membership 现有用例打桩 `AccountTransport`。
- 前端：`frontend/tests/team/*.test.mjs` 源码级断言（分区接链尾、三态分支、开关默认关、
  i18n 成对），接进 `ci.yml`。

## 9. 待维护者拍板

1. **项目名是否允许上云**。默认不上（只传哈希，管理者起别名），团队设置里可整体打开。
   若你认为律所内部共享项目名是理所当然的，把默认值翻成 true 即可。
2. **节约时间系数**：每次 AI 编辑 3 分钟、每轮对话 2 分钟，是拍脑袋的初值，UI 上明写公式。
3. 首期「一人一团队」的约束是否可接受（律师同时挂两家所的情况极少）。

## 10. 层级、加入流程与入口地图（2026-09-07 晚，维护者补充要求）

维护者要求：除了最终看板，必须有一套层级架构与加入顺序，并且**每一层能力都要有看得见的 UI 入口**
（尽调插件的教训：核心能力做好了、UI 上没入口，用户不知道怎么用，只能重新发版）。

### 10.1 三层结构

```
个人用户（官网注册）
  └─ 团队 team（一人一团队；OWNER / ADMIN / MEMBER）
       └─ 律所 firm（多个团队并入；由「总部团队」的 OWNER/ADMIN 管理）
```

- `firm`：id / name / headTeamId / joinCode / createdAt。律所没有独立的人员名单，
  管理者就是总部团队的 OWNER/ADMIN，这样不破坏「一人一团队」。
- `team` 增加：`firmId`（可空）、`joinCode`（8 位大写字母数字，唯一，可重置）。
- 权限：总部团队 OWNER/ADMIN 看全所；子团队 OWNER/ADMIN 看本团队明细 + 全所 KPI 合计；
  MEMBER 只看自己 + 本团队合计。

### 10.2 加入方式（每条都有入口）

| 动作 | 谁 | 怎么做 | 入口 |
|---|---|---|---|
| 创建团队 | 无团队的个人 | 填团队名 | IDE 设置「团队」/ 官网账户页「我的团队」 |
| 加入团队（邀请码） | 无团队的个人 | 输入 8 位团队邀请码 | 同上，与「创建」并排 |
| 加入团队（被邀请） | 无团队的个人 | 管理者按手机号邀请，本人在「收到的邀请」点接受 | 同上 |
| 邀请成员 | 团队 OWNER/ADMIN | 显示并复制邀请码；或按手机号定向邀请 | 团队看板「成员」区顶部 |
| 创建律所 | 团队 OWNER | 填律所名，本团队成为总部团队 | 团队看板「律所」区 |
| 团队并入律所 | 团队 OWNER | 输入律所邀请码 | 团队看板「律所」区 |
| 邀请团队加入律所 | 总部团队 OWNER/ADMIN | 显示并复制律所邀请码 | 律所看板「团队」区 |
| 团队退出律所 / 律所移除团队 | 该团队 OWNER / 总部 OWNER/ADMIN | 按钮 | 同上 |

### 10.3 API 增量（官网，Bearer awdk_）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | /api/account/team/join | 无团队者 | `{code}` 按邀请码加入 |
| POST | /api/account/team/join-code/regenerate | OWNER/ADMIN | 重置团队邀请码 |
| POST | /api/account/team/firm | 团队 OWNER，且团队不在任何律所 | `{name}` 创建律所，本团队为总部 |
| POST | /api/account/team/firm/join | 团队 OWNER，且团队不在任何律所 | `{code}` 团队并入律所 |
| PATCH | /api/account/team/firm | 总部 OWNER/ADMIN | `{name?}` |
| POST | /api/account/team/firm/join-code/regenerate | 总部 OWNER/ADMIN | 重置律所邀请码 |
| DELETE | /api/account/team/firm/teams/{teamId} | 总部 OWNER/ADMIN，或该团队 OWNER 退出 | 总部团队不可退出 |
| GET | /api/account/team | 任意 | 响应增加 `joinCode`（仅 OWNER/ADMIN 可见）、`firm`（null 或 `{id,name,headTeamId,isHead,joinCode?,teams[{id,name,memberCount,isHead}]}`） |
| GET | /api/account/team/summary?range=&scope=team\|firm | 成员 | `scope=firm` 只对律所内团队可用；子团队管理者拿到 `kpi` 全所合计 + 本团队 `members/projects`；总部管理者 `members/projects` 全所并附 `teams[]` 按团队合计 |

### 10.4 入口地图（验收清单，缺一项算没做完）

1. IDE 设置页导航「团队」常显（personal 组），不依赖任何开关。
2. IDE 设置页「账户」分区在已连接账户时显示一行「团队：未加入 / 团队名 · 律所名」并可跳到「团队」分区。
3. 官网账户页新增「我的团队」页签，功能与 IDE 分区一致（创建/加入/邀请码/成员/律所/看板）。
4. 无团队态同时展示「创建团队」「输入邀请码加入」「收到的邀请」三条路，不藏在二级菜单。
5. 团队看板里「律所」区常显：未入所时给「创建律所」「输入律所邀请码并入」，入所后给律所名、团队列表、
   看板范围切换（本团队 / 全所）。
6. 数据共享开关与「立即上报」在团队看板顶部，不藏在系统设置深处。

### 10.5 桌面侧实现与本节的偏差（2026-09-07 实现记）

本节 §10.1-§10.4 的桌面侧已实现（官网侧由另一路并行做）。**与上文字面不同的地方，以代码为准**：

1. **本机透传层的 `PATCH` 一律用 `PUT`。** §10.3 的表写的是官网侧动词；桌面前端只有
   `uni.request` 一个出口，它的 method 枚举里根本没有 PATCH。所以「改律所名」在
   `/api/account/team/firm` 上是 `PUT`，出站到官网那一跳仍是 `PATCH`（`AccountService.updateFirm`）。
   与既有的「改团队设置」「改成员角色」同一处理，护栏
   `AccountServiceTest.teamHierarchyOutboundShape`。
2. **`GET /team/summary` 的 `scope` 在桌面端只做枚举归一，不做鉴权。** 非 `firm` 一律回落 `team`，
   但「能不能看全所」完全交给官网判——把角色判定抄一份到桌面端，等于给了「改本机一个值就看全所」
   的机会。`TeamPanel` 的 `canManage` / `canManageFirm` 同理，只决定按钮显不显示。
3. **「是不是总部团队」读 `firm.isHead`，不用 `headTeamId`。** §10.3 的 `firm` 形状两个字段都有，
   实现只认 `isHead`：拿 `firm.headTeamId === team.id` 自推，两个字段里任何一个缺失或改名都会推错，
   而推错的后果是把「移出团队」按钮显示给不该有的人。
4. **「退出律所」复用 `DELETE /team/firm/teams/{teamId}`**，前端传自己的 `team.id`。
   `team.id` 取不到时不发请求——同 §7 里 `myAccountId` 那条，猜一个 id 出去会把别人的团队踢出律所。
5. **邀请到期时间来自服务端的 `expiresAt`（字段名待官网确认）。** 取不到时界面说「有效期以官网为准」，
   **绝不按 §6 的「7 天有效」在前端算一个日期**——那个数字看起来精确，官网一改有效期就在骗人。
6. **「各团队」表只在 `summary.teams` 存在时渲染**，不拿 `firm.teams` 顶——后者只有名册
   （id/name/memberCount/isHead），没有统计数字。
7. **KPI 第一块的文案是「使用人数」不是「本周使用人数」**（§7 原文）：档位可切 7/30/90，
   写死「本周」在另外两档上是错的。「几人里有几人」走 caption，分母（`kpi.memberCount`）
   取不到时整行不显示。
8. 官网侧 §10.4 第 3 条（账户页「我的团队」页签）不在本仓，桌面侧无从验证。
9. **KPI 磁贴的列数写死成 5**（`repeat(5, minmax(0,1fr))`），不用弹性列。走查发现的 4+1 不是
   一个断点没调好，而是任何「按可用宽度自动定列数」的写法都会在某个常见窗口宽度上把 5 个元素
   排成 4+1：`flex: 1 1 140px` 在 1440 上裂，`auto-fit + minmax(120px,1fr)` 在 1280 上裂
   （实测：容器 795/735/690 五块同排，600 变 4+1）。固定五列实测容器 600px 以上五块同排、
   文字零裁切；再窄的容器与本页其它表格同一处境（`.team-table` 本来就是 `min-width:560px` +
   横向滚动）。
