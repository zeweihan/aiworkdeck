package com.checkba.version;

import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.Project;
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
 * Task 9：从云端更新（快进/真合并/冲突三选一/裁决重推）+ 上传被拒自动整合重推。
 *
 * 骨架同 CloudSyncUploadTest（Task 8）：双仓 fixture，root 下是「桌面」，一个 file://
 * 裸仓当「云端」；「同事」用 peer 克隆裸仓、手工维护 v2 tree.json（JSON 手作法照
 * GitHttpIngestTest）、提交、推回去，制造分叉。
 *
 * 方向钉死：这里的合并是 origin/master 并入本地 master——ours=本地=我这边的，
 * theirs=云端=云端的，与 Task 7 结束工作撞车（那边 MAIN=同事的）方向相反。
 */
class CloudSyncUpdateTest {

    private Path root;
    private ProjectRepoService repoSvc;
    private ProjectTreeManifestService manifestSvc;
    private WorkSessionService svc;

    private Map<Long, ProjectFile> db;
    private long nextFileId;
    private Map<Long, WorkSession> sessions;
    private long nextSessionId;
    private Map<Long, CloudConnection> connections;
    private long nextConnId;
    private Map<Long, ProjectRemote> remotes;
    private long nextRemoteId;

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

        // 清单 v2 归一化里 author 解析不到就回退到项目 owner——测试用 mock 的
        // UserRepository/ProjectRepository 都不认识"同事"这个用户名，靠 owner 兜底
        // 才能让 peer 手工写的 v2 节点新建成功（否则清单缺创建者信息会抛异常）。
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        Project owner = new Project();
        owner.setId(7L);
        owner.setUserId(1L);
        when(projectRepo.findById(any())).thenReturn(Optional.of(owner));
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

        svc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler, fileRepo, event -> { });
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

        cloud = new CloudSyncService(repoSvc, svc, manifestSvc, fileRepo, cloudConnRepo, projectRemoteRepo) {
            @Override
            protected String httpPost(String url, String body, String sessionToken) {
                throw new UnsupportedOperationException("本测试不需要真实 HTTP 调用");
            }
        };
    }

    // ---- helpers ------------------------------------------------------

    private String bareRemote(Path dir) throws Exception {
        Git.init().setBare(true).setDirectory(dir.toFile())
                .setInitialBranch("master").call().close();
        return dir.toUri().toString();
    }

    /** 建一个 file:// 裸仓当云端 + CloudConnection/ProjectRemote 行，setRemoteOrigin 指过去。 */
    private void linkToBareRemote(long projectId) throws Exception {
        Path remoteDir = Files.createTempDirectory("cloud-remote");
        String url = bareRemote(remoteDir);
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

    /** 云端裸仓当前 master 的 sha（直接开裸仓读，不经过本地 fetch）。 */
    private String remoteMasterShaOfBare() throws Exception {
        String url = repoSvc.remoteOriginUrl(7L);
        try (Git remoteGit = Git.open(Path.of(java.net.URI.create(url)).toFile())) {
            return remoteGit.getRepository().resolve("master").getName();
        }
    }

    private List<ProjectFile> dbRowsOf(long projectId) {
        return db.values().stream().filter(f -> f.getProjectId().equals(projectId)).toList();
    }

    /**
     * 「同事」克隆云端裸仓、新增一个文件 + 在 v2 清单里添一个节点、提交、推回去。
     * 清单从零开始起（本仓库开启版本记录之前没有 .awd/tree.json 这份历史），
     * 已存在时接着追加，两种起点都要能用。
     */
    private void advancePeerWithNewFile(String fileName, String content) throws Exception {
        Path peerDir = Files.createTempDirectory("cloud-peer");
        String url = repoSvc.remoteOriginUrl(7L);
        try (Git peer = Git.cloneRepository().setURI(url).setDirectory(peerDir.toFile()).call()) {
            Files.writeString(peerDir.resolve(fileName), content);
            appendPeerManifestNode(peerDir, fileName);
            peer.add().addFilepattern(".").call();
            peer.commit().setMessage("同事新增\n\nX-AWD-Kind: session")
                    .setAuthor("同事", "p@example.com").call();
            peer.push().call();
        }
    }

    /** 「同事」克隆云端裸仓、改写既有文件内容、提交、推回去（不碰清单，制造真实内容冲突用）。 */
    private void advancePeerRewriting(String fileName, String content) throws Exception {
        Path peerDir = Files.createTempDirectory("cloud-peer");
        String url = repoSvc.remoteOriginUrl(7L);
        try (Git peer = Git.cloneRepository().setURI(url).setDirectory(peerDir.toFile()).call()) {
            Files.writeString(peerDir.resolve(fileName), content);
            peer.add().addFilepattern(".").call();
            peer.commit().setMessage("同事修改\n\nX-AWD-Kind: session")
                    .setAuthor("同事", "p@example.com").call();
            peer.push().call();
        }
    }

    private void appendPeerManifestNode(Path peerDir, String fileName) throws Exception {
        var om = new ObjectMapper();
        Path manifestPath = peerDir.resolve(".awd/tree.json");
        List<TreeManifest.Node> nodes = new ArrayList<>();
        if (Files.exists(manifestPath)) {
            nodes.addAll(om.readValue(manifestPath.toFile(), TreeManifest.class).nodes());
        }
        nodes.add(new TreeManifest.Node(
                null, null, fileName, false, "txt", nodes.size(), null, false, null,
                "uid-" + fileName + "-" + System.nanoTime(), null, fileName, "同事"));
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath,
                om.writerWithDefaultPrettyPrinter().writeValueAsString(new TreeManifest(2, nodes)));
    }

    // ---- tests ----------------------------------------------------------

    @Test
    void fastForwardUpdateMaterialisesFilesAndDatabase() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advancePeerWithNewFile("同事新增.txt", "内容");
        CloudSyncService.UpdateResult r = cloud.updateFromCloud(7L, 1L, "韩泽伟");
        assertEquals(CloudSyncService.UpdateStatus.UPDATED, r.status());
        assertEquals("内容", Files.readString(root.resolve("projects/7/同事新增.txt")));
        assertTrue(dbRowsOf(7L).stream().anyMatch(f -> "同事新增.txt".equals(f.getName())));
        assertFalse(r.affectedFileIds().isEmpty());
    }

    @Test
    void divergedCleanUpdateMergesAndPushesBack() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advancePeerWithNewFile("同事的.txt", "同事内容");
        Files.writeString(root.resolve("projects/7/我的.txt"), "我的内容");
        repoSvc.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        CloudSyncService.UpdateResult r = cloud.updateFromCloud(7L, 1L, "韩泽伟");
        assertEquals(CloudSyncService.UpdateStatus.UPDATED, r.status());
        // 双亲合并 + 两侧都在 + 已自动重推（远端 tip == 本地 tip）
        assertEquals(2, repoSvc.log(7L, "master", 5).get(0).parents().size());
        assertEquals("同事内容", Files.readString(root.resolve("projects/7/同事的.txt")));
        assertEquals(repoSvc.resolveRef(7L, "master"), remoteMasterShaOfBare());
    }

    @Test
    void conflictingUpdateOpensWindowAndBothResolutionKeepsCloudCopy() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advancePeerRewriting("合同.txt", "云端的第二稿");
        Files.writeString(root.resolve("projects/7/合同.txt"), "我的第二稿");
        repoSvc.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        CloudSyncService.UpdateResult r = cloud.updateFromCloud(7L, 1L, "韩泽伟");
        assertEquals(CloudSyncService.UpdateStatus.CONFLICT, r.status());
        assertTrue(repoSvc.repositoryMerging(7L));

        CloudSyncService.UpdateResult done = cloud.resolveCloudMerge(7L,
                Map.of("合同.txt", WorkSessionService.Resolution.BOTH), 1L, "韩泽伟");
        assertEquals(CloudSyncService.UpdateStatus.UPDATED, done.status());
        assertFalse(repoSvc.repositoryMerging(7L));
        assertEquals("我的第二稿", Files.readString(root.resolve("projects/7/合同.txt"))); // MAIN=ours 缺省内容
        assertTrue(Files.list(root.resolve("projects/7")).map(p -> p.getFileName().toString())
                .anyMatch(n -> n.contains("来自：云端")));
        assertEquals(repoSvc.resolveRef(7L, "master"), remoteMasterShaOfBare()); // 裁决后重推
    }

    @Test
    void abortCloudMergeLeavesBothSidesIntact() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advancePeerRewriting("合同.txt", "云端的第二稿");
        Files.writeString(root.resolve("projects/7/合同.txt"), "我的第二稿");
        repoSvc.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        cloud.updateFromCloud(7L, 1L, "韩泽伟");
        assertTrue(repoSvc.repositoryMerging(7L));

        String notice = cloud.abortCloudMerge(7L);
        assertNotNull(notice);
        assertFalse(repoSvc.repositoryMerging(7L));
        // 无损中止：工作区回到冲突窗口之前——本地那份稿件原样还在。
        assertEquals("我的第二稿", Files.readString(root.resolve("projects/7/合同.txt")));
    }

    @Test
    void updateIsRefusedDuringActiveSession() throws Exception {
        linkToBareRemote(7L);
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        svc.commitNow(7L, 1L, "韩泽伟", null);
        VersionException e = assertThrows(VersionException.class,
                () -> cloud.updateFromCloud(7L, 1L, "韩泽伟"));
        assertTrue(e.isUserFacing());
    }

    @Test
    void rejectedUploadNowAutoIntegratesWhenClean() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advancePeerWithNewFile("同事的.txt", "同事内容");
        Files.writeString(root.resolve("projects/7/我的.txt"), "我的内容");
        repoSvc.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        CloudSyncService.UploadResult r = cloud.uploadToCloud(7L, false);
        assertEquals(CloudSyncService.UploadStatus.UPLOADED, r.status()); // 合并后重推成功
        assertEquals(repoSvc.resolveRef(7L, "master"), remoteMasterShaOfBare());
    }
}
