package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
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
    private ProjectTreeManifestService manifestSvc;
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
        repoSvc = new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));
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
        manifestSvc = new ProjectTreeManifestService(fileRepo, repoSvc, new ObjectMapper(),
                mock(UserRepository.class), mock(ProjectRepository.class));

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

        svc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler, fileRepo, event -> {});
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

    /**
     * P3-T4 审查 I1：干净路径与冲突路径必须以同一种方式提交。旧实现里干净合并由 JGit
     * 自己提交，落库的 {@code .awd/tree.json} 是 Git 对两份 JSON 做的文本合并，而数据库
     * 随后才被清单并集改写——已提交的清单与数据库当场分叉，且分叉不会有任何提交去弥合。
     * 现在两条路都是「先按数据库算清单、再连同内容一起收进同一个采纳提交」。
     *
     * fixture 是单边改动：主线一动不动，稿改一份文件的内容、再把另一份删进回收站——
     * 这是最朴素的一次采纳，也是这条干净路径唯一被真实走通的证据（既有用例里主线都
     * 动过）。那一次软删除让判别力落到实处：Git 对清单做文本合并会照单收下「回收站」，
     * 而并集只加不减、数据库里那一行仍在用——旧实现提交的正是前者。
     */
    @Test
    void cleanAdoptCommitsManifestBuiltFromDatabaseInTheSameNode() throws Exception {
        db.put(501L, file(501L, "合同.txt"));
        db.put(502L, file(502L, "附件.txt"));
        write("合同.txt", "主线起点");
        write("附件.txt", "主线起点");
        mainlineWork("起点");

        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        long draftId = created.draft().getId();
        write("合同.txt", "稿上改动");
        db.get(502L).setIsDeleted(true);   // 稿上把《附件》删进回收站（软删除不动磁盘）
        svc.commitNow(7L, 1L, "韩泽伟", "稿上存档");

        WorkSessionService.AdoptOutcome r = svc.adoptDraft(7L, draftId, 1L, "韩泽伟");

        assertTrue(r.success(), "单边改动必须一次干净采纳成功");
        assertTrue(r.conflictingPaths().isEmpty());
        assertNotNull(r.sha());
        assertFalse(repoSvc.repositoryMerging(7L), "干净采纳不能留下任何合并残局");
        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L));
        assertEquals("稿上改动", read("合同.txt"));

        VersionEntry head = repoSvc.log(7L, "HEAD", 1).get(0);
        assertEquals(r.sha(), head.sha());
        assertEquals(2, head.parents().size(), "采纳必须是双亲合并节点");
        assertEquals("采纳：试验稿", head.message());
        assertEquals("session", head.kind());

        assertFalse(db.get(502L).getIsDeleted(),
                "前置：并集只加不减，稿上那次删除不会随采纳带过来");

        byte[] blob = repoSvc.readBlobAtCommit(7L, r.sha(), ProjectTreeManifestService.MANIFEST_PATH);
        assertNotNull(blob, "文件树清单必须进这一个采纳提交，不能留在它之外");
        TreeManifest committed = new ObjectMapper()
                .readValue(new String(blob, java.nio.charset.StandardCharsets.UTF_8),
                        TreeManifest.class);
        assertEquals(manifestSvc.capture(7L), committed,
                "采纳提交里的清单必须就是采纳后数据库的样子，不能是 Git 的文本合并结果");
    }

    /**
     * P3-T4 审查 I2：并集旧实现无条件用稿侧的 name/filePath/parentId 覆盖数据库。
     * 主线在稿开出去之后改了名时，git 的三方合并已经按主线那一侧落了盘（磁盘上只有
     * 新名字），数据库却被改回稿记得的旧路径——文件树里多出一行指向不存在的文件，
     * 律师双击就是打不开。数据库要跟随磁盘现实。
     */
    @Test
    void adoptKeepsMainlineRenameInsteadOfResurrectingTheDraftPath() throws Exception {
        db.put(501L, file(501L, "合同.txt"));
        db.put(502L, file(502L, "附件.txt"));
        write("合同.txt", "起点");
        write("附件.txt", "起点");
        mainlineWork("起点");

        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        long draftId = created.draft().getId();
        write("附件.txt", "稿改了附件");   // 稿只动另一份文件，不碰改名的那份
        svc.commitNow(7L, 1L, "韩泽伟", "稿上存档");

        // 主线把《合同》改名，磁盘与数据库一起改
        svc.switchToMainline(7L, 1L, "韩泽伟");
        Files.move(root.resolve("projects/7/合同.txt"),
                root.resolve("projects/7/合同（终稿）.txt"));
        db.get(501L).setName("合同（终稿）.txt");
        db.get(501L).setFilePath("projects/7/合同（终稿）.txt");
        mainlineWork("主线改了名");

        WorkSessionService.AdoptOutcome r = svc.adoptDraft(7L, draftId, 1L, "韩泽伟");

        assertTrue(r.success());
        assertTrue(Files.exists(root.resolve("projects/7/合同（终稿）.txt")),
                "前置：合并后磁盘上是主线的新名字");
        assertFalse(Files.exists(root.resolve("projects/7/合同.txt")),
                "前置：旧名字在磁盘上已经不存在");

        assertEquals("合同（终稿）.txt", db.get(501L).getName(),
                "数据库要跟随磁盘现实，不能被稿的旧清单改回去");
        assertEquals("projects/7/合同（终稿）.txt", db.get(501L).getFilePath());
        assertTrue(db.values().stream()
                        .noneMatch(f -> "projects/7/合同.txt".equals(f.getFilePath())),
                "文件树里不能留下指向不存在文件的幽灵行");
        assertEquals("稿改了附件", read("附件.txt"), "稿自己的改动照旧进主线");

        // I1 在这个 fixture 里才有判别力：两边都改过清单，Git 对两份 JSON 做的文本合并
        // 与并集后的数据库必然不同——旧实现提交的是前者，数据库是后者，当场分叉。
        byte[] blob = repoSvc.readBlobAtCommit(7L, r.sha(), ProjectTreeManifestService.MANIFEST_PATH);
        assertNotNull(blob, "文件树清单必须进这一个采纳提交");
        TreeManifest committed = new ObjectMapper()
                .readValue(new String(blob, java.nio.charset.StandardCharsets.UTF_8),
                        TreeManifest.class);
        assertEquals(manifestSvc.capture(7L), committed,
                "落库清单必须以数据库为源，不能是 Git 对两份 JSON 的文本合并");
    }

    // ---- 1b. 从旧版本另起一稿、零改动就采纳：ALREADY_UP_TO_DATE 不能污染数据库 -----

    /**
     * 损坏向量：律师从旧版本另起一稿（稿 tip 是主线的祖先），一笔都不改就点「采纳」。
     * JGit 的合并结果是 ALREADY_UP_TO_DATE（SAFE 态、无 MERGE_HEAD）——旧实现仍然把
     * {@code outcome.success()} 当成「可以走 completeAdopt」的信号，于是
     * {@code unionApply} 把稿 tip 那份旧版本清单当成"新内容"、按 draft-wins 覆盖进
     * 当前数据库：文件树被静默改回旧版模样，磁盘和 Git 历史都不动、也不产生任何
     * 提交，律师在时间线上看不到任何痕迹，DB 与磁盘就此分叉。
     *
     * fixture 用排序（{@code sortOrder}）而不是改名让两版清单能区分开——改名会撞上
     * {@code ProjectTreeManifestService.mainlineLocationWins} 那条「稿的旧路径在磁盘上
     * 已经不存在时数据库跟随磁盘现实」的现实校验，反而测不出问题；排序不受那条校验
     * 保护，旧实现会原样被 draft-wins 覆盖，是这个损坏向量的直接判别信号。
     */
    @Test
    void adoptingDraftFromAnAncestorCommitWithNoChangesDoesNotCorruptTheFileTree() throws Exception {
        db.put(501L, file(501L, "合同.txt"));
        write("合同.txt", "起点");
        mainlineWork("起点");
        String oldCommit = repoSvc.resolveRef(7L, repoSvc.mainBranch());

        // 主线继续往前走一笔，只改排序——旧版本的清单从此和当前版本不同
        db.get(501L).setSortOrder(5);
        mainlineWork("主线调整了排序");

        int commitCountBefore = repoSvc.log(7L, "HEAD", 100).size();

        // 从第一笔（旧版本）另起一稿，一笔都不改
        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, oldCommit, "旧版本的稿", 1L, "韩泽伟");

        WorkSessionService.AdoptOutcome r =
                svc.adoptDraft(7L, created.draft().getId(), 1L, "韩泽伟");

        assertTrue(r.success());
        assertEquals(5, db.get(501L).getSortOrder(),
                "RED 时会被 unionApply 改回旧排序（0）——这就是损坏的直接证据");

        assertEquals(commitCountBefore, repoSvc.log(7L, "HEAD", 100).size(),
                "没有任何实质内容，不该产生新提交");
        assertFalse(repoSvc.repositoryMerging(7L), "必须落回 SAFE 态");

        assertEquals(WorkSession.Status.MERGED, sessions.get(created.draft().getId()).getStatus());
        assertFalse(repoSvc.listBranches(7L).contains(created.draft().getBranchName()),
                "稿分支应已删除");

        assertNotNull(r.notice());
        assertTrue(r.notice().contains("没有任何改动"),
                "要告诉律师这一稿没有实质内容、没有生成版本");
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
        assertEquals("还有文件没选留哪一份", e.getMessage());
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

    /**
     * 「两份都留」撞名：同目录里已经有一份《……（来自：试验稿）.txt》（上一次采纳留下的，
     * 或者律师自己起的这个名字）。绝不能覆盖它，要追加序号另存。
     */
    @Test
    void bothChoiceAppendsSequenceNumberWhenSideBySideNameIsTaken() throws Exception {
        db.put(501L, file(501L, "合同.txt"));

        ConflictScene scene = stageAdoptConflict("合同.txt");
        write("合同（来自：试验稿）.txt", "早先另存过的一份");

        WorkSessionService.AdoptOutcome r = svc.resolveAdopt(7L, scene.draftId(),
                Map.of("合同.txt", WorkSessionService.Resolution.BOTH), 1L, "韩泽伟");

        assertTrue(r.success());
        assertEquals("早先另存过的一份", read("合同（来自：试验稿）.txt"),
                "已经存在的同名文件绝不能被另存覆盖");
        assertEquals("稿：合同.txt", read("合同（来自：试验稿）2.txt"),
                "撞名时在后面追加序号另存");

        ProjectFile copy = db.values().stream()
                .filter(f -> "合同（来自：试验稿）2.txt".equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("追加序号的那一份也要在文件树里建行"));
        assertEquals("projects/7/合同（来自：试验稿）2.txt", copy.getFilePath());
    }

    /**
     * C1 的保险带：万一冲突记录还是在裁决之前被谁抹掉了（仓库停在 MERGING_RESOLVED、
     * 索引里一条冲突都不剩），resolveAdopt 必须显式失败，绝不能让「覆盖全部冲突」的
     * 校验空转、把带冲突标记的半成品当裁决结果提交进主线。技术档异常——修好
     * pendingChanges 之后这一步不该再发生，发生即 bug，不给 userFacing 粉饰。
     */
    @Test
    void resolveAdoptRefusesWhenConflictRecordWasWipedOut() throws Exception {
        db.put(501L, file(501L, "合同.txt"));
        ConflictScene scene = stageAdoptConflict("合同.txt");

        // 模拟「有人把冲突 add 掉了」：git add 对冲突路径的语义就是「我解决了」
        try (org.eclipse.jgit.lib.Repository repo = repoSvc.open(7L);
             org.eclipse.jgit.api.Git git = new org.eclipse.jgit.api.Git(repo)) {
            git.add().addFilepattern(".").call();
        }
        assertTrue(repoSvc.conflictingPaths(7L).isEmpty(), "前置：冲突记录已被抹掉");
        assertTrue(repoSvc.repositoryMerging(7L), "前置：仓库仍停在合并中");

        VersionException e = assertThrows(VersionException.class,
                () -> svc.resolveAdopt(7L, scene.draftId(),
                        Map.of("合同.txt", WorkSessionService.Resolution.MAIN), 1L, "韩泽伟"));

        assertFalse(e.isUserFacing(), "这是内部不变式被破坏，不该粉饰成业务提示");
        assertTrue(e.getMessage().contains("冲突记录已丢失"));
        assertEquals(scene.mainTipBeforeAdopt(), repoSvc.resolveRef(7L, "HEAD"),
                "绝不能落下任何提交");
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(scene.draftId()).getStatus(),
                "稿不能被标处理");
        assertTrue(repoSvc.listBranches(7L).contains(scene.draftBranch()), "稿分支不能被删");
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

        assertEquals("这次采纳没有完成，你的两份稿件都还在", WorkSessionService.adoptAbortedNotice());
    }

    /**
     * P3 终审 C1：中止采纳绝不能销毁「这次合并之外」的未提交编辑。
     *
     * 现场：冲突窗口里版本捕获整体关闭（{@code onChangeSignal} 直接返回，自动存档不落地），
     * 而律师可能在这期间继续编辑别的文件几个小时——那些改动只在磁盘上、一笔都没进历史。
     * 旧实现的中止是 {@code reset --hard HEAD}，会把工作区里**所有**跟踪文件回滚到 HEAD，
     * 窗口期的编辑全部消失且无从恢复，而按钮旁边的提示还写着「你的两份稿件都还在」。
     *
     * 修复后中止只还原「这次合并触及的路径」（冲突路径 ∪ HEAD↔MERGE_HEAD 的差异），
     * 窗口外的文件分毫不动。
     */
    @Test
    void abortAdoptKeepsUncommittedEditsOutsideTheMergeWindow() throws Exception {
        db.put(501L, file(501L, "合同.txt"));
        db.put(503L, file(503L, "无关.txt"));
        // 与这次合并完全无关的一份文件：两条线都没碰过它，中止不该动它一个字节。
        write("无关.txt", "主线上的原样");

        ConflictScene scene = stageAdoptConflict("合同.txt");
        String draftBranch = scene.draftBranch();
        String draftTip = repoSvc.resolveRef(7L, draftBranch);
        String mainTip = repoSvc.resolveRef(7L, "HEAD");
        assertEquals("主线上的原样", read("无关.txt"), "前置：无关文件此刻还是主线那一版");

        // 律师在裁决窗口里干了几个小时的活：改无关文件（版本捕获全关，一笔都没提交），
        // 也在冲突文件的半成品上动过手。
        write("无关.txt", "窗口期改了几个小时的内容");
        write("合同.txt", "冲突半成品上乱动过");

        svc.abortAdopt(7L);

        assertEquals("窗口期改了几个小时的内容", read("无关.txt"),
                "RED：全树 reset --hard 会把窗口期的未提交编辑连根销毁");
        assertEquals("主线：合同.txt", read("合同.txt"), "冲突文件回到主线那一版");
        assertFalse(Files.exists(root.resolve("projects/7/稿新增.txt")),
                "合并检出的稿独有文件要随中止一起撤走");

        assertFalse(repoSvc.repositoryMerging(7L), "待裁决状态应被清掉");
        assertNull(repoSvc.mergeHeadRef(7L), "MERGE_HEAD 要被清掉");
        assertEquals(mainTip, repoSvc.resolveRef(7L, "HEAD"), "历史永不重写：HEAD 一步不动");
        assertEquals(draftTip, repoSvc.resolveRef(7L, draftBranch), "稿 tip 完好");
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(scene.draftId()).getStatus());

        // 中止之后仓库必须是健康的：窗口期的编辑能正常落进后续版本。
        String sha = svc.commitNow(7L, 1L, "韩泽伟", "窗口期的编辑");
        assertNotNull(sha, "中止后仓库要能正常提交，索引不能留着冲突残渣");
        assertEquals("窗口期改了几个小时的内容",
                new String(repoSvc.readBlobAtCommit(7L, sha, "无关.txt"),
                        java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * P3 终审 I2：冲突采纳返回的重载列表漏了「合并已经改写、但没冲突」的文件。
     *
     * JGit 的冲突合并仍然会把非冲突的稿侧改动检出到工作区；旧实现的 affectedFileIds
     * 只有「切回主线侧」那一步的差异，律师站在主线上按采纳时那一步是空的——打开中的
     * 编辑器端着旧字节，一次 autosave 就把稿的改动写回去，随后
     * {@code commitMergeResolution} 的 {@code git add .} 把它收进采纳提交，改动无声丢失。
     */
    @Test
    void conflictingAdoptFromMainlineReportsFilesRewrittenByTheMerge() throws Exception {
        db.put(501L, file(501L, "合同.txt"));
        db.put(503L, file(503L, "稿改的.txt"));
        write("合同.txt", "起点");
        write("稿改的.txt", "起点");
        mainlineWork("起点");

        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        write("合同.txt", "稿：合同");
        write("稿改的.txt", "只有稿改过的内容");
        svc.commitNow(7L, 1L, "韩泽伟", "稿上存档");

        // 回主线并只改冲突文件——《稿改的.txt》主线一动不动，合并时是干净检出。
        svc.switchToMainline(7L, 1L, "韩泽伟");
        write("合同.txt", "主线：合同");
        mainlineWork("主线的工作");

        // 律师就站在主线上按「采纳这一稿」：内部那次停靠不产生任何差异。
        WorkSessionService.AdoptOutcome r =
                svc.adoptDraft(7L, created.draft().getId(), 1L, "韩泽伟");

        assertFalse(r.success(), "前置：这一步应该造出一个采纳冲突");
        assertEquals(List.of("合同.txt"), r.conflictingPaths());
        assertEquals("只有稿改过的内容", read("稿改的.txt"),
                "前置：合并已经把稿的非冲突改动检出到工作区");
        assertTrue(r.affectedFileIds().contains(503L),
                "RED：合并改写过的非冲突文件必须进重载列表，否则 autosave 会把它写回旧字节");
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

    // ---- 冲突窗口里刷新 /status 绝不能抹掉冲突记录 ---------------------------

    /**
     * P3-T4 审查 C1：版本面板每次挂载都会拉一次 {@code /status}，而 {@code /status} 的
     * changedCount 走 {@link WorkSessionService#pendingChangesLocked}。旧实现里那条路径
     * 无条件做两次 {@code git add}，在冲突窗口里等于把索引里的冲突标记全部「解决」掉：
     * 仓库从 MERGING 变 MERGING_RESOLVED、{@code getConflicting()} 清空（JGit 探针实证），
     * 随后 {@link WorkSessionService#resolveAdopt} 的「覆盖全部冲突」校验空转、逐文件裁决
     * 一次都不执行，带 {@code <<<<<<<} 标记的半成品被当成裁决结果提交进主线、稿分支还被
     * 删掉——不可逆的数据事故，而触发它的只是律师刷新了一下面板。
     */
    @Test
    void statusRefreshInConflictWindowKeepsConflictsAndNeverPollutesMainline() throws Exception {
        db.put(501L, file(501L, "合同.txt"));
        ConflictScene scene = stageAdoptConflict("合同.txt");

        List<FileChange> pending = svc.pendingChangesLocked(7L);

        assertTrue(repoSvc.repositoryMerging(7L), "/status 刷新不能改变待裁决状态");
        assertEquals(List.of(".awd/tree.json", "合同.txt"), repoSvc.conflictingPaths(7L),
                "/status 刷新绝不能抹掉索引里的冲突记录");
        assertEquals(List.of(".awd/tree.json", "合同.txt"),
                pending.stream().map(FileChange::path).sorted().toList(),
                "冲突窗口里 changedCount 的语义就是「还等着裁决的文件数」");
        assertEquals(scene.mainTipBeforeAdopt(), repoSvc.resolveRef(7L, "HEAD"),
                "只读刷新不能推进 HEAD");

        // 刷新之后三选一仍然真实生效
        WorkSessionService.AdoptOutcome r = svc.resolveAdopt(7L, scene.draftId(),
                Map.of("合同.txt", WorkSessionService.Resolution.MAIN), 1L, "韩泽伟");

        assertTrue(r.success());
        assertEquals("主线：合同.txt", read("合同.txt"), "裁决必须真的执行，不能空转");
        for (String path : List.of("合同.txt", ProjectTreeManifestService.MANIFEST_PATH)) {
            String bytes = new String(repoSvc.readBlobAtCommit(7L, "HEAD", path),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(bytes.contains("<<<<<<<"),
                    "主线里绝不能出现冲突标记: " + path);
            assertFalse(bytes.contains(">>>>>>>"),
                    "主线里绝不能出现冲突标记: " + path);
        }
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
    void resumeSessionRejectsWhileMerging() throws Exception {
        db.put(501L, file(501L, "合同.txt"));

        ConflictScene scene = stageAdoptConflict("合同.txt");
        assertTrue(repoSvc.repositoryMerging(7L), "前置：应该停在待裁决状态");

        VersionException e = assertThrows(VersionException.class, () -> svc.resumeSession(7L));

        assertTrue(e.isUserFacing());
        assertEquals("请先处理正在进行的采纳", e.getMessage());
        assertTrue(repoSvc.repositoryMerging(7L), "守卫后仍在待裁决状态");
        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L),
                "裁决现场所在的分支不能被切走");
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(scene.draftId()).getStatus());
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
