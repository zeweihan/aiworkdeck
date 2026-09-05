package com.checkba.version;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 自动开启（dev-board#438）与团队服务器侧 {@code prepare-remote} 并发建同一个版本库。
 *
 * <p>真实时序：{@code CloudSyncService.shareToCloud} 先在服务器上 POST 建项目
 * （{@code ProjectService.ProjectCreatedEvent} → {@link VersionLifecycleService#autoEnableNow}
 * 在 taskExecutor 线程上建仓 + 落「初始版本」），**紧接着**就打 prepare-remote。
 * 两条路径以前没有共同的锁，互相踩 JGit 的 refs 目录，三种坏法：
 * <ol>
 *   <li>prepare-remote 当场抛异常（律师看到「没能放进团队案件库」）；</li>
 *   <li>prepare 走成「未初始化 → initEmptyForReceive 幂等 no-op」，留下一个带着孤立
 *       「初始版本」的仓库，共享方带完整历史首推没有共同祖先，REJECTED_NONFASTFORWARD；</li>
 *   <li>仓库换对了，但工作区残留 {@code .awd/}——pre-receive 的停靠把它提交成一个根提交，
 *       首推照样被拒（REJECTED_OTHER_REASON，看着像另一个 bug，其实是同一个）。</li>
 * </ol>
 *
 * <p>受控实验里连建 5 个项目 3 个坏，而两条路径**单跑**各自都是绿的——这条只有 e2e
 * （app-e2e J11）抓得到，所以这里用 latch 把它压成一条可重复的单元用例。
 *
 * <p>不管谁先跑，收尾状态必须逐字相同：等待首推的空仓（仓库在、一笔提交都没有）
 * + 工作区里没有 {@code .awd/}。
 */
class PrepareRemoteRaceTest {

    private static final int ROUNDS = 20;

    private Path root;
    private ProjectRepoService repoSvc;
    private WorkSessionService sessionSvc;
    private VersionLifecycleService lifecycle;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        root = tmp;

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(new ProjectStorageResolver(props, null));

        ProjectFileRepository fileRepo = mock(ProjectFileRepository.class);
        when(fileRepo.findByProjectId(any())).thenReturn(new ArrayList<>());
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        when(projectRepo.findById(any())).thenAnswer(i -> {
            Project p = new Project();
            p.setId(i.getArgument(0));
            p.setUserId(9100L);
            return Optional.of(p);
        });
        UserRepository userRepo = mock(UserRepository.class);
        User u = new User();
        u.setUsername("韩泽伟");
        when(userRepo.findById(any())).thenReturn(Optional.of(u));

        ProjectTreeManifestService manifestSvc = new ProjectTreeManifestService(
                fileRepo, repoSvc, new ObjectMapper(), userRepo, projectRepo);

        WorkSessionRepository sessionRepo = mock(WorkSessionRepository.class);
        when(sessionRepo.findByProjectIdOrderByStartedAtDesc(any())).thenReturn(List.of());
        when(sessionRepo.findFirstByProjectIdAndStatusAndSessionType(any(), any(), any()))
                .thenReturn(Optional.empty());

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        sessionSvc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler, fileRepo, e -> {});
        sessionSvc.setDebounceMillis(600_000);

        lifecycle = new VersionLifecycleService(sessionSvc, repoSvc, projectRepo, userRepo,
                mock(ProjectRemoteRepository.class), Runnable::run);
    }

    @Test
    void autoEnableRacingPrepareRemoteAlwaysLeavesAnEmptyReceiveTarget() throws Exception {
        List<String> failures = new CopyOnWriteArrayList<>();

        for (int round = 1; round <= ROUNDS; round++) {
            long projectId = 1000L + round;
            // 服务器上刚 POST 建出来的项目：目录在、里面什么都还没有。
            Files.createDirectories(repoSvc.workTree(projectId));

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            Thread autoEnable = new Thread(() -> {
                await(start);
                try {
                    lifecycle.autoEnableNow(projectId, 9100L, "韩泽伟");
                } catch (RuntimeException e) {
                    failures.add("自动开启抛出异常: project=" + projectId + " " + e);
                } finally {
                    done.countDown();
                }
            }, "auto-enable-" + round);
            Thread prepare = new Thread(() -> {
                await(start);
                try {
                    sessionSvc.prepareRemoteRepository(projectId, 9100L, "韩泽伟");
                } catch (RuntimeException e) {
                    failures.add("prepare-remote 抛出异常: project=" + projectId + " " + e);
                } finally {
                    done.countDown();
                }
            }, "prepare-remote-" + round);

            autoEnable.start();
            prepare.start();
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "第 " + round + " 轮有线程没跑完");
            autoEnable.join();
            prepare.join();

            checkRound(round, projectId, failures);
        }

        assertEquals(List.of(), failures,
                "自动开启与 prepare-remote 并发时，仓库必须落在「等待首推的空仓」这一个状态上");
    }

    private void checkRound(int round, long projectId, List<String> failures) {
        String where = "第 " + round + " 轮 project=" + projectId + "：";
        if (!repoSvc.isInitialized(projectId)) {
            failures.add(where + "仓库根本没建出来，共享方首推会直接 404");
            return;
        }
        try {
            List<VersionEntry> history = repoSvc.log(projectId, "HEAD", 10);
            if (!history.isEmpty()) {
                failures.add(where + "空仓里留下了 " + history.size()
                        + " 笔提交（首推没有共同祖先，会被整体拒绝）");
            }
        } catch (RuntimeException e) {
            failures.add(where + "读历史失败（仓库被踩坏了）: " + e);
        }
        if (Files.exists(repoSvc.workTree(projectId).resolve(".awd"))) {
            failures.add(where + "工作区残留 .awd/，pre-receive 的停靠会把它提交成一个根提交，首推照样被拒");
        }
    }

    /** 同一个项目上，两条路径谁先谁后都必须收在同一个状态上——串行跑一遍钉死这个不变式。 */
    @Test
    void eitherOrderEndsInTheSameState() throws Exception {
        long a = 2001L;
        Files.createDirectories(repoSvc.workTree(a));
        lifecycle.autoEnableNow(a, 9100L, "韩泽伟");
        assertFalse(repoSvc.log(a, "HEAD", 10).isEmpty(), "前提：自动开启落了「初始版本」");
        assertTrue(sessionSvc.prepareRemoteRepository(a, 9100L, "韩泽伟"),
                "自动开启在前时，prepare-remote 要把它换成等待首推的空仓");
        assertTrue(repoSvc.log(a, "HEAD", 10).isEmpty(), "换过之后不该还有任何提交");
        assertFalse(Files.exists(repoSvc.workTree(a).resolve(".awd")), "工作区里的 .awd/ 也要清掉");

        long b = 2002L;
        Files.createDirectories(repoSvc.workTree(b));
        assertTrue(sessionSvc.prepareRemoteRepository(b, 9100L, "韩泽伟"));
        lifecycle.autoEnableNow(b, 9100L, "韩泽伟");
        assertTrue(repoSvc.isInitialized(b));
        assertTrue(repoSvc.log(b, "HEAD", 10).isEmpty(),
                "prepare-remote 在前时，自动开启必须看见「已初始化」直接放手，不许再落初始版本");
        assertFalse(Files.exists(repoSvc.workTree(b).resolve(".awd")),
                "自动开启放手了就不该写清单——留下 .awd/ 一样会让首推被拒");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
