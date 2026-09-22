// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Task 8：CloudConnection/ProjectRemote 接入 + CloudSyncService 连接与上传 + 结束工作自动上传。
 *
 * 双仓 fixture：root 下的仓库是「桌面」，一个 file:// 裸仓当「云端」（凭据全用占位值，
 * file:// 传输本来就不校验）。connect() 的 HTTP 调用走匿名子类覆写 {@code httpPost} seam
 * 打桩，其余仓库操作全部落在真实的 JGit 仓库上。
 */
class CloudSyncUploadTest {

    private Path root;
    private ProjectRepoService repoSvc;
    private ProjectTreeManifestService manifestSvc;
    private WorkSessionService svc;
    private List<Object> publishedEvents;

    private Map<Long, ProjectFile> db;
    private long nextFileId;
    private Map<Long, WorkSession> sessions;
    private long nextSessionId;
    private Map<Long, CloudConnection> connections;
    private long nextConnId;
    private Map<Long, ProjectRemote> remotes;
    private long nextRemoteId;

    private String lastHttpUrl;
    private String lastHttpHeaderToken;
    private String cannedResponse;

    private CloudConnectionRepository cloudConnRepo;
    private ProjectRemoteRepository projectRemoteRepo;
    private CloudSyncService cloud;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));
        repoSvc.init(7L, "韩泽伟", "hzw@example.com");
        try (var r = repoSvc.open(7L)) {
            BareHub.quiesceAutoGc(r); // 本地仓库后面要做多次 fetch/push，见 BareHub 类注释
        }

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
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        manifestSvc = new ProjectTreeManifestService(fileRepo, repoSvc, new ObjectMapper(),
                mock(UserRepository.class), projectRepo);

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
                .thenAnswer(i -> List.of());
        when(sessionRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(sessions.get(i.getArgument(0))));

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();

        publishedEvents = new ArrayList<>();
        svc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler, fileRepo,
                publishedEvents::add);
        svc.setDebounceMillis(60_000);

        connections = new HashMap<>();
        nextConnId = 1L;
        cloudConnRepo = mock(CloudConnectionRepository.class);
        when(cloudConnRepo.save(any(CloudConnection.class))).thenAnswer(i -> {
            CloudConnection c = i.getArgument(0);
            if (c.getId() == null) c.setId(nextConnId++);
            connections.put(c.getId(), c);
            return c;
        });
        when(cloudConnRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(connections.get(i.getArgument(0))));
        doAnswer(i -> {
            CloudConnection c = i.getArgument(0);
            connections.remove(c.getId());
            return null;
        }).when(cloudConnRepo).delete(any(CloudConnection.class));

        remotes = new HashMap<>();
        nextRemoteId = 1L;
        projectRemoteRepo = mock(ProjectRemoteRepository.class);
        when(projectRemoteRepo.save(any(ProjectRemote.class))).thenAnswer(i -> {
            ProjectRemote r = i.getArgument(0);
            if (r.getId() == null) r.setId(nextRemoteId++);
            remotes.put(r.getId(), r);
            return r;
        });
        when(projectRemoteRepo.findByProjectId(any())).thenAnswer(i -> remotes.values().stream()
                .filter(r -> r.getProjectId().equals(i.getArgument(0)))
                .findFirst());
        when(projectRemoteRepo.findByConnectionId(any())).thenAnswer(i -> remotes.values().stream()
                .filter(r -> r.getConnectionId().equals(i.getArgument(0)))
                .toList());
        doAnswer(i -> {
            ProjectRemote r = i.getArgument(0);
            remotes.remove(r.getId());
            return null;
        }).when(projectRemoteRepo).delete(any(ProjectRemote.class));

        cloud = new CloudSyncService(repoSvc, svc, manifestSvc, fileRepo, cloudConnRepo, projectRemoteRepo, projectRepo) {
            @Override
            protected String httpPost(String url, String body, String sessionToken) {
                lastHttpUrl = url;
                lastHttpHeaderToken = sessionToken;
                return cannedResponse;
            }
        };
    }

    // ---- helpers ------------------------------------------------------

    private String bareRemote(Path dir) throws Exception {
        return BareHub.init(dir);
    }

    /** 建一个 file:// 裸仓当云端 + CloudConnection/ProjectRemote 行，setRemoteOrigin 指过去。 */
    private void linkToBareRemote(long projectId) throws Exception {
        Path remoteDir = Files.createTempDirectory("cloud-remote");
        String url = bareRemote(remoteDir);
        link(projectId, url);
    }

    /** 建一个不可达地址的连接（模拟离线）。*/
    private void linkToUnreachableRemote(long projectId) {
        link(projectId, "http://127.0.0.1:1/x.git");
    }

    private void link(long projectId, String url) {
        repoSvc.setRemoteOrigin(projectId, url);
        CloudConnection conn = new CloudConnection();
        conn.setServerUrl("http://server:9696");
        conn.setUsername("韩泽伟");
        conn.setDisplayName("韩泽伟");
        conn.setDeviceToken("awdt_test");
        conn.setCreatedAt(LocalDateTime.now());
        conn = cloudConnRepo.save(conn);

        ProjectRemote remote = new ProjectRemote();
        remote.setProjectId(projectId);
        remote.setConnectionId(conn.getId());
        remote.setPendingUpload(false);
        remote.setCreatedAt(LocalDateTime.now());
        projectRemoteRepo.save(remote);
    }

    private ProjectRemote remoteRowOf(long projectId) {
        return remotes.values().stream()
                .filter(r -> r.getProjectId().equals(projectId))
                .findFirst().orElseThrow();
    }

    /** 云端裸仓当前 master 的 sha（直接开裸仓读，不经过本地 fetch）。 */
    private String remoteMasterShaOfBare() throws Exception {
        String url = repoSvc.remoteOriginUrl(7L);
        try (Git remoteGit = Git.open(Path.of(java.net.URI.create(url)).toFile())) {
            return remoteGit.getRepository().resolve("master").getName();
        }
    }

    /** 另一个「同事」克隆云端裸仓、提交、推回去，制造分叉。 */
    private void advanceBareRemoteFromPeer(String message) throws Exception {
        Path peerDir = Files.createTempDirectory("cloud-peer");
        String url = repoSvc.remoteOriginUrl(7L);
        try (Git peer = Git.cloneRepository().setURI(url).setDirectory(peerDir.toFile()).call()) {
            Files.writeString(peerDir.resolve("合同.txt"), "同事的第二稿");
            peer.add().addFilepattern(".").call();
            peer.commit().setMessage(message).setAuthor("同事", "p@example.com").call();
            peer.push().call();
        }
    }

    // ---- tests ----------------------------------------------------------

    @Test
    void connectStoresTokenFromServerResponse() {
        cannedResponse = """
                {"code":0,"data":{"tokenId":1,"token":"awdt_abc","userId":5,
                "username":"hanzewei","displayName":"韩泽伟"}}
                """;
        CloudConnection conn = cloud.connect("http://server:9696", "hanzewei", "pw", "MacBook", 1L);
        assertEquals("awdt_abc", conn.getDeviceToken());
        assertTrue(lastHttpUrl.endsWith("/api/auth/device-token"));
    }

    @Test
    void uploadPushesMainlineAndClearsPending() throws Exception {
        linkToBareRemote(7L);
        CloudSyncService.UploadResult r = cloud.uploadToCloud(7L, false);
        assertEquals(CloudSyncService.UploadStatus.UPLOADED, r.status());
        assertFalse(remoteRowOf(7L).getPendingUpload());
        assertEquals(repoSvc.resolveRef(7L, "master"), remoteMasterShaOfBare());
    }

    /** 造一段没收尾的活：改动落在工作段分支上，主线一动没动。 */
    private void openWorkSessionWithUncommittedVersion() throws Exception {
        svc.enableVersionRecording(7L, "韩泽伟", "hzw@example.com");
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "还没结束工作的改动");
        svc.commitNow(7L, 1L, "韩泽伟", null);
        assertTrue(svc.activeSession(7L).isPresent(), "前提不成立：此刻应当有一段没收尾的活");
    }

    /**
     * dev-board 0.44.1 清单 B3。手头这段活还没结束时点「交稿」，界面弹的是一句「已交稿」，
     * 而什么都没交——那次编辑还在工作段分支上，主线与案件库一模一样，git 回 UP_TO_DATE，
     * 而 pushMainlineToOrigin 把 UP_TO_DATE 与 OK 归成同一档（都是 pushed=true），
     * uploadToCloud 于是照着成功那条路走：报 UPLOADED、清 pendingUpload、重盖 lastSyncSha。
     *
     * 前端的三步清单本来该在点下去之前就把人拦住（#848），但它的判据是页面快照、
     * 漏拦了；后端这一句假「已交稿」就是漏拦之后的唯一那道兜底，所以它必须说实话。
     */
    @Test
    void uploadWhileWorkSessionIsOpenReportsNothingToSubmitInsteadOfSuccess() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        String inLibrary = remoteMasterShaOfBare();
        openWorkSessionWithUncommittedVersion();

        CloudSyncService.UploadResult r = cloud.uploadToCloud(7L, false);

        assertEquals(CloudSyncService.UploadStatus.NOTHING_TO_SUBMIT, r.status(),
                "什么都没送出去，不许报成「已交稿」");
        assertNotNull(r.message(), "这一档必须带一句能指出下一步的话");
        assertEquals(inLibrary, remoteMasterShaOfBare(), "案件库那一侧一个字都不该变");
    }

    /**
     * 同一档的第二半：不许清 pendingUpload。这次根本没有同步发生，把黄灯清掉等于
     * 告诉律师「都交上去了」。也不该顺手置成 true——没有哪一次交稿被拒。
     */
    @Test
    void uploadWhileWorkSessionIsOpenLeavesPendingUploadUntouched() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        remoteRowOf(7L).setPendingUpload(true); // 早先一次后台上传没成，黄灯还亮着
        openWorkSessionWithUncommittedVersion();

        cloud.uploadToCloud(7L, false);

        assertTrue(remoteRowOf(7L).getPendingUpload(), "这次什么都没交，不许把黄灯清掉");
    }

    /**
     * 反向护栏：主线与案件库一致、且手头没有没收尾的活（界面上那句「和大家的稿一致」），
     * 点了交稿仍旧回 UPLOADED。新加的那一档只认「有活没收尾」，不许把这条路也一起改掉。
     */
    @Test
    void uploadWithNothingNewButNoOpenSessionIsStillUploaded() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);

        CloudSyncService.UploadResult r = cloud.uploadToCloud(7L, false);

        assertEquals(CloudSyncService.UploadStatus.UPLOADED, r.status());
        assertFalse(remoteRowOf(7L).getPendingUpload());
    }

    /**
     * 为什么这一档只能在 push **之后**判、不能为了省一次网络往返直接跳过 push：
     * 那一次 push 的 refspec 里还带着里程碑标签（MILESTONE_SPEC）。主线没动、但律师
     * 刚给某一版起了名字时，跳过 push 就等于把这个名字永远留在本机。
     */
    @Test
    void milestoneTagsStillReachTheLibraryWhenMainlineDidNotMove() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        String named = repoSvc.resolveRef(7L, "master");
        repoSvc.tagMilestone(7L, named, "签约版");
        openWorkSessionWithUncommittedVersion();

        cloud.uploadToCloud(7L, false);

        String url = repoSvc.remoteOriginUrl(7L);
        try (Git remoteGit = Git.open(Path.of(java.net.URI.create(url)).toFile())) {
            assertFalse(
                    remoteGit.getRepository().getRefDatabase()
                            .getRefsByPrefix("refs/tags/awd/milestone/").isEmpty(),
                    "里程碑标签仍要随这次 push 上到案件库");
        }
    }

    /**
     * Task 8 时这条路径只置黄灯（REMOTE_AHEAD）。Task 9 升级：没有未收尾工作/没站在稿上
     * 时被拒后会自动整合——这里两边真改了同一处（合同.txt 的内容），整合遇到真实冲突，
     * 状态从 REMOTE_AHEAD 变成新增的 CONFLICT，仓库停在合并窗口等裁决（同 CloudSyncUpdateTest
     * 的冲突用例），不再是简单的"下次再试"。
     */
    @Test
    void rejectedUploadWithRealConflictOpensConflictWindow() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advanceBareRemoteFromPeer("同事的第二稿");
        Files.writeString(root.resolve("projects/7/合同.txt"), "我的第二稿");
        repoSvc.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        CloudSyncService.UploadResult r = cloud.uploadToCloud(7L, false);
        assertEquals(CloudSyncService.UploadStatus.CONFLICT, r.status());
        assertTrue(repoSvc.repositoryMerging(7L));
        assertTrue(remoteRowOf(7L).getPendingUpload());
    }

    /**
     * v2 终审 I2：后台路径（结束工作的 onMainlineMerged 自动上传）被拒时**不做自动整合**，
     * 只置 pendingUpload——后台没有通道通知打开中的编辑器重载（v1 地雷 #11 的 autosave
     * 覆盖形态），后台整合撞冲突还会开出律师不知情的 MERGING 窗口。与上一条前台用例
     * 同一份分叉 fixture，只有 background 参数不同，两条语义各有护栏。
     */
    @Test
    void rejectedBackgroundUploadOnlyMarksPendingWithoutIntegrating() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advanceBareRemoteFromPeer("同事的第二稿");
        Files.writeString(root.resolve("projects/7/合同.txt"), "我的第二稿");
        repoSvc.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        String masterBefore = repoSvc.resolveRef(7L, "master");

        CloudSyncService.UploadResult r = cloud.uploadToCloud(7L, true);

        assertEquals(CloudSyncService.UploadStatus.REMOTE_AHEAD, r.status());
        assertTrue(remoteRowOf(7L).getPendingUpload());
        assertFalse(repoSvc.repositoryMerging(7L), "后台被拒不得开出合并窗口");
        assertEquals(masterBefore, repoSvc.resolveRef(7L, "master"), "后台被拒不得改写本地主线");
        assertEquals("我的第二稿", Files.readString(root.resolve("projects/7/合同.txt")),
                "后台被拒不得改写磁盘（打开中的编辑器无从得知）");
    }

    @Test
    void offlineBackgroundUploadSwallowsAndMarksPending() {
        linkToUnreachableRemote(7L);
        assertDoesNotThrow(() -> {
            CloudSyncService.UploadResult r = cloud.uploadToCloud(7L, true);
            assertEquals(CloudSyncService.UploadStatus.OFFLINE_PENDING, r.status());
        });
        assertTrue(remoteRowOf(7L).getPendingUpload());
    }

    @Test
    void disconnectRevokesRemoteTokenWithRealIdAndAuthHeader() {
        cannedResponse = """
                {"code":0,"data":{"tokenId":42,"token":"awdt_xyz","userId":5,
                "username":"hanzewei","displayName":"韩泽伟"}}
                """;
        CloudConnection conn = cloud.connect("http://server:9696", "hanzewei", "pw", "MacBook", 1L);

        ProjectRemote remote = new ProjectRemote();
        remote.setProjectId(7L);
        remote.setConnectionId(conn.getId());
        remote.setPendingUpload(false);
        remote.setCreatedAt(LocalDateTime.now());
        projectRemoteRepo.save(remote);

        cannedResponse = """
                {"code":0,"message":"已撤销"}
                """;
        cloud.disconnect(conn.getId(), 1L);

        assertTrue(lastHttpUrl.endsWith("/device-token/42/revoke"));
        assertEquals("awdt_xyz", lastHttpHeaderToken);
        assertFalse(connections.containsKey(conn.getId()));
        assertTrue(remotes.isEmpty());
    }

    @Test
    void disconnectWithoutTokenIdSkipsRemoteRevokeButDeletesLocally() {
        CloudConnection conn = new CloudConnection();
        conn.setServerUrl("http://server:9696");
        conn.setUsername("韩泽伟");
        conn.setDisplayName("韩泽伟");
        conn.setUserId(1L);
        conn.setDeviceToken("awdt_notoken");
        conn.setTokenId(null);
        conn.setCreatedAt(LocalDateTime.now());
        conn = cloudConnRepo.save(conn);

        ProjectRemote remote = new ProjectRemote();
        remote.setProjectId(7L);
        remote.setConnectionId(conn.getId());
        remote.setPendingUpload(false);
        remote.setCreatedAt(LocalDateTime.now());
        projectRemoteRepo.save(remote);

        lastHttpUrl = null;
        cloud.disconnect(conn.getId(), 1L);

        assertNull(lastHttpUrl);
        assertFalse(connections.containsKey(conn.getId()));
        assertTrue(remotes.isEmpty());
    }

    @Test
    void endSessionPublishesMainlineMergedEvent() throws Exception {
        svc.enableVersionRecording(7L, "韩泽伟", "hzw@example.com");
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "改一笔");
        svc.commitNow(7L, 1L, "韩泽伟", null);
        svc.endSession(7L, 1L, "韩泽伟", "一段工作");
        assertTrue(publishedEvents.stream().anyMatch(e ->
                e instanceof WorkSessionService.MainlineMergedEvent m && m.projectId() == 7L));
    }
}
