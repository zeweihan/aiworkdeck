package com.checkba.version;

import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkSessionServiceTest {

    private Path root;
    private ProjectRepoService repoSvc;
    private WorkSessionService svc;
    private Map<Long, WorkSession> sessions;
    private long nextSessionId;
    // 并发测试要另起一个指向同一份仓库/会话状态的 WorkSessionService（换成 spy 过的
    // ProjectRepoService），所以这三个协作者需要留在字段上，供测试方法复用。
    private ProjectTreeManifestService manifestSvc;
    private WorkSessionRepository sessionRepo;
    private ThreadPoolTaskScheduler scheduler;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(props);
        repoSvc.init(7L, "韩泽伟", "hzw@example.com");

        ProjectFileRepository fileRepo = mock(ProjectFileRepository.class);
        when(fileRepo.findByProjectId(7L)).thenReturn(new ArrayList<>());
        manifestSvc = new ProjectTreeManifestService(fileRepo, repoSvc, new ObjectMapper());

        sessions = new HashMap<>();
        nextSessionId = 1L;
        sessionRepo = mock(WorkSessionRepository.class);
        when(sessionRepo.save(any(WorkSession.class))).thenAnswer(i -> {
            WorkSession s = i.getArgument(0);
            if (s.getId() == null) s.setId(nextSessionId++);
            sessions.put(s.getId(), s);
            return s;
        });
        when(sessionRepo.findFirstByProjectIdAndStatus(any(), any())).thenAnswer(i ->
                sessions.values().stream()
                        .filter(s -> s.getProjectId().equals(i.getArgument(0))
                                && s.getStatus() == i.getArgument(1))
                        .findFirst());

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();

        svc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler);
        svc.setDebounceMillis(60_000); // 测试里不让防抖自己触发，全部手动 commitNow
    }

    @Test
    void firstChangeSignalStartsSessionImplicitly() {
        assertTrue(svc.activeSession(7L).isEmpty());

        svc.onChangeSignal(7L, 1L, "韩泽伟");

        var s = svc.activeSession(7L);
        assertTrue(s.isPresent());
        assertTrue(s.get().getBranchName().startsWith("work/"));
        assertEquals(s.get().getBranchName(), repoSvc.currentBranch(7L),
                "开始工作后应已切到工作分支");
    }

    @Test
    void secondSignalReusesTheSameSession() {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        String branch = svc.activeSession(7L).orElseThrow().getBranchName();
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        assertEquals(branch, svc.activeSession(7L).orElseThrow().getBranchName());
    }

    @Test
    void endSessionMergesBackToMainAndClosesSession() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");

        String sha = svc.endSession(7L, 1L, "韩泽伟", "发客户第一稿");

        assertNotNull(sha);
        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L));
        assertEquals("二稿", Files.readString(root.resolve("projects/7/合同.txt")));
        assertTrue(svc.activeSession(7L).isEmpty());
        assertEquals(WorkSession.Status.MERGED,
                sessions.values().iterator().next().getStatus());
    }

    @Test
    void endSessionGeneratesTitleWhenNotProvided() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");

        svc.endSession(7L, 1L, "韩泽伟", null);

        String title = sessions.values().iterator().next().getTitle();
        assertNotNull(title);
        assertFalse(title.isBlank(), "未命名时服务端必须生成一个标题");
    }

    @Test
    void discardSessionThrowsAwayTheWholeBranch() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "不要的改动");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");

        svc.discardSession(7L, 1L);

        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L));
        assertEquals("初稿", Files.readString(root.resolve("projects/7/合同.txt")),
                "丢弃后应回到主线内容");
        assertTrue(svc.activeSession(7L).isEmpty());
        assertEquals(WorkSession.Status.DISCARDED,
                sessions.values().iterator().next().getStatus());
    }

    /**
     * 回归测试：discardSession 之前，工作区在最后一次自动存档之后又被编辑过（未提交），
     * 是脏的。原实现直接 checkoutBranch(master) 会被 JGit 拒绝（CheckoutConflictException），
     * 律师看到「丢弃失败」且卡在工作段里出不去。修复后 discardSession 会先把一切
     * （含这份脏改动）收进即将被删除的分支，checkout 时工作区已经干净。
     */
    @Test
    void discardSessionHandlesDirtyEditAfterLastAutoSave() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "已存档的版本");
        svc.commitNow(7L, 1L, "韩泽伟", "自动存档");

        // 存档之后又编辑了同一文件，但没有再提交——此时工作区是脏的
        Files.writeString(root.resolve("projects/7/合同.txt"), "存档之后又改了但没保存");

        assertDoesNotThrow(() -> svc.discardSession(7L, 1L),
                "丢弃不应该因为工作区存在未提交的脏改动而失败");

        assertEquals("初稿", Files.readString(root.resolve("projects/7/合同.txt")),
                "丢弃后应回到主线内容，脏改动也要被撤销");
        assertTrue(svc.activeSession(7L).isEmpty());
    }

    /**
     * 回归测试：最后一次自动存档之后新建的文件是 untracked，原实现的
     * checkoutBranch 根本不会碰它，会残留在磁盘上（清单同步把 DB 行软删之后，
     * 下一段工作的 git add . 又会把它重新捡回来）。修复后 discardSession 会先把
     * 这个 untracked 文件也提交进即将被删除的分支，删分支即删干净。
     */
    @Test
    void discardSessionRemovesUntrackedFileCreatedAfterLastAutoSave() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "已存档的版本");
        svc.commitNow(7L, 1L, "韩泽伟", "自动存档");

        Path newFile = root.resolve("projects/7/新建草稿.txt");
        Files.writeString(newFile, "存档之后新建的文件，从未提交过");
        assertTrue(Files.exists(newFile), "测试前置条件：新文件应已落到磁盘");

        svc.discardSession(7L, 1L);

        assertFalse(Files.exists(newFile),
                "丢弃后，存档之后新建的 untracked 文件应该从磁盘上消失");
    }

    @Test
    void revertCreatesNewVersionRatherThanRewritingHistory() throws Exception {
        String firstSha = repoSvc.log(7L, "HEAD", 1).get(0).sha();

        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");
        svc.endSession(7L, 1L, "韩泽伟", "第一次工作");

        int before = repoSvc.log(7L, "HEAD", 100).size();
        String revertSha = svc.revertTo(7L, firstSha, 1L, "韩泽伟");
        int after = repoSvc.log(7L, "HEAD", 100).size();

        assertNotNull(revertSha);
        assertTrue(after > before, "退回必须新增版本，不得删除历史");
        assertEquals("初稿", Files.readString(root.resolve("projects/7/合同.txt")));
    }

    /**
     * 回归测试：revertTo 必须像 endSession/discardSession 一样取消掉武装中的防抖
     * 定时器，否则定时器可能在 revertTo 还原工作区的过程中触发，把半还原的工作区
     * 提交上去，或者和 revertTo 自己的 commitAll 抢同一个 git 锁。
     *
     * 这里不去让调度器真的触发（那是靠 sleep 赌时机的脆弱写法），而是同步地断言
     * revertTo 调用之后 pending 里该项目的定时器已经被清掉——这是修复本身的直接、
     * 确定性的证据。
     */
    @Test
    void revertToCancelsThePendingAutoSaveTimer() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        assertTrue(pendingContains(svc, 7L),
                "onChangeSignal 之后应该已经武装了防抖定时器，这是本测试的前提");

        svc.revertTo(7L, "HEAD", 1L, "韩泽伟");

        assertFalse(pendingContains(svc, 7L),
                "revertTo 必须像 endSession/discardSession 一样调用 cancelPending，"
                        + "否则防抖定时器会在还原工作区期间仍处于武装状态");
    }

    @SuppressWarnings("unchecked")
    private static boolean pendingContains(WorkSessionService target, long projectId) throws Exception {
        Field f = WorkSessionService.class.getDeclaredField("pending");
        f.setAccessible(true);
        Map<Long, ?> pending = (Map<Long, ?>) f.get(target);
        return pending.containsKey(projectId);
    }

    /**
     * spec 5.2 规定的三个结束触发之一：30 分钟无变更信号自动结束工作段。
     * 把阈值调到 200ms 取得确定性，用有界轮询等待触发，而不是裸 sleep 赌时机。
     */
    @Test
    void idleTimeoutAutoEndsSessionWhenNoFurtherActivity() throws Exception {
        svc.setIdleEndMillis(200);
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && svc.activeSession(7L).isPresent()) {
            Thread.sleep(20);
        }

        assertTrue(svc.activeSession(7L).isEmpty(), "空闲超时后应自动结束工作段");
        WorkSession s = sessions.values().iterator().next();
        assertEquals(WorkSession.Status.MERGED, s.getStatus());
        assertNotNull(s.getTitle());
        assertFalse(s.getTitle().isBlank(), "空闲自动结束也要走默认命名");
    }

    /** 回归测试：手动 endSession 必须像 cancelPending 的其他调用点一样取消空闲定时器。 */
    @Test
    void manualEndSessionCancelsTheIdleTimer() throws Exception {
        svc.setIdleEndMillis(60_000);
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        assertTrue(idleTimerArmed(svc, 7L), "onChangeSignal 之后应已武装空闲定时器，这是本测试的前提");

        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");
        svc.endSession(7L, 1L, "韩泽伟", "手动结束");

        assertFalse(idleTimerArmed(svc, 7L), "手动结束后空闲定时器必须被取消，否则之后还会被空跑一次");
    }

    @SuppressWarnings("unchecked")
    private static boolean idleTimerArmed(WorkSessionService target, long projectId) throws Exception {
        Field f = WorkSessionService.class.getDeclaredField("idleTimers");
        f.setAccessible(true);
        Map<Long, ?> idleTimers = (Map<Long, ?>) f.get(target);
        return idleTimers.containsKey(projectId);
    }

    /**
     * 回归测试：证明按项目维度的可重入锁确实生效——两个线程对同一 projectId
     * 并发调用 commitNow（会经过 endSession/revertTo 同样的临界区）时不能同时
     * 跑到 repoService.commitAll 里面。
     *
     * 不用 sleep 赌真实调度器和终结操作会不会撞上（那样测试要么难以稳定复现，
     * 要么会变成偶发失败）。改用受控的两阶段闩门：让线程 A 先卡在 commitAll
     * 内部（模拟"已经开始执行、cancel(false) 拦不住"的场景），给线程 B 一个
     * 有界但充裕的窗口去尝试抢入同一临界区，断言窗口期内 commitAll 的调用次数
     * 仍然是 1——这就是锁确实被持有的确定性证据。窗口结束后放行 A，再确认两个
     * 线程都能顺利收尾、互不干扰。
     */
    @Test
    void repoMutatingCallsForSameProjectAreMutuallyExclusive() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "线程 A 的改动");

        ProjectRepoService spyRepo = spy(repoSvc);
        AtomicInteger commitAllCalls = new AtomicInteger();
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);

        doAnswer(invocation -> {
            int n = commitAllCalls.incrementAndGet();
            if (n == 1) {
                aEntered.countDown();
                assertTrue(releaseA.await(5, TimeUnit.SECONDS),
                        "测试主线程没有按预期放行，测试环境本身有问题");
            }
            return invocation.callRealMethod();
        }).when(spyRepo).commitAll(anyLong(), any(), any(), any(), any(), any());

        // 复用同一份会话状态（sessionRepo/manifestSvc 都指向同一个内存 Map 和同一个
        // 磁盘仓库），但改走 spy 过的 repoService，这样才能在 commitAll 内部插入闩门。
        WorkSessionService lockedSvc = new WorkSessionService(spyRepo, manifestSvc, sessionRepo, scheduler);
        lockedSvc.setDebounceMillis(60_000);

        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        Thread a = new Thread(() -> {
            try {
                lockedSvc.commitNow(7L, 1L, "韩泽伟", "A 的自动存档");
            } catch (Throwable t) {
                errors.add(t);
            }
        }, "worksession-test-a");
        a.start();

        assertTrue(aEntered.await(5, TimeUnit.SECONDS),
                "线程 A 应该已经进入临界区并卡在 commitAll 里");

        Thread b = new Thread(() -> {
            try {
                lockedSvc.commitNow(7L, 1L, "韩泽伟", "B 的自动存档");
            } catch (Throwable t) {
                errors.add(t);
            }
        }, "worksession-test-b");
        b.start();

        // 给 B 一个有界但充裕的窗口去尝试抢入临界区；锁生效的话它进不去，
        // commitAllCalls 应该在这段时间内一直停在 1。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline && commitAllCalls.get() < 2) {
            Thread.sleep(10);
        }
        assertEquals(1, commitAllCalls.get(),
                "锁没生效的话 B 会在 A 还没放行时就抢先跑进 commitAll，这里应该还卡着没进去");

        releaseA.countDown();
        a.join(5_000);
        b.join(5_000);

        assertFalse(a.isAlive(), "线程 A 应该已经结束");
        assertFalse(b.isAlive(), "线程 B 应该已经结束");
        assertTrue(errors.isEmpty(), "两个线程都不应该抛异常: " + errors);
        assertEquals(2, commitAllCalls.get(), "两次 commitNow 最终都应该各自跑到 commitAll，只是不能同时跑");
    }
}
