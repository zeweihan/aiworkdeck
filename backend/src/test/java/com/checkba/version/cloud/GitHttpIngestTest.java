package com.checkba.version.cloud;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.UserService;
import com.checkba.version.WorkSessionService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * push 落库集成测试：锁内接收/脏区停靠/路径级物化/待同步延后（Task 6）。
 * 与 GitHttpProtocolTest 共用同一套骨架，但需要额外的 WorkSessionService/ProjectFileRepository
 * 依赖，独立成一个测试类。desktop profile + 独立内存 H2 的两条地雷同 GitHttpProtocolTest。
 */
// security.local-mode=false：保持本测试写作时的登录会话语义，并避免 local-mode 的
// LocalIdentityService 静态注册泄漏到同 JVM 后续测试（desktop profile 现默认开启 local-mode）。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"security.local-mode=false"})
@ActiveProfiles("desktop")
class GitHttpIngestTest {

    static Path root;

    private static final String DB_URL = "jdbc:h2:mem:git-http-ingest-test-" + System.nanoTime()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE";

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("git-http-ingest-test");
        registry.add("storage.local.root-path", () -> root.toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @LocalServerPort
    int port;

    @Autowired
    WorkSessionService sessionService;

    @Autowired
    com.checkba.version.ProjectRepoService repoService;

    @Autowired
    ProjectFileRepository fileRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ProjectRepository projectRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DeviceTokenService deviceTokenService;

    private long seededUserId;

    private String remoteUrl(long projectId) {
        return "http://localhost:" + port + "/git/" + projectId + ".git";
    }

    private Git clone(long projectId, Path clientDir, String token) throws Exception {
        return Git.cloneRepository()
                .setURI(remoteUrl(projectId))
                .setDirectory(clientDir.toFile())
                .setCredentialsProvider(creds(token))
                .call();
    }

    private Iterable<PushResult> push(Git client, String token) throws Exception {
        return client.push()
                .setRemote("origin")
                .setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master"))
                .setCredentialsProvider(creds(token))
                .call();
    }

    private UsernamePasswordCredentialsProvider creds(String token) {
        return new UsernamePasswordCredentialsProvider("git_user", token);
    }

    /** seed：项目行 + 文件行 + enableVersionRecording（初始提交自带 v2 清单）。 */
    private String seedRecordedProject(long projectId) throws Exception {
        String username = "git_user_" + projectId;
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPassword(UserService.encodePassword("x"));
        user = userRepository.save(user);
        seededUserId = user.getId();

        jdbcTemplate.update(
                "insert into project (id, name, project_type, listed_company_name, target_company_name, user_id) "
                        + "values (?, ?, ?, ?, ?, ?)",
                projectId, "git-http-ingest-test-" + projectId, "BLANK", "", "", user.getId());

        String token = deviceTokenService.issue(user.getId(), "git-http-ingest-test").plaintext();

        Files.createDirectories(root.resolve("projects/" + projectId));
        Files.writeString(root.resolve("projects/" + projectId + "/合同.txt"), "初稿");
        ProjectFile f = new ProjectFile();
        f.setProjectId(projectId);
        f.setIsFolder(false);
        f.setName("合同.txt");
        f.setSortOrder(0);
        f.setFilePath("projects/" + projectId + "/合同.txt");
        f.setUserId(seededUserId);
        f.setIsDeleted(false);
        f.setCreatedAt(LocalDateTime.now());
        fileRepository.save(f);
        sessionService.enableVersionRecording(projectId, "韩泽伟", "hzw@example.com");
        return token;
    }

    @Test
    void pushMaterialisesWorkTreeAndDatabase(@TempDir Path clientDir) throws Exception {
        String token = seedRecordedProject(21L);
        try (Git client = clone(21L, clientDir, token)) {
            // 改既有文件 + 在清单里添一个新节点（模拟另一台桌面端的 capture 产物）
            Files.writeString(clientDir.resolve("合同.txt"), "第二稿");
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            var manifest = om.readValue(clientDir.resolve(".awd/tree.json").toFile(),
                    com.checkba.version.TreeManifest.class);
            var nodes = new java.util.ArrayList<>(manifest.nodes());
            nodes.add(new com.checkba.version.TreeManifest.Node(
                    null, null, "新文件.txt", false, "txt", 1, null, false, null,
                    "uid-new-1", null, "新文件.txt", "git_user_21"));
            Files.writeString(clientDir.resolve(".awd/tree.json"),
                    om.writerWithDefaultPrettyPrinter().writeValueAsString(
                            new com.checkba.version.TreeManifest(2, nodes)));
            Files.writeString(clientDir.resolve("新文件.txt"), "同事新增");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("同事修改\n\nX-AWD-Kind: session")
                    .setAuthor("同事", "p@example.com").call();
            push(client, token);
        }
        // 服务端工作区被物化 + 数据库出现新行
        assertEquals("第二稿", Files.readString(root.resolve("projects/21/合同.txt")));
        assertEquals("同事新增", Files.readString(root.resolve("projects/21/新文件.txt")));
        assertTrue(fileRepository.findByProjectId(21L).stream()
                .anyMatch(f -> "新文件.txt".equals(f.getName())));
    }

    @Test
    void ingestIsDeferredWhileSessionActiveAndCaughtUpOnDiscard(@TempDir Path clientDir)
            throws Exception {
        String token = seedRecordedProject(22L);
        try (Git client = clone(22L, clientDir, token)) {
            // 服务端开一段工作（HEAD 切去 work/*）
            sessionService.onChangeSignal(22L, seededUserId, "网页端用户");
            sessionService.commitNow(22L, seededUserId, "网页端用户", null);
            // 客户端推进 master
            Files.writeString(clientDir.resolve("合同.txt"), "第二稿");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("同事修改").setAuthor("同事", "p@example.com").call();
            push(client, token);
            // 延后：工作区不动（工作段的内容还端着）
            assertNotEquals("第二稿", Files.readString(root.resolve("projects/22/合同.txt")));
            // 丢弃工作段 → 补做同步
            sessionService.discardSession(22L, seededUserId);
            assertEquals("第二稿", Files.readString(root.resolve("projects/22/合同.txt")));
        }
    }

    /**
     * v2 终审 C1：服务器仓库停在合并窗口（网页端律师正在做冲突三选一）期间收到的 push
     * 必须整体拒收——窗口期放进来的 push 让 master 前进，随后的裁决提交会以新 master
     * 为第一父落地，同事刚推上来的内容被静默回退。时序安排：先造好窗口之外的服务端
     * 历史、再 clone（客户端拿到的是当前 master），窗口只由 mergeNoCommit 开出、不再
     * 推进 master——这样客户端这笔 push 本来是能快进成功的，被拒只能是 C1 钩子的作用，
     * 不是 old-sha 对不上被 git 原生拒绝。
     */
    @Test
    void pushIsRejectedWhileServerRepositoryIsMerging(@TempDir Path clientDir) throws Exception {
        String token = seedRecordedProject(24L);
        // 服务端先造分叉历史（稿分支与主线各改同一文件），此时还不开窗口
        repoService.createBranch(24L, "draft/qa", "master");
        repoService.checkoutBranch(24L, "draft/qa");
        Files.writeString(root.resolve("projects/24/合同.txt"), "稿：内容");
        repoService.commitAll(24L, "稿上修改", "auto", null, "网页端", "w@example.com");
        repoService.checkoutBranch(24L, "master");
        Files.writeString(root.resolve("projects/24/合同.txt"), "主线：内容");
        repoService.commitAll(24L, "主线修改", "auto", null, "网页端", "w@example.com");

        try (Git client = clone(24L, clientDir, token)) {
            // 客户端在当前 master 之上备好一笔本来能快进的提交
            Files.writeString(clientDir.resolve("合同.txt"), "同事的修改");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("同事修改").setAuthor("同事", "p@example.com").call();

            // 服务端开出真实冲突的合并窗口（MERGING，master ref 不动）
            var outcome = repoService.mergeNoCommit(24L, "draft/qa", "采纳", "网页端", "w@example.com");
            assertFalse(outcome.success(), "前置：这一步应该造出真实冲突");
            assertTrue(repoService.repositoryMerging(24L), "前置：仓库应停在合并窗口");
            String masterAtWindow = repoService.resolveRef(24L, "master");

            var results = push(client, token);
            boolean rejected = false;
            for (var r : results)
                for (var u : r.getRemoteUpdates())
                    if (u.getStatus() != RemoteRefUpdate.Status.OK
                            && u.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)
                        rejected = true;
            assertTrue(rejected, "MERGING 窗口内的 push 必须被拒收");
            assertEquals(masterAtWindow, repoService.resolveRef(24L, "master"),
                    "被拒的 push 不得让 master 前进");
            assertTrue(repoService.repositoryMerging(24L), "合并窗口不得被 push 破坏");
        }
    }

    @Test
    void dirtyMainlineIsDockedAndPushRejected(@TempDir Path clientDir) throws Exception {
        String token = seedRecordedProject(23L);
        try (Git client = clone(23L, clientDir, token)) {
            // 服务端主线脏且无段（「脏但无段」路径）：直接写盘不发信号
            Files.writeString(root.resolve("projects/23/合同.txt"), "网页端未存档的编辑");
            Files.writeString(clientDir.resolve("合同.txt"), "第二稿");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("同事修改").setAuthor("同事", "p@example.com").call();
            var results = client.push().setCredentialsProvider(creds(token))
                    .setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master")).call();
            boolean rejected = false;
            for (var r : results)
                for (var u : r.getRemoteUpdates())
                    if (u.getStatus() != RemoteRefUpdate.Status.OK
                            && u.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)
                        rejected = true;
            assertTrue(rejected, "脏区停靠后 master 已前进，这次 push 必须被拒");
            // 网页端的编辑没有丢：停靠提交已落在 master 上
            assertEquals("网页端未存档的编辑",
                    Files.readString(root.resolve("projects/23/合同.txt")));
        }
    }
}
