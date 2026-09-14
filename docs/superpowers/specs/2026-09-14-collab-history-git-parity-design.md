# 协作历史：与程序员用 git 的体验完全对齐（设计稿）

日期：2026-09-14。看板卡：dev-board#623（提示文案）、#624（提交历史标签页）、#625（参与人重复）。
用户裁决：**不分期、不阉割，一次交付最终成果**；有缺口就补上。

## 0. 背景与现状（已核实）

- 顶栏「同事交了新稿」的判据是纯 ref 比较（`CloudSyncService.cloudStatus` 的 `remoteAhead`），不读作者。
  同一官网账号在两台机器上桥接案件库落到**同一行** `app_users`，远端提交署名与本机相同。
- 提交署名：`authorName` 统一取展示名；`authorEmail` 有两套合成公式
  （`{name}@aiworkdeck.local` 与 `user-{本机userId}@aiworkdeck.local`），后者在不同人之间会碰撞。
  `VersionEntry` 没有邮箱字段。
- 时间线端点 `GET /version/timeline` 每节点 8 字段（sha/message/authorName/when/kind/note/parents/milestone），
  `kind` 只有 `auto|session`；没有分支名、没有裁决记录；变更清单另一端点按需拉。
- **交稿（push）与签出（clone）在 git 历史里零痕迹**；「取回最新稿」提交信息是常量。
- **冲突三选一的裁决结果不落库**，裁决合并与干净合并同形。
- 案件库服务端 `GitHttpController` 鉴权时已解析出 userId，但 `deny()` 丢掉返回值，
  `postReceiveHook → ingestPushedMainline(projectId, old, new)` 不带推送者。
- 参与人「2 人」：本机成员表 `hanzewei` 与案件库成员表 `awd_hanzewei` 是同一官网账户
  （头像 URL 同一 accountId），前端按 username 字符串去重所以显示两次。案件库侧该案卷只有 1 行成员。

## 1. 目标：程序员在 IDE 里看 git 历史能做的，律师在这里都能做

对照表（界面延续零 Git 术语纪律，只换词不减能力）：

| 程序员的 git / IDE | 本产品界面 | 状态 |
|---|---|---|
| `git log --graph --all` 分支泳道图 | 主线 + 各稿 + 案件库最新稿 的泳道图 | 新增 |
| 提交行：hash / 作者 / 时间 / 消息 / refs 标签 | 版本行：短编号 / 作者（本人标「你」）/ 时间 / 标题 / 标签（主线、稿名、案件库、本机） | 新增 |
| 提交类型辨识（merge、revert、initial） | 结束工作 / 取回最新稿 / 采纳一稿 / 退回 / 初始版本 / 自动存档 | 新增 kind 细分 |
| 点提交看变更文件 + 每文件 diff | 展开看增删改清单 + 每份文件「对比」 | 复用既有对比标签 |
| 任意两个提交 compare | 多选两行「对比这两版」→ 文件清单 → 逐文件对比 | 新增 |
| 按作者 / 路径 / 消息 / 日期过滤 | 工具栏筛选：参与人 / 文件 / 关键词 / 日期 | 新增 |
| ahead/behind 计数 | 「本机领先 N 版 · 案件库领先 M 版」 | 新增 |
| 远端 reflog：谁 push、谁 clone | 事件行：谁何时交了稿（含几版）、谁签出了一份、谁加入/被加入、谁取回 | **新增（服务端事件表）** |
| 冲突合并的 resolution 记录 | 「这次合并里 A.docx 留了你这边 / 留了同事那边 / 两边都留」 | **新增（提交尾注）** |
| 设备/来源辨识 | 事件与文案区分「你在另一台电脑」与「同事」 | 新增（设备名 + 账户级作者标识） |
| checkout / revert / branch from commit | 退回到这一版 / 从这一版另起一稿 / 标记重要版本 | 复用 `VersionNodeDetail` 逻辑 |
| 分页与大仓库 | 游标分页，默认 100 行，滚到底加载 | 新增 |
| 键盘 | 上下选行、Enter 展开、Esc 收起 | 新增 |

## 2. 数据层改动

### 2.1 提交署名统一为账户级标识（对新提交生效）

- 新增 `VersionAuthorResolver`（`com.checkba.version` 包）：唯一出口 `AuthorIdent resolve(projectId, userId)`
  → `{name, email}`。`name` 仍是 `UserService.signatureName`。`email` 规则：
  项目已绑定案件库（`ProjectRemote` 存在）→ `{CloudConnection.username}@collab.aiworkdeck.local`（那个 `awd_xxx`，跨机器稳定、跨人唯一）；
  未绑定 → `{本机 username}@local.aiworkdeck.local`。
- 收敛四处：`WorkSessionService.email(String)`、`VersionController.email(Long)`、
  `VersionLifecycleService.authorEmail(Long)`、`CloudSyncService.authorEmail(Long,String)` 全部改调 resolver；
  `user-{userId}@aiworkdeck.local` 公式删除。`ProjectFileService`（自动存档）与 `TextFileEditTools` 的作者也走 resolver。
- `VersionEntry` 加 `authorEmail`；`ProjectRepoService.toEntry` 填 `AuthorIdent.getEmailAddress()`。
- 「是不是本人」判定 `self`：先比邮箱（新提交），邮箱是旧公式（不含 `collab.`/`local.` 域）时回落比展示名。
  判定逻辑集中在 `VersionAuthorResolver.isSelf(entry, projectId, userId)`，前端不自己猜。

### 2.2 裁决尾注 `X-AWD-Resolutions`

- 三个裁决合并调用点（`WorkSessionService` 结束工作裁决 / 采纳裁决、`CloudSyncService.resolveCloudMerge`）
  把 `Map<path, MAIN|DRAFT|BOTH>` 传进 `ProjectRepoService.commitMergeResolution`，提交信息追加尾注：
  `X-AWD-Resolutions: <path>=<MAIN|DRAFT|BOTH>; <path>=...`（path 里的 `;`/`=` 用 URL 编码）。
- `extractTrailer` 解析回 `VersionEntry.resolutions`（`List<{path, kept}>`）。
  `kept` 语义按语境翻译成界面词：MAIN=「留了主线/你这边」、DRAFT=「留了稿/同事那边」、BOTH=「两边都留」。
- 尾注契约加进 `CommitTrailerContractTest`。

### 2.3 案件库服务端事件表 `collab_event`

实体 `CollabEvent`（`com.checkba.version.cloud` 包，JPA，与现有实体同样的建表方式）：
`id, projectId, kind, actorUserId, tokenId(nullable), fromSha, toSha, commitCount, targetUserId(nullable), detail(text, JSON), createdAt`。

`kind`：
- `SHARED`：案卷放进案件库（`POST /api/projects` 由桌面 `shareToCloud` 触发时记；detail 带项目名）。
- `CHECKOUT`：某设备第一次对该仓库 `git-upload-pack`（按 `(projectId, tokenId)` 首次）。
- `PUSH`：`git-receive-pack` 成功推进主线（`postReceiveHook` 里记，`fromSha/toSha/commitCount`）。
- `PULLED`：桌面 `integrateFromCloud` 成功后主动上报（`POST /api/projects/{rid}/collab-events`，body `{kind:"PULLED", toSha}`），
  只允许 `PULLED` 这一种客户端上报的 kind，其余 kind 服务端拒绝。
- `MEMBER_ADDED` / `MEMBER_REMOVED` / `MEMBER_ROLE_CHANGED`：`ProjectMemberService` 三个写入口记，`targetUserId` 是被操作的人。

推送者与设备：
- `DeviceTokenService.resolveUserId` 改为返回 `ResolvedToken{userId, tokenId}`；`GitAccessService.authorize` 与
  `GitHttpController.authorizeTarget/deny` 把它传到 `postReceiveHook`/upload-pack 处理点；
  `ingestPushedMainline` 加 `ResolvedToken pusher` 参数。
- 设备名：`POST /api/auth/awdk-login` 请求体加可选 `deviceName`；桌面端 `OfficialCloudService.connectOfficial`
  传本机主机名（`InetAddress.getLocalHost().getHostName()`，取不到就省略）；`DeviceToken.name` 存它，
  取不到仍是「账户桥接」。`CloudConnection` 记下自己的 `tokenId`（已有），前端据此把事件标成「本机」/「你的另一台电脑」。

读端点（案件库侧）：`GET /api/projects/{rid}/collab-events?limit&before`，回
`[{id, kind, actor{userId, username, displayName, avatarUrl}, device{tokenId, name}, fromSha, toSha, commitCount, target{...}, detail, createdAt}]`，
权限 `requireMember`（CLIENT 也可读，与只读成员同口径）。
桌面代理：`GET /api/cloud/projects/{id}/events` → `CloudSyncService.proxyCollabEvents`（形制照 `proxyMemberLookup`），
额外带 `selfUserId`（案件库侧本人 userId，来自 `CloudConnection`）与 `selfTokenId`。

### 2.4 本机统一历史端点

`GET /api/projects/{id}/version/history`
参数：`limit`（默认 100，上限 500）、`cursor`（上一页最后一行的 `sha`）、`author`（authorEmail 或展示名）、
`fileId`、`q`（标题/消息子串）、`from`/`to`（日期）、`includeAuto`（默认 false，自动存档折叠成计数）。

返回：
```
{
  head: { branch: "mainline" | "draft", draftName },
  ahead: N, behind: M,                       // 本机主线 vs origin/master；未绑定时都为 0
  remoteAheadAuthors: [...], remoteAheadBySelf: bool,   // 与 cloudStatus 同源同值
  entries: [{
    sha, shortId(7), title, message, authorName, authorEmail, self: bool,
    when, kind: auto|session, type: initial|session|pull|adopt|revert|auto|upgrade,
    parents, refs: [{type: mainline|draft|remote|local, name}],
    milestone, resolutions: [{path, kept}], remote: bool,   // remote=只在 origin/master 上、本机还没整合
    autoCount,                                              // 折叠进这一行的自动存档数
    changes: {added, modified, deleted, renamed}            // name-status 计数，关 rename 检测
  }],
  nextCursor
}
```
`type` 判定：初始版本消息 → initial；`cloudMergeTitle()` → pull；`采纳：`/`Adopt: ` 前缀 → adopt；
退回消息 → revert；升级清单 → upgrade；kind=auto → auto；其余 session。判定放在后端一处（`HistoryTypeClassifier`），
消息模板常量从生成侧引用，不在别处重抄字符串。

RevWalk 范围：本机主线 + 所有进行中稿分支 + `origin/master`（有绑定时），`--all` 语义；
`refs` 标签按分支尖端打；`remote:true` 的行是 `origin/master` 独有的提交。

`GET /api/projects/{id}/version/compare?from=<ref>&to=<ref>` → `FileChange[]`（`diffNameStatus(from, to)`，过滤 `.awd/`），
供「对比这两版」。

### 2.5 `cloudStatus` 补作者信息（#623）

`cloudStatus(projectId, userId)` 在 `remoteAhead` 为真时 walk `master..origin/master`（上限 200），
回 `remoteAheadCount`、`remoteAheadAuthors`（去重展示名，最多 3 个）、`remoteAheadBySelf`
（全部 `isSelf` 为真）。`checkCloud` 同签名。`CloudController` 把当前 userId 传进去。

### 2.6 参与人去重（#625）

- 本机与案件库的 `GET /projects/{id}/members` 都补 `accountId`（官网账户 id）：案件库侧取 `account_binding.external_account_id`，
  本机侧本人取 `AccountService` 当前账户 id，其余成员按 `account_binding` 查、查不到为 null。
- 前端 `loadProjectMembers` 去重键改为：`accountId` 相同 → 同一人；否则 `username` 相同；否则云端 `awd_` + 本机 username 相同。
  合并后保留本机条目，云端条目的 `role`/`joinedAt` 以案件库为准覆盖（案件库是权威源）。
- 去重逻辑抽成 `utils/mergeMembers.js` 并配单测。

## 3. 前端：「提交历史」标签页

### 3.1 入口与标签

- 新 tabType `commit-history`，单例 id `commit-history_{projectId}`，形制照 `market-detail`
  （`fileOpenTabs.openCommitHistoryTab(spec)`、`fileKind.NON_FILE_TAB_TYPES`、两窗格 `v-else-if`、图标走 `GLYPHS`）。
- 入口三处：顶栏协作徽章（`project-overview.vue:66-75`，改为打开标签页；`spec.focus = 'remote'` 时定位到第一条 remote 行）；
  `CollabDialog` 「这份案卷」tab 内「查看提交历史」按钮；左栏 `VersionPanel` 顶部「完整历史」链接。
  底部状态栏的同一句话仍打开协作抽屉（交稿/取回操作留在那里）。协作抽屉全部功能保留。

### 3.2 布局（浅色外壳，沿用 `--awd-` 令牌，禁 emoji）

```
+----------------------------------------------------------------------------------+
| 工具栏：[参与人 v] [文件 v] [关键词 ____] [日期范围]   本机领先 1 版 · 案件库领先 2 版 |
|         [取回最新稿] [交稿]   （两个按钮直接复用 CollabDialog 的动作与结果处理）      |
+---------------------------------------------------+------------------------------+
| 泳道图 | 列表（按日分组）                           | 详情（选中一行）              |
|  o     | 09-14 上午 · 你在另一台电脑交了稿 · 2 版     | 标题 / 作者 / 时间 / 短编号   |
|  |\    |   [案件库] 修改股权部分  韩泽伟(你) 08:31   | 标签：主线 · 案件库           |
|  | o   |   [案件库] 补充意见书节选  韩泽伟(你) 08:20  | 裁决：A.docx 留了你这边        |
|  |/    | 09-13 · 结束工作：核对注册资本  你 21:58     | 文件：+2 ~3 -0               |
|  o     |   自动存档 x6（展开）                        |   意见书.docx  修改  [对比]   |
|  o     | 09-10 · 签出：韩泽伟（MacBook Pro）          | 操作：和上一版对比 / 退回 /   |
|  o     | 09-10 · 放进案件库                            |      从这一版另起一稿 / 标记  |
+---------------------------------------------------+------------------------------+
```

- 列表行两类：**版本行**（来自 `/version/history`）与**事件行**（来自 `/cloud/projects/{id}/events`），按时间倒序合并。
  事件行有独立样式（浅底、无泳道节点），文案：
  `交稿`→「{谁} 交了稿 · {n} 版」；`CHECKOUT`→「{谁} 签出了一份」；`PULLED`→「{谁} 取回了最新稿」；
  `SHARED`→「{谁} 把案卷放进了案件库」；`MEMBER_*`→「{谁} 把 {某人} 加进了案卷 / 移出 / 改为 {角色}」。
  `{谁}` 的规则：actor 是本人且 tokenId 是本机 → 「你」；本人非本机 → 「你（{设备名}）」；他人 → 展示名。
- 泳道图：`utils/historyGraph.js` 纯函数，输入 entries（sha、parents、refs），输出每行 `{lane, connections}`；
  SVG 渲染，颜色按 ref 类型（主线 / 稿 / 案件库）。配单测（直线、分叉、合并、双亲跨行）。
- 多选：Cmd/Ctrl 点两行 → 工具栏出现「对比这两版」→ 右侧详情变成两版之间的文件清单（`/version/compare`），每份文件「对比」
  打开既有 `openVersionCompareTab`（docx 桌面）或 `openVersionTextDiffTab`。
- 详情区操作复用 `VersionNodeDetail` 的方法（抽成 `composables/useVersionActions.js`，两处共用），不复制逻辑。
- 键盘：上下选行、Enter 展开/收起、Esc 取消选中；焦点在标签页内才响应。
- 空态：未开版本记录 → 引导开启；未绑定案件库 → 只显示本机历史并提示「放进案件库后能看到同事的记录」。
- 刷新：随既有 120 秒协作轮询与窗口 focus 刷新 `ahead/behind` 与事件；列表手动刷新按钮。

### 3.3 文案（#623）

四处同源（顶栏徽章、底部状态栏、`CloudSyncBar`、`CollabDialog`）改为读 `remoteAheadBySelf/remoteAheadAuthors/remoteAheadCount`：
- 全部本人：「你在另一台电脑交了新稿 · 2 版」/ "You submitted 2 new drafts from another computer"
- 一人：「张三交了新稿 · 1 版」；多人：「张三等 2 人交了新稿 · 5 版」
- 算不出：保持「同事交了新稿」。
i18n 键中英同步（`workbench.js` + `version.js` 与 `en-US/`）。`glossary.md` 补词条。

## 4. 测试与验证

- 后端：`VersionAuthorResolverTest`（邮箱规则与 isSelf 回落）、`CommitTrailerContractTest` 加裁决尾注、
  `HistoryEndpointTest`（类型判定、refs、remote 行、分页、筛选、changes 计数）、`CollabEventTest`
  （receive-pack 记 PUSH 带推送者与设备、首次 upload-pack 记 CHECKOUT、PULLED 只许客户端上报、成员三事件）、
  `CloudStatusAuthorsTest`（bySelf 三态）、`ProjectMemberAccountIdTest`。用 `BareHub` 既有形制。
- 前端：`tests/version-history/{historyGraph,mergeMembers,collabWording,historyMerge}.test.mjs`；
  `tab-visibility/file-kind.test.mjs` 加 `commit-history`。
- app-e2e J11 扩展：B 交稿后，A 的历史标签页出现事件行「律师乙 交了稿」与 `remote:true` 版本行；
  冲突裁决后合并行显示裁决结果；A 顶栏文案为「律师乙交了新稿 · 1 版」；同账号双设备场景用 S 上同一账号签两枚令牌模拟，
  断言文案为「你在另一台电脑交了新稿」。
- 真机走查（Fable 亲自）：本机桌面端对着真实案件库打开标签页截图，核对「你在另一台电脑交了新稿」、参与人 1 人、事件行。

## 5. 部署与交付

- 案件库 `aiworkdeck-case` jar 更新（`/opt/aiworkdeck/case`，rsync `backend.jar.new` → mv → restart，保留 rollback），
  新表由 JPA 建（与现有实体同机制，上线前在本地 PG 验一次 DDL）。云后端 addin 实例同 jar 可一起更新。
- 桌面端随下一版发版；本次交付 = PR 合入 master + 案件库上线 + 三张卡落实记录。
- 文档：`.claude/agents/version-control.md` 加「协作历史 / 事件表 / 署名 resolver / 裁决尾注」段落并修正过时描述
  （时间线已有合并连线、标签常驻）。
