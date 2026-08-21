package com.checkba.service.quota;

import com.checkba.model.dto.ProjectFileBatchRequest;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dev-board#74 审计条目：StageQuotaService.checkAdmission 的 check-then-act 竞态——
 * 两个并发 batchMove 请求各自读到同一份"移入前用量"，都算出未超额、都放行，合计超出
 * FREE_MAX_FILES。
 *
 * <p>与 DdService/ShareholderMeetingService 那两条 ensureFolder 竞态不同形状：checkAdmission
 * 本身不带 @Transactional，是在调用方（ProjectFileService.move/batchMove，本来就是
 * @Transactional）已经开着的事务里跑的。这里不能再用"进程内锁 + REQUIRES_NEW 子事务"那一套
 * ——那样反而会把 move 真正的写入拆到另一个事务里，动了 move 本已很复杂的失败回滚语义。
 * 改用悲观行锁（SELECT ... FOR UPDATE）钉住这个已经存在的事务边界：锁在事务提交/回滚前
 * 不释放，第二个请求的锁获取会等到第一个请求整体提交为止，锁到手时看到的已经是提交后的
 * 真实用量。
 *
 * <p>并发构造手法与 DdService/ShareholderMeetingService 的两个 ensureFolder 竞态测试同一套
 * "单向门"：不能用双向栅栏对称等待，修复后第二个线程会卡在行锁上，走不到这次查询，
 * 双向栅栏会互相等成死锁；单向门只让先到的那个等一小段超时。
 */
@SpringBootTest(properties = {
        // LOCK_TIMEOUT 显式拉长到 10 秒：H2 默认行锁等待上限只有 2 秒，比下面 ReadGate
        // 单向门里"先到者最多等 3 秒"还短——第二个线程真去抢行锁时会先被 H2 自己的锁超时
        // 打断，而不是等到 ReadGate 放行，制造出与竞态本身无关的假失败。
        "spring.datasource.url=jdbc:h2:mem:stage-quota-concurrent-move;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "storage.local.root-path=target/test-storage-stage-quota-race",
        "security.license.dir=target/test-license-stage-quota-race",
        "security.local-mode=true"
})
@ActiveProfiles("desktop")
class StageQuotaConcurrentMoveTest {

    static final AtomicReference<ReadGate> ACTIVE_GATE = new AtomicReference<>();

    @TestConfiguration
    static class RacingRepositoryConfig {

        @Bean
        @Primary
        ProjectFileRepository racingProjectFileRepository(
                @Qualifier("projectFileRepository") ProjectFileRepository real) {
            InvocationHandler handler = (proxy, method, args) -> {
                ReadGate gate = ACTIVE_GATE.get();
                if (gate != null
                        && "findByProjectIdAndParentIdAndIsDeletedFalseOrderBySortOrderAsc".equals(method.getName())
                        && args != null && args.length == 2) {
                    Object result = invokeReal(real, method, args);
                    gate.onRead(String.valueOf(args[1]));
                    return result;
                }
                return invokeReal(real, method, args);
            };
            return (ProjectFileRepository) Proxy.newProxyInstance(
                    ProjectFileRepository.class.getClassLoader(),
                    new Class<?>[]{ProjectFileRepository.class},
                    handler);
        }

        private static Object invokeReal(Object real, java.lang.reflect.Method method, Object[] args) {
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException(cause);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** 单向门，语义与 {@code DdServiceEnsureFolderConcurrentTest.ReadGate} 完全一致。 */
    static final class ReadGate {
        private final String targetKey;
        private final AtomicBoolean firstArrived = new AtomicBoolean(false);
        private final CountDownLatch secondArrived = new CountDownLatch(1);

        ReadGate(String targetKey) { this.targetKey = targetKey; }

        void onRead(String key) throws InterruptedException {
            if (!targetKey.equals(key)) return;
            if (firstArrived.compareAndSet(false, true)) {
                secondArrived.await(3, TimeUnit.SECONDS);
            } else {
                secondArrived.countDown();
            }
        }
    }

    @Autowired
    private ProjectFileService projectFileService;
    @Autowired
    private ProjectFileRepository projectFileRepository;

    @AfterEach
    void tearDown() {
        ACTIVE_GATE.set(null);
    }

    private ProjectFile save(Long projectId, Long parentId, String name, boolean isFolder, Long size) {
        ProjectFile f = new ProjectFile();
        f.setProjectId(projectId);
        f.setParentId(parentId);
        f.setName(name);
        f.setIsFolder(isFolder);
        f.setFileSize(size);
        f.setIsDeleted(false);
        f.setSortOrder(0);
        f.setUserId(1L);
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.now());
        return projectFileRepository.save(f);
    }

    @Test
    void 并发批量移入缓存区不能合计超过免费额度() throws Exception {
        long projectId = 9401L;
        Long stagingFolderId = save(projectId, null, StageQuotaService.STAGING_FOLDER_NAME, true, null).getId();

        // 缓存区已有 18 个文件（上限 20），刚好留 2 个名额
        for (int i = 0; i < 18; i++) {
            save(projectId, stagingFolderId, "existing" + i + ".txt", false, 1L);
        }

        // 两批各 2 个文件，各自单独看都没超额（18+2=20），并发一起看就超了（18+2+2=22）
        Long a1 = save(projectId, null, "a1.txt", false, 1L).getId();
        Long a2 = save(projectId, null, "a2.txt", false, 1L).getId();
        Long b1 = save(projectId, null, "b1.txt", false, 1L).getId();
        Long b2 = save(projectId, null, "b2.txt", false, 1L).getId();

        ACTIVE_GATE.set(new ReadGate(String.valueOf(stagingFolderId)));

        ProjectFileBatchRequest reqA = new ProjectFileBatchRequest();
        reqA.setFileIds(List.of(a1, a2));
        reqA.setTargetParentId(stagingFolderId);
        ProjectFileBatchRequest reqB = new ProjectFileBatchRequest();
        reqB.setFileIds(List.of(b1, b2));
        reqB.setTargetParentId(stagingFolderId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        int rejections = 0;
        try {
            Future<Object> fA = pool.submit(() -> {
                projectFileService.batchMove(projectId, reqA, 1L);
                return null;
            });
            Future<Object> fB = pool.submit(() -> {
                projectFileService.batchMove(projectId, reqB, 1L);
                return null;
            });

            rejections += countQuotaRejection(fA);
            rejections += countQuotaRejection(fB);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, rejections,
                "两批各 2 个、合计会超额，必须恰好拒绝一批，不能两批都放行也不能两批都拒绝");

        long finalCount = projectFileRepository
                .findByProjectIdAndParentIdAndIsDeletedFalseOrderBySortOrderAsc(projectId, stagingFolderId)
                .size();
        assertTrue(finalCount <= StageQuotaService.FREE_MAX_FILES,
                "缓存区最终文件数不能超过免费额度: " + finalCount);
    }

    private static int countQuotaRejection(Future<Object> future) throws Exception {
        try {
            future.get(20, TimeUnit.SECONDS);
            return 0;
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof com.checkba.exception.StageQuotaExceededException) {
                return 1;
            }
            throw e;
        }
    }
}
