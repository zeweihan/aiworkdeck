package com.checkba.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定注册闸与防滥用限速的契约（插件云后端加固）：
 * - 注册闸 open/closed 两态，local-mode 旁路；
 * - 5 次失败锁 10 分钟，到期自动解除，成功清零；
 * - 注册按 IP 限频；
 * - 所有失败文案不含「登录」「未授权」「请先」子串（前端 api.js 据此清会话）。
 */
class AuthAbuseGuardTest {

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    private AuthAbuseGuard serverGuard(String registrationMode) {
        return new AuthAbuseGuard(false, registrationMode, now::get);
    }

    private void advance(Duration d) {
        now.addAndGet(d.toMillis());
    }

    private static void assertNotMistakenForLogout(String message) {
        assertNotNull(message);
        assertFalse(message.contains("登录"), "文案不得含「登录」: " + message);
        assertFalse(message.contains("未授权"), "文案不得含「未授权」: " + message);
        assertFalse(message.contains("请先"), "文案不得含「请先」: " + message);
    }

    // ==================== 注册闸 ====================

    @Test
    @DisplayName("registration-mode=closed：注册被业务错误拒绝，文案不像掉线")
    void closedRegistrationRejected() {
        AuthAbuseGuard guard = serverGuard("closed");
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, guard::requireRegistrationOpen);
        assertTrue(e.getMessage().contains("未开放自助注册"), e.getMessage());
        assertNotMistakenForLogout(e.getMessage());
    }

    @Test
    @DisplayName("registration-mode=open（默认）：放行")
    void openRegistrationAllowed() {
        assertDoesNotThrow(serverGuard("open")::requireRegistrationOpen);
    }

    @Test
    @DisplayName("local-mode：closed 也不影响（单机产品不受注册闸约束）")
    void localModeBypassesRegistrationGate() {
        AuthAbuseGuard guard = new AuthAbuseGuard(true, "closed", now::get);
        assertDoesNotThrow(guard::requireRegistrationOpen);
    }

    // ==================== 登录失败锁定 ====================

    @Test
    @DisplayName("5 次失败后锁定，10 分钟后自动解除")
    void fiveFailuresLockThenAutoUnlock() {
        AuthAbuseGuard guard = serverGuard("open");
        for (int i = 0; i < 4; i++) {
            guard.recordLoginFailure("1.2.3.4", "alice");
            assertDoesNotThrow(() -> guard.checkLoginAttempt("1.2.3.4", "alice"));
        }
        guard.recordLoginFailure("1.2.3.4", "alice");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guard.checkLoginAttempt("1.2.3.4", "alice"));
        assertNotMistakenForLogout(e.getMessage());

        advance(Duration.ofMinutes(10).plusMillis(1));
        assertDoesNotThrow(() -> guard.checkLoginAttempt("1.2.3.4", "alice"));
    }

    @Test
    @DisplayName("锁定按 IP+用户名维度：其他用户名与其他 IP 不受影响")
    void lockIsScopedToIpAndUsername() {
        AuthAbuseGuard guard = serverGuard("open");
        for (int i = 0; i < 5; i++) {
            guard.recordLoginFailure("1.2.3.4", "alice");
        }
        assertThrows(IllegalArgumentException.class,
                () -> guard.checkLoginAttempt("1.2.3.4", "alice"));
        assertDoesNotThrow(() -> guard.checkLoginAttempt("1.2.3.4", "bob"));
        assertDoesNotThrow(() -> guard.checkLoginAttempt("5.6.7.8", "alice"));
    }

    @Test
    @DisplayName("成功登录清空失败计数")
    void successClearsFailureCounter() {
        AuthAbuseGuard guard = serverGuard("open");
        for (int i = 0; i < 4; i++) {
            guard.recordLoginFailure("1.2.3.4", "alice");
        }
        guard.recordLoginSuccess("1.2.3.4", "alice");
        // 清零后再来 4 次失败也不该锁
        for (int i = 0; i < 4; i++) {
            guard.recordLoginFailure("1.2.3.4", "alice");
        }
        assertDoesNotThrow(() -> guard.checkLoginAttempt("1.2.3.4", "alice"));
    }

    @Test
    @DisplayName("失败计数窗口：距上次失败超 10 分钟则归零重来")
    void staleFailuresExpire() {
        AuthAbuseGuard guard = serverGuard("open");
        for (int i = 0; i < 4; i++) {
            guard.recordLoginFailure("1.2.3.4", "alice");
        }
        advance(Duration.ofMinutes(11));
        guard.recordLoginFailure("1.2.3.4", "alice"); // 窗口已过，这是新一轮的第 1 次
        assertDoesNotThrow(() -> guard.checkLoginAttempt("1.2.3.4", "alice"));
    }

    @Test
    @DisplayName("local-mode：失败再多也不锁")
    void localModeBypassesLockout() {
        AuthAbuseGuard guard = new AuthAbuseGuard(true, "open", now::get);
        for (int i = 0; i < 20; i++) {
            guard.recordLoginFailure("1.2.3.4", "alice");
        }
        assertDoesNotThrow(() -> guard.checkLoginAttempt("1.2.3.4", "alice"));
    }

    // ==================== 注册限频 ====================

    @Test
    @DisplayName("同 IP 注册超限被拒，窗口过后恢复")
    void registrationRateLimitPerIp() {
        AuthAbuseGuard guard = serverGuard("open");
        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> guard.checkRegistrationRate("1.2.3.4"));
            guard.recordRegistration("1.2.3.4");
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guard.checkRegistrationRate("1.2.3.4"));
        assertNotMistakenForLogout(e.getMessage());
        // 其他 IP 不受影响
        assertDoesNotThrow(() -> guard.checkRegistrationRate("5.6.7.8"));

        advance(Duration.ofHours(1).plusMillis(1));
        assertDoesNotThrow(() -> guard.checkRegistrationRate("1.2.3.4"));
    }

    // ==================== 成员查询限频（dev-board#444） ====================

    /**
     * lookup 与 addMember 是同一个探测面（「这个手机号注册过没有」），共用一个计数：
     * 分开计就等于把额度翻倍，攻击者交替调两个端点即可。
     */
    @Test
    @DisplayName("同一管理员查人超限被拒，窗口过后恢复；lookup 与 addMember 共用计数")
    void memberLookupRateLimitPerRequester() {
        AuthAbuseGuard guard = serverGuard("open");
        for (int i = 0; i < 30; i++) {
            assertDoesNotThrow(() -> guard.checkMemberLookupRate(77L));
            guard.recordMemberLookup(77L);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guard.checkMemberLookupRate(77L));
        assertNotMistakenForLogout(e.getMessage());
        // 别的管理员不受影响
        assertDoesNotThrow(() -> guard.checkMemberLookupRate(88L));

        advance(Duration.ofMinutes(10).plusMillis(1));
        assertDoesNotThrow(() -> guard.checkMemberLookupRate(77L));
    }

    @Test
    @DisplayName("local-mode：查人不限频（单机产品没有探测面）")
    void localModeBypassesMemberLookupRate() {
        AuthAbuseGuard guard = new AuthAbuseGuard(true, "open", now::get);
        for (int i = 0; i < 100; i++) {
            guard.recordMemberLookup(77L);
        }
        assertDoesNotThrow(() -> guard.checkMemberLookupRate(77L));
    }
}
