package com.checkba.service.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class VerificationCodeStoreTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final VerificationCodeStore store = new VerificationCodeStore(clock::get);

    @Test
    @DisplayName("签发-验证-核销：验证成功后同码立即失效（一次性）")
    void verifyConsumesCode() {
        String code = store.issue("login", "13800000000");
        assertEquals(6, code.length());
        assertTrue(store.verify("login", "13800000000", code));
        assertFalse(store.verify("login", "13800000000", code), "已核销的码不可复用");
    }

    @Test
    @DisplayName("scene 隔离：login 码不能用于 bind")
    void scenesAreIsolated() {
        String code = store.issue("login", "13800000000");
        assertFalse(store.verify("bind", "13800000000", code));
        assertTrue(store.verify("login", "13800000000", code));
    }

    @Test
    @DisplayName("过期即失效（5 分钟 TTL）")
    void expiryInvalidates() {
        String code = store.issue("login", "13800000000");
        clock.addAndGet(VerificationCodeStore.TTL.toMillis() + 1);
        assertFalse(store.verify("login", "13800000000", code));
    }

    @Test
    @DisplayName("冷却期内重发被拒；冷却期过后可重发且旧码作废")
    void resendCooldown() {
        String first = store.issue("login", "13800000000");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.issue("login", "13800000000"));
        assertTrue(e.getMessage().contains("频繁"));

        clock.addAndGet(VerificationCodeStore.RESEND_COOLDOWN.toMillis() + 1);
        String second = store.issue("login", "13800000000");
        if (!first.equals(second)) {
            assertFalse(store.verify("login", "13800000000", first), "重发后旧码应作废");
        }
        assertTrue(store.verify("login", "13800000000", second));
    }

    @Test
    @DisplayName("连续验错 5 次作废，正确码也救不回来（防在线爆破）")
    void attemptCapInvalidates() {
        String code = store.issue("login", "13800000000");
        for (int i = 0; i < VerificationCodeStore.MAX_ATTEMPTS; i++) {
            assertFalse(store.verify("login", "13800000000", "000000".equals(code) ? "111111" : "000000"));
        }
        assertFalse(store.verify("login", "13800000000", code));
    }

    @Test
    @DisplayName("单手机号日上限：第 11 条被拒，跨天窗口滚动后恢复")
    void dailyCapPerPhone() {
        for (int i = 0; i < VerificationCodeStore.MAX_PER_TARGET_PER_DAY; i++) {
            store.issue("login", "13800000000");
            clock.addAndGet(VerificationCodeStore.RESEND_COOLDOWN.toMillis() + 1);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.issue("login", "13800000000"));
        assertTrue(e.getMessage().contains("上限"));
        // 其他手机号不受影响
        assertDoesNotThrow(() -> store.issue("login", "13900000000"));
        // 24 小时后窗口滚动
        clock.addAndGet(java.time.Duration.ofHours(24).toMillis() + 1);
        assertDoesNotThrow(() -> store.issue("login", "13800000000"));
    }

    @Test
    @DisplayName("发送失败回收：invalidate 后立即重发不吃冷却")
    void invalidateClearsCooldown() {
        store.issue("login", "13800000000");
        store.invalidate("login", "13800000000");
        assertDoesNotThrow(() -> store.issue("login", "13800000000"));
    }

    @Test
    @DisplayName("空码/空白码一律 false")
    void blankCodeRejected() {
        store.issue("login", "13800000000");
        assertFalse(store.verify("login", "13800000000", null));
        assertFalse(store.verify("login", "13800000000", "  "));
    }

    // ---- 并发：签发与核销必须对同一 key 互斥 ----

    /**
     * 可控时钟兼交会点：武装后，第一个读表的线程停在原地，别的线程照常拿到时间。
     * 借它把「一个线程正停在临界区里」这件事做成确定性的，不靠 sleep 撞运气。
     */
    private static final class GateClock implements LongSupplier {
        volatile boolean armed = false;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger hits = new AtomicInteger();
        private final long fixed = 1_000_000L;

        @Override
        public long getAsLong() {
            if (armed && hits.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return fixed;
        }
    }

    @Test
    @DisplayName("并发核销互斥：一个线程在临界区里时另一个 verify 必须被挡住（否则 ++attempts 丢更新，5 次上限形同虚设）")
    void verifyIsAtomicPerKey() throws Exception {
        GateClock gate = new GateClock();
        VerificationCodeStore s = new VerificationCodeStore(gate);
        String code = s.issue("login", "13800000000");
        gate.armed = true;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> s.verify("login", "13800000000", "000000"));
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "第一个 verify 应当进入临界区");

            Future<Boolean> second = pool.submit(() -> s.verify("login", "13800000000", "111111"));
            assertThrows(TimeoutException.class, () -> second.get(600, TimeUnit.MILLISECONDS),
                    "同一 key 的 verify 必须互斥：读计数与写回计数之间放行第二个线程就会丢更新");

            gate.release.countDown();
            assertFalse(first.get(5, TimeUnit.SECONDS));
            assertFalse(second.get(5, TimeUnit.SECONDS));
            // 两次错都必须真的记上
            assertNotEquals(code, "000000");
            assertEquals(2, s.attemptsForTest("login", "13800000000"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发签发互斥：同一 key 两个线程同时进来，第二个必须撞上冷却而不是也发一条")
    void issueIsAtomicPerKey() throws Exception {
        GateClock gate = new GateClock();
        VerificationCodeStore s = new VerificationCodeStore(gate);
        gate.armed = true;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> s.issue("login", "13800000000"));
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "第一个 issue 应当进入临界区");

            Future<String> second = pool.submit(() -> s.issue("login", "13800000000"));
            assertThrows(TimeoutException.class, () -> second.get(600, TimeUnit.MILLISECONDS),
                    "同一 key 的签发必须互斥：查冷却与写新码之间放行第二个线程，两条码就会一起发出去");

            gate.release.countDown();
            assertNotNull(first.get(5, TimeUnit.SECONDS));
            ExecutionException boom = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertTrue(boom.getCause().getMessage().contains("频繁"), "第二个线程应被冷却挡下");
            // 冷却挡下的那次不该算进当日条数
            assertEquals(1, s.dailySendsForTest("13800000000"));
        } finally {
            pool.shutdownNow();
        }
    }
}
