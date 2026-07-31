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
 * Task 7：服务端 endSession 冲突化——结束工作时主线被同事推进、又跟这段工作撞了车，
 * 走三选一（对比 Task 4 的 DraftAdoptTest：那边是「采纳一稿」撞车，这边是「结束工作」
 * 撞车，语义方向相反——ours=master=同事的，theirs=工作段=我这边的）。
 *
 * fixture 照 DraftAdoptTest（真实 Git 仓库 + HashMap 假仓储）。「同事推进主线」用一个
 * peer 克隆经 file:// 直推服务端 gitDir——工作段期间 HEAD 停在 work/* 分支，master
 * 没有被检出，push 不会被 JGit 的 denyCurrentBranch 拒绝。
 */
class SessionEndConflictTest {

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

    private void write(String relPath, String content) throws Exception {
        Files.writeString(root.resolve("projects/7").resolve(relPath), content);
    }

    /** 写盘 + 手动落一笔存档。 */
    private void writeAndCommit(String relPath, String content) throws Exception {
        write(relPath, content);
        svc.commitNow(7L, 1L, "韩泽伟", null);
    }

    /** enable + 隐式开段 + 落一笔存档，让工作段真正处于 ACTIVE。 */
    private void enableAndStartSession() {
        svc.enableVersionRecording(7L, "韩泽伟", "hzw@example.com");
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        svc.commitNow(7L, 1L, "韩泽伟", null);
    }

    private WorkSession sessionOf(long id) {
        return sessions.get(id);
    }

    /** 工作段开着的时候，用 peer 仓库把服务端 master 推进一步。 */
    private void advanceMasterFromPeer(Path peerDir, String content) throws Exception {
        String url = repoSvc.gitDir(7L).toUri().toString();
        try (Git peer = Git.cloneRepository().setURI(url).setDirectory(peerDir.toFile())
                .setBranch("master").call()) {
            Files.writeString(peerDir.resolve("合同.txt"), content);
            peer.add().addFilepattern(".").call();
            peer.commit().setMessage("同事的工作\n\nX-AWD-Kind: session")
                    .setAuthor("同事", "peer@example.com").call();
            peer.push().setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master")).call();
        }
    }

    // ---------------------------------------------------------------------

    @Test
    void endSessionOnAdvancedMainlineWithoutOverlapMergesCleanlyInOneCommit(@TempDir Path peer)
            throws Exception {
        enableAndStartSession();                       // helper：enable + onChangeSignal + commitNow
        writeAndCommit("我的文件.txt", "我的内容");      // 工作段里改不相干的文件
        advanceMasterFromPeer(peer, "同事的第二稿");
        WorkSessionService.SessionEndResult r =
                svc.endSession(7L, 1L, "韩泽伟", "两不相干");
        assertNull(r.conflict());
        assertNotNull(r.sha());
        // 双亲合并提交、两侧内容都在、清单同提交（地雷 #21 口径）
        List<VersionEntry> log = repoSvc.log(7L, "master", 10);
        assertEquals(2, log.get(0).parents().size());
        assertEquals("两不相干", log.get(0).message());
        assertEquals("同事的第二稿", Files.readString(root.resolve("projects/7/合同.txt")));
        assertEquals("我的内容", Files.readString(root.resolve("projects/7/我的文件.txt")));
        assertNotNull(repoSvc.readBlobAtCommit(7L, r.sha(), ".awd/tree.json"));
    }

    @Test
    void endSessionConflictStaysActiveAndResolvesWithBoth(@TempDir Path peer) throws Exception {
        enableAndStartSession();
        writeAndCommit("合同.txt", "我这边的第二稿");     // 与同事改同一文件 → 冲突
        advanceMasterFromPeer(peer, "同事的第二稿");
        WorkSessionService.SessionEndResult r =
                svc.endSession(7L, 1L, "韩泽伟", "撞车的工作");
        assertNull(r.sha());
        assertNotNull(r.conflict());
        assertTrue(r.conflict().conflictingPaths().contains("合同.txt"));
        assertEquals(WorkSession.Status.ACTIVE, sessionOf(r.conflict().sessionId()).getStatus());
        assertTrue(repoSvc.repositoryMerging(7L));

        WorkSessionService.SessionEndResult done = svc.resolveSessionEnd(7L,
                r.conflict().sessionId(),
                Map.of("合同.txt", WorkSessionService.Resolution.BOTH), 1L, "韩泽伟");
        assertNotNull(done.sha());
        assertFalse(repoSvc.repositoryMerging(7L));
        assertEquals(WorkSession.Status.MERGED, sessionOf(r.conflict().sessionId()).getStatus());
        assertEquals("同事的第二稿", Files.readString(root.resolve("projects/7/合同.txt")));
        // BOTH 的副本来自工作段侧
        assertTrue(Files.list(root.resolve("projects/7"))
                .map(p -> p.getFileName().toString())
                .anyMatch(n -> n.contains("来自") && n.contains("撞车的工作")));
    }

    @Test
    void abortSessionEndReturnsToBranchWithNothingLost(@TempDir Path peer) throws Exception {
        enableAndStartSession();
        writeAndCommit("合同.txt", "我这边的第二稿");
        advanceMasterFromPeer(peer, "同事的第二稿");
        WorkSessionService.SessionEndResult r = svc.endSession(7L, 1L, "韩泽伟", "撞车的工作");
        assertNotNull(r.conflict());

        String notice = svc.abortSessionEnd(7L);
        assertNotNull(notice);
        assertFalse(repoSvc.repositoryMerging(7L));
        // 回到工作段分支，内容原样
        WorkSession s = sessionOf(r.conflict().sessionId());
        assertEquals(WorkSession.Status.ACTIVE, s.getStatus());
        assertEquals(s.getBranchName(), repoSvc.currentBranch(7L));
        assertEquals("我这边的第二稿", Files.readString(root.resolve("projects/7/合同.txt")));
    }
}
