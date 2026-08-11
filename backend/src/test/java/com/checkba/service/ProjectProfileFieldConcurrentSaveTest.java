package com.checkba.service;

import com.checkba.model.entity.ProjectProfileField;
import com.checkba.repository.ProjectProfileFieldRepository;
import com.checkba.service.ai.tools.WebTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Callable;
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
 * 问题①的真测试：ProjectProfileServiceTest 里那个「并发撞车时按已存在处理不抛异常」用例
 * 全程是 mock repository，不会复现 Hibernate 撞唯一约束时把当前事务标记 rollback-only 的
 * 真实行为，证明不了「重试真的救得回来」——本类走真 Spring 容器 + 真 H2 + 真
 * (project_id, field_key) 唯一约束 + 真事务，构造两次货真价实的并发写同一行。
 *
 * <p>并发的构造方式：包一层代理在真实的 {@link ProjectProfileFieldRepository} 前面，在
 * "findByProjectIdAndFieldKey" 和 "save" 两个方法上做文章。find 那道栅栏先行：两个线程
 * 各自的 find() 都会真正查一次库（此时都应该读到空 Optional），但调用要等两边都查完才
 * 一起放行返回——防的是"线程 B 起步慢，A 已经整笔提交，B 的 find 直接读到 A 那行走了
 * 普通更新分支"这种窗口，那样竞态压根不会发生，重试路径一次都不会跑，但断言照样能过。
 * 两个线程都过了 find 栅栏之后才轮到 save 仲裁：第一个到达 save() 的调用直接放行（模拟
 * 两个请求都读到空 Optional 后，先物理 INSERT 成功的那个）；第二个到达 save() 的调用先
 * 原地等第一个的 save() 调用已经发出，再放行去调用真正的 save()。IDENTITY 生成策略下
 * save() 对新实体是立即 INSERT，H2 对同一唯一键的并发写会阻塞第二个 INSERT 直到第一个
 * 事务提交/回滚——所以放行之后，第二个请求的物理 INSERT 会在真实场景下等到第一个事务
 * 提交、再撞上真实的唯一约束，抛出真实的 DataIntegrityViolationException，与终审探针
 * 描述的竞态完全一致，不需要额外的时序猜测。
 *
 * <p>不预先假定哪个请求（值 A / 值 B）会赢得 CAS：真实线程调度不保证顺序，所以断言时按
 * "先拿到 save() 的那个" 与 "另一个" 来对照，而不是按提交顺序硬编码某个字面量。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:profile-field-concurrent-save;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("desktop")
class ProjectProfileFieldConcurrentSaveTest {

    /** 每个用例独立一个仲裁器，跨用例不许互相影响。 */
    static final AtomicReference<RaceArbiter> ACTIVE_ARBITER = new AtomicReference<>();

    @TestConfiguration
    static class RacingRepositoryConfig {

        /**
         * 用 @Primary 顶掉真实的 Spring Data 仓储 bean：ProjectProfileService 按类型自动装配
         * ProjectProfileFieldRepository，会拿到这个包装后的代理；真实仓储通过
         * "projectProfileFieldRepository"（Spring Data 默认 bean 名 = 接口名首字母小写）这个
         * 限定名单独注入进来，代理内部所有调用都转发给它，只在 save() 上加仲裁逻辑。
         */
        @Bean
        @Primary
        ProjectProfileFieldRepository racingProjectProfileFieldRepository(
                @Qualifier("projectProfileFieldRepository") ProjectProfileFieldRepository real) {
            InvocationHandler handler = (proxy, method, args) -> {
                RaceArbiter arbiter = ACTIVE_ARBITER.get();
                if (arbiter != null && "save".equals(method.getName())
                        && args != null && args.length == 1 && args[0] instanceof ProjectProfileField) {
                    ProjectProfileField entity = (ProjectProfileField) args[0];
                    return arbiter.aroundSave(entity, () -> invokeReal(real, method, args));
                }
                if (arbiter != null && "findByProjectIdAndFieldKey".equals(method.getName())
                        && args != null && args.length == 2) {
                    return arbiter.aroundFind(() -> invokeReal(real, method, args));
                }
                return invokeReal(real, method, args);
            };
            return (ProjectProfileFieldRepository) Proxy.newProxyInstance(
                    ProjectProfileFieldRepository.class.getClassLoader(),
                    new Class<?>[]{ProjectProfileFieldRepository.class},
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
     * 谁先到 save() 谁直接放行；后到的等前面那次 save() 调用已经发出（不必等它所在的事务
     * 提交）再放行——真实的"等提交"由 H2 对同一唯一键的写锁负责，不需要仲裁器自己模拟。
     *
     * <p>save 仲裁之前还有一道 find 栅栏：两个线程各自的 findByProjectIdAndFieldKey() 都要
     * 先真正查完库，才一起放行返回给调用方。没有这道栅栏时，先起步的线程可能在后起步的
     * 线程查库之前就已经整笔提交——后者的 find 会直接读到那一行，走普通更新分支而不是撞
     * 唯一约束触发重试，竞态窗口被跳过，但断言（最终一行、值为后到的、uid 为先插那行的）
     * 照样能通过，测出来的是假阳性。栅栏钉死了"两次 find 都发生在任何一次 save 之前"，
     * 逼着两个线程都读到空 Optional、都尝试 INSERT，重试路径因此必然被真正跑到。
     */
    static final class RaceArbiter {
        private final AtomicBoolean firstClaimed = new AtomicBoolean(false);
        private final CountDownLatch firstSaveDispatched = new CountDownLatch(1);
        private final AtomicReference<String> firstFieldValue = new AtomicReference<>();
        private final AtomicReference<String> firstUid = new AtomicReference<>();
        private final CountDownLatch bothFindsDone = new CountDownLatch(2);

        Object aroundFind(Callable<Object> realFind) throws Exception {
            Object result = realFind.call();
            bothFindsDone.countDown();
            if (!bothFindsDone.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待两个线程的 find() 都完成超时");
            }
            return result;
        }

        Object aroundSave(ProjectProfileField entity, Callable<Object> realSave) throws Exception {
            if (firstClaimed.compareAndSet(false, true)) {
                firstFieldValue.set(entity.getFieldValue());
                firstUid.set(entity.getUid());
                try {
                    return realSave.call();
                } finally {
                    firstSaveDispatched.countDown();
                }
            }
            if (!firstSaveDispatched.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待第一个请求调用 save() 超时");
            }
            return realSave.call();
        }
    }

    @Autowired
    private ProjectProfileService service;
    @Autowired
    private ProjectProfileFieldRepository repository;

    /** 真 WebTools 的 @PostConstruct 会起线程预热 Playwright，全量启动上下文时挡掉。 */
    @MockBean
    private WebTools webTools;

    @AfterEach
    void tearDown() {
        ACTIVE_ARBITER.set(null);
    }

    @Test
    void 并发写同一字段不抛异常_库里留后到的值_uid用先插那行的() throws Exception {
        long projectId = 9001L;
        // 不依赖真实 Project 行：projectId 是裸 Long 外键，saveUserFieldTx 找不到项目时
        // project 变量为 null，对 fieldKey=client 的写入路径没有任何影响。
        RaceArbiter arbiter = new RaceArbiter();
        ACTIVE_ARBITER.set(arbiter);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<java.util.Map<String, Object>> f1 =
                    pool.submit(() -> service.saveUserField(projectId, "client", "并发值-A"));
            Future<java.util.Map<String, Object>> f2 =
                    pool.submit(() -> service.saveUserField(projectId, "client", "并发值-B"));

            // 断言的核心：两次并发写都必须成功返回，任何一边抛异常（无论是原始的
            // DataIntegrityViolationException 还是修复前实测出现的 UnexpectedRollbackException）
            // 都要让这两行直接失败，而不是被 ExecutionException 悄悄吞掉。
            java.util.Map<String, Object> r1 = f1.get(15, TimeUnit.SECONDS);
            java.util.Map<String, Object> r2 = f2.get(15, TimeUnit.SECONDS);
            assertNotNull(r1);
            assertNotNull(r2);
        } finally {
            pool.shutdownNow();
        }

        List<ProjectProfileField> rows = repository.findByProjectId(projectId);
        assertEquals(1, rows.size(), "唯一约束下最终只应该留一行");
        ProjectProfileField finalRow = rows.get(0);

        String expectedFinalValue = "并发值-A".equals(arbiter.firstFieldValue.get()) ? "并发值-B" : "并发值-A";
        assertEquals(expectedFinalValue, finalRow.getFieldValue(),
                "库里应该留后到的那次写入——先插入那行随后被重试路径的更新覆盖了字段值");
        assertEquals(arbiter.firstUid.get(), finalRow.getUid(),
                "uid 必须是先插入那行的，不是重试时新生成的那个");
    }
}
