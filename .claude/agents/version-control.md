---
name: version-control
description: 项目级版本记录领域。任务涉及版本记录/工作段（work session）、时间线、退回版本、丢弃工作、项目 Git 仓库（com.checkba.version 包）、文件树清单 .awd/tree.json 时，先读本文档再动代码。
---

# 版本记录 领域地图

职责边界：给每个项目建一个后台 Git 仓库，把「开启版本记录后律师的每一次动作」记成一条对律师不可见的版本历史，界面上只讲「工作」「版本」「退回」，不出现任何 Git 术语。第 2 期已落地内容对比（「和上一版对比」，桌面 docx 走修订稿、其余走文本红绿对比）与重要版本标记；不做多稿并行 / 另起一稿 / 采纳-放弃-冲突三选一（第 3 期）。

## 关键文件地图

**后端 `backend/src/main/java/com/checkba/version/`（12 个类）**

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
  - `safeRepoPath()`（:508）/`repoRelativePath()`（:525）：所有外部传入路径（`file-bytes`/`file-text` 的 `path` 查询参数、`timeline?fileId` 解出的 `ProjectFile.filePath`）唯一的合法性校验入口——拒绝 `..`/绝对路径/`.awd/` 前缀；`repoRelativePath` 额外校验 `filePath` 前缀真的属于本项目（`projects/{id}/`），归属不符直接抛技术档异常。新增任何吃路径的接口都要过这一关，不要自己重新拼校验逻辑。
- `VersionController.java` —— REST 层，`/api/projects/{projectId}/version/*`。权限统一走 `requireMember()`（:146），CLIENT 与非成员一律拒绝。异常处理器 `onVersionError`（:138）按 `VersionException.isUserFacing()` 决定是否回显 message，HTTP 恒 200，用 `code` 区分成败。第 2 期新增四个端点：`GET /versions/{ref}/file-bytes?path=`（原始字节，给桌面 docx 对比用）、`GET /versions/{ref}/file-text?path=`（Tika 抽取纯文本，给 `DocDiffViewer` 文本对比降级用）、`POST /versions/{sha}/milestone`（标重要版本，`{name}` 限 64 字）、`GET /timeline?fileId=`（单文件历史，见下方核心契约）。
- `ProjectTreeManifestService.java` —— 数据库文件树 ↔ `.awd/tree.json` 双向同步。`capture()`（:45）连软删除节点一起收集；`applyToDatabase()`（:98）差异同步（不删表重建），`topoSort()`（:204）保证父节点先于子节点处理。
- `RepoMaintenanceJob.java` —— 每日 03:30 对所有已开启版本记录的项目跑 `gc()`，只做 GC，不做任何历史清理（spec 5.5）。
- 数据模型：`WorkSession.java`（JPA 实体，状态机见下）、`WorkSessionRepository.java`、`VersionEntry.java`（时间线一条记录）、`FileChange.java`（ADD/MODIFY/DELETE/RENAME）、`MergeOutcome.java`、`TreeManifest.java`（`.awd/tree.json` 的 Java 表示）、`VersionException.java`（`userFacing` 双档异常）。

**前端 `frontend/src/components/version/`（5 个组件）**

- `VersionPanel.vue` —— 左栏挂载入口（`project-overview.vue` :567-570，`leftPaneKey === 'version'` 分支），三态：未开启引导 / 加载失败态 / 已开启（`WorkSessionBar` + `VersionTimeline`）。`provide()` 下发 `projectId` 给子组件用 `inject`；还接一个 `fileFilter` prop（`{fileId, name}`），非空时渲染「只看《{name}》的历史」过滤条 + 「显示全部」清除按钮，透传给 `VersionTimeline` 的 `file-filter`（单文件历史入口，见下方核心契约）。
- `WorkSessionBar.vue` —— 顶部状态条：工作中显示「工作中（已改 N 份文件）」+「结束本次工作」「丢弃」；空闲态固定文案「当前没有进行中的工作」（**注意：全仓没有「主线」这个用户可见文案**，命名弹窗走 `.awd-dialog` + uni-app 的 `.uni-input-input`，不是外层 `.awd-input`）。
- `VersionTimeline.vue` —— 拉 `getVersionTimeline`，按 `kind==='session'` 分组（`grouped` computed，:67-79），自动存档折进对应工作段节点下可展开；节点标题有 milestone 时前置「重要版本」flag 并用 milestone 名字整个替换掉原标题（`titleOf()` 只在无 milestone 时才退回 `note || message`）。
- `VersionNodeDetail.vue` —— 点某个节点弹出的详情弹窗：拉该 sha 的变更列表、「退回到这一版」二次确认、「标为重要版本」（`openMilestoneNaming` 起独立的嵌套 `.awd-dialog`，同款 `.uni-input-input` 陷阱）、对 `MODIFY` 类型且非根提交（`version.parents.length > 0`）的改动行渲染「和上一版对比」按钮，`@tap` 上抛 `{path, sha}` 交给宿主页面决定走桌面修订稿分支还是文本降级分支。
- `VersionCompareTab.vue` —— 第 2 期新增，「和上一版对比」的桌面 docx 展示宿主，**只读、绝无保存路径**：不订阅 `lo-relay` 的 `modified` 信号、不进保活池（`_libreRefs`/LRU 一概不注册）、`beforeUnmount` 只 `dispose executor` + 移除 `<webview>`。流程：并行下载新旧字节 + 启动引擎 → `load_document` 新版 → `compare_document` 一次性生成修订并自动切只读。

**前端集成点**

- `frontend/src/services/api.js`（:1575-1660 附近）具名导出：`getVersionStatus`、`enableVersionControl`、`getVersionTimeline`（第 2 期加了 `fileId` 参数）、`getVersionChanges`、`endWorkSession`、`discardWorkSession`、`resumeWorkSession`、`revertToVersion`，第 2 期新增 `markVersionMilestone`、`fetchVersionFileBytes`（桌面 docx 对比取字节）、`getVersionFileText`（文本对比降级取纯文本）。一一对应 `VersionController` 的接口。
- `frontend/src/pages/project-overview/fileOpenTabs.js` 的 `onVersionCompareFile({path, sha})`：`VersionNodeDetail` 一路冒泡上来的「和上一版对比」入口，`桌面 + docx/doc` 走 `openVersionCompareTab`（`VersionCompareTab.vue`，LOWA 修订稿），其余（浏览器目标、或非 docx 文件）走 `openVersionTextDiffTab`（`DocDiffViewer.vue` 的 `versionSpec` 模式，Monaco 红绿文本对比降级）——两个标签都是 `leftFiles`/`rightFiles` 里的普通标签页，跟侧栏 `version` 面板互不影响。
- `frontend/src/components/FileTree.vue` 右键菜单「这份文件的历史」（`@tap="$emit('file-history', contextMenu.targetItem); closeContextMenu()"`）→ `project-overview.vue` 的 `onFileHistory(file)`：设置 `versionFileFilter = {fileId, name}` 并把左栏切到 `version` 面板。右键菜单本身绑定的是原生 `@contextmenu.prevent`（不是 uni `@tap`），真实鼠标右键能直接触发（见下方「验证」一节的 e2e 配方）。
- `frontend/src/config/leftSidebarPlugins.js`（:43-50）：固定入口 `version`（图标为时钟 SVG path，非图片资源），已在 `LEFT_SIDEBAR_PLUGINS` 数组里，`getPluginsForUser('CLIENT')` 不返回它（CLIENT 只见 `dd-files`，与后端权限口径一致）。
- 后端触发点：`ProjectFileService.signalChange()`（:1171）与 `FileController.signalChange()`（:85）两处调用 `workSessionService.onChangeSignal(...)`，都用 try/catch 包死、绝不阻断文件操作/上传。
- **退回后重载打开中的编辑器（响应驱动，不走 SSE）**：`VersionNodeDetail.confirmRevert` 拿到 `revertToVersion` 响应里的 `affectedFileIds` 随 `reverted` 事件上抛 → `VersionTimeline.onReverted` → `VersionPanel.onReverted`（`refresh()` + `$emit('reverted-files', ids)`）→ `project-overview.vue` 的 `@reverted-files="onVersionRevertedFiles"` → `fileOpenTabs.js` 的 `onVersionRevertedFiles()`：只对左右两窗格里当前打开、id 命中、`useLibreEditor` 为真的标签，复用 `agentClientActions.js` 的 `handleEditorReloadFile`（AI 改文档后刷新编辑器走的同一条路）。**绝不能用 `closeFile` 实现重载**——它会先 `flushSave`，把退回前的旧字节写回覆盖退回结果。
  - `handleEditorReloadFile` 分两半处理：**非活动**保活实例摘出 `libreLruKeys` 卸载（下次激活重挂载）；**当前正显示**的实例摘不掉（活动文件必进池），走 `librePool.js` 的 `reloadActiveLibreInstances(fileId)` → `LibreOfficeEditor.reloadFromBackend()` 就地 `load_document` 换文档。缺了后半段就是律师看着的那份不刷新，autosave 把「旧内容 + 新编辑」写回、退回被冲掉（真机复现过，见「已知地雷」第 11 条）。
## 核心契约

**工作段状态机**：`ACTIVE → MERGED | DISCARDED`（`WorkSession.Status`，无回头路）。同一项目同一时刻至多一个 ACTIVE（`findFirstByProjectIdAndStatus`）。`resumeSession()` 用于崩溃/强杀后回到未结束的工作段分支，不改变状态。

**提交消息尾注**：`X-AWD-Kind: auto | session`，可选一行 `X-AWD-Note: ...`。`auto` = 工作段内自动存档；`session` = 工作段本身的合并节点（也用于 `enableVersionRecording` 的初始提交、`revertTo` 的退回提交）。解析见 `ProjectRepoService.extractTrailer()`（:172），按行 `trim()` 后判前缀，容忍消息里混有其他内容。

**仓库位置**：`gitDir = data/repos/project-{id}.git`，`workTree = data/projects/{id}`（`ProjectRepoService.gitDir()`/`workTree()`，:62-68）。两者分离是为了 `.git` 目录不出现在 `data/projects/` 下被 RAG 扫描、压缩包导出、搜索误伤。

**文件树清单 `.awd/tree.json`**：`ProjectTreeManifestService.MANIFEST_PATH`。存在理由——数据库才是文件树真源（软删除不动磁盘文件，改名失败时数据库仍可能已改名），单靠磁盘文件跟踪不出一个版本的完整目录结构/排序/回收站状态。`TreeManifest.CURRENT_VERSION = 1`。每次 `commitNow`/`revertTo` 都会重新 `capture()` + `writeToWorkTree()`，保证清单跟随每一笔提交。

**退回 = 新建版本**：`revertTo()` 先给当前状态落一笔（保证退回本身可撤销）→ 用 `diffNameStatus(ref, HEAD)` 算出要改的文件 → 逐文件覆盖/删除工作区 → 同步清单到数据库 → 再提交一笔 `kind=session` 的「退回到早先的版本」。**历史只增不减**，且退回会隐式开启一段新工作（因为 `commitNow` 内部调用 `ensureSession`）。返回类型是 `WorkSessionService.RevertResult(String sha, List<Long> affectedFileIds)`——提交成功后把变更路径（滤 `.awd/`）匹配到 `ProjectFileRepository.findByProjectId` 的记录收集 fileId，整段包 try/catch，失败只影响 `affectedFileIds`（退化为空列表），不影响 `sha`。`VersionController.revert` 把 `affectedFileIds` 一并放进响应 `data`，前端凭它决定重载哪些打开中的编辑器标签（见上方「前端集成点」）。

**对比方向**：`fileOpenTabs.js` 的 `onVersionCompareFile({path, sha})` 里 `newRef = sha`（当前打开的这个版本，「这一版」）、`oldRef = sha + '^'`（它的直接父提交，「上一版」）——**永远是「点开的版本」对「它的上一版」**，不是「点开的版本」对「当前 HEAD」。`VersionCompareTab.vue` 用 `newRef` 走 `load_document`、`oldRef` 走 `compare_document` 的 `baseBytes`；`DocDiffViewer.vue` 的降级分支同样把 `oldLabel`/`newLabel` 写死成「上一版」/「这一版」（见 `openVersionTextDiffTab`）。

**比较修订署名统一为「版本对比」**：`office_thread.js` 的 `compare_document` 在派发 `.uno:CompareDocuments` 前显式 `setRedlineAuthor('版本对比')`；不会泄漏成上一次操作者的真名，是因为 `execCommand()`（office_thread.js）**每次派发任何命令前都会先重设一次作者**（`p.__agent` 为真→AI Workdeck，否则→人工用户名），`compare_document` 内部的显式设置是在这个通用重设之后再覆盖一次——两层保险，不依赖调用顺序。

**里程碑（重要版本）**：`ProjectRepoService.tagMilestone()` 打一个**附注标签**（annotated tag），标签名固定 `refs/tags/awd/milestone/{sha 前 12 位}`，标签名字里的自由文本不放在 tag name（Git ref 名字符集受限），而是放在 **tag message** 里；`setForceUpdate(true)` 使得对同一版本重新命名 = 直接覆盖旧标签，不产生历史标签垃圾。反向查询 `listMilestones()`/`milestonesIn()` 按**完整 sha**（`walk.peel(tag).getName()`）建 map 回填到 `VersionEntry.milestone`，`VersionTimeline.vue` 据此在节点标题前置「重要版本」flag 并整个替换掉原标题。

**AI 落版的钩子挂在编排器的 `Loop Finished` 分支，不是 `runLoop` 调用返回处**：`AgentOrchestrator` 处理消息的入口方法标了 `@Async("taskExecutor")`（:213），整段多轮工具调用循环在后台线程异步跑，`runLoop()` 本身会为每一轮工具调用递归调用自己（:461/:550）；只有当某一轮模型给出的是最终答复（不再要求工具调用）、代码走到「4. Default: Loop Finished」这个分支（日志 `Agent Loop Finished for {conversationId}`）时，才会依次触发记忆管线 (`memoryPipelineService.onConversationTurnCompleted`) 和 `workSessionService.commitAiRound(projectId, userId)`（:669）。想在"AI 改完文档"这个时间点上再挂新逻辑，只能挂在这个分支，挂在触发 `runLoop` 的外层方法返回处不会等到循环真正结束（`@Async` 方法调用本身立即返回）。`commitAiRound` 已知局限：编辑器自动保存是异步的，这一轮里未 flush 的改动不进这一笔，会带进下一次普通存档（方法注释已写明，非本期新问题）。

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
10. **`revertTo` 绝不能靠 `EditorBridgeService` 的 SSE 通知打开中的编辑器重载**——踩过一次真实的生产惰性代码：`EditorBridgeService.sendReloadFileAction` 开头 `if (currentConversationId == null) return`，那个 ThreadLocal 只有 `AgentOrchestrator` 在 AI 工具调用期间设置；`revertTo` 唯一的调用方 `VersionController.revert` 是普通 REST 端点（律师从时间线点按钮），线程上永远没有会话 id，通知永远发不出去，单测却因为 mock 了 `EditorBridgeService` 全绿，问题只有真机才暴露。修复后 `WorkSessionService` 不再注入 `EditorBridgeService`，改走响应驱动：`revertTo` 把 `affectedFileIds` 随返回值带回去，前端自己决定重载谁（见上方「前端集成点」）。以后任何「后端主动推给前端」的新功能，先确认对应的通知通道在目标调用路径上到底有没有值，不要被单测的 mock 掩盖过去。
11. **`revertTo` 不改 `wpsFileId`，所以任何「靠键变化触发重挂载」的重载都不会发生**——`LibreOfficeEditor` 既不 `watch` `file`，模板 key 也只含 `file.id`（`project-overview.vue` `:key="'libre-left-' + file.id"`），所以「后端 bump 一个 id 前端就会重新加载」这个流传下来的假设本来就不成立（`DocumentCheckpointService.restore` 同样只 `storage.save` 不 bump，同一条链路同源潜伏）。重载活动实例只有一条路：显式调 `LibreOfficeEditor.reloadFromBackend()` 就地换文档。真机反证跑过：不调它时编辑器里仍是「退回前内容 + 新编辑」，25 秒后后端文件被 autosave 覆盖成旧内容。
12. **对比宿主（`VersionCompareTab.vue`）绝不能接自动保存/保活池**——它展示的是历史版本的字节，一旦被 autosave/保活复用逻辑当成普通编辑器实例对待，就会把「历史稿」当成「当前稿」的一次编辑写回真实文件路径，等于用旧版本覆盖当前工作——比 revert 的静默失败更糟（那是数据事故，不是体验问题）。所以它不订阅 `lo-relay` 的 `modified` 信号、不注册 `_libreRefs`/LRU，销毁即销毁，没有任何「保留实例下次复用」的优化空间，改这个组件时**不要**为了性能顺手把它接进 `librePool.js` 的保活体系。
13. **`compare_document` 每个引擎实例只能调一次**——命令末尾会派发 `.uno:CompareDocuments` 生成修订后再派发 `.uno:EditDoc` 切只读，而 `.uno:EditDoc` 是**开关（toggle）语义**，不是幂等的「设为只读」；同一个实例上第二次调用 `compare_document`（比如失败重试）会把已经切到只读的文档重新打回可编辑。失败重试的唯一正确做法是让宿主换一个新 `key` 整体重建 `VersionCompareTab` 实例（新引擎/新 webview），组件内部不做、也不应该做重试。
14. **改 `AgentOrchestrator` 的构造依赖必须同步更新 `EvalHarness.java:163` 附近手写的 `new AgentOrchestrator(...)` 调用**——这是本条目第三次被记进地雷（AI 编排器 Phase 3、面板五连修都踩过一次），`EvalHarness` 不走 Spring 容器注入、是手工拼构造参数跑回放评测，编排器构造器每加一个新依赖，这里就要跟着补一个参数，否则编译直接失败或（更隐蔽地）参数错位——这次是版本记录接入 `WorkSessionService.commitAiRound` 时又踩了一次。改编排器构造器前先跑一遍 `grep -rn "new AgentOrchestrator" backend/src/test`，别等编译报错才发现。

## 验证

- 本领域后端单测（`cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test`，覆盖 `ChangeDescriptionTest`/`ChangeSignalWiringTest`/`ProjectRepoBranchTest`/`ProjectRepoHistoryTest`/`ProjectRepoServiceTest`/`RepoMaintenanceTest`/`TreeManifestCaptureTest`/`TreeManifestSyncTest`/`VersionControllerAuthTest`/`WorkSessionRepositoryTest`/`WorkSessionServiceTest`）。本机必须 JDK 21，系统默认 25 会 SIGBUS。
- `cd frontend && npm run test:lowa-e2e`——基线 44 步；组 13 是 `compare_document` 的生产 action 探针（真引擎里加载新版、以旧版字节比较、断言产出修订且 `redlineCount > 0`），覆盖桌面 docx 修订稿对比这一半，浏览器目标测不到（无 Electron webview）。
- `cd frontend && npm run test:app-e2e`——J9 旅程（第 2 期扩展后共 21 步，见 `run.mjs` J9 段）：①开启版本记录→两段完整工作（上传文件→工作中→命名结束）→时间线同时保留两个命名节点→退回到非当前 HEAD 的早先节点→断言退回后时间线节点数增加（历史只增不减）；②打开命名节点→「标为重要版本」命名确认→断言时间线出现「重要版本」flag 与新标题；③追加一段工作产生真实 `MODIFY` 变更（先 UI 上传一份文件并结束工作让它进历史，再用裸 REST 覆盖上传同一 `wpsFileId` 的字节、结束第二段工作——浏览器目标不能靠编辑文档产生 MODIFY，同名 UI 上传又会被 `createFile` 的同名校验拒绝，这是唯一能在浏览器目标里造出 MODIFY 的手段）→打开该节点断言 `MODIFY` 行的「和上一版对比」按钮→点击→断言走文本对比降级分支渲染出「上一版/这一版」标签头；④文件树右键测试文件→「这份文件的历史」→断言过滤条「只看《文件名》的历史」出现→「显示全部」恢复。旅程里版本面板与文件树共用同一侧栏挂载点，互斥渲染，脚本会来回切换 rail 按钮触发面板重新挂载（这也是裸 REST 改动后让版本面板重新拉取"工作中"状态的手段，面板只在挂载时读一次状态、没有轮询）。
