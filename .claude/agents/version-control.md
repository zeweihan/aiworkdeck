---
name: version-control
description: 项目级版本记录领域。任务涉及版本记录/工作段（work session）、时间线、退回版本、丢弃工作、项目 Git 仓库（com.checkba.version 包）、文件树清单 .awd/tree.json 时，先读本文档再动代码。
---

# 版本记录 领域地图

职责边界：给每个项目建一个后台 Git 仓库，把「开启版本记录后律师的每一次动作」记成一条对律师不可见的版本历史，界面上只讲「工作」「版本」「退回」，不出现任何 Git 术语。不做内容 diff（第 2 期）、不做多稿并行（第 3 期）。

## 关键文件地图

**后端 `backend/src/main/java/com/checkba/version/`（11 个类）**

- `ProjectRepoService.java` —— 只认识 Git 概念的薄封装：建仓库/开关分支/提交/合并/GC/读历史/读某版某文件字节。不认识「工作段」业务语义。
  - `gitDir()`/`workTree()`（:62-68）：仓库位置分离，见下方核心契约。
  - `commitAll()`（:119）：整体 add+commit，无变更返回 null（不产生空提交），消息尾注见下方契约。
  - `merge()`（:320-359）：**`setFastForward(NO_FF)` 强制禁用快进** + `setCommit(false)` 手工 `git.commit().setAuthor(...)`——两处都是本期唯一两条端到端测试才抓到的地雷，单测全部漏过，见下方「已知地雷」。冲突时 `git reset --hard HEAD` 回到合并前状态，两份稿件都保留。
  - `gc()`（:366）：只重打包 + 清理不可达对象，不碰可达历史。
- `WorkSessionService.java` —— 工作段生命周期业务逻辑，唯一认识「工作段」的类。
  - `onChangeSignal()`（:113）：外部改动信号入口，隐式开工作段 + 防抖（默认 2 分钟，`setDebounceMillis` 测试用）调度自动存档。
  - `ensureSession()`（:143）：没有 ACTIVE 工作段则建分支 `work/{currentTimeMillis}` 并切过去。
  - `endSession()`（:180）/`discardSession()`（:216）/`revertTo()`（:241）：见下方状态机与契约。
  - 按 projectId 的 `ReentrantLock`（:53，`repoLock()`）包住所有改仓库状态的路径；必须可重入，`endSession`/`revertTo` 内部会再调 `commitNow`。
  - `describeChanges()`（:299）：生成时间线文案，过滤 `.awd/` 前缀、去扩展名——律师看到的是「修改了《股权转让协议》」。
- `VersionController.java` —— REST 层，`/api/projects/{projectId}/version/*`。权限统一走 `requireMember()`（:146），CLIENT 与非成员一律拒绝。异常处理器 `onVersionError`（:138）按 `VersionException.isUserFacing()` 决定是否回显 message，HTTP 恒 200，用 `code` 区分成败。
- `ProjectTreeManifestService.java` —— 数据库文件树 ↔ `.awd/tree.json` 双向同步。`capture()`（:45）连软删除节点一起收集；`applyToDatabase()`（:98）差异同步（不删表重建），`topoSort()`（:204）保证父节点先于子节点处理。
- `RepoMaintenanceJob.java` —— 每日 03:30 对所有已开启版本记录的项目跑 `gc()`，只做 GC，不做任何历史清理（spec 5.5）。
- 数据模型：`WorkSession.java`（JPA 实体，状态机见下）、`WorkSessionRepository.java`、`VersionEntry.java`（时间线一条记录）、`FileChange.java`（ADD/MODIFY/DELETE/RENAME）、`MergeOutcome.java`、`TreeManifest.java`（`.awd/tree.json` 的 Java 表示）、`VersionException.java`（`userFacing` 双档异常）。

**前端 `frontend/src/components/version/`（4 个组件）**

- `VersionPanel.vue` —— 左栏挂载入口（`project-overview.vue` :567-570，`leftPaneKey === 'version'` 分支），三态：未开启引导 / 加载失败态 / 已开启（`WorkSessionBar` + `VersionTimeline`）。`provide()` 下发 `projectId` 给子组件用 `inject`。
- `WorkSessionBar.vue` —— 顶部状态条：工作中显示「工作中（已改 N 份文件）」+「结束本次工作」「丢弃」；空闲态固定文案「当前没有进行中的工作」（**注意：全仓没有「主线」这个用户可见文案**，命名弹窗走 `.awd-dialog` + uni-app 的 `.uni-input-input`，不是外层 `.awd-input`）。
- `VersionTimeline.vue` —— 拉 `getVersionTimeline`，按 `kind==='session'` 分组（`grouped` computed，:67-79），自动存档折进对应工作段节点下可展开。
- `VersionNodeDetail.vue` —— 点某个节点弹出的详情弹窗：拉该 sha 的变更列表、「退回到这一版」二次确认。

**前端集成点**

- `frontend/src/services/api.js`（:1575-1630）具名导出：`getVersionStatus`、`enableVersionControl`、`getVersionTimeline`、`getVersionChanges`、`endWorkSession`、`discardWorkSession`、`resumeWorkSession`、`revertToVersion`。一一对应 `VersionController` 的接口。
- `frontend/src/config/leftSidebarPlugins.js`（:43-50）：固定入口 `version`（图标为时钟 SVG path，非图片资源），已在 `LEFT_SIDEBAR_PLUGINS` 数组里，`getPluginsForUser('CLIENT')` 不返回它（CLIENT 只见 `dd-files`，与后端权限口径一致）。
- 后端触发点：`ProjectFileService.signalChange()`（:1171）与 `FileController.signalChange()`（:85）两处调用 `workSessionService.onChangeSignal(...)`，都用 try/catch 包死、绝不阻断文件操作/上传。

## 核心契约

**工作段状态机**：`ACTIVE → MERGED | DISCARDED`（`WorkSession.Status`，无回头路）。同一项目同一时刻至多一个 ACTIVE（`findFirstByProjectIdAndStatus`）。`resumeSession()` 用于崩溃/强杀后回到未结束的工作段分支，不改变状态。

**提交消息尾注**：`X-AWD-Kind: auto | session`，可选一行 `X-AWD-Note: ...`。`auto` = 工作段内自动存档；`session` = 工作段本身的合并节点（也用于 `enableVersionRecording` 的初始提交、`revertTo` 的退回提交）。解析见 `ProjectRepoService.extractTrailer()`（:172），按行 `trim()` 后判前缀，容忍消息里混有其他内容。

**仓库位置**：`gitDir = data/repos/project-{id}.git`，`workTree = data/projects/{id}`（`ProjectRepoService.gitDir()`/`workTree()`，:62-68）。两者分离是为了 `.git` 目录不出现在 `data/projects/` 下被 RAG 扫描、压缩包导出、搜索误伤。

**文件树清单 `.awd/tree.json`**：`ProjectTreeManifestService.MANIFEST_PATH`。存在理由——数据库才是文件树真源（软删除不动磁盘文件，改名失败时数据库仍可能已改名），单靠磁盘文件跟踪不出一个版本的完整目录结构/排序/回收站状态。`TreeManifest.CURRENT_VERSION = 1`。每次 `commitNow`/`revertTo` 都会重新 `capture()` + `writeToWorkTree()`，保证清单跟随每一笔提交。

**退回 = 新建版本**：`revertTo()`（:241）先给当前状态落一笔（保证退回本身可撤销）→ 用 `diffNameStatus(ref, HEAD)` 算出要改的文件 → 逐文件覆盖/删除工作区 → 同步清单到数据库 → 再提交一笔 `kind=session` 的「退回到早先的版本」。**历史只增不减**，且退回会隐式开启一段新工作（因为 `commitNow` 内部调用 `ensureSession`）。

## 已知地雷

1. **历史永不重写**——硬不变量，理由与 Git 自己一致，为将来推云端仓库（v2）打基础。唯一例外是删除从未合并进主线的工作段分支（`discardSession`/`deleteBranch(force=true)`）。护栏测试：`RepoMaintenanceTest.gcPreservesEveryReachableVersion`，GC 前后逐条比对每个 `VersionEntry.sha()`。
2. **结束工作的合并必须禁用快进**（`MergeCommand.FastForwardMode.NO_FF`，`ProjectRepoService.merge()` :330）。单人场景下主线在工作期间几乎不变，默认合并就是快进——快进只挪 ref、不产生提交，调用方传入的工作段标题和 `kind=session` 尾注会无处可去，时间线上出不来这个工作段的命名节点。这个 bug 是端到端测试（`app-e2e` J9）才抓到的，单元测试全部漏了；护栏测试 `ProjectRepoBranchTest.mergeIsFastForwardWhenMainUntouched` 断言 `r.fastForward()` 为 false 且合并提交仍有两个父提交。
3. **`ProjectMemberService.hasReadPermission`/`isClient` 的参数顺序是 `(projectId, userId)`**（`VersionController.requireMember()` :149/:152）。两参数同为 `Long`，写反了能编译、Mockito 按位置匹配桩数据也能过单测，接真实 bean 后权限判断整体失效。`VersionControllerAuthTest.java` 头部注释专门点名了这条，改这段代码前先读那段注释。
4. **JGit 的 `MergeCommand` 没有 `setAuthor`**——真正三方合并如果让 `setCommit(true)` 自动建提交，作者会退化成 `new PersonIdent(repo)`（读不到 git config 再退化成 JVM `user.name`），署名就不是操作者本人了。必须 `setCommit(false)` 让 JGit 只准备工作区/索引（`MERGE_HEAD` 留在磁盘），随后手工 `git.commit().setAuthor(authorName, authorEmail).call()`；`ALREADY_UP_TO_DATE` 分支不受影响，直接沿用 JGit 结果、不必手工建提交。护栏测试：`ProjectRepoBranchTest.mergeOfTrueThreeWayCreatesMergeCommitWithGivenAuthor` 断言署名等于传入的 `authorName`。
5. **版本记录失败绝不能阻断主流程**——`ProjectFileService.signalChange()`/`FileController.signalChange()` 的 try/catch 必须包死；`WorkSessionService`/`ProjectRepoService` 内部方法失败一律抛 `VersionException`（唯一出口）。`VersionException` 分 `userFacing` 两档（`isUserFacing()`）：技术性消息（可能含分支名等 Git 术语，如「合并失败: work/1001」）只写服务端日志；业务性消息（如「当前没有进行中的工作」「本次工作还没能收尾，你的改动都还在」）才用 `VersionException.userFacing(...)` 显式标记、原样回显给前端。`VersionControllerAuthTest.technicalVersionExceptionIsMaskedWithGenericMessage`/`userFacingVersionExceptionIsShownAsIs` 是护栏。
6. **界面零 Git 术语**——包括中文直译。「主线」也算（是 trunk 的直译），空闲态文案已改成「当前没有进行中的工作」（`WorkSessionBar.vue`）。改文案前 grep 一下现有组件模板，不要凭直觉猜产品文案；`frontend/tests/app-e2e/run.mjs` J9 段的注释记录过一次「brief 假设的文案与实际组件不符」的教训。
7. **`.awd/tree.json` 对律师不可见**——任何面向用户的文件列表/变更列表都要过滤掉它。`VersionController.changes()`（:88-89）和 `status()`（:52-53）都手工 `filter(c -> !c.path().startsWith(".awd/"))`；`WorkSessionService.describeChanges()`（:299-304）同样过滤。新增任何返回文件列表的接口都要记得加这行过滤。
8. **不要在 `src/test/resources` 放 classpath 根的 `schema.sql`/`application-test.yml`**——会全局影响所有嵌入式数据库测试。测试配置用类级 `@TestPropertySource` 就地指定（`WorkSessionRepositoryTest.java` :19-23），H2 保留字冲突用连接串 `NON_KEYWORDS=VALUE`（`MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1`，与既有 `IdorAuthIntegrationTest`/`DesktopContextSmokeTest` 同一约定）。
9. **`MergeOutcome.fastForward` 字段目前恒为 `false`**——`merge()` 里没有真正判断是否发生过快进，直接写死 `false` 构造 `MergeOutcome`（:346）。测试名字里出现的 `mergeIsFastForwardWhenMainUntouched` 测的是「即使可以快进，行为上也被强制变成非快进」，不是这个字段的取值真实性——不要被字段名和测试名误导去做「按字段值分支」的新功能。

## 验证

- 本领域后端单测（`cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test`，覆盖 `ChangeDescriptionTest`/`ChangeSignalWiringTest`/`ProjectRepoBranchTest`/`ProjectRepoHistoryTest`/`ProjectRepoServiceTest`/`RepoMaintenanceTest`/`TreeManifestCaptureTest`/`TreeManifestSyncTest`/`VersionControllerAuthTest`/`WorkSessionRepositoryTest`/`WorkSessionServiceTest`）。本机必须 JDK 21，系统默认 25 会 SIGBUS。
- `cd frontend && npm run test:app-e2e`——J9 旅程覆盖：开启版本记录→两段完整工作（上传文件→工作中→命名结束）→时间线同时保留两个命名节点→退回到非当前 HEAD 的早先节点→断言退回后时间线节点数增加（历史只增不减）。旅程里版本面板与文件树共用同一侧栏挂载点，互斥渲染，脚本会在上传前后来回切换 rail 按钮。
