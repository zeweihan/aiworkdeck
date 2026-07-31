package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
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
 * union 复活语义收紧——三方基线感知（v1 裁定「服务层无法区分机械软删与亲手软删」被
 * 用户否决后的落地）。核心规则见 {@link ProjectTreeManifestService#unionApply(long,
 * TreeManifest, TreeManifest)} 的判定矩阵：DB 行在回收站、对方清单显示 active 时，
 * 只有当对方相对合并基线真的做过复活动作才放行，基线显示对方也没动过删除状态
 * （本方自己亲手软删）时不放行；基线里压根没有这个节点（对方新增）时仍然放行，
 * 这是 v1 就有、绝不能因为加了基线判定而回归的关键场景。
 *
 * fixture 照 {@link SessionEndConflictTest}/{@link DraftAdoptTest}：真实 Git 仓库 +
 * HashMap 假仓储；用例 1、3 走全链路（endSession/adoptDraft），用例 2、4 判定矩阵
 * 本身用 JSON 手作的清单直调 {@link ProjectTreeManifestService#unionApply}，不必
 * 每次都搭一整套 peer 推进现场。
 */
class UnionReviveGuardTest {

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

    // ---- helpers ------------------------------------------------------------

    private ProjectFile file(long id, String name) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(7L);
        f.setName(name);
        f.setIsFolder(false);
        f.setFileType("txt");
        f.setSortOrder(0);
        f.setFilePath("projects/7/" + name);
        f.setIsDeleted(false);
        f.setUserId(1L);
        return f;
    }

    private void write(String relPath, String content) throws Exception {
        Files.writeString(root.resolve("projects/7").resolve(relPath), content);
    }

    /** 主线上完整走一段工作：隐式开段 → 收尾提交 → 合并回主线（结束后没有 ACTIVE 工作段）。 */
    private void mainlineWork(String title) {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        svc.endSession(7L, 1L, "韩泽伟", title);
    }

    /** 工作段开着的时候，用 peer 仓库把服务端 master 推进一步——只碰不相干的文件。 */
    private void advanceMasterFromPeer(Path peerDir, String fileName, String content) throws Exception {
        String url = repoSvc.gitDir(7L).toUri().toString();
        try (Git peer = Git.cloneRepository().setURI(url).setDirectory(peerDir.toFile())
                .setBranch("master").call()) {
            Files.writeString(peerDir.resolve(fileName), content);
            peer.add().addFilepattern(".").call();
            peer.commit().setMessage("同事的工作\n\nX-AWD-Kind: session")
                    .setAuthor("同事", "peer@example.com").call();
            peer.push().setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master")).call();
        }
    }

    /** 判定矩阵单测用：手作一个 v2 节点，只关心 uid 与 isDeleted。 */
    private TreeManifest.Node v2Node(String uid, String name, boolean deleted) {
        return new TreeManifest.Node(null, null, name, false, "txt", 0, null,
                deleted, null, uid, null, name, null);
    }

    // ---- 1. 被否决场景的直接回归：亲手软删的文件不能被并集复活 -----------------

    /**
     * 工作段里把文件软删 + commitNow（清单记下 deleted）→ peer 推进 master 改不相干
     * 文件（对 这份文件 的清单状态毫无触碰，从合并基线到 peer tip 一直是 active，
     * 因为 peer 压根没碰过它）→ endSession 真合并。这就是被用户否决的那条行为：
     * 旧实现的 unionApply（无基线）看到「DB deleted、对方清单 active」就无条件复活，
     * 而这次复活纯粹是「对方清单是分叉前的旧状态」的机械副作用，不是对方真的救回来。
     */
    @Test
    void handDeletedFileSurvivesSessionEndMerge(@TempDir Path peer) throws Exception {
        db.put(501L, file(501L, "合同.txt"));
        mainlineWork("起点"); // 建一个带清单的「起点」提交，充当后面真三方合并的基线

        svc.onChangeSignal(7L, 1L, "韩泽伟"); // 开一段新工作，从「起点」分叉
        db.get(501L).setIsDeleted(true);      // 亲手把《合同》软删进回收站
        assertNotNull(svc.commitNow(7L, 1L, "韩泽伟", null), "前置：软删除要真的落一笔存档");

        // peer 在这期间推进了 master，但只碰不相干的文件——从基线到 peer tip，
        // 《合同》的清单状态原封不动，一直是 active（peer 根本没有「救回」它这个动作）。
        advanceMasterFromPeer(peer, "不相干.txt", "同事的工作");

        WorkSessionService.SessionEndResult r = svc.endSession(7L, 1L, "韩泽伟", "软删的这段工作");

        assertNull(r.conflict(), "前置：不该有内容冲突（两边没有碰同一份用户文件）");
        assertNotNull(r.sha(), "前置：真合并必须成功落一笔");
        assertTrue(db.get(501L).getIsDeleted(),
                "RED：亲手软删的文件被否决场景复活——旧实现（无基线）会在这里把它错误地救回来");
    }

    // ---- 2. 判定矩阵：基线确认对方真复活了才放行 --------------------------

    /**
     * 基线里这个节点是 deleted、对方（传给 unionApply 的 manifest）清单标 active——
     * 对方相对基线真的做了复活动作，并集必须放行。直接 JSON 手作 v2 清单调三参
     * unionApply 验证判定矩阵，不必真的搭一趟 peer git 推进现场。
     */
    @Test
    void genuineReviveByPeerIsApplied() {
        String uid = "uid-合同";
        db.put(501L, file(501L, "合同.txt"));
        db.get(501L).setUid(uid);
        db.get(501L).setIsDeleted(true);

        TreeManifest base = new TreeManifest(2, List.of(v2Node(uid, "合同.txt", true)));
        TreeManifest theirs = new TreeManifest(2, List.of(v2Node(uid, "合同.txt", false)));

        manifestSvc.unionApply(7L, theirs, base);

        assertFalse(db.get(501L).getIsDeleted(),
                "基线是 deleted、对方是 active：对方真的复活了它，并集必须放行");
    }

    // ---- 3. v1 关键场景不能回归：基线缺节点（稿上新建文件）仍要放行复活 -------

    /**
     * 稿上新建文件 → 切回主线（清单同步语义下，主线的清单没有这个节点，行被机械
     * 判成回收站）→ 采纳这一稿。合并基线是「主线 tip」与「稿 tip」的合并基线，也就是
     * 稿分叉出去之前的那一版——那一版根本不认识这个后来才新建的文件，baseByUid 里
     * 压根没有它的 uid。这正是判定矩阵里「基线没有这个节点」的一支：必须放行复活，
     * 这是 v1 就有的关键行为（DraftAdoptTest.cleanAdoptMergesDraftUnionsManifestAndDeletesBranch
     * 已经覆盖过一次同款场景，这里额外钉一个直接聚焦这一支判定矩阵的回归）。
     */
    @Test
    void draftCreatedFileStillRevivesOnAdopt() throws Exception {
        db.put(501L, file(501L, "合同.txt"));
        write("合同.txt", "主线起点");
        mainlineWork("起点");

        WorkSessionService.DraftCreateResult created =
                svc.createDraft(7L, null, "试验稿", 1L, "韩泽伟");
        long draftId = created.draft().getId();

        write("附件.txt", "稿上新增");
        db.put(502L, file(502L, "附件.txt"));
        svc.commitNow(7L, 1L, "韩泽伟", "稿上新增");

        svc.switchToMainline(7L, 1L, "韩泽伟");
        assertTrue(db.get(502L).getIsDeleted(),
                "前置：回到主线后，稿新增文件的行被清单同步机械判成回收站状态");

        WorkSessionService.AdoptOutcome r = svc.adoptDraft(7L, draftId, 1L, "韩泽伟");

        assertTrue(r.success());
        assertTrue(r.conflictingPaths().isEmpty());
        assertFalse(db.get(502L).getIsDeleted(),
                "基线（稿分叉前那一版）压根没有这个节点：三方基线判定必须放行复活，"
                        + "不能因为加了基线判定反而堵死 v1 这条关键路径");
    }

    // ---- 4. 两参 unionApply（无基线）原样保留 v1 语义 -----------------------

    /**
     * 两参版本委托三参版本、base 传 null——判定矩阵在 base 不可得时整体退回 v1 行为
     * （放行）。{@link TreeManifestSyncTest#unionApplyRestoresFromRecycleBinButNeverSendsAnActiveRowThere}
     * 是这条语义最初的护栏，这里在服务层用真实的 {@link WorkSessionService} 依赖装配
     * 再钉一遍，确认三方基线这个新特性没有悄悄改写两参路径的行为。
     */
    @Test
    void twoArgUnionApplyKeepsV1Semantics() {
        db.put(501L, file(501L, "稿新增.docx"));
        db.get(501L).setIsDeleted(true);
        db.put(502L, file(502L, "主线独有.docx"));

        manifestSvc.unionApply(7L, new TreeManifest(1, List.of(
                new TreeManifest.Node(501L, null, "稿新增.docx", false, "txt", 0,
                        "projects/7/稿新增.docx", false, 1L, null, null, null, null),
                new TreeManifest.Node(502L, null, "主线独有.docx", false, "txt", 1,
                        "projects/7/主线独有.docx", true, 1L, null, null, null, null))));

        assertFalse(db.get(501L).getIsDeleted(),
                "两参 unionApply（无基线）：deleted→active 依旧复活，v1 语义不能变");
        assertFalse(db.get(502L).getIsDeleted(),
                "两参 unionApply：并集只加不减，active 行不能被送进回收站");
    }
}
