package com.checkba.service;

import com.checkba.model.entity.DdItem;
import com.checkba.model.entity.DdRequest;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.DdItemRepository;
import com.checkba.repository.DdRequestRepository;
import com.checkba.repository.ProjectFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
 * dev-board#74 审计条目：DdService.ensureFolder 的 check-then-create 竞态——两个尽调清单项
 * （同一 DdRequest 下的兄弟项）第一次上传文件时并发触发，都会先确保共享的祖先文件夹存在
 * （"客户提供的文件" 根目录、请求名子目录），(project_id, parent_id, name) 上没有 DB
 * 唯一约束（团队约定不加），两次并发的"查不到就建"都能通过检查各插一行，产生重复文件夹。
 *
 * 真 Spring 容器 + 真 H2：代理包在 {@link ProjectFileRepository} 前面，在
 * findByProjectIdAndParentIdAndName 上做"第一个到达的先暂停，等第二个也到达（或超时）
 * 再继续"的单向门——不能用双向栅栏对称等待，因为修复后第二个线程会卡在 DB 行锁式的
 * 进程内锁上，根本走不到这次查询，双向栅栏会互相等成死锁。单向门只让先到的那个等一小段
 * 超时，最坏情况多等这一个超时窗口，不会卡死。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dd-ensure-folder-race;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "storage.local.root-path=target/test-storage-dd-ensure-folder-race"
})
@ActiveProfiles("desktop")
class DdServiceEnsureFolderConcurrentTest {

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
                if (gate != null && "findByProjectIdAndParentIdAndName".equals(method.getName())
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

    /**
     * 单向门：只栅指定 key 的头两次读。第一个到达的线程原地等第二个也到达（最多 3 秒，
     * 超时就直接放行，避免修复生效后第二个线程被进程内锁挡住、永远等不到而死锁）；
     * 第二个到达的线程只负责唤醒第一个，自己立即放行，不等待。
     */
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
    private DdService ddService;
    @Autowired
    private DdRequestRepository ddRequestRepository;
    @Autowired
    private DdItemRepository ddItemRepository;
    @Autowired
    private ProjectFileRepository projectFileRepository;

    @AfterEach
    void tearDown() {
        ACTIVE_GATE.set(null);
    }

    @Test
    void 并发上传兄弟清单项共用同一个客户文件根目录不产生重复() throws Exception {
        long projectId = 9201L;
        DdRequest req = new DdRequest();
        req.setProjectId(projectId);
        req.setName("尽调请求-并发测试");
        req.setCreatedBy(9L);
        req = ddRequestRepository.save(req);

        DdItem itemA = new DdItem();
        itemA.setDdRequestId(req.getId());
        itemA.setTitle("文件A");
        itemA.setSortOrder(0);
        itemA = ddItemRepository.save(itemA);

        DdItem itemB = new DdItem();
        itemB.setDdRequestId(req.getId());
        itemB.setTitle("文件B");
        itemB.setSortOrder(1);
        itemB = ddItemRepository.save(itemB);

        // 两个上传共享的第一个 ensureFolder 调用：ensureFolder(projectId, null, "客户提供的文件", ...)
        ACTIVE_GATE.set(new ReadGate(projectId + "/null/客户提供的文件"));

        MockMultipartFile fileA = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1});
        MockMultipartFile fileB = new MockMultipartFile("file", "b.pdf", "application/pdf", new byte[]{2});
        Long itemAId = itemA.getId();
        Long itemBId = itemB.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DdItem> f1 = pool.submit(() -> ddService.uploadFile(itemAId, fileA, 9L));
            Future<DdItem> f2 = pool.submit(() -> ddService.uploadFile(itemBId, fileB, 9L));
            assertNotNull(f1.get(20, TimeUnit.SECONDS));
            assertNotNull(f2.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        List<ProjectFile> clientFolders = projectFileRepository.findByProjectId(projectId).stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsFolder()) && "客户提供的文件".equals(f.getName()))
                .toList();
        assertEquals(1, clientFolders.size(),
                "两个并发上传必须共用同一个「客户提供的文件」根目录，不能各建一个: " + clientFolders);
    }
}
