package com.checkba.version.cloud;

import com.checkba.version.ProjectRepoService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对内嵌 smart HTTP 端点做真实 JGit 客户端克隆/推送。
 * 必须 desktop profile：默认 profile 连本机 Postgres，CI 必挂（v1 已知地雷）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("desktop")
class GitHttpProtocolTest {

    static Path root;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("git-http-test");
        registry.add("storage.local.root-path", () -> root.toAbsolutePath().toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    ProjectRepoService repoService;

    private String remoteUrl(long projectId) {
        return "http://localhost:" + port + "/git/" + projectId + ".git";
    }

    private void seedProject(long projectId) throws Exception {
        Files.createDirectories(root.resolve("projects/" + projectId));
        Files.writeString(root.resolve("projects/" + projectId + "/合同.txt"), "初稿");
        repoService.init(projectId, "韩泽伟", "hzw@example.com");
    }

    @Test
    void cloneOverHttpGetsSeedContent(@TempDir Path clientDir) throws Exception {
        seedProject(7L);
        try (Git client = Git.cloneRepository()
                .setURI(remoteUrl(7L))
                .setDirectory(clientDir.toFile())
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
                .call()) {
            Files.writeString(clientDir.resolve("合同.txt"), "第二稿");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("修改").setAuthor("客户端", "c@example.com").call();
            client.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master"))
                    .call();
            ObjectId clientHead = client.getRepository().resolve("master");
            assertEquals(clientHead.getName(), repoService.resolveRef(8L, "master"));
        }
    }

    @Test
    void unknownProjectReturns404(@TempDir Path clientDir) {
        Exception e = assertThrows(Exception.class, () -> Git.cloneRepository()
                .setURI(remoteUrl(999L))
                .setDirectory(clientDir.toFile())
                .call());
        // JGit 6.9 的 CloneCommand 把 fetch 阶段的 TransportException 包成
        // InvalidRemoteException，顶层 message 固定是 "Invalid remote: origin"，
        // 真正的 404 信息在 cause 里（如 "... not found"）——查全链而不是只查顶层。
        String chain = e.getMessage() + " " + (e.getCause() == null ? "" : e.getCause().getMessage());
        assertTrue(chain.contains("404") || chain.contains("not found") || chain.contains("无法"));
    }
}
