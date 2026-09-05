package com.checkba.version;

import com.checkba.model.dto.ProjectCreateRequest;
import com.checkba.model.entity.Project;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.ProjectService;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本记录默认开启（dev-board#438）。
 *
 * <p>走真 Spring 容器而不是手工 new：这条链上大半的风险恰恰在**接线**——
 * {@code createProject} 发的事件有没有被监听到、{@code @TransactionalEventListener}
 * 会不会在事务提交前就跑、异步派发到不到 taskExecutor。手工 new 一个监听器直接调
 * 方法的话，这些全部零覆盖。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:version-auto-enable;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "storage.local.root-path=target/test-storage-auto-enable"
})
@ActiveProfiles("desktop")
class VersionAutoEnableTest {

    @Autowired private ProjectService projectService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectRepoService repoService;
    @Autowired private WorkSessionService sessionService;
    @Autowired private VersionLifecycleService lifecycleService;
    @Autowired private ProjectStorageResolver storageResolver;

    /**
     * 存储根是固定的 target 子目录，而内存库每次跑都是空的、项目 id 从 1 重新发——
     * 上一轮留下的 {@code repos/project-N.git} 会让「前提：这个项目还没开过版本记录」
     * 当场为假。每轮开跑前先清干净。
     */
    @org.junit.jupiter.api.BeforeAll
    static void clearStorageRoot() throws IOException {
        // 必须走 resolveConfiguredPath：surefire 的 user.dir 是 backend/，而配置里的相对
        // 路径会被上提一级解析到仓库根，直接 Path.of("target", ...) 删的是另一个目录。
        Path dir = ProjectStorageResolver.resolveConfiguredPath("target/test-storage-auto-enable");
        if (!Files.isDirectory(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 删不掉的留着，下面的断言会直接把问题暴露出来
                }
            });
        }
    }

    @Test
    void newProjectGetsVersionRecordingWithoutAnybodyPressingAnything() {
        long projectId = createManagedProject("自动开启-新建");

        assertTrue(awaitInitialized(projectId),
                "新建项目之后版本记录应自动开启，并留下「初始版本」那一笔");
    }

    @Test
    void firstChangeSignalTurnsItOnForAProjectThatNeverHadIt() throws IOException {
        long projectId = seedProjectRowOnly("自动开启-存量");
        Path root = storageResolver.projectRoot(projectId);
        Files.createDirectories(root);
        Files.writeString(root.resolve("合同.txt"), "初稿");
        assertFalse(repoService.isInitialized(projectId), "前提：这个项目还没开过版本记录");

        sessionService.onChangeSignal(projectId, 9100L, "韩泽伟");

        assertTrue(awaitInitialized(projectId), "存量项目的第一个变更信号应惰性开启版本记录");
    }

    @Test
    void optedOutProjectIsNeverTurnedBackOnByEitherTrigger() throws IOException {
        long projectId = seedProjectRowOnly("自动开启-已关掉");
        Project p = projectRepository.findById(projectId).orElseThrow();
        p.setVersionOptOut(true);
        projectRepository.save(p);
        Path root = storageResolver.projectRoot(projectId);
        Files.createDirectories(root);
        Files.writeString(root.resolve("合同.txt"), "初稿");

        // 触发点 (b)：变更信号
        sessionService.onChangeSignal(projectId, 9100L, "韩泽伟");
        // 触发点 (a)：建项目事件（同一个 projectId 直接调监听器入口，省掉再建一个项目）
        lifecycleService.autoEnableNow(projectId, 9100L, "韩泽伟");

        assertFalse(awaitInitialized(projectId),
                "律师关掉过版本记录，两个自动触发点都不许再把它开回来");
    }

    @Test
    void oversizedFolderIsRefusedAndNeverEstimatedTwice() throws IOException {
        long projectId = seedProjectRowOnly("自动开启-大文件夹");
        Path root = storageResolver.projectRoot(projectId);
        Files.createDirectories(root);
        for (int i = 0; i < 5; i++) {
            Files.writeString(root.resolve("卷宗" + i + ".txt"), "x".repeat(200));
        }

        lifecycleService.setGuardrailForTest(100L, 20000);
        try {
            lifecycleService.autoEnableNow(projectId, 9100L, "韩泽伟");
            assertFalse(repoService.isInitialized(projectId),
                    "工作区超过护栏阈值时不许自动开启");

            // 被拒过一次之后就记在进程里了：即便阈值放开，也不再重估（避免反复遍历几十 G）
            lifecycleService.setGuardrailForTest(Long.MAX_VALUE, Integer.MAX_VALUE);
            lifecycleService.onAutoEnableRequest(
                    new WorkSessionService.AutoEnableRequest(projectId, 9100L, "韩泽伟"));
            assertFalse(awaitInitializedShort(projectId), "被护栏拒过的项目本进程内不再重估");
        } finally {
            lifecycleService.setGuardrailForTest(2L * 1024 * 1024 * 1024, 20000);
        }
    }

    @Test
    void estimateStopsAsSoonAsTheThresholdIsCrossed() throws IOException {
        Path root = Files.createTempDirectory("awd-estimate");
        for (int i = 0; i < 50; i++) {
            Files.writeString(root.resolve("f" + i + ".txt"), "0123456789");
        }
        Files.createDirectories(root.resolve(".awd"));
        Files.writeString(root.resolve(".awd").resolve("tree.json"), "{}".repeat(500));

        VersionLifecycleService.Estimate est = VersionLifecycleService.estimate(root, 25L, 20000);

        assertTrue(est.exceeded(), "超过体积阈值应判超限");
        assertTrue(est.files() <= 4,
                "必须撞到阈值就停：扫过的文件数远小于目录里的 50 个，实际=" + est.files());

        VersionLifecycleService.Estimate all =
                VersionLifecycleService.estimate(root, Long.MAX_VALUE, Integer.MAX_VALUE);
        assertFalse(all.exceeded());
        assertEquals(50, all.files(), ".awd/ 是我们自己写的清单，不该算进律师的材料里");
    }

    /**
     * 团队服务器侧：{@code shareToCloud} 先在服务器上 POST 建一个项目（于是被自动开启，
     * 落了一笔空的「初始版本」），紧接着 prepare-remote，然后带着完整历史首推。
     * 两段历史没有共同祖先，不把那个空仓换掉的话首推会被整体拒绝——
     * 「放进团队案件库」这条路整个断掉。
     */
    @Test
    void autoEnabledServerProjectIsTurnedBackIntoAnEmptyReceiveTarget() throws IOException {
        long projectId = createManagedProject("自动开启-当接收方");
        assertTrue(awaitInitialized(projectId), "前提：新建项目已被自动开启");

        assertTrue(sessionService.resetToReceiveReadyIfNeverUsed(projectId),
                "从没用过的仓库应能换成等待首推的空仓");

        assertTrue(repoService.isInitialized(projectId), "空仓本身要在（不然 push 直接 404）");
        assertTrue(repoService.log(projectId, "HEAD", 10).isEmpty(),
                "换过之后不该还有任何提交，否则共享方首推仍然没有共同祖先");
        assertFalse(Files.exists(storageResolver.projectRoot(projectId).resolve(".awd")),
                "工作区里的 .awd/ 也要清掉——留着会被 push 前的停靠当脏区提交成一个根提交");
    }

    @Test
    void aRepositoryThatActuallyHasContentIsNeverResetForReceive() throws IOException {
        long projectId = seedProjectRowOnly("自动开启-有内容不许重置");
        Path root = storageResolver.projectRoot(projectId);
        Files.createDirectories(root);
        Files.writeString(root.resolve("合同.txt"), "初稿");
        sessionService.enableVersionRecording(projectId, "韩泽伟", "hzw@example.com");

        assertFalse(sessionService.resetToReceiveReadyIfNeverUsed(projectId),
                "有真实内容的仓库绝不许被 prepare-remote 抹掉");
        assertFalse(repoService.log(projectId, "HEAD", 10).isEmpty(), "历史必须原样还在");
    }

    // ---- helpers ----------------------------------------------------------

    private long createManagedProject(String name) {
        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setProjectType("BLANK");
        req.setName(name);
        return projectService.createProject(req, 9100L).getId();
    }

    /** 只落一行 project，不走 createProject——模拟「本功能上线之前就存在的项目」。 */
    private long seedProjectRowOnly(String name) {
        Project project = new Project();
        project.setName(name);
        project.setProjectType("BLANK");
        project.setListedCompanyName("");
        project.setTargetCompanyName("");
        project.setUserId(9100L);
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        return projectRepository.save(project).getId();
    }

    /**
     * 正向等待：判据是「已初始化**且**已有初始版本那一笔提交」。
     * 只判 isInitialized 会有竞态——{@code repo.create()} 一落地目录就在了，
     * 但初始提交还在后面几毫秒，断言时间线会偶发空。
     */
    private boolean awaitInitialized(long projectId) {
        return await(projectId, 10_000);
    }

    /** 反向断言用：等一小会儿确认它确实没被开起来（等太久只会把用例拖慢）。 */
    private boolean awaitInitializedShort(long projectId) {
        return await(projectId, 1_500);
    }

    private boolean await(long projectId, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (enabledWithInitialVersion(projectId)) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        // 反向断言要更严：只要仓库目录已经建出来了就算「被开启了」，
        // 不能因为初始提交还没落地就当成没开。
        return repoService.isInitialized(projectId);
    }

    private boolean enabledWithInitialVersion(long projectId) {
        if (!repoService.isInitialized(projectId)) return false;
        try {
            return !repoService.log(projectId, "HEAD", 10).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
