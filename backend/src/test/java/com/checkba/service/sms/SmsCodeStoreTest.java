package com.checkba.service.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SmsCodeStoreTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final SmsCodeStore store = new SmsCodeStore(clock::get);

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
        clock.addAndGet(SmsCodeStore.TTL.toMillis() + 1);
        assertFalse(store.verify("login", "13800000000", code));
    }

    @Test
    @DisplayName("冷却期内重发被拒；冷却期过后可重发且旧码作废")
    void resendCooldown() {
        String first = store.issue("login", "13800000000");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.issue("login", "13800000000"));
        assertTrue(e.getMessage().contains("频繁"));

        clock.addAndGet(SmsCodeStore.RESEND_COOLDOWN.toMillis() + 1);
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
        for (int i = 0; i < SmsCodeStore.MAX_ATTEMPTS; i++) {
            assertFalse(store.verify("login", "13800000000", "000000".equals(code) ? "111111" : "000000"));
        }
        assertFalse(store.verify("login", "13800000000", code));
    }

    @Test
    @DisplayName("单手机号日上限：第 11 条被拒，跨天窗口滚动后恢复")
    void dailyCapPerPhone() {
        for (int i = 0; i < SmsCodeStore.MAX_PER_PHONE_PER_DAY; i++) {
            store.issue("login", "13800000000");
            clock.addAndGet(SmsCodeStore.RESEND_COOLDOWN.toMillis() + 1);
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
}
