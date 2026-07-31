package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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
 * 第 3 期 Task 3：稿的创建与双向切线。
 *
 * fixture 结构照 {@link WorkSessionServiceTest}（工作段/切线部分）与
 * {@link TreeManifestSyncTest}（用一个真的 HashMap 背后的假仓储，让
 * applyToDatabase 的效果可以直接从 db 里读出来，而不是只验证 save 被调用过）。
 */
class DraftLifecycleTest {

    private Path root;
    private ProjectRepoService repoSvc;
    private WorkSessionService svc;
    private Map<Long, WorkSession> sessions;
    private long nextSessionId;
    private Map<Long, ProjectFile> db;
    private long nextFileId;
    private ProjectTreeManifestService manifestSvc;
    private WorkSessionRepository sessionRepo;
    private ThreadPoolTaskScheduler scheduler;
    private ProjectFileRepository fileRepo;

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
        fileRepo = mock(ProjectFileRepository.class);
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
        sessionRepo = mock(WorkSessionRepository.class);
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

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();

        svc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler, fileRepo, event -> {});
        svc.setDebounceMillis(60_000); // 测试里不让防抖自己触发，全部手动 commitNow
    }

    private ProjectFile file(long id, long projectId, String filePath, String name) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(projectId);
        f.setName(name);
        f.setIsFolder(false);
        f.setFileType("txt");
        f.setSortOrder(0);
        f.setFilePath(filePath);
        f.setIsDeleted(false);
        f.setUserId(1L);
        return f;
    }

    // ---- 1. 从旧版本开稿：DB 树要回到那一版的样子 --------------------------

    /**
     * 从旧版本另起一稿，DB 里的文件树必须跟着回到那一版的样子——机制同 revertTo 的
     * 清单同步（syncManifestFromRef → applyToDatabase）。用一个真的 db map 背后的假
     * 仓储：v1 只有一份「合同.txt」，v2 改了它的名字、又新增一份「备忘.txt」；从 v1
     * 开稿之后，「合同.txt」的名字必须回到 v1 的样子，v2 才新增的「备忘.txt」必须
     * 被清单同步判定为「清单里没有」而软删——这正是 applyToDatabase 生效的证据。
     */
    @Test
    void createDraftFromOldVersionSyncsDatabaseTreeBackToThatVersion() throws Exception {
        long projectId = 20L;
        Files.createDirectories(root.resolve("projects/" + projectId));
        Files.writeString(root.resolve("projects/" + projectId + "/合同.txt"), "v1 内容");

        db.put(501L, file(501L, projectId, "projects/" + projectId + "/合同.txt", "合同.txt"));

        svc.enableVersionRecording(projectId, "韩泽伟", "hzw@example.com");
        String v1Sha = repoSvc.log(projectId, "HEAD", 1).get(0).sha();

        svc.onChangeSignal(projectId, 1L, "韩泽伟");
        db.get(501L).setName("合同-改名.txt");
        Files.writeString(root.resolve("projects/" + projectId + "/合同.txt"), "v2 内容");
        Files.writeString(root.resolve("projects/" + projectId + "/备忘.txt"), "备忘内容");
        db.put(502L, file(502L, projectId, "projects/" + projectId + "/备忘.txt", "备忘.txt"));
        svc.commitNow(projectId, 1L, "韩泽伟", "改名+新增");
        svc.endSession(projectId, 1L, "韩泽伟", "v2 工作");

        assertEquals("合同-改名.txt", db.get(501L).getName(), "测试前置条件：v2 时名字确已改过");
        assertFalse(db.get(502L).getIsDeleted());

        reset(fileRepo);
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

        svc.createDraft(projectId, v1Sha, "旧版试验稿", 1L, "韩泽伟");

        verify(fileRepo, atLeastOnce()).save(any(ProjectFile.class));
        assertEquals("合同.txt", db.get(501L).getName(), "DB 树应该回到 v1 的样子：名字改回去");
        assertTrue(db.get(502L).getIsDeleted(), "v1 里没有的文件，同步后应该被判定为回收站状态");
    }

    // ---- 2. 开稿后 currentBranch 是 draft/* --------------------------------

    @Test
    void createDraftSwitchesToDraftBranch() {
        WorkSessionService.DraftCreateResult result =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");

        assertTrue(repoSvc.currentBranch(7L).startsWith("draft/"),
                "开稿后当前分支应该是 draft/*");
        assertEquals(repoSvc.currentBranch(7L), result.draft().getBranchName());
        assertEquals(repoSvc.currentBranch(7L), result.lineSwitch().branch());
        assertEquals(WorkSession.SessionType.DRAFT, result.draft().getSessionType());
        assertEquals(WorkSession.Status.ACTIVE, result.draft().getStatus());
        assertEquals("试验稿", result.draft().getTitle());
    }

    @Test
    void createDraftRejectsBlankName() {
        VersionException e = assertThrows(VersionException.class,
                () -> svc.createDraft(7L, null, "  ", 1L, "韩泽伟"));
        assertTrue(e.isUserFacing());
    }

    @Test
    void createDraftRejectsNameLongerThan64() {
        String tooLong = "字".repeat(65);
        VersionException e = assertThrows(VersionException.class,
                () -> svc.createDraft(7L, null, tooLong, 1L, "韩泽伟"));
        assertTrue(e.isUserFacing());
    }

    // ---- 3. listDrafts 只回 ACTIVE 的 DRAFT，按建立时间倒序 -----------------

    @Test
    void listDraftsOnlyReturnsActiveDraftsInDescendingOrder() {
        WorkSession d1 = svc.createDraft(7L, null, "稿一", 1L, "韩泽伟").draft();
        svc.switchToMainline(7L, 1L, "韩泽伟");
        WorkSession d2 = svc.createDraft(7L, null, "稿二", 1L, "韩泽伟").draft();

        d1.setStartedAt(LocalDateTime.now().minusMinutes(10));
        d2.setStartedAt(LocalDateTime.now());

        // 模拟稿一已经被处理掉（Task 4 才会真正实现 adopt/abandon，这里只测过滤）
        d1.setStatus(WorkSession.Status.MERGED);

        List<WorkSession> drafts = svc.listDrafts(7L);

        assertEquals(1, drafts.size(), "只应该回 ACTIVE 的 DRAFT");
        assertEquals(d2.getId(), drafts.get(0).getId());
    }

    @Test
    void listDraftsOrdersActiveDraftsByStartedAtDescending() {
        WorkSession d1 = svc.createDraft(7L, null, "稿一", 1L, "韩泽伟").draft();
        svc.switchToMainline(7L, 1L, "韩泽伟");
        WorkSession d2 = svc.createDraft(7L, null, "稿二", 1L, "韩泽伟").draft();

        d1.setStartedAt(LocalDateTime.now().minusMinutes(10));
        d2.setStartedAt(LocalDateTime.now());

        List<WorkSession> drafts = svc.listDrafts(7L);

        assertEquals(List.of(d2.getId(), d1.getId()), drafts.stream().map(WorkSession::getId).toList(),
                "较晚建立的稿应该排在前面");
    }

    // ---- 4. 来回切换后两线内容各自完好（正反双证） --------------------------

    @Test
    void switchingBetweenLinesKeepsEachLineIntact() throws Exception {
        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        String draftBranch = created.draft().getBranchName();
        long draftId = created.draft().getId();

        Files.writeString(root.resolve("projects/7/合同.txt"), "稿上的改动");
        svc.commitNow(7L, 1L, "韩泽伟", "稿上存档");

        svc.switchToMainline(7L, 1L, "韩泽伟");
        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L));
        assertEquals("初稿", Files.readString(root.resolve("projects/7/合同.txt")),
                "反证：回到主线应该看到主线内容，不能被稿的改动污染");

        svc.switchToDraft(7L, draftId, 1L, "韩泽伟");
        assertEquals(draftBranch, repoSvc.currentBranch(7L));
        assertEquals("稿上的改动", Files.readString(root.resolve("projects/7/合同.txt")),
                "正证：切回稿应该看到稿上的改动");
    }

    @Test
    void switchToDraftIsIdempotentWhenAlreadyOnIt() {
        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");

        WorkSessionService.LineSwitchResult result =
                svc.switchToDraft(7L, created.draft().getId(), 1L, "韩泽伟");

        assertEquals(created.draft().getBranchName(), result.branch());
        assertTrue(result.affectedFileIds().isEmpty(), "已经在这一稿上，切换应幂等返回空 affected");
    }

    @Test
    void switchToDraftRejectsUnknownOrNonActiveDraft() {
        VersionException e = assertThrows(VersionException.class,
                () -> svc.switchToDraft(7L, 99999L, 1L, "韩泽伟"));
        assertTrue(e.isUserFacing());
    }

    // ---- 5. 切回主线时 ACTIVE 工作段分支被选为目标 --------------------------

    @Test
    void switchToMainlineTargetsActiveWorkSessionBranchWhenPresent() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        String workBranch = svc.activeSession(7L).orElseThrow().getBranchName();
        Files.writeString(root.resolve("projects/7/合同.txt"), "工作中的改动");

        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        assertTrue(repoSvc.currentBranch(7L).startsWith("draft/"));
        assertTrue(svc.activeSession(7L).isPresent(), "开稿不应该结束既有工作段");
        assertEquals(workBranch, svc.activeSession(7L).orElseThrow().getBranchName());

        WorkSessionService.LineSwitchResult result = svc.switchToMainline(7L, 1L, "韩泽伟");

        assertEquals(workBranch, result.branch(), "应该回到 ACTIVE 工作段自己的分支，而不是 master");
        assertEquals(workBranch, repoSvc.currentBranch(7L));
        assertEquals("工作中的改动", Files.readString(root.resolve("projects/7/合同.txt")),
                "工作段自己的改动应该完好带回来");

        assertNotEquals(created.draft().getBranchName(), repoSvc.currentBranch(7L));
    }

    @Test
    void switchToMainlineTargetsMasterWhenNoActiveWorkSession() {
        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        assertTrue(svc.activeSession(7L).isEmpty(), "本场景没有既有工作段，这是测试前提");

        WorkSessionService.LineSwitchResult result = svc.switchToMainline(7L, 1L, "韩泽伟");

        assertEquals(repoSvc.mainBranch(), result.branch());
        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L));
    }

    // ---- 6. 切换返回的 affectedFileIds 命中变更文件 -------------------------

    @Test
    void switchReturnsAffectedFileIdsForChangedFiles() throws Exception {
        db.put(501L, file(501L, 7L, "projects/7/合同.txt", "合同.txt"));

        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "稿上改动");
        svc.commitNow(7L, 1L, "韩泽伟", "稿上存档");

        WorkSessionService.LineSwitchResult result = svc.switchToMainline(7L, 1L, "韩泽伟");

        assertEquals(List.of(501L), result.affectedFileIds(), "应该命中这次切换改动过的文件");

        WorkSessionService.LineSwitchResult back =
                svc.switchToDraft(7L, created.draft().getId(), 1L, "韩泽伟");
        assertEquals(List.of(501L), back.affectedFileIds(), "切回去同一份差异也应该被命中");
    }

    // ---- 额外：预警场景——ACTIVE 工作段 + 已切去稿上时，陈旧空闲定时器不能硬切用户 --

    /**
     * T1 审查者留的预警：endSession/autoEndIfIdle 假设「当前 checkout == 段分支」，
     * 稿的双向切线打破了这个假设。这里直接反射调用私有的 autoEndIfIdle（模式照
     * {@link WorkSessionServiceTest} 里的 invokeAutoEndIfIdle），模拟"陈旧定时器
     * 恰好还带着这段工作正确的 sessionId、但用户此刻已经切去稿上"这个窗口场景，
     * 断言：用户不会被硬切回主线，这段工作段依然 ACTIVE，稿分毫无损。
     */
    @Test
    void idleTimeoutWhileOnDraftDoesNotStealUserAwayOrDamageEitherLine() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        WorkSession work = svc.activeSession(7L).orElseThrow();
        Files.writeString(root.resolve("projects/7/合同.txt"), "工作中的改动");

        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        String draftBranch = created.draft().getBranchName();
        assertEquals(draftBranch, repoSvc.currentBranch(7L), "测试前提：现在已经在稿上");
        // 稿是从当前 HEAD（也就是刚停靠的"工作中的改动"）开出来的，稿上初始内容
        // 本就该带着这份改动——这里记下切换前的内容，而不是想当然地认定是"初稿"。
        String draftContentBeforeIdleTrigger =
                Files.readString(root.resolve("projects/7/合同.txt"));

        invokeAutoEndIfIdle(svc, 7L, work.getId());

        assertEquals(draftBranch, repoSvc.currentBranch(7L), "用户不应该被空闲定时器切走");
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(work.getId()).getStatus(),
                "ACTIVE 工作段不应该被一个陈旧的空闲定时器误杀");
        assertEquals("工作中的改动",
                new String(repoSvc.readBlobAtCommit(7L, work.getBranchName(), "合同.txt")),
                "工作段自己分支上的内容应该分毫无损");
        assertEquals(draftContentBeforeIdleTrigger,
                Files.readString(root.resolve("projects/7/合同.txt")),
                "稿上的内容不应该被这次误触发的自动结束改动分毫");
    }

    private static void invokeAutoEndIfIdle(WorkSessionService target, long projectId, Long sessionId)
            throws Exception {
        Method m = WorkSessionService.class.getDeclaredMethod("autoEndIfIdle", long.class, Long.class);
        m.setAccessible(true);
        m.invoke(target, projectId, sessionId);
    }

    // ---- P3-T3 审查 Important：脏但无段时开稿不能撞 checkout 冲突 -----------

    /**
     * dockCurrentLine 曾经只在「有段」（onDraftBranch || activeSession 存在）时才
     * 停靠，但有几条真实路径会把主线工作区弄脏而不经过 onChangeSignal 发信号
     * （AI artifact 保存、尽调插件上传、分片上传中途、解压时序缺陷）——这里直接用
     * Files.writeString 模拟这样一个不发信号的写入者：不开任何段，直接弄脏主线
     * 工作区，然后开稿。
     *
     * 要真正撞到 checkout 冲突，稿的目标版本（{@code ref}）必须和当前 HEAD 的内容
     * 不同——如果 ref 恰好等于当前 HEAD，JGit 的 checkout 发现目标树跟索引一致，
     * 根本不需要碰这个文件，也就不会冲突。所以这里先手工推进 HEAD 一笔真实提交
     * （v1→v2，绕过 svc，模拟"已经存在的历史"），再在 v2 之上弄脏工作区、且开稿
     * 目标定成 v1——checkout 需要把这个文件从 v2 的内容改写成 v1 的内容，但工作区
     * 上还有一份既不是 v1 也不是 v2 的脏内容，JGit 必须拒绝（CheckoutConflictException，
     * 在服务层包成技术档 VersionException）。
     *
     * 旧实现在这种「脏且无段」的状态下会跳过停靠，直接走到这一步撞冲突，开稿
     * 反复失败。这里断言：开稿本身成功；脏内容作为一笔 auto 提交留在了主线历史里
     * （不是被悄悄丢弃、也不是被塞进一个新孵出的工作段）；没有任何 WORK 工作段被
     * 孵出来（activeSession 为空、分支列表里没有 work/* 分支）；稿正常建立。
     */
    @Test
    void createDraftAutoDocksDirtyMainlineWithNoSessionInstead() throws Exception {
        String v1Sha = repoSvc.log(7L, "HEAD", 1).get(0).sha();

        // 推进 HEAD 到 v2：绕过 svc 的一笔真实提交，模拟"已经存在的历史"。
        Files.writeString(root.resolve("projects/7/合同.txt"), "v2 内容");
        repoSvc.commitAll(7L, "推进到 v2", "auto", null, "系统", "sys@x");

        // 模拟不经过 onChangeSignal 就弄脏工作区的写入者：不开任何段。
        Files.writeString(root.resolve("projects/7/合同.txt"), "没发信号就落下的脏内容");
        assertTrue(svc.activeSession(7L).isEmpty(), "测试前提：此刻没有任何 ACTIVE 工作段");

        // 稿目标定成 v1——跟当前脏工作区、跟当前 HEAD(v2) 都不同，checkout 真的需要
        // 改写这个文件，这是撞上 CheckoutConflictException 的必要条件。
        WorkSessionService.DraftCreateResult result =
                svc.createDraft(7L, v1Sha, "旧版试验稿", 1L, "韩泽伟");

        assertNotNull(result, "开稿应该成功，不应该撞 checkout 冲突");
        assertTrue(repoSvc.currentBranch(7L).startsWith("draft/"), "稿应该正常建立并切过去");

        List<VersionEntry> mainHistory = repoSvc.log(7L, repoSvc.mainBranch(), 5);
        assertEquals("auto", mainHistory.get(0).kind(),
                "脏内容应该作为一笔 auto 自动存档留在主线历史里");
        assertEquals("没发信号就落下的脏内容",
                new String(repoSvc.readBlobAtCommit(7L, repoSvc.mainBranch(), "合同.txt")),
                "主线历史上的这笔提交应该真的带着那份脏内容");

        assertTrue(svc.activeSession(7L).isEmpty(), "不能因为停靠而孵出一个 WORK 工作段");
        assertTrue(repoSvc.listBranches(7L).stream().noneMatch(b -> b.startsWith("work/")),
                "仓库里不应该出现任何 work/* 分支");
    }

    // ---- MERGING 态拒绝一切切线/开稿（用 T2 的 repositoryMerging） -----------

    /**
     * 用 {@link ConflictMergeTest} 同样的手法直接在 project 7 上造一个冲突，让仓库
     * 停在 MERGING 态，然后断言 createDraft/switchToDraft/switchToMainline 三个入口
     * 全部拒绝——那期间工作区是裁决现场，任何切线的 checkout 都会把它冲掉。
     */
    @Test
    void allThreeEntriesRejectWhileRepositoryIsMerging() throws Exception {
        repoSvc.createBranch(7L, "other/1", "HEAD");
        Files.writeString(root.resolve("projects/7/合同.txt"), "主线改动");
        repoSvc.commitAll(7L, "主线", "auto", null, "A", "a@x");
        repoSvc.checkoutBranch(7L, "other/1");
        Files.writeString(root.resolve("projects/7/合同.txt"), "另一支改动");
        repoSvc.commitAll(7L, "另一支", "auto", null, "A", "a@x");
        repoSvc.checkoutBranch(7L, repoSvc.mainBranch());
        repoSvc.mergeKeepingConflicts(7L, "other/1", "试合并", "A", "a@x");
        assertTrue(repoSvc.repositoryMerging(7L), "测试前提：仓库应该已经停在 MERGING 态");

        assertTrue(assertThrows(VersionException.class,
                () -> svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟")).isUserFacing());
        assertTrue(assertThrows(VersionException.class,
                () -> svc.switchToDraft(7L, 1L, 1L, "韩泽伟")).isUserFacing());
        assertTrue(assertThrows(VersionException.class,
                () -> svc.switchToMainline(7L, 1L, "韩泽伟")).isUserFacing());
    }
}
