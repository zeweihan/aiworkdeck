package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 平台通道的身份作用域。这里守的是「这次 AI 调用花的是谁的额度」——
 * 传丢了不会静默记错账（{@link PlatformAiChannel} 在多租户下会拒绝），但会让功能不可用，
 * 所以嵌套与跨线程重放两条都要有护栏。
 */
class PlatformAiUserScopeTest {

    @Test
    @DisplayName("作用域内可见，退出后清空")
    void setAndClear() {
        assertNull(PlatformAiUserScope.current());
        PlatformAiUserScope.run(7L, () -> assertEquals(7L, PlatformAiUserScope.current()));
        assertNull(PlatformAiUserScope.current());
    }

    @Test
    @DisplayName("嵌套：退出内层恢复外层，而不是清空")
    void nestedRestoresOuter() {
        PlatformAiUserScope.run(1L, () -> {
            PlatformAiUserScope.run(2L, () -> assertEquals(2L, PlatformAiUserScope.current()));
            assertEquals(1L, PlatformAiUserScope.current(), "内层退出后必须恢复外层身份");
        });
    }

    @Test
    @DisplayName("抛异常也要还原（否则线程池里的下一个任务会顶着别人的身份跑）")
    void restoresOnException() {
        assertThrows(RuntimeException.class, () -> PlatformAiUserScope.run(3L, () -> {
            throw new RuntimeException("boom");
        }));
        assertNull(PlatformAiUserScope.current());
    }

    @Test
    @DisplayName("跨线程不自动传递：不 wrap 就是 null（这正是每个异步点必须显式重放的原因）")
    void notInheritedByPoolThreads() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Long seen = PlatformAiUserScope.call(5L, () -> {
                try {
                    return pool.submit(PlatformAiUserScope::current).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertNull(seen);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("wrap 显式重放：池线程上拿到提交者的身份")
    void wrapReplaysIdentity() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Long seen = PlatformAiUserScope.call(5L, () -> {
                try {
                    return pool.submit(PlatformAiUserScope.wrap(PlatformAiUserScope::current)).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertEquals(5L, seen);
            // 任务跑完后池线程不得残留身份
            assertNull(pool.submit(PlatformAiUserScope::current).get());
        } finally {
            pool.shutdownNow();
        }
    }
}
