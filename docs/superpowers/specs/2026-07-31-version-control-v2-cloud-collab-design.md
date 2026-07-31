# 项目级版本记录 v2：云端仓库与多人协作 设计

日期：2026-07-31
前置：v1（第 0-3 期）已全部落地并合并（PR #214/#218）。本文承接 v1 spec（2026-07-28-project-git-version-control-design.md）第十一节的预留设计。
范围：云端仓库、推送拉取、多人协作。一期做完整多人链路（用户裁定，不分期）。

## 〇、已确认的产品决策（brainstorming 记录）

1. **云端归属：两者都支持**——协议与客户端按律所自托管设计；官方在自有 ECS 上运营一个托管实例作为默认选项（同一套后端，无特殊分支逻辑）。曾评估用 Gitee 等成熟仓库替代官方托管以降运维成本，结论是协议可行但「自动开账号」只能半自动（gitee.com 无代开账号 API、注册需手机实名）、且失去「真项目」特性、保密叙事对法律客户不利，**用户裁定弃用，官方自己运营托管实例**。
2. **服务端形态：现有后端内嵌 Git smart HTTP 端点**——认证复用 User/ProjectMember，不引入第二服务，不用 SSH。实现载体（计划期核验后修正）：后端是 Spring Boot 3.2.4（jakarta.servlet），而 JGit 6.9 的 `org.eclipse.jgit.http.server`（GitServlet）仍是 javax.servlet 系、无法直接挂载；改用 JGit 核心包内 servlet 无关的 `UploadPack`/`ReceivePack` 在普通 Spring Controller 里直写 smart HTTP 协议（info/refs 广告 + 两个 POST 端点，零新依赖），协议对外完全等价。
3. **同步时机：结束工作自动上传 + 手动入口**——「结束本次工作」后自动上传主线（失败静默记待传）；打开项目时检查云端更新并提示；另有手动「立即上传」「从云端更新」。工作中的段绝不自动上传（半成品不出本机）。
4. **一期范围：一次做完多人协作**——含账号、共享、接入、双向同步、并发冲突三选一、成员权限。
5. **云端项目形态：真项目**——push 后服务端把工作区与数据库同步到新主线，网页瘦客户端直接看到最新文件，云端时间线/对比可用。

## 一、拓扑

- **团队服务器** = 现有后端以 web 形态部署（`deploy/web`，prod/默认 profile，MySQL/PG），新增 Git smart HTTP 端点暴露 `/git/{projectId}.git`（UploadPack/ReceivePack 直写，见决策 2）。律所自托管与官方托管是同一份部署物。
- **桌面端** = 现有单机形态（desktop profile，H2）不变，后端进程内新增云端同步能力（JGit 网络操作全部发生在本地后端，前端只调 REST）。
- **协作模型** = 每人本地完整仓库 + 云端 origin。个人的工作段在本地合并进本地主线，主线推到云端共享；`work/*`、`draft/*` 分支是个人的，永不上传。
- 一个本地项目至多关联一个云端项目；官方托管实例只是「团队服务器地址默认填官方域名」，无多租户新概念——项目级成员隔离就是租户隔离，v2 不建租户/计费/配额层。

## 二、认证与成员映射

- **设备令牌（服务端新增）**：现有 session 是内存态（重启失效），不能承载 git 认证。新增实体 `DeviceToken`（id、userId、tokenHash（SHA-256）、name、createdAt、lastUsedAt）。桌面端用团队账号密码调新端点 `POST /api/auth/device-token` 换取长期令牌（明文仅发放时返回一次）。
- **令牌通行**：`AuthController.getUserIdFromSession()` 是全仓鉴权唯一静态入口（约 26 个调用方）。令牌带可识别前缀（如 `awdt_`），该入口识别前缀后走 DeviceToken 查验——**一处改动让令牌在所有既有 REST 端点与 git 端点通行**，桌面端对团队服务器的一切调用（列项目、加成员、git 推拉）都用它。
- **Git 认证**：smart HTTP Basic = 用户名 + 设备令牌。GitServlet 前置 Filter：upload-pack（拉）要求 `hasReadPermission(projectId, userId)`；receive-pack（推）要求 `hasWritePermission`；`isClient` 为真一律拒绝（与 v1 版本面板 `requireMember` 拒 CLIENT 同一口径）。注意参数序是 `(projectId, userId)`（v1 已知地雷 #3）。
- **成员映射**：就是现有 `ProjectMember`，项目共享 = 在团队服务器上加成员。桌面端提供代理入口（按用户名加成员，透传现有成员端点），网页端用现有成员管理 UI。
- **撤销**：桌面端「断开云端连接」调服务端撤销本设备令牌；服务端提供本人令牌列表/撤销端点（防笔记本丢失）。
- **传输安全**：官方托管强制 HTTPS；自托管允许 http（内网场景）但设置页明示风险。

## 三、项目身份与清单 v2（跨机器身份）

v1 的 `.awd/tree.json`（version=1）携带本机 H2 自增 id（id/parentId/userId）与带本地项目前缀的 `filePath`（`projects/{本地id}/...`），跨机器全部失效。v2 引入清单 version=2：

- **节点身份 = 稳定 UUID**：`ProjectFile` 新增 `uid` 列（字符串 UUID，`ddl-auto: update` 自动加列）。新建文件即赋值；存量行在升级后首次 capture 时回填。清单 v2 节点为 `{uid, parentUid, name, isFolder, fileType, sortOrder, relPath, deleted, author}`：
  - `relPath` = repo 相对路径（capture 时剥 `projects/{id}/` 前缀，apply 时加回本机前缀）；
  - `author` = 云端用户名字符串（本地未连接云端时为本地用户名），替代 v1 的本机 userId。
- **同步匹配**：v2 清单按 `uid` 匹配数据库行（`uid` 命中即同一节点，本地 id 无关紧要）；「清单有、库无」按 uid 建新行（本地 id 由 H2 分配）。`applyToDatabase` / `unionApply` 的同步 vs 并集语义、topoSort、软删除处理全部沿用 v1。
- **向后兼容**：`readAtRef` 读到 version=1 清单（旧历史的退回/对比/单文件历史）仍走 v1 的按 id 匹配路径，行为一字不改。本地历史中新提交一律写 v2。
- **远端关联（本地新增，两个实体）**：`CloudConnection`（serverUrl、username、deviceToken——设置页「云端协作」管理的全局连接，通常一条，允许多条以兼顾律所自托管+官方托管并存）；`ProjectRemote`（localProjectId 唯一、connectionId、remoteProjectId、lastSyncSha、pendingUpload 标记）。令牌存本地 H2（单机文件库，与本地文档同一保密边界，不额外加密）。
- **旧项目清单升级**：服务端项目若 HEAD 清单仍是 v1（v1 时代的历史、升级后无新提交），在首次被「共享/开放接入」时由服务端落一笔「清单格式升级」提交（kind=session、当场 capture 写 v2 清单），保证克隆方读到的 HEAD 清单必为 v2。桌面端克隆对 HEAD 清单为 v1 的远端直接拒绝并提示服务端未升级。

## 四、推拉语义（桌面端）

`ProjectRepoService` 兑现 v1 预留：新增 `addRemote / fetch / push`（JGit 内建 HTTP transport，无新依赖；credentials 用 `UsernamePasswordCredentialsProvider`）。业务层新增 `CloudSyncService`（只有它认识「上传/更新/共享/接入」语义），全部操作在 `WorkSessionService` 同一把 `repoLock` 内。

- **上传到云端（push）**：推 `master` + `awd/milestone/*` 标签。触发：① `endSession` 成功后自动（异步，失败只置 `pendingUpload`，绝不阻断结束工作——v1 地雷 #5 同一纪律）；② 手动「立即上传」；③ 联网恢复后的下次任意触发点补传。
  - **被拒（non-FF，主线被同事推进）**：自动 fetch → 本地 `origin/master` 并入本地 `master`（真合并，`mergeNoCommit` 内核）→ 干净则提交合并（双亲，作者=本人）+ 清单按数据库重算收进同一提交（v1 地雷 #21 同一纪律）+ 自动重推；冲突则进入**云端冲突窗口**（见下）。
- **从云端更新（pull）**：fetch → 本地 master 是 origin/master 祖先则快进 + `applyToDatabase` 全量同步 + `reload-files` 通用链强刷打开中的编辑器（`forceActive:true`，律师亲手点的动作，与退回同级）；已分叉则走与上传被拒完全相同的真合并流程。打开项目/挂载版本面板时自动 fetch 检查（静默失败），有新版本亮提示。
- **云端冲突窗口**：复用 v1 采纳的全套内核与守卫——`mergeKeepingConflicts` 停在 MERGING、`conflictingPaths`、path-scoped `abortMerge`（地雷「中止绝不全树 reset」）、`commitMergeResolution`、冲突窗口零 `git add`（地雷 #20）、`.adopt-pending-bar` 面板外固定条。三选一弹窗文案换语境：「云端的 / 我这边的 / 两份都留」；对比方向 = 云端为基线、本地为增量（与三选一措辞顺序一致，同 v1 采纳的方向约定）。`/status` 反查 MERGE_HEAD：指向 `refs/remotes/origin/master` 即云端冲突（区别于稿采纳冲突），崩溃恢复同样幂等。裁决提交后自动重推。
- **与 v1 状态机互斥**：「从云端更新」前置要求与采纳一致——无 ACTIVE 工作段、不在稿上、无 MERGING（有则 user-facing 拒绝「请先结束或丢弃当前工作」）。上传只推已进主线的内容，无前置要求。工作段进行期间本地 master 只会被自己推进（更新被前置挡住），因此**桌面端 `endSession` 合并保持 v1 的无冲突不变式**。
- **克隆接入**：`CloudSyncService.cloneFromCloud(serverUrl, remoteProjectId)`——本地建项目行 → `git clone`（等价 init+addRemote+fetch+checkout master）→ 读 HEAD 清单 v2 `applyToDatabase` 落库 → 文件即在工作区就位，正常打开编辑。要求远端 HEAD 有 v2 清单（服务端共享时保证）。
- **共享上云**：本地已有项目 →「共享到云端」：调团队服务器建项目端点得 remoteProjectId → 服务端开启版本记录（建空仓）→ 本地 addRemote + 首推 master → 服务端落库。此后加成员即可协作。

## 五、服务端落库与服务端状态机

- **push 落库**：`ReceivePack` 注册 `PostReceiveHook`——master 前进后，在该项目 `repoLock` 内执行「checkout master 到工作区 + `syncManifestFromRef("HEAD")` 全量同步落库」（复用切线协议③④步）。前置：服务器 HEAD 在 master 且无 MERGING 且无 ACTIVE 工作段；不满足则置「待同步」标记，由下次 `/status`、下次 push 或空闲结束后补做。receive-pack 本身只动 ref，永不碰工作区。
- **服务端 `endSession` 冲突化（v2 唯一的状态机改动）**：v1 有「单人合并、结束工作前从不会有人抢先修改主线」的不变式，`merge()` 冲突即技术档异常。v2 里 push 会在网页端用户工作期间推进服务器 master，该不变式在服务端失效。改法：服务端 `endSession` 的合并冲突不再异常，转入与桌面端相同的云端冲突窗口（`mergeKeepingConflicts` + 三选一，文案「同事的 / 我这边的 / 两份都留」），裁决后完成合并、工作段正常收尾。这让 `mergeKeepingConflicts`（v1 的可测试拆分产物，无业务调用方）获得第一个真实调用方。桌面端 `endSession` 不变。
- **并发 push**：git ref 更新原子，晚到者收 non-FF 拒绝走客户端自动合并流程；落库钩子在 repoLock 内串行。
- **里程碑标签**：随推拉双向同步；同一版本被两人先后命名时后推者覆盖（`setForceUpdate(true)` 既有语义），可接受。
- **GC**：`RepoMaintenanceJob` 两侧照跑，只清不可达对象，remote-tracking refs 可达性天然保留。

## 六、律师界面（零 Git 术语）

术语表：push=「上传到云端」、pull=「从云端更新」、clone=「从云端接一个项目」、共享=「共享到云端」。

- **设置页「云端协作」**：管理 `CloudConnection`——服务器地址（默认官方域名，可改自托管地址）、账号登录（换设备令牌）、连接状态、断开连接（撤令牌）。
- **版本面板顶部云端状态区**（仅已关联项目显示）：三态「已同步 / 有待上传 / 云端有更新」+「立即上传」「从云端更新」按钮。注意窄侧栏换行（v1 地雷 #24：一行超过 2 个 flex-shrink:0 按钮必查溢出）。
- **项目入口**：项目列表「从云端接一个项目」（列出我是成员且已开版本记录的云端项目）；项目上「共享到云端」（仅未关联时）。
- **成员**：桌面端简单代理（按用户名加成员/列成员），完整管理走网页端。
- **冲突呈现**：复用三选一弹窗组件，标签按语境（云端冲突「云端的/我这边的」；服务端结束工作冲突「同事的/我这边的」），每行可先对比再选，可中止且两边无损。
- **时间线**：合并提交作为普通顶层节点呈现（沿用 v1 对分叉连线图的有意降级）；同事的工作段节点随更新自然出现在时间线上，作者名来自提交署名（云端用户名）。

## 七、离线语义

本地优先，网络是增强不是依赖：

- v1 全部功能离线完全可用；版本记录永不因云端不可达而降级（地雷 #5 延伸到网络层）。
- 上传失败静默置 `pendingUpload`，状态区亮「有待上传」，下次触发点自动补传；「从云端更新」在不可达时按钮置灰并提示。
- 令牌失效/服务器不可达只在状态区亮黄，绝不弹窗打断、绝不阻断任何本地流程。
- fetch/push 设超时（连接 5s / 整体 60s 量级，对齐 registry 同步既有范式），绝不无限挂起占着 repoLock。

## 八、测试

- **后端单测/集成**：GitServlet 鉴权矩阵（OWNER/ADMIN/PARTICIPANT/READ_ONLY/CLIENT/非成员 × upload-pack/receive-pack）；DeviceToken 发放/查验/撤销与静态入口识别；push 落库钩子（含「待同步」延后补做路径）；服务端 endSession 冲突化；清单 v2 capture/apply、uid 回填、v1 清单兼容路径；**双仓库集成测试**：JGit 客户端在测试内对嵌入式 GitServlet 完成 push 被拒 → fetch → 合并 → 重推全循环（不需要两个 Spring 上下文，客户端侧直接用 JGit API 模拟桌面仓库）。
- **护栏延续**：v1 全部既有护栏必须保持绿（历史不可重写、NO_FF、path-scoped abort、冲突窗口零 add、清单同提交等）。
- **e2e（J11）**：两个隔离桌面后端实例 + 一个 web 形态团队服务端（三端口、三份 H2/storage 隔离）：A 登录连接 → 共享项目 → 加 B 为成员 → B 接入克隆 → B 改文件结束工作（自动上传）→ A「从云端更新」看到 B 的改动与时间线节点 → A、B 并发修改同一文件 → 后推者触发三选一 → 裁决「两份都留」→ 双方最终一致。断言认组件独有选择器（v1 纪律）。

## 九、不在本次范围

- Gitee/通用 Git 远端与接入助手（已评估，用户裁定弃用）
- 多租户计费/配额/组织层；SSH 传输；细粒度令牌权限（令牌=全账号）
- 段落级自动合并；离线 P2P 同步；一个项目关联多个远端
- CLIENT 角色参与协作（保持只读项目视图，无 git 通路）
- 对话历史/变量库/尽调数据的云端同步（各有各的生命周期，同 v1 裁定）
