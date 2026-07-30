package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 第 3 期 Task 4：采纳 / 裁决 / 中止 / 放弃，以及清单并集。
 *
 * fixture 照 {@link DraftLifecycleTest}：一个真的 Git 仓库 + 一个真的 HashMap 背后的
 * 假仓储，所有断言都落在真实的文件字节、真实的提交历史与真实的 db 行上。
 */
class DraftAdoptTest {

    private Path root;
    private ProjectRepoService repoSvc;
    private WorkSessionService svc;
    private Map<Long, WorkSession> sessions;
    private long nextSessionId;
    private Map<Long, ProjectFile> db;
    private long nextFileId;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(props);
        repoSvc.init(7L, "韩泽伟", "hzw@example.com");

        db = new HashMap<>();
        nextFileId = 100L;
        ProjectFileRepository fileRepo = mock(ProjectFileRepository.class);
        when(fileRepo.findByProjectId(any())).thenAnswer(i -> {
            Long pid = i.getArgument(0);
            List<ProjectFile> out = new ArrayList<>();
            for (ProjectFile f : db.values()) if (f.getProjectId().equals(pid)) out.add(f);
            return out;
        });
        when(fileRepo.save(any(ProjectFile.class))).thenAnswer(i -> {
            ProjectFile p = i.getArgument(0);
            if (p.getId() == null) p.setId(nextFileId++);
            db.put(p.getId(), p);
            return p;
        });
        ProjectTreeManifestService manifestSvc =
                new ProjectTreeManifestService(fileRepo, repoSvc, new ObjectMapper());

        sessions = new HashMap<>();
        nextSessionId = 1L;
        WorkSessionRepository sessionRepo = mock(WorkSessionRepository.class);
        when(sessionRepo.save(any(WorkSession.class))).thenAnswer(i -> {
            WorkSession s = i.getArgument(0);
            if (s.getId() == null) s.setId(nextSessionId++);
            sessions.put(s.getId(), s);
            return s;
        });
        when(sessionRepo.findFirstByProjectIdAndStatusAndSessionType(any(), any(), any())).thenAnswer(i ->
                sessions.values().stream()
                        .filter(s -> s.getProjectId().equals(i.getArgument(0))
                                && s.getStatus() == i.getArgument(1)
                                && s.getSessionType() == i.getArgument(2))
                        .findFirst());
        when(sessionRepo.findByProjectIdAndStatusAndSessionTypeOrderByStartedAtDesc(any(), any(), any()))
                .thenAnswer(i -> sessions.values().stream()
                        .filter(s -> s.getProjectId().equals(i.getArgument(0))
                                && s.getStatus() == i.getArgument(1)
                                && s.getSessionType() == i.getArgument(2))
                        .sorted(Comparator.comparing(WorkSession::getStartedAt).reversed())
                        .toList());
        when(sessionRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(sessions.get(i.getArgument(0))));

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();

        svc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler, fileRepo);
        svc.setDebounceMillis(60_000); // 测试里不让防抖自己触发，全部手动落版
    }

    // ---- helpers ----------------------------------------------------------

    private ProjectFile file(long id, String name, Long parentId, boolean folder) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(7L);
        f.setParentId(parentId);
        f.setName(name);
        f.setIsFolder(folder);
        f.setFileType(folder ? null : "txt");
        f.setSortOrder(0);
        f.setFilePath(folder ? null : "projects/7/" + name);
        f.setIsDeleted(false);
        f.setUserId(1L);
        return f;
    }

    private ProjectFile file(long id, String name) {
        return file(id, name, null, false);
    }

    private void write(String relPath, String content) throws Exception {
        Files.writeString(root.resolve("projects/7").resolve(relPath), content);
    }

    private String read(String relPath) throws Exception {
        return Files.readString(root.resolve("projects/7").resolve(relPath));
    }

    /** 主线上完整走一段工作：隐式开段 → 收尾提交 → 合并回主线（结束后没有 ACTIVE 工作段）。 */
    private void mainlineWork(String title) {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        svc.endSession(7L, 1L, "韩泽伟", title);
    }

    /**
     * 造一个采纳冲突现场并停在待裁决状态，返回稿 id。
     *
     * 除了 names 里两边都改过的文件，两条线各自还新增一份只有自己有的文件（连带 db 行）——
     * 这会让两边的文件树清单同时发生变化、进而让内部清单文件 .awd/tree.json 也参与冲突，
     * 从而覆盖「内部清单冲突不能出现在律师面前」和「并集不能把主线独有的行送进回收站」
     * 这两条不变式。
     */
    private record ConflictScene(long draftId, String draftBranch, String mainTipBeforeAdopt,
                                 WorkSessionService.AdoptOutcome outcome) {}

    private ConflictScene stageAdoptConflict(String... names) throws Exception {
        for (String n : names) write(n, "起点：" + n);
        mainlineWork("起点");

        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        for (String n : names) write(n, "稿：" + n);
        write("稿新增.txt", "只有稿有");
        db.put(601L, file(601L, "稿新增.txt"));
        svc.commitNow(7L, 1L, "韩泽伟", "稿上存档");

        svc.switchToMainline(7L, 1L, "韩泽伟");
        for (String n : names) write(n, "主线：" + n);
        write("主线新增.txt", "只有主线有");
        db.put(602L, file(602L, "主线新增.txt"));
        mainlineWork("主线的工作");

        svc.switchToDraft(7L, created.draft().getId(), 1L, "韩泽伟");
        String mainTipBeforeAdopt = repoSvc.resolveRef(7L, repoSvc.mainBranch());
        WorkSessionService.AdoptOutcome r =
                svc.adoptDraft(7L, created.draft().getId(), 1L, "韩泽伟");
        assertFalse(r.success(), "前置：这一步应该造出一个采纳冲突");
        assertTrue(repoSvc.repositoryMerging(7L), "前置：应该停在待裁决状态");
        return new ConflictScene(created.draft().getId(), created.draft().getBranchName(),
                mainTipBeforeAdopt, r);
    }

    // ---- 1. 干净采纳 -------------------------------------------------------

    @Test
    void cleanAdoptMergesDraftUnionsManifestAndDeletesBranch() throws Exception {
        db.put(501L, file(501L, "合同.txt"));
        // 先在主线上落一版带文件树清单的起点（setUp 里的初始提交是直接建仓库落的，
        // 没有清单；不垫这一步，后面的清单同步全是空操作，测不出并集）。
        write("合同.txt", "主线起点");
        mainlineWork("起点");

        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        long draftId = created.draft().getId();
        String draftBranch = created.draft().getBranchName();

        // 稿上新增一份文件（磁盘 + db 行）
        write("附件.txt", "稿上新增");
        db.put(502L, file(502L, "附件.txt"));
        svc.commitNow(7L, 1L, "韩泽伟", "稿上新增");

        // 主线上改另一份文件，走完整一段工作（结束后没有 ACTIVE 工作段）
        svc.switchToMainline(7L, 1L, "韩泽伟");
        assertTrue(db.get(502L).getIsDeleted(),
                "前置：回到主线后，稿新增文件的行应被清单同步判为回收站状态");
        write("合同.txt", "主线改动");
        mainlineWork("主线的工作");

        // 律师站在稿上按「采纳这一稿」
        svc.switchToDraft(7L, draftId, 1L, "韩泽伟");
        String mainTipBefore = repoSvc.resolveRef(7L, repoSvc.mainBranch());

        WorkSessionService.AdoptOutcome r = svc.adoptDraft(7L, draftId, 1L, "韩泽伟");

        assertTrue(r.success());
        assertTrue(r.conflictingPaths().isEmpty());

        VersionEntry head = repoSvc.log(7L, "HEAD", 1).get(0);
        assertEquals(2, head.parents().size(), "采纳必须是双亲合并节点");
        assertEquals("采纳：试验稿", head.message());
        assertEquals("session", head.kind());
        assertEquals(r.sha(), head.sha());
        assertNotEquals(mainTipBefore, head.sha());
        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L), "采纳后应停在主线上");

        assertEquals(WorkSession.Status.MERGED, sessions.get(draftId).getStatus());
        assertFalse(repoSvc.listBranches(7L).contains(draftBranch), "稿分支应已删除");
        assertTrue(svc.listDrafts(7L).isEmpty());

        assertEquals("稿上新增", read("附件.txt"), "稿的新增文件应该进主线");
        assertEquals("主线改动", read("合同.txt"), "主线自己的改动不能被稿冲掉");

        assertFalse(db.get(502L).getIsDeleted(), "清单并集：稿新增文件的行应该回到文件树");
        assertTrue(r.affectedFileIds().contains(502L), "稿侧文件要进重载列表");
        assertTrue(r.affectedFileIds().contains(501L),
                "从稿上采纳时主线侧文件在磁盘上也变过，同样要进重载列表");
    }

    // ---- 2. 有 ACTIVE 工作段时拒绝采纳 --------------------------------------

    @Test
    void adoptRejectedWhileAnotherWorkSessionIsActive() throws Exception {
        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        svc.switchToMainline(7L, 1L, "韩泽伟");

        write("合同.txt", "主线在改");
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        assertTrue(svc.activeSession(7L).isPresent(), "前置：存在一段进行中的工作");

        VersionException e = assertThrows(VersionException.class,
                () -> svc.adoptDraft(7L, created.draft().getId(), 1L, "韩泽伟"));

        assertTrue(e.isUserFacing());
        assertEquals("请先结束或丢弃当前工作，再采纳这一稿", e.getMessage());
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(created.draft().getId()).getStatus());
        assertFalse(repoSvc.repositoryMerging(7L), "被拒绝的采纳不能留下任何合并残局");
    }

    // ---- 3. 冲突采纳：保持待裁决，稿分毫无损 --------------------------------

    @Test
    void conflictingAdoptKeepsMergingStateAndLeavesDraftIntact() throws Exception {
        db.put(501L, file(501L, "合同.txt"));

        ConflictScene scene = stageAdoptConflict("合同.txt");
        long draftId = scene.draftId();
        String draftBranch = scene.draftBranch();
        WorkSessionService.AdoptOutcome r = scene.outcome();

        assertFalse(r.success());
        assertTrue(repoSvc.conflictingPaths(7L).contains(ProjectTreeManifestService.MANIFEST_PATH),
                "防假阳性：内部清单确实一起冲突了，下面那条过滤断言才有意义");
        assertEquals(List.of("合同.txt"), r.conflictingPaths(),
                "只有律师认识的文件才该出现在冲突清单里");
        assertTrue(r.conflictingPaths().stream().noneMatch(p -> p.startsWith(".awd/")),
                "内部文件树清单不能出现在律师面前");
        assertNull(r.sha());

        assertTrue(repoSvc.repositoryMerging(7L), "必须保留待裁决状态");
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(draftId).getStatus(), "稿不能被标处理");
        assertTrue(repoSvc.listBranches(7L).contains(draftBranch), "稿分支不能被删");
        assertEquals(repoSvc.resolveRef(7L, draftBranch), repoSvc.mergeHeadRef(7L),
                "待裁决的另一父就是这一稿的 tip");
        assertEquals("稿：合同.txt",
                new String(repoSvc.readBlobAtCommit(7L, draftBranch, "合同.txt")),
                "稿上的内容分毫无损");

        // 「合并前主线 tip 就是 HEAD」：合并还没提交，HEAD 一步都没动过。
        assertEquals(scene.mainTipBeforeAdopt(), repoSvc.resolveRef(7L, "HEAD"),
                "待裁决状态下 HEAD 仍然停在合并前的主线 tip");
        assertEquals("主线：合同.txt",
                new String(repoSvc.readBlobAtCommit(7L, "HEAD", "合同.txt")),
                "HEAD 这一版就是主线合并前的那一版，「用主线的」的字节从这里取");
    }

    // ---- 4. 裁决缺文件 -----------------------------------------------------

    @Test
    void resolveAdoptRejectsWhenSomeConflictHasNoChoice() throws Exception {
        ConflictScene scene = stageAdoptConflict("甲.txt", "乙.txt");
        assertEquals(List.of("乙.txt", "甲.txt"), scene.outcome().conflictingPaths(),
                "前置：两份文件都要律师做出选择");

        VersionException e = assertThrows(VersionException.class,
                () -> svc.resolveAdopt(7L, scene.draftId(),
                        Map.of("甲.txt", WorkSessionService.Resolution.MAIN), 1L, "韩泽伟"));

        assertTrue(e.isUserFacing());
        assertEquals("还有文件没有做出选择", e.getMessage());
        assertTrue(repoSvc.repositoryMerging(7L), "拒绝之后仍要停在待裁决状态");
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(scene.draftId()).getStatus());
        assertEquals(scene.mainTipBeforeAdopt(), repoSvc.resolveRef(7L, "HEAD"),
                "被拒绝的裁决不能落下任何提交");
    }

    // ---- 5. 三选一各自生效 -------------------------------------------------

    @Test
    void resolveAdoptAppliesMainDraftAndBothChoices() throws Exception {
        db.put(500L, file(500L, "案卷", null, true));
        db.put(501L, file(501L, "合同.txt", 500L, false));

        long draftId = stageAdoptConflict("甲.txt", "乙.txt", "合同.txt").draftId();

        WorkSessionService.AdoptOutcome r = svc.resolveAdopt(7L, draftId, Map.of(
                "甲.txt", WorkSessionService.Resolution.MAIN,
                "乙.txt", WorkSessionService.Resolution.DRAFT,
                "合同.txt", WorkSessionService.Resolution.BOTH), 1L, "韩泽伟");

        assertTrue(r.success());
        assertEquals("主线：甲.txt", read("甲.txt"), "选「用主线的」应该落主线字节");
        assertEquals("稿：乙.txt", read("乙.txt"), "选「用这一稿的」应该落稿字节");
        assertEquals("主线：合同.txt", read("合同.txt"), "两份都留：原文件仍是主线字节");
        assertEquals("稿：合同.txt", read("合同（来自：试验稿）.txt"),
                "两份都留：稿字节另存为同目录的新文件");

        assertNotNull(repoSvc.readBlobAtCommit(7L, "HEAD", "合同（来自：试验稿）.txt"),
                "另存的那一份必须真的进了这一版");

        ProjectFile copy = db.values().stream()
                .filter(f -> "合同（来自：试验稿）.txt".equals(f.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("两份都留应该建一条新的文件树行"));
        assertEquals(500L, copy.getParentId(), "新行的父目录复制原行");
        assertEquals("txt", copy.getFileType());
        assertEquals(1L, copy.getUserId());
        assertFalse(copy.getIsFolder());
        assertFalse(copy.getIsDeleted());
        assertEquals("projects/7/合同（来自：试验稿）.txt", copy.getFilePath());
        assertEquals("合同.txt", db.get(501L).getName(), "原行不能被顶掉");

        assertFalse(db.get(601L).getIsDeleted(), "清单并集：稿独有文件的行应该回到文件树");
        assertFalse(db.get(602L).getIsDeleted(), "清单并集绝不能把主线独有文件的行送进回收站");
        assertEquals("只有主线有", read("主线新增.txt"));
        assertEquals("只有稿有", read("稿新增.txt"));
    }

    // ---- 6. 裁决提交的形制 -------------------------------------------------

    @Test
    void resolvedAdoptProducesTwoParentSessionNodeWithAdoptMessage() throws Exception {
        ConflictScene scene = stageAdoptConflict("合同.txt");
        long draftId = scene.draftId();
        String draftBranch = scene.draftBranch();

        WorkSessionService.AdoptOutcome r = svc.resolveAdopt(7L, draftId,
                Map.of("合同.txt", WorkSessionService.Resolution.DRAFT), 1L, "韩泽伟");

        assertTrue(r.success());
        VersionEntry head = repoSvc.log(7L, "HEAD", 1).get(0);
        assertEquals(2, head.parents().size(), "裁决提交必须是双亲合并节点");
        assertEquals("session", head.kind());
        assertEquals("采纳：试验稿", head.message());
        assertEquals("韩泽伟", head.authorName());
        assertEquals(r.sha(), head.sha());

        assertFalse(repoSvc.repositoryMerging(7L), "裁决提交后不再是待裁决状态");
        assertEquals(WorkSession.Status.MERGED, sessions.get(draftId).getStatus());
        assertFalse(repoSvc.listBranches(7L).contains(draftBranch));
        assertEquals("稿：合同.txt", read("合同.txt"));
    }

    // ---- 7. 中止采纳 -------------------------------------------------------

    @Test
    void abortAdoptRestoresMainlineAndLeavesBothLinesIntact() throws Exception {
        ConflictScene scene = stageAdoptConflict("合同.txt");
        long draftId = scene.draftId();
        String draftBranch = scene.draftBranch();
        String draftTip = repoSvc.resolveRef(7L, draftBranch);
        String mainTip = repoSvc.resolveRef(7L, "HEAD");

        svc.abortAdopt(7L);

        assertFalse(repoSvc.repositoryMerging(7L), "待裁决状态应被清掉");
        assertEquals("主线：合同.txt", read("合同.txt"), "主线内容回到合并前");
        assertEquals(mainTip, repoSvc.resolveRef(7L, "HEAD"), "历史永不重写：HEAD 一步不动");
        assertEquals(draftTip, repoSvc.resolveRef(7L, draftBranch), "稿 tip 完好");
        assertEquals("稿：合同.txt",
                new String(repoSvc.readBlobAtCommit(7L, draftBranch, "合同.txt")));
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(draftId).getStatus(), "稿原样还在");
        assertEquals(1, svc.listDrafts(7L).size());

        assertEquals("这次采纳没有完成，你的两份稿件都还在", WorkSessionService.ADOPT_ABORTED_NOTICE);
    }

    // ---- 裁决期间的自动存档绝不能落地 ---------------------------------------

    /**
     * 律师对着三选一弹窗时，编辑器的自动保存还在后台跑。这期间工作区里是带冲突标记的
     * 半成品，而 MERGE_HEAD 还在磁盘上——一旦让自动存档提交下去，JGit 会把它写成双亲
     * 提交、顺手清掉合并状态，等于一次后台保存悄悄"完成"了一场没人裁决过的采纳，
     * 冲突标记还永久进了主线。
     */
    @Test
    void autoSaveDuringAdoptDecisionNeverCommits() throws Exception {
        ConflictScene scene = stageAdoptConflict("合同.txt");

        svc.onChangeSignal(7L, 1L, "韩泽伟");
        assertNull(svc.commitNow(7L, 1L, "韩泽伟", null), "裁决期间不能落自动存档");
        assertNull(svc.commitAiRound(7L, 1L), "裁决期间 AI 轮次也不能落版");

        assertTrue(svc.activeSession(7L).isEmpty(), "裁决期间不能凭空开出一段工作");
        assertTrue(repoSvc.repositoryMerging(7L), "合并状态不能被自动存档清掉");
        assertEquals(scene.mainTipBeforeAdopt(), repoSvc.resolveRef(7L, "HEAD"),
                "HEAD 一步都不能动");

        // 守卫之后正常裁决仍然走得通
        WorkSessionService.AdoptOutcome r = svc.resolveAdopt(7L, scene.draftId(),
                Map.of("合同.txt", WorkSessionService.Resolution.MAIN), 1L, "韩泽伟");
        assertTrue(r.success());
        assertEquals("主线：合同.txt", read("合同.txt"));
    }

    // ---- 8. 放弃这一稿 -----------------------------------------------------

    @Test
    void abandonDraftFromDraftSwitchesBackDeletesBranchAndKeepsMainlineClean() throws Exception {
        db.put(501L, file(501L, "合同.txt"));

        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        long draftId = created.draft().getId();
        String draftBranch = created.draft().getBranchName();

        write("合同.txt", "稿上污染");
        write("只在稿上.txt", "稿上新增");
        svc.commitNow(7L, 1L, "韩泽伟", "稿上存档");

        WorkSessionService.LineSwitchResult result = svc.abandonDraft(7L, draftId, 1L, "韩泽伟");

        assertEquals(repoSvc.mainBranch(), result.branch());
        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L), "应该切回主线");
        assertTrue(result.affectedFileIds().contains(501L), "被切换改写过的文件要进重载列表");

        assertFalse(repoSvc.listBranches(7L).contains(draftBranch), "稿分支应被删除");
        assertEquals(WorkSession.Status.DISCARDED, sessions.get(draftId).getStatus());
        assertTrue(svc.listDrafts(7L).isEmpty());

        assertEquals("初稿", read("合同.txt"), "主线内容不能被稿污染");
        assertFalse(Files.exists(root.resolve("projects/7/只在稿上.txt")),
                "稿上新增的文件不能漏到主线");
    }

    @Test
    void abandonDraftFromMainlineLeavesCurrentLineUntouched() throws Exception {
        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        long draftId = created.draft().getId();
        svc.switchToMainline(7L, 1L, "韩泽伟");

        WorkSessionService.LineSwitchResult result = svc.abandonDraft(7L, draftId, 1L, "韩泽伟");

        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L));
        assertTrue(result.affectedFileIds().isEmpty(), "不在这一稿上时没有文件被改写");
        assertFalse(repoSvc.listBranches(7L).contains(created.draft().getBranchName()));
        assertEquals(WorkSession.Status.DISCARDED, sessions.get(draftId).getStatus());
    }

    @Test
    void abandonRejectsUnknownDraft() {
        VersionException e = assertThrows(VersionException.class,
                () -> svc.abandonDraft(7L, 99999L, 1L, "韩泽伟"));
        assertTrue(e.isUserFacing());
    }

    // ---- 9. 三个方法在 MERGING 态时被守卫拒绝 ------

    @Test
    void endSessionRejectsWhileMerging() throws Exception {
        db.put(501L, file(501L, "合同.txt"));

        ConflictScene scene = stageAdoptConflict("合同.txt");
        assertTrue(repoSvc.repositoryMerging(7L), "前置：应该停在待裁决状态");

        VersionException e = assertThrows(VersionException.class,
                () -> svc.endSession(7L, 1L, "韩泽伟", "应该被拒绝"));

        assertTrue(e.isUserFacing());
        assertEquals("请先处理正在进行的采纳", e.getMessage());
        assertTrue(repoSvc.repositoryMerging(7L), "守卫后仍在待裁决状态");
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(scene.draftId()).getStatus(),
                "稿仍为 ACTIVE");
    }

    @Test
    void discardSessionRejectsWhileMerging() throws Exception {
        db.put(501L, file(501L, "合同.txt"));

        ConflictScene scene = stageAdoptConflict("合同.txt");
        assertTrue(repoSvc.repositoryMerging(7L), "前置：应该停在待裁决状态");

        VersionException e = assertThrows(VersionException.class,
                () -> svc.discardSession(7L, 1L));

        assertTrue(e.isUserFacing());
        assertEquals("请先处理正在进行的采纳", e.getMessage());
        assertTrue(repoSvc.repositoryMerging(7L), "守卫后仍在待裁决状态");
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(scene.draftId()).getStatus(),
                "稿仍为 ACTIVE");
    }

    @Test
    void revertToRejectsWhileMerging() throws Exception {
        db.put(501L, file(501L, "合同.txt"));

        ConflictScene scene = stageAdoptConflict("合同.txt");
        String mainTipBefore = repoSvc.resolveRef(7L, repoSvc.mainBranch());
        assertTrue(repoSvc.repositoryMerging(7L), "前置：应该停在待裁决状态");

        VersionException e = assertThrows(VersionException.class,
                () -> svc.revertTo(7L, mainTipBefore, 1L, "韩泽伟"));

        assertTrue(e.isUserFacing());
        assertEquals("请先处理正在进行的采纳", e.getMessage());
        assertTrue(repoSvc.repositoryMerging(7L), "守卫后仍在待裁决状态");
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(scene.draftId()).getStatus(),
                "稿仍为 ACTIVE");
    }
}
