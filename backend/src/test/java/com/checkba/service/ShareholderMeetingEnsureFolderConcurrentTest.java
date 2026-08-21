package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ShareholderMeetingCheck;
import com.checkba.repository.ProjectFileRepository;
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
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * dev-board#74 审计条目：ShareholderMeetingService.ensureFolder 的 TOCTOU 竞态——双击
 * "开始核查"按钮或前端重试，会让两次 start()/fetch-cninfo 同时跑到 ensureWorkpaperFolders，
 * 都先确保底稿夹根目录（"股东大会核查"）存在。(project_id, parent_id, name) 上没有 DB
 * 唯一约束，两次并发的"查不到就建"都能通过检查各插一行，产生重复文件夹。
 * 与 {@code DdServiceEnsureFolderConcurrentTest} 同一形状、同一套单向门测试手法
 * （类头注释里有完整的死锁规避说明）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:shm-ensure-folder-race;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "storage.local.root-path=target/test-storage-shm-ensure-folder-race"
})
@ActiveProfiles("desktop")
class ShareholderMeetingEnsureFolderConcurrentTest {

    static final AtomicReference<ReadGate> ACTIVE_GATE = new AtomicReference<>();

    @TestConfiguration
    static class RacingRepositoryConfig {

        @Bean
        @Primary
        ProjectFileRepository racingProjectFileRepository(
                @Qualifier("projectFileRepository") ProjectFileRepository real) {
            InvocationHandler handler = (proxy, method, args) -> {
                Object result = invokeReal(real, method, args);
                ReadGate gate = ACTIVE_GATE.get();
                if (gate != null && "findByProjectIdAndParentIdAndNameAndIsDeletedFalse".equals(method.getName())
                        && args != null && args.length == 3) {
                    Long projectId = (Long) args[0];
                    Long parentId = (Long) args[1];
                    String name = (String) args[2];
                    gate.onRead(projectId + "/" + parentId + "/" + name);
                }
                return result;
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
    private ShareholderMeetingService meetingService;
    @Autowired
    private ProjectFileRepository projectFileRepository;

    @AfterEach
    void tearDown() {
        ACTIVE_GATE.set(null);
    }

    @Test
    void 并发确保底稿夹根目录不产生重复文件夹() throws Exception {
        long projectId = 9301L;
        ShareholderMeetingCheck check = meetingService.create(
                projectId, "某某股份有限公司", "600000", "2026年第一次临时股东大会",
                LocalDate.of(2026, 8, 21), 9L);

        // 两次调用共享的第一个 ensureFolder：ensureFolder(projectId, null, WORKPAPER_ROOT, ...)
        ACTIVE_GATE.set(new ReadGate(projectId + "/null/" + ShareholderMeetingService.WORKPAPER_ROOT));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> meetingService.ensureWorkpaperFolders(check, 9L));
            Future<?> f2 = pool.submit(() -> meetingService.ensureWorkpaperFolders(check, 9L));
            assertNotNull(f1.get(20, TimeUnit.SECONDS));
            assertNotNull(f2.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        List<ProjectFile> rootFolders = projectFileRepository.findByProjectId(projectId).stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsFolder())
                        && ShareholderMeetingService.WORKPAPER_ROOT.equals(f.getName()))
                .toList();
        assertEquals(1, rootFolders.size(),
                "两次并发的 ensureWorkpaperFolders 必须共用同一个底稿夹根目录，不能各建一个: " + rootFolders);
    }
}
