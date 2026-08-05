package com.checkba.version.cloud;

import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.model.entity.User;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.UserService;
import com.checkba.storage.StorageProperties;
import com.checkba.version.CloudSyncService;
import com.checkba.version.ProjectRepoService;
import com.checkba.version.ProjectTreeManifestService;
import com.checkba.version.TreeManifest;
import com.checkba.version.VersionController;
import com.checkba.version.WorkSession;
import com.checkba.version.WorkSessionRepository;
import com.checkba.version.WorkSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 10：共享上云 + 从云端接项目端到端——Spring 上下文当云端服务器（root1），
 * 手工 new 的整套 style A 栈当桌面端（root2/root3），Hutool seam 不打桩，真打
 * 嵌入式服务器 HTTP（价值所在：跨机器 uid 一致性走真链路，不是桩出来的假象）。
 * desktop profile + 独立内存 H2 两条地雷同 GitHttpProtocolTest/GitHttpIngestTest。
 */
// security.local-mode=false：保持本测试写作时的登录会话语义，并避免 local-mode 的
// LocalIdentityService 静态注册泄漏到同 JVM 后续测试（desktop profile 现默认开启 local-mode）。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"security.local-mode=false"})
@ActiveProfiles("desktop")
class ShareCloneRoundTripTest {

    static Path root1;

    private static final String DB_URL = "jdbc:h2:mem:share-clone-test-" + System.nanoTime()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE";

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) throws Exception {
        root1 = Files.createTempDirectory("share-clone-test");
        registry.add("storage.local.root-path", () -> root1.toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @LocalServerPort
    int port;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ProjectFileRepository serverFileRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DeviceTokenService deviceTokenService;

    @Autowired
    VersionController versionController;

    @Autowired
    ProjectRepoService repoService;

    @Autowired
    WorkSessionService sessionService;

    @Autowired
    ProjectTreeManifestService manifestService;

    private String serverUrl() {
        return "http://localhost:" + port;
    }

    // ---- 桌面侧：手工 new 的整套 style A 栈 -------------------------------

    /** 一台桌面机的完整服务栈：独立 storage root + 独立（桩）DB，CloudSyncService 不覆写 HTTP seam。 */
    private static final class DesktopStack {
        Path root;
        ProjectRepoService repoSvc;
        ProjectTreeManifestService manifestSvc;
        WorkSessionService sessionSvc;
        CloudSyncService cloud;
        Map<Long, ProjectFile> fileDb;
        ProjectRepository projectRepo;
    }

    private DesktopStack desktopStackOn(Path root) {
        DesktopStack s = new DesktopStack();
        s.root = root;

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        s.repoSvc = new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));

        Map<Long, ProjectFile> fileDb = new HashMap<>();
        long[] nextFileId = {100L};
        ProjectFileRepository fileRepo = mock(ProjectFileRepository.class);
        when(fileRepo.findByProjectId(any())).thenAnswer(i -> {
            Long pid = i.getArgument(0);
            List<ProjectFile> out = new ArrayList<>();
            for (ProjectFile f : fileDb.values()) if (f.getProjectId().equals(pid)) out.add(f);
            return out;
        });
        when(fileRepo.save(any(ProjectFile.class))).thenAnswer(i -> {
            ProjectFile p = i.getArgument(0);
            if (p.getId() == null) p.setId(nextFileId[0]++);
            fileDb.put(p.getId(), p);
            return p;
        });
        s.fileDb = fileDb;

        Map<Long, Project> projectDb = new HashMap<>();
        long[] nextProjectId = {1L};
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        when(projectRepo.save(any(Project.class))).thenAnswer(i -> {
            Project p = i.getArgument(0);
            if (p.getId() == null) p.setId(nextProjectId[0]++);
            projectDb.put(p.getId(), p);
            return p;
        });
        when(projectRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(projectDb.get(i.getArgument(0))));
        s.projectRepo = projectRepo;

        s.manifestSvc = new ProjectTreeManifestService(fileRepo, s.repoSvc, new ObjectMapper(),
                mock(UserRepository.class), projectRepo);

        Map<Long, WorkSession> sessions = new HashMap<>();
        long[] nextSessionId = {1L};
        WorkSessionRepository sessionRepo = mock(WorkSessionRepository.class);
        when(sessionRepo.save(any(WorkSession.class))).thenAnswer(i -> {
            WorkSession se = i.getArgument(0);
            if (se.getId() == null) se.setId(nextSessionId[0]++);
            sessions.put(se.getId(), se);
            return se;
        });
        when(sessionRepo.findFirstByProjectIdAndStatusAndSessionType(any(), any(), any())).thenAnswer(i ->
                sessions.values().stream()
                        .filter(se -> se.getProjectId().equals(i.getArgument(0))
                                && se.getStatus() == i.getArgument(1)
                                && se.getSessionType() == i.getArgument(2))
                        .findFirst());
        when(sessionRepo.findByProjectIdAndStatusAndSessionTypeOrderByStartedAtDesc(any(), any(), any()))
                .thenAnswer(i -> List.of());
        when(sessionRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(sessions.get(i.getArgument(0))));

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();

        s.sessionSvc = new WorkSessionService(s.repoSvc, s.manifestSvc, sessionRepo, scheduler, fileRepo,
                event -> { });
        s.sessionSvc.setDebounceMillis(60_000);

        Map<Long, CloudConnection> conns = new HashMap<>();
        long[] nextConnId = {1L};
        CloudConnectionRepository connRepo = mock(CloudConnectionRepository.class);
        when(connRepo.save(any(CloudConnection.class))).thenAnswer(i -> {
            CloudConnection c = i.getArgument(0);
            if (c.getId() == null) c.setId(nextConnId[0]++);
            conns.put(c.getId(), c);
            return c;
        });
        when(connRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(conns.get(i.getArgument(0))));

        Map<Long, ProjectRemote> remotes = new HashMap<>();
        long[] nextRemoteId = {1L};
        ProjectRemoteRepository remoteRepo = mock(ProjectRemoteRepository.class);
        when(remoteRepo.save(any(ProjectRemote.class))).thenAnswer(i -> {
            ProjectRemote r = i.getArgument(0);
            if (r.getId() == null) r.setId(nextRemoteId[0]++);
            remotes.put(r.getId(), r);
            return r;
        });
        when(remoteRepo.findByProjectId(any())).thenAnswer(i -> remotes.values().stream()
                .filter(r -> r.getProjectId().equals(i.getArgument(0)))
                .findFirst());
        when(remoteRepo.findByConnectionId(any())).thenAnswer(i -> remotes.values().stream()
                .filter(r -> r.getConnectionId().equals(i.getArgument(0)))
                .toList());

        s.cloud = new CloudSyncService(s.repoSvc, s.sessionSvc, s.manifestSvc, fileRepo,
                connRepo, remoteRepo, projectRepo);
        return s;
    }

    private String uidOf(Map<Long, ProjectFile> db, long projectId, String name) {
        return db.values().stream()
                .filter(f -> f.getProjectId() == projectId && name.equals(f.getName()))
                .map(ProjectFile::getUid)
                .findFirst()
                .orElseThrow();
    }

    // ---- tests ------------------------------------------------------------

    @Test
    void shareThenCloneRoundTrip(@TempDir Path desktopA, @TempDir Path desktopB) throws Exception {
        userService.register("userA", "pw123456", "userA");

        DesktopStack a = desktopStackOn(desktopA);
        Files.createDirectories(a.root.resolve("projects/7"));
        Files.writeString(a.root.resolve("projects/7/合同.txt"), "初稿");

        ProjectFile file = new ProjectFile();
        file.setId(100L);
        file.setProjectId(7L);
        file.setIsFolder(false);
        file.setName("合同.txt");
        file.setSortOrder(0);
        file.setFilePath("projects/7/合同.txt");
        file.setUserId(1L);
        file.setIsDeleted(false);
        file.setCreatedAt(LocalDateTime.now());
        a.fileDb.put(100L, file);

        Project localProject = new Project();
        localProject.setId(7L);
        localProject.setName("客户材料尽调");
        localProject.setUserId(1L);
        a.projectRepo.save(localProject);

        a.sessionSvc.enableVersionRecording(7L, "韩泽伟", "hzw@example.com");

        CloudConnection conn = a.cloud.connect(serverUrl(), "userA", "pw123456", "测试机", 1L);
        Map<String, Object> shared = a.cloud.shareToCloud(7L, conn.getId(), 1L);
        long rid = ((Number) shared.get("remoteProjectId")).longValue();

        // 服务器物化了：工作区文件 + DB 行都在（首推 zeroId 路径）
        assertEquals("初稿", Files.readString(root1.resolve("projects/" + rid + "/合同.txt")));
        assertTrue(serverFileRepository.findByProjectId(rid).stream()
                .anyMatch(f -> "合同.txt".equals(f.getName())));

        // 第二台桌面（root3 手工栈）接入：文件与 DB 都长出来，且 uid 与共享方一致
        DesktopStack b = desktopStackOn(desktopB);
        CloudConnection connB = b.cloud.connect(serverUrl(), "userA", "pw123456", "另一台设备", 9L);
        Map<String, Object> accepted = b.cloud.cloneFromCloud(connB.getId(), rid, 9L);
        long localId = ((Number) accepted.get("localProjectId")).longValue();

        assertEquals("初稿", Files.readString(desktopB.resolve("projects/" + localId + "/合同.txt")));

        String uidOnA = uidOf(a.fileDb, 7L, "合同.txt");
        String uidOnB = uidOf(b.fileDb, localId, "合同.txt");
        assertEquals(uidOnA, uidOnB);
    }

    @Test
    void prepareRemoteUpgradesV1ManifestProject() throws Exception {
        long projectId = 777L;
        String username = "git_user_v1_" + projectId;
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPassword(UserService.encodePassword("x"));
        user = userRepository.save(user);

        jdbcTemplate.update(
                "insert into project (id, name, project_type, listed_company_name, target_company_name, user_id) "
                        + "values (?, ?, ?, ?, ?, ?)",
                projectId, "share-clone-v1-test", "BLANK", "", "", user.getId());

        Files.createDirectories(root1.resolve("projects/" + projectId));
        Files.writeString(root1.resolve("projects/" + projectId + "/合同.txt"), "初稿");
        sessionService.enableVersionRecording(projectId, "韩泽伟", "hzw@example.com");

        // 服务器上把 HEAD 清单硬改回 v1 再提交，模拟"v1 时代"补开云端协作的老项目
        ObjectMapper om = new ObjectMapper();
        TreeManifest v1 = new TreeManifest(1, List.of());
        Files.writeString(root1.resolve("projects/" + projectId + "/.awd/tree.json"),
                om.writerWithDefaultPrettyPrinter().writeValueAsString(v1));
        repoService.commitAll(projectId, "手工改回 v1 清单", "auto", null, "测试", "test@example.com");

        TreeManifest before = manifestService.readAtRef(projectId, "HEAD");
        assertEquals(1, before.version());

        String token = deviceTokenService.issue(user.getId(), "v1-upgrade-test").plaintext();
        var response = versionController.prepareRemote(projectId, token);
        assertEquals(0, response.getBody().get("code"));

        TreeManifest after = manifestService.readAtRef(projectId, "HEAD");
        assertEquals(2, after.version());

        var entries = repoService.log(projectId, "HEAD", 10);
        assertTrue(entries.stream().anyMatch(e -> "升级版本记录格式".equals(e.message())));
    }
}
