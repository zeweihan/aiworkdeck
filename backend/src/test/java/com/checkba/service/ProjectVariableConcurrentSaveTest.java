package com.checkba.service;

import com.checkba.model.entity.ProjectVariable;
import com.checkba.repository.ProjectVariableRepository;
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
 * dev-board#74 审计条目：createOrUpdateVariable 的 TOCTOU 撞唯一约束报通用 500。
 *
 * 走法与 {@code ProjectProfileFieldConcurrentSaveTest} 完全一致（同一个坑、同一套修法）：
 * 真 Spring 容器 + 真 H2 + 真 (project_id, name) 唯一约束 + 真事务，代理包在
 * {@link ProjectVariableRepository} 前面，在 findByProjectIdAndName / save 两个方法上
 * 做仲裁，逼出两次并发写都读到空 Optional、都尝试 INSERT 的真实竞态窗口，而不是靠线程
 * 调度的运气——没有仲裁的话，先起步的线程可能在后起步的线程查库之前就已经提交，
 * 后者的 find 会直接读到那一行走更新分支，竞态窗口被跳过，断言却可能照样通过（假阳性）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-variable-concurrent-save;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("desktop")
class ProjectVariableConcurrentSaveTest {

    static final AtomicReference<RaceArbiter> ACTIVE_ARBITER = new AtomicReference<>();

    @TestConfiguration
    static class RacingRepositoryConfig {

        @Bean
        @Primary
        ProjectVariableRepository racingProjectVariableRepository(
                @Qualifier("projectVariableRepository") ProjectVariableRepository real) {
            InvocationHandler handler = (proxy, method, args) -> {
                RaceArbiter arbiter = ACTIVE_ARBITER.get();
                if (arbiter != null && "save".equals(method.getName())
                        && args != null && args.length == 1 && args[0] instanceof ProjectVariable) {
                    ProjectVariable entity = (ProjectVariable) args[0];
                    return arbiter.aroundSave(entity, () -> invokeReal(real, method, args));
                }
                if (arbiter != null && "findByProjectIdAndName".equals(method.getName())
                        && args != null && args.length == 2) {
                    return arbiter.aroundFind(() -> invokeReal(real, method, args));
                }
                return invokeReal(real, method, args);
            };
            return (ProjectVariableRepository) Proxy.newProxyInstance(
                    ProjectVariableRepository.class.getClassLoader(),
                    new Class<?>[]{ProjectVariableRepository.class},
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

    static final class RaceArbiter {
        private final AtomicBoolean firstClaimed = new AtomicBoolean(false);
        private final CountDownLatch firstSaveDispatched = new CountDownLatch(1);
        private final AtomicReference<String> firstValue = new AtomicReference<>();
        private final CountDownLatch bothFindsDone = new CountDownLatch(2);

        Object aroundFind(Callable<Object> realFind) throws Exception {
            Object result = realFind.call();
            bothFindsDone.countDown();
            if (!bothFindsDone.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待两个线程的 find() 都完成超时");
            }
            return result;
        }

        Object aroundSave(ProjectVariable entity, Callable<Object> realSave) throws Exception {
            if (firstClaimed.compareAndSet(false, true)) {
                firstValue.set(entity.getValue());
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
    private ProjectVariableService service;
    @Autowired
    private ProjectVariableRepository repository;

    @AfterEach
    void tearDown() {
        ACTIVE_ARBITER.set(null);
    }

    private static ProjectVariable variable(Long projectId, String name, String value) {
        ProjectVariable v = new ProjectVariable();
        v.setProjectId(projectId);
        v.setName(name);
        v.setValue(value);
        v.setType("TEXT");
        return v;
    }

    @Test
    void 并发创建同名变量不抛异常_库里只留一行() throws Exception {
        long projectId = 9101L;
        RaceArbiter arbiter = new RaceArbiter();
        ACTIVE_ARBITER.set(arbiter);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProjectVariable> f1 = pool.submit(
                    () -> service.createOrUpdateVariable(variable(projectId, "甲方名称", "并发值-A")));
            Future<ProjectVariable> f2 = pool.submit(
                    () -> service.createOrUpdateVariable(variable(projectId, "甲方名称", "并发值-B")));

            // 断言的核心：两次并发写都必须成功返回，不能有一边被 catch(Exception) 兜成
            // {code:1,"服务器内部错误"}（GlobalExceptionHandler 会把 DataIntegrityViolationException
            // /UnexpectedRollbackException 都吞成这个通用错误，这里绕过控制器直接测服务层，
            // 异常会原样从 Future.get() 抛出）。
            ProjectVariable r1 = f1.get(15, TimeUnit.SECONDS);
            ProjectVariable r2 = f2.get(15, TimeUnit.SECONDS);
            assertNotNull(r1);
            assertNotNull(r2);
        } finally {
            pool.shutdownNow();
        }

        List<ProjectVariable> rows = repository.findByProjectId(projectId);
        assertEquals(1, rows.size(), "唯一约束下最终只应该留一行: " + rows);

        String expectedFinalValue = "并发值-A".equals(arbiter.firstValue.get()) ? "并发值-B" : "并发值-A";
        assertEquals(expectedFinalValue, rows.get(0).getValue(),
                "库里应该留后到的那次写入——先插入那行随后被重试路径的更新覆盖了值");
    }
}
