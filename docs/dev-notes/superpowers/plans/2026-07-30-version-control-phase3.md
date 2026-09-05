# 项目级版本记录 第 3 期 实施计划（另起一稿）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 律师能从任意版本「另起一稿」试大改，随时在主线与稿之间切换，最终「采纳」（可能冲突，逐文件三选一）或「放弃」。

**Architecture:** 稿 = `WorkSession` 加 `sessionType=DRAFT` 的长命分支（`draft/{ts}`），结束语义与工作段相反（绝不自动合并、不受空闲自动结束管辖）。切线 = 停靠当前线（commitNow）→ checkout → 清单同步回数据库 → 受影响编辑器强制重载（复用第 2 期 revert 链路）。采纳 = 保留冲突态的 NO_FF 合并 + 三选一逐文件裁决 + 清单并集规则。当前在哪条线由 git `currentBranch` 派生，不另存状态。

**Tech Stack:** JGit（RepositoryState / DirCache 冲突态）/ Spring Boot / uni-app Vue3

**Spec:** `docs/superpowers/specs/2026-07-28-project-git-version-control-design.md` §5.9（另起一稿与冲突）、§5.8（从这一版另起一稿）
**领域文档（必读）:** `.claude/agents/version-control.md` —— 全部地雷适用，尤其锁纪律、历史永不重写、编辑器重载三轮教训。

## Global Constraints

- **历史永不重写**。放弃稿 = 删除从未并入主线的分支（既有唯一例外的自然延伸）；采纳失败的中止 = `reset --hard` 回合并前 + 清 MERGING 态，不碰任何已有提交。
- **界面零 Git 术语**（含中文直译）。本期用语：另起一稿 / 稿 / 切到这一稿 / 回到主线工作 / 采纳这一稿 / 放弃这一稿 / 两边都改了，请选一份 / 用主线的 / 用这一稿的 / 两份都留。**注意「主线」在第 2 期被判为 Git 直译清出了界面**——本期涉及双线切换绕不开指称，统一用「主线工作」指主线侧（完整说法），不单独用「主线」二字做状态名；实施时如发现更顺口的说法可全局替换但须一致。
- **失败不阻断主流程**；技术异常不回显（`VersionException` 双档）。
- **锁纪律**：一切改仓库状态的路径进 `repoLock(projectId)` 可重入临界区。
- **权限** `(projectId, userId)`；新端点全部进 `VersionControllerAuthTest` 的 `Endpoint` 参数化矩阵。
- **e2e 断言认组件真渲染的独有选择器**，不许用 body innerText 包含（第 2 期终审 Critical 的直接教训）。
- **改 `AgentOrchestrator` 构造依赖必须同步 EvalHarness**（本期预计不动编排器；若动，此条生效）。
- mvn 必须 JDK 21；前端 npm；**一律不用 emoji**；commit message 末尾 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`；显式路径提交；`.superpowers/` 报告不入库。
- 分支：`claude/version-control-phase3`（含第 2 期恢复的 19 提交，PR #218）。

## 已核实的第 2 期终态接缝（实施者不必重查）

- `WorkSession.Status = {ACTIVE, MERGED, DISCARDED}`，无 type 字段（本期加）。
- `WorkSessionService`：`endSession → SessionEndResult(sha, notice)`（空段路径不抛、返回 notice）；`revertTo → RevertResult(sha, affectedFileIds)`；`armIdleTimer`/`cancelPending`/`repoLock` 私有基建齐备；`ensureSession` 在创建分支时武装空闲定时器。
- `ProjectRepoService.merge(...)`：NO_FF + `setCommit(false)` + 手工署名提交；**冲突路径现状 = 收集 conflictingPaths 后立即 `reset --hard HEAD`**（`:423-427`）——本期需要新的「保留冲突态」变体，不改这个既有方法的行为（endSession 还依赖它）。
- `readBlobAtCommit(projectId, ref, path)` 取任意版本字节（null=不存在）；`resolveRef(projectId, ref)`；`safeRepoPath`/`repoRelativePath` 校验链。
- 编辑器强制重载链：后端返回 affectedFileIds → 前端 `reverted-files` 事件 → `onVersionRevertedFiles` → `handleEditorReloadFile(action, {forceActive:true})` → `LibreOfficeEditor.reloadFromBackend()`。**本期切线/采纳复用同一链，事件与处理器改成通用名**。
- `ProjectTreeManifestService`：`capture`/`writeToWorkTree`/`readAtRef`/`applyToDatabase`（差异同步）。
- 前端：`WorkSessionBar`（工作中/空闲两态 + 命名弹窗模式）、`VersionPanel`（provide projectId、fileFilter、错误态）、`VersionNodeDetail`（footer 按钮组 + `.awd-dialog` 命名弹窗模式）、`openVersionCompareTab({projectId, path, name, newRef, oldRef})` 可对任意两 ref 开修订对比。

## 关键设计决策（写死在本计划里，实施不再讨论）

1. **在哪条线 = `currentBranch` 派生**：`master` 或 `work/*` → 主线侧；`draft/*` → 对应稿。不新增持久化状态，杜绝失步。
2. **稿上改动不生成工作段**：`onChangeSignal` 里若 `currentBranch` 是 `draft/*`，跳过 `ensureSession`，只走防抖 `commitNow`（自动存档落在稿分支，`kind=auto`）；空闲定时器在稿上**不武装**（稿以天为寿命，绝不自动合并——这是稿与工作段的本质区别）。
3. **采纳前置条件：没有进行中的主线工作段**。否则 userFacing 拒绝「请先结束或丢弃当前工作，再采纳这一稿」。理由：避免「稿→master 合并」与「工作段→master 合并」互相制造二阶冲突，第 3 期不打开这个复杂度。创建稿/切换线**不受**此限制（切走前停靠即可）。
4. **冲突态就是 git 的 MERGING 态**，不另建持久化：崩溃后靠 `RepositoryState` + `MERGE_HEAD` 还原「正在采纳哪一稿」。`/status` 透出，前端据此重开三选一弹窗。
5. **清单并集规则（spec 5.9 原文落地）**：采纳提交前，DB 树 = 「当前 DB（主线侧）」∪「稿 tip 的清单节点」，同 id 冲突取稿侧属性；然后 capture → writeToWorkTree → 随合并提交入库。「两份都留」的新副本行在此步之前建好 DB 行。
6. **时间线不画分叉连线图**（spec 5.8 的「连线画出」降级为：稿列表 + 稿内时间线自动呈现——HEAD 派生已天然做到）。这是对 spec 的**有意偏离**，记入领域文档；真图形化留给未来需求。

## 文件结构

**后端**（全部在 `com.checkba.version` 既有文件上扩展 + 测试）

| 文件 | 改动 |
|---|---|
| `WorkSession.java` | +`sessionType` 列（`enum SessionType {WORK, DRAFT}`，默认 WORK） |
| `WorkSessionRepository.java` | 查询方法带 type 变体 |
| `ProjectRepoService.java` | +`mergeKeepingConflicts`、`repositoryMerging`、`mergeHeadRef`、`abortMerge`、`commitMergeResolution` |
| `WorkSessionService.java` | 稿生命周期：`createDraft`/`listDrafts`/`switchToDraft`/`switchToMainline`/`adoptDraft`/`resolveAdopt`/`abortAdopt`/`abandonDraft` + `LineSwitchResult`；`onChangeSignal` 稿分支守卫 |
| `VersionController.java` | +8 端点 + `/status` 扩展（onDraft / adoptConflict） |

**前端**

| 文件 | 改动 |
|---|---|
| `services/api.js` | +8 具名导出 |
| `version/WorkSessionBar.vue` | +稿态（「正在稿《x》上修改」+ 回到主线工作/采纳/放弃） |
| `version/DraftList.vue`（新建） | 稿列表：名称/建立时间/切换；空态引导 |
| `version/VersionPanel.vue` | 挂 DraftList；status 扩展消费；adoptConflict 时唤起弹窗 |
| `version/VersionNodeDetail.vue` | footer +「从这一版另起一稿」（命名弹窗复用） |
| `version/AdoptConflictDialog.vue`（新建） | 三选一逐文件 + 每行「对比」按钮 |
| `pages/project-overview/fileOpenTabs.js` + `project-overview.vue` | `reverted-files` 链通用化为 `reload-files`；新事件接线 |
| `tests/app-e2e/run.mjs` | J10 稿旅程 |
| `.claude/agents/version-control.md` | 第 3 期契约/地雷/偏离记录 |

---

### Task 1: WorkSession 稿类型与守卫

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/WorkSession.java`、`WorkSessionRepository.java`、`WorkSessionService.java`
- Test: `backend/src/test/java/com/checkba/version/DraftSessionGuardTest.java`（新建）

**Interfaces:**
- Produces:
  - `enum WorkSession.SessionType { WORK, DRAFT }`；实体 +`@Enumerated(STRING) @Column(nullable=false, length=8) sessionType`，既有构造路径默认 WORK（注意存量行：列加 `columnDefinition` 默认 `'WORK'`，desktop H2 `ddl-auto: update` 自动补列）
  - `WorkSessionRepository.findFirstByProjectIdAndStatusAndSessionType(Long, Status, SessionType)`、`findByProjectIdAndStatusAndSessionTypeOrderByStartedAtDesc(...)`
  - `activeSession(projectId)` 语义收窄为「进行中的 WORK 段」（改用带 type 查询）；新增 `activeDraftOnBranch(projectId)`：`currentBranch` 是 `draft/*` 时返回对应 ACTIVE DRAFT 行（按 branchName 匹配），否则 empty
  - `onChangeSignal`：`currentBranch` 为 `draft/*` 时**跳过 ensureSession 与空闲定时器**，仅排防抖 `commitNow`（提交仍走锁）

- [ ] **Step 1: 失败测试**（`DraftSessionGuardTest`，测试基建照 `WorkSessionServiceTest` 的 seeded/mock 模式复制其 `setUp`）：

```java
    @Test
    void changeSignalOnDraftBranchDoesNotCreateWorkSession() throws Exception {
        // 手工造一条稿分支并切过去（Task 3 才有 createDraft，这里直接用 repo 层）
        repoSvc.createBranch(7L, "draft/1001", "HEAD");
        repoSvc.checkoutBranch(7L, "draft/1001");

        svc.onChangeSignal(7L, 1L, "韩泽伟");

        assertTrue(svc.activeSession(7L).isEmpty(), "稿上改动不得隐式开工作段");
        assertEquals("draft/1001", repoSvc.currentBranch(7L), "不得被切走");
    }

    @Test
    void draftBranchChangeStillAutoArchives() throws Exception {
        repoSvc.createBranch(7L, "draft/1001", "HEAD");
        repoSvc.checkoutBranch(7L, "draft/1001");
        Files.writeString(root.resolve("projects/7/合同.txt"), "稿上改动");

        String sha = svc.commitNow(7L, 1L, "韩泽伟", null);

        assertNotNull(sha, "稿上防抖存档必须照常工作");
        assertEquals("auto", repoSvc.log(7L, "HEAD", 1).get(0).kind());
    }

    @Test
    void idleTimerNotArmedOnDraft() throws Exception {
        repoSvc.createBranch(7L, "draft/1001", "HEAD");
        repoSvc.checkoutBranch(7L, "draft/1001");
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        // 反射读 idleTimers（模式照 WorkSessionServiceTest 既有写法）
        var f = WorkSessionService.class.getDeclaredField("idleTimers");
        f.setAccessible(true);
        var timers = (java.util.Map<?, ?>) f.get(svc);
        assertFalse(timers.containsKey(7L), "稿上不得武装空闲自动结束");
    }
```

**注意**：`commitNow` 现实现会调 `ensureSession`——本任务要把「稿分支守卫」下沉到位：`commitNow` 在稿分支上跳过 `ensureSession`（清单写入与 commitAll 照旧）。测试 2 正是护这个。

- [ ] **Step 2: 跑红** `-Dtest=DraftSessionGuardTest`
- [ ] **Step 3: 实现**（实体列 + 仓储查询 + `activeSession` 收窄 + `onChangeSignal`/`commitNow` 守卫。守卫写成私有 `boolean onDraftBranch(long projectId)`：`repoService.currentBranch(projectId).startsWith("draft/")`，try/catch 返回 false——查询分支失败时按主线处理，绝不阻断）
- [ ] **Step 4: 跑绿** 本包全部 `-Dtest='com.checkba.version.*Test'`（`activeSession` 语义变了，既有测试如有受影响按新语义修断言，不许弱化）
- [ ] **Step 5: 提交** `feat(version): 工作段类型与稿分支守卫`

---

### Task 2: 保留冲突态的合并原语

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/ProjectRepoService.java`
- Test: `backend/src/test/java/com/checkba/version/ConflictMergeTest.java`（新建）

**Interfaces:**
- Produces:
  - `MergeOutcome mergeKeepingConflicts(long projectId, String branchName, String message, String authorName, String authorEmail)` —— 与既有 `merge` 同形（NO_FF、setCommit(false)、成功路径手工署名提交），**唯一区别：冲突时不 reset**，保留 MERGING 态与冲突索引，返回 `success=false + conflictingPaths`
  - `boolean repositoryMerging(long projectId)` —— `repo.getRepositoryState() == RepositoryState.MERGING || MERGING_RESOLVED`
  - `String mergeHeadRef(long projectId)` —— MERGING 态下 `repo.resolve("MERGE_HEAD")` 的 sha，否则 null
  - `void abortMerge(long projectId)` —— `reset --hard HEAD` + 确认 RepositoryState 回 SAFE（JGit 的 hard reset 会清 MERGE_HEAD，第 1 期审查已核）
  - `String commitMergeResolution(long projectId, String message, String authorName, String authorEmail)` —— MERGING 态下 `git.add(".")`（含 update）后手工提交；**必须产出双亲**（MERGE_HEAD 在，CommitCommand 自动带上第二父——第 1 期 Task 4 修署名时已验证过该机制）；提交后 RepositoryState 回 SAFE。消息带 `X-AWD-Kind: session` 尾注（复用 KIND_TRAILER）

- [ ] **Step 1: 失败测试**（fixture 照 `ProjectRepoBranchTest.seeded`；四条）：

```java
    @Test
    void conflictKeepsMergingStateAndBothTipsIntact(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "draft/1", "HEAD");
        Files.writeString(root.resolve("projects/7/合同.txt"), "主线改动");
        s.commitAll(7L, "主线", "auto", null, "A", "a@x");
        s.checkoutBranch(7L, "draft/1");
        Files.writeString(root.resolve("projects/7/合同.txt"), "稿上改动");
        s.commitAll(7L, "稿", "auto", null, "A", "a@x");
        s.checkoutBranch(7L, s.mainBranch());

        MergeOutcome r = s.mergeKeepingConflicts(7L, "draft/1", "采纳", "A", "a@x");

        assertFalse(r.success());
        assertTrue(r.conflictingPaths().contains("合同.txt"));
        assertTrue(s.repositoryMerging(7L), "必须保留 MERGING 态");
        assertEquals(s.resolveRef(7L, "draft/1"), s.mergeHeadRef(7L));
    }

    @Test
    void abortMergeRestoresCleanMainline(@TempDir Path root) throws Exception {
        // 同上构造冲突后：
        s.abortMerge(7L);
        assertFalse(s.repositoryMerging(7L));
        assertEquals("主线改动", Files.readString(root.resolve("projects/7/合同.txt")));
        assertEquals("稿上改动", new String(s.readBlobAtCommit(7L, "draft/1", "合同.txt")), "稿分毫无损");
    }

    @Test
    void resolveThenCommitProducesTwoParentSessionNode(@TempDir Path root) throws Exception {
        // 冲突后模拟裁决：直接把选定内容写进工作区
        Files.writeString(root.resolve("projects/7/合同.txt"), "稿上改动");
        String sha = s.commitMergeResolution(7L, "采纳：试验稿", "A", "a@x");
        assertNotNull(sha);
        VersionEntry head = s.log(7L, "HEAD", 1).get(0);
        assertEquals(2, head.parents().size(), "裁决提交必须是双亲合并节点");
        assertEquals("session", head.kind());
        assertFalse(s.repositoryMerging(7L));
    }

    @Test
    void cleanMergeStillCommitsWithAuthor(@TempDir Path root) throws Exception {
        // 不同文件改动 → 无冲突路径，行为与既有 merge 等价（success、双亲、署名）
    }
```

（第四条补完整代码，断言照 `mergeOfTrueThreeWayCreatesMergeCommitWithGivenAuthor` 的口径。）

- [ ] **Step 2: 跑红 → Step 3: 实现 → Step 4: 跑绿**（本包全量；**既有 `merge` 一行不动**）
- [ ] **Step 5: 提交** `feat(version): 保留冲突态的合并原语与裁决提交`

---

### Task 3: 稿的创建与双向切线

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`
- Test: `backend/src/test/java/com/checkba/version/DraftLifecycleTest.java`（新建）

**Interfaces:**
- Produces:
  - `record LineSwitchResult(String branch, List<Long> affectedFileIds)`
  - `WorkSession createDraft(long projectId, String ref, String name, Long userId, String userName)` —— 锁内：`ref` 空则取当前 HEAD；停靠当前线（`commitNow`）；建 `draft/{currentTimeMillis}` 于 `ref`；checkout；`syncManifestFromRef("HEAD")` 同步 DB（从旧版本开稿时 DB 树要回到那一版的样子——机制同 revertTo 的清单同步）；建 DRAFT/ACTIVE 行（title=name 必填）；**取消空闲定时器**；返回行。受影响文件 = `diffNameStatus(切换前HEAD, ref)` 映射 DB id，随 `LineSwitchResult` 语义一并返回（方法返回值包一层或拆两个接口，实施取简；控制器最终响应必须含 affectedFileIds）
  - `List<WorkSession> listDrafts(long projectId)` —— ACTIVE 的 DRAFT 行，按建立时间倒序
  - `LineSwitchResult switchToDraft(long projectId, long draftId, Long userId, String userName)` / `LineSwitchResult switchToMainline(long projectId, Long userId, String userName)` —— 锁内：停靠当前线 → checkout 目标（主线侧目标 = ACTIVE WORK 段的分支若有，否则 master）→ 清单同步 → affected ids → 定时器语义（到主线且有 ACTIVE WORK 段则重新武装；到稿则不武装）
  - 校验：switchToDraft 目标必须是本项目 ACTIVE 的 DRAFT（否则 userFacing「这一稿不存在或已处理」）；createDraft 的 name 必填 ≤64（userFacing，口径照里程碑）

- [ ] **Step 1: 失败测试**（六条：从旧版本开稿后 DB 树回到该版（fixture 预置清单差异，断言 `applyToDatabase` 生效——mock `ProjectFileRepository` 记录 save 调用）；开稿后 currentBranch 是 draft/*；listDrafts 只回 ACTIVE DRAFT；来回切换后两线内容各自完好（文件系统断言）；切回主线时 ACTIVE WORK 段分支被选为目标；切换返回的 affectedFileIds 命中变更文件）
- [ ] **Step 2-4: 红 → 实现 → 绿**（本包全量）
- [ ] **Step 5: 提交** `feat(version): 另起一稿与主线双向切换`

---

### Task 4: 采纳 / 裁决 / 中止 / 放弃

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`、`ProjectTreeManifestService.java`
- Test: `backend/src/test/java/com/checkba/version/DraftAdoptTest.java`（新建）

**Interfaces:**
- Produces:
  - `record AdoptOutcome(boolean success, String sha, List<String> conflictingPaths, List<Long> affectedFileIds)`
  - `AdoptOutcome adoptDraft(long projectId, long draftId, Long userId, String userName)` —— 前置：无 ACTIVE WORK 段（userFacing「请先结束或丢弃当前工作，再采纳这一稿」）、目标是 ACTIVE DRAFT、仓库不在 MERGING 态。锁内：若当前在该稿上先 `switchToMainline` 停靠；`mergeKeepingConflicts(draft 分支, "采纳：{name}")`；成功 → **清单并集**（见下）→ 稿标 MERGED → 删稿分支 → affected ids（diff 合并前 HEAD..新 HEAD）→ 返回；冲突 → 保持 MERGING，返回 conflictingPaths（**此时不动 session 行、不删分支**）
  - `enum Resolution { MAIN, DRAFT, BOTH }`
  - `AdoptOutcome resolveAdopt(long projectId, long draftId, Map<String, Resolution> resolutions, Long userId, String userName)` —— 校验：MERGING 态在、`mergeHeadRef == 稿 tip`、resolutions 覆盖**全部** conflictingPaths（少一个 userFacing「还有文件没有做出选择」）。逐文件：MAIN → `readBlobAtCommit(合并前主线 tip, path)` 写工作区；DRAFT → 稿 tip 同理；BOTH → 主线字节留原 path，稿字节写 `《原名（来自：{稿名}）》.ext`（同目录；重名再撞则追加序号），并建 DB 行（复制原行的 parentId/fileType/userId，新 name/filePath）。然后清单并集 → capture/write → `commitMergeResolution` → 稿 MERGED → 删分支 → affected ids → 返回
  - `void abortAdopt(long projectId)` —— MERGING 态下 `abortMerge`；稿保持 ACTIVE 原样（userFacing 响应「这次采纳没有完成，你的两份稿件都还在」——spec 第七节原句）
  - `LineSwitchResult abandonDraft(long projectId, long draftId, Long userId, String userName)` —— 目标是 ACTIVE DRAFT；若当前在该稿上先切回主线侧；删分支（force）；标 DISCARDED；返回切换结果（不在稿上时 affectedFileIds 为空）
  - `ProjectTreeManifestService.unionApply(long projectId, TreeManifest draftManifest)` —— **清单并集规则**：对 draftManifest 每个节点：DB 有同 id → 以稿侧属性覆盖（`applyAttributes` 复用）；DB 无 → 走 `applyToDatabase` 的新建路径（含 id 占用重映射）。**与 `applyToDatabase` 的区别：DB 有、稿清单无的行不动**（主线独有文件保留——这正是「并集」与「同步」的差别）。实现上抽出 applyToDatabase 的节点新建/更新内核复用，禁止复制粘贴两份

- [ ] **Step 1: 失败测试**（八条最少集）：
  1. 干净采纳：两线改不同文件 → success、主线 HEAD 双亲、稿 MERGED、分支已删、affected 含两侧文件、DB 含稿新增文件的行（并集生效）
  2. 有 ACTIVE WORK 段时采纳被 userFacing 拒
  3. 冲突采纳：返回 conflictingPaths、MERGING 保持、稿仍 ACTIVE
  4. resolveAdopt 缺文件裁决 → userFacing 拒、MERGING 保持
  5. 三选一各自生效：MAIN/DRAFT 的字节断言；BOTH → 原 path 是主线字节 + 新文件《合同（来自：试验稿）.txt》是稿字节 + DB 有新行
  6. 裁决后 HEAD 双亲、kind=session、消息「采纳：{名}」
  7. abortAdopt 后：MERGING 清、主线内容回合并前、稿 tip 与分支完好、稿仍 ACTIVE
  8. abandonDraft（在稿上时）：切回主线、分支删、DISCARDED、主线文件未被稿污染
- [ ] **Step 2-4: 红 → 实现 → 绿**（本包全量 + 跑 `TreeManifestSyncTest` 确认内核抽取无回归）
- [ ] **Step 5: 提交** `feat(version): 采纳三选一、中止与放弃，清单并集规则`

---

### Task 5: REST 端点与状态透出

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/VersionController.java`
- Test: `backend/src/test/java/com/checkba/version/VersionControllerAuthTest.java`（矩阵扩展）+ 行为测试并入 `DraftAdoptTest` 风格新类或既有类

**Interfaces:**
- Produces（全部 `requireMember`；写路径 userFacing 校验；响应形制照既有 `{code, data}`）：

| 方法 | 路径 | body / 响应要点 |
|---|---|---|
| POST | `/draft` | `{ref?, name}` → `{draftId, branch, affectedFileIds}` |
| GET | `/drafts` | `{drafts:[{id, name, startedAt}]}` |
| POST | `/draft/{id}/switch` | → `{affectedFileIds}` |
| POST | `/switch-mainline` | → `{affectedFileIds}` |
| POST | `/draft/{id}/adopt` | → `{success, sha?, conflictingPaths?, affectedFileIds?}` |
| POST | `/draft/{id}/resolve` | `{resolutions:{path: "MAIN"\|"DRAFT"\|"BOTH"}}` → 同 adopt 成功形 |
| POST | `/draft/{id}/abort-adopt` | → `{aborted:true}`，message=spec 原句 |
| POST | `/draft/{id}/abandon` | → `{affectedFileIds}` |

`/status` 扩展：`onDraft: {id, name} | null`（`activeDraftOnBranch` 派生）、`adoptConflict: {draftId, draftName, conflictingPaths} | null`（`repositoryMerging` + `mergeHeadRef` 反查 ACTIVE DRAFT——崩溃恢复的关键）、`drafts` 计数或列表由 GET /drafts 单独拉。

- [ ] **Step 1**: 鉴权矩阵 `Endpoint` 枚举 +8 项（含 `verifyNeverCalled`）；IDOR：draftId 属他项目 → 拒（session 行的 projectId 校验）
- [ ] **Step 2-4: 红 → 实现 → 绿**（本包全量）
- [ ] **Step 5: 提交** `feat(version): 稿生命周期 REST 端点与冲突态透出`

---

### Task 6: 前端——稿态状态条、稿列表、开稿入口

**Files:**
- Modify: `frontend/src/services/api.js`、`version/WorkSessionBar.vue`、`version/VersionPanel.vue`、`version/VersionNodeDetail.vue`
- Create: `frontend/src/components/version/DraftList.vue`

**要点：**

- api.js +8 具名导出（写法照既有版本记录组）：`createDraft(projectId, ref, name)` / `listDrafts` / `switchToDraft(projectId, draftId)` / `switchToMainline` / `adoptDraft` / `resolveAdopt(projectId, draftId, resolutions)` / `abortAdopt` / `abandonDraft`
- `WorkSessionBar`：`/status` 的 `onDraft` 非空 → 稿态：「正在稿《{name}》上修改」+ 按钮「回到主线工作」「采纳这一稿」「放弃这一稿」（放弃走 `uni.showModal` 二次确认，文案「这一稿的所有改动都会被丢掉，确定吗？」）；工作态/空闲态照旧。props 从 `VersionPanel` 传 `onDraft`
- `DraftList.vue`：挂在 `VersionPanel` 时间线上方（有稿才显示）：每行名称+日期+「切到这一稿」；顶部「另起一稿」按钮（从当前版本开稿，命名弹窗）。空态不渲染
- `VersionNodeDetail` footer +「从这一版另起一稿」（命名弹窗复用既有模式；成功 toast「已建立稿《{name}》，正在切换」+ emit）
- **所有切线/采纳/放弃成功响应里的 `affectedFileIds` 都要走编辑器重载链**（Task 7 通用化后的事件）；随后刷新面板与文件树
- 失败 toast 一律 `(e && e.message) || 兜底`
- emits 全链注册（check:emits）

- [ ] 实现 → `check:emits` + `build:h5` 绿 → 提交 `feat(version): 稿态状态条、稿列表与开稿入口`

---

### Task 7: 前端——三选一弹窗与重载链通用化

**Files:**
- Create: `frontend/src/components/version/AdoptConflictDialog.vue`
- Modify: `version/VersionPanel.vue`、`pages/project-overview/fileOpenTabs.js`、`project-overview.vue`

**要点：**

- **重载链通用化**：`reverted-files` 事件与 `onVersionRevertedFiles` 改名为 `reload-files` / `onVersionReloadFiles`（语义：版本操作改了磁盘，这些 fileId 的打开中编辑器要强刷）。retro 修改 `VersionNodeDetail` 退回路径的 emit 与各层注册；切线/采纳/放弃路径复用同一事件。**改名而不是并存两条链**——两条同义链是下一个维护者的地雷
- `AdoptConflictDialog`：props `{projectId, draftId, draftName, conflictingPaths}`；每行：文件名 + 三选一（radio：用主线的 / 用这一稿的 / 两份都留）+「对比」按钮（`openVersionCompareTab({projectId, path, name, newRef: 主线tip, oldRef: 稿tip})`——两 tip 从后端 adoptConflict 响应带出或再查 `/status`；对比标签打开时弹窗**不关**，弹窗加 `z-index` 低于标签区处理或先收起——实施时看 `.awd-mask` 的层级现状，保证两者可并用，做不到就「对比」前先收起弹窗、看完可重开（adoptConflict 态在 /status 里，重开无损）
- 底部：「确认采纳」（全部选完才可点，调 `resolveAdopt`）「先不采纳」（调 `abortAdopt`，toast 后端消息「这次采纳没有完成，你的两份稿件都还在」）
- `VersionPanel`：`/status` 带 `adoptConflict` 时（含崩溃后重开面板）自动弹出该弹窗
- 文案零 Git 术语；不用 emoji

- [ ] 实现 → `check:emits` + `build:h5` 绿 → 提交 `feat(version): 采纳冲突三选一弹窗，重载链通用化`

---

### Task 8: e2e J10 与领域文档

**Files:**
- Modify: `frontend/tests/app-e2e/run.mjs`、`.claude/agents/version-control.md`

**J10 旅程**（浏览器目标；上传/裸 REST 造改动的配方照 J9；断言认组件独有选择器——第 2 期教训写死）：

1. 开启版本记录 → 一段命名工作垫底
2. 节点详情「从这一版另起一稿」→ 命名「试验稿」→ 断言状态条进入稿态（「正在稿《试验稿》上修改」——认 WorkSessionBar 的稿态选择器）
3. 稿上上传一个新文件 → 「回到主线工作」→ 断言稿态消失、该文件从文件树消失（稿的改动不漏到主线）
4. 「切到这一稿」→ 断言文件回来（两线内容隔离的正反双证）
5. 回主线 → 主线上用裸 REST 改文件 A → 稿上也改文件 A（裸 REST）→ 回主线 → 「采纳这一稿」→ 断言三选一弹窗出现且列出 A（认 AdoptConflictDialog 选择器）
6. 选「两份都留」→ 确认采纳 → 断言文件树同时出现《A》与《A（来自：试验稿）》、时间线出现「采纳：试验稿」节点、稿列表清空
7. 再开一稿 → 「放弃这一稿」→ 确认 → 断言回主线、稿列表空、时间线无采纳节点

**领域文档**：稿生命周期状态机（DRAFT: ACTIVE→MERGED|DISCARDED、绝不自动合并、不受空闲结束管辖）、切线协议（停靠→checkout→清单同步→重载链）、MERGING 态即冲突态（崩溃恢复靠 /status 反查）、清单并集与同步的语义区别、`reload-files` 通用重载链、对 spec 5.8「分叉连线图」的有意降级。地雷补：稿分支守卫（onChangeSignal/commitNow 见 draft/* 跳过 ensureSession）、采纳前置无 WORK 段的理由。

- [ ] J10 实跑全绿（隔离端口配方沿用）→ 文档更新 → 提交 `test+docs(version): 第 3 期稿旅程与领域文档`

---

## 收尾验证

后端全量 `mvn test`（0 fail 0 error）；`check:emits`；`build:h5`；`test:app-e2e`（J1-J10）；lowa-e2e 本期不动 worker 无需重跑。全绿后推送 → CI → 第 3 期终审（整段 diff + 台账 Minor 分诊）→ 修复 → 交付。

## 第 3 期完成后的状态

律师能：从任意版本另起一稿并命名；在主线工作与稿之间随时切换（两线内容与文件树完全隔离，打开中的编辑器自动跟随）；采纳一稿（冲突时逐文件三选一，可先对比再选，可中止且两边无损）；放弃一稿。spec 第 0-3 期全部落地，v1 收官；v2（云端仓库与多人协作）另起工程。
