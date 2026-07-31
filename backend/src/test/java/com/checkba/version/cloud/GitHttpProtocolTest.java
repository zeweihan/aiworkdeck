package com.checkba.version.cloud;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.UserService;
import com.checkba.version.ProjectRepoService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对内嵌 smart HTTP 端点做真实 JGit 客户端克隆/推送。
 * 必须 desktop profile：默认 profile 连本机 Postgres，CI 必挂（v1 已知地雷）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("desktop")
class GitHttpProtocolTest {

    static Path root;

    // 供 @DynamicPropertySource 复用的固定库名——lambda 里如果现算 System.nanoTime()，
    // Spring 每次取值都会重新求值，可能出现 schema 建在一个实例、查询打到另一个同名不同库的
    // h2:mem 实例上（h2:mem 按名字区分库）。这里在类初始化时只算一次，保证全程同一个库。
    private static final String DB_URL = "jdbc:h2:mem:git-http-test-" + System.nanoTime()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE";

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("git-http-test");
        registry.add("storage.local.root-path", () -> root.toAbsolutePath().toString());
        // Task 3 新增：鉴权矩阵要落库真实 User/Project 行。默认 desktop profile 的数据源指向
        // 开发者本机 ~/.aiworkdeck/local.mv.db（真实桌面数据！），不隔离会用测试数据覆盖真实项目行
        // （曾实测踩过：id=8/9 恰好命中本机已有项目，被测试静默改名）。这里换成进程内隔离的 H2。
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @LocalServerPort
    int port;

    @Autowired
    ProjectRepoService repoService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DeviceTokenService deviceTokenService;

    private final Map<Long, String> tokenByProject = new HashMap<>();

    private String remoteUrl(long projectId) {
        return "http://localhost:" + port + "/git/" + projectId + ".git";
    }

    private void seedProject(long projectId) throws Exception {
        Files.createDirectories(root.resolve("projects/" + projectId));
        Files.writeString(root.resolve("projects/" + projectId + "/合同.txt"), "初稿");
        repoService.init(projectId, "韩泽伟", "hzw@example.com");
        seedMembership(projectId);
    }

    /**
     * 只建鉴权矩阵所需的 User/Project/令牌，不初始化 git 仓库——用于测试"已认证但仓库未初始化"的 404。
     * Project.id 是 @GeneratedValue(IDENTITY)：走 JpaRepository.save() 时 Hibernate 无视手动
     * setId() 的值、坚持插入时自增分配，导致行的真实主键跟 git URL 里的 projectId 对不上、
     * 鉴权矩阵永远查不到项目（实测踩过）。这里直接 JDBC 插入指定主键绕开该限制。
     */
    private void seedMembership(long projectId) {
        String username = "git_user_" + projectId;
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPassword(UserService.encodePassword("x"));
        user = userRepository.save(user);

        jdbcTemplate.update(
                "insert into project (id, name, project_type, listed_company_name, target_company_name, user_id) "
                        + "values (?, ?, ?, ?, ?, ?)",
                projectId, "git-http-test-" + projectId, "BLANK", "", "", user.getId());

        tokenByProject.put(projectId,
                deviceTokenService.issue(user.getId(), "git-http-test").plaintext());
    }

    @Test
    void cloneOverHttpGetsSeedContent(@TempDir Path clientDir) throws Exception {
        seedProject(7L);
        try (Git client = Git.cloneRepository()
                .setURI(remoteUrl(7L))
                .setDirectory(clientDir.toFile())
                .setCredentialsProvider(
                        new UsernamePasswordCredentialsProvider("git_user", tokenByProject.get(7L)))
                .call()) {
            assertEquals("初稿", Files.readString(clientDir.resolve("合同.txt")));
        }
    }

    @Test
    void pushOverHttpAdvancesServerMaster(@TempDir Path clientDir) throws Exception {
        seedProject(8L);
        try (Git client = Git.cloneRepository()
                .setURI(remoteUrl(8L))
                .setDirectory(clientDir.toFile())
                .setCredentialsProvider(
                        new UsernamePasswordCredentialsProvider("git_user", tokenByProject.get(8L)))
                .call()) {
            Files.writeString(clientDir.resolve("合同.txt"), "第二稿");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("修改").setAuthor("客户端", "c@example.com").call();
            client.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master"))
                    .setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider("git_user", tokenByProject.get(8L)))
                    .call();
            ObjectId clientHead = client.getRepository().resolve("master");
            assertEquals(clientHead.getName(), repoService.resolveRef(8L, "master"));
        }
    }

    @Test
    void unknownProjectReturns404(@TempDir Path clientDir) throws Exception {
        // 只建鉴权矩阵（认证/权限均通过），但不 repoService.init——测的是仓库未初始化的 404，
        // 不是鉴权失败；鉴权检查现在排在 isInitialized 前面，没有合法凭据永远拿不到 404。
        seedMembership(999L);
        Exception e = assertThrows(Exception.class, () -> Git.cloneRepository()
                .setURI(remoteUrl(999L))
                .setDirectory(clientDir.toFile())
                .setCredentialsProvider(
                        new UsernamePasswordCredentialsProvider("git_user", tokenByProject.get(999L)))
                .call());
        // JGit 6.9 的 CloneCommand 把 fetch 阶段的 TransportException 包成
        // InvalidRemoteException，顶层 message 固定是 "Invalid remote: origin"，
        // 真正的 404 信息在 cause 里（如 "... not found"）——查全链而不是只查顶层。
        String chain = e.getMessage() + " " + (e.getCause() == null ? "" : e.getCause().getMessage());
        assertTrue(chain.contains("404") || chain.contains("not found") || chain.contains("无法"));
    }

    @Test
    void wrongTokenIsRejected(@TempDir Path clientDir) throws Exception {
        seedProject(9L);
        Exception e = assertThrows(Exception.class, () -> Git.cloneRepository()
                .setURI(remoteUrl(9L))
                .setDirectory(clientDir.toFile())
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("u", "awdt_bad"))
                .call());
        assertTrue(e.getMessage().contains("not authorized")
                || e.getMessage().contains("401") || e.getMessage().contains("403"));
    }
}
