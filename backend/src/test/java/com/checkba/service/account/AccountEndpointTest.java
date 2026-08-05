package com.checkba.service.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定官网地址的传输安全红线：明文 awdk_ Key 只允许走 https 或回环 http。
 * 这里最要紧的是**前缀不能当主机判据**——127.0.0.1.evil.com 这类域名会骗过 startsWith。
 */
class AccountEndpointTest {

    @Test
    @DisplayName("https 放行并去掉尾部斜杠")
    void httpsAllowed() {
        assertEquals("https://www.aiworkdeck.com", AccountEndpoint.requireSecure("https://www.aiworkdeck.com"));
        assertEquals("https://www.aiworkdeck.com", AccountEndpoint.requireSecure("https://www.aiworkdeck.com/"));
        assertEquals("https://WWW.AIWORKDECK.COM", AccountEndpoint.requireSecure("https://WWW.AIWORKDECK.COM"));
    }

    @Test
    @DisplayName("回环 http 放行：本地起官网联调")
    void loopbackHttpAllowed() {
        assertEquals("http://localhost:3000", AccountEndpoint.requireSecure("http://localhost:3000"));
        assertEquals("http://127.0.0.1:3000", AccountEndpoint.requireSecure("http://127.0.0.1:3000"));
        assertEquals("http://127.0.0.53", AccountEndpoint.requireSecure("http://127.0.0.53"));
        assertEquals("http://[::1]:3000", AccountEndpoint.requireSecure("http://[::1]:3000"));
    }

    @Test
    @DisplayName("非回环 http 一律拒绝")
    void remoteHttpRejected() {
        assertThrows(IllegalArgumentException.class, () -> AccountEndpoint.requireSecure("http://www.aiworkdeck.com"));
        assertThrows(IllegalArgumentException.class, () -> AccountEndpoint.requireSecure("http://192.168.1.10:3000"));
    }

    @Test
    @DisplayName("伪装成回环的域名必须拒绝（前缀匹配会放行它们）")
    void loopbackLookalikesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AccountEndpoint.requireSecure("http://127.0.0.1.evil.example"));
        assertThrows(IllegalArgumentException.class,
                () -> AccountEndpoint.requireSecure("http://localhost.evil.example"));
    }

    @Test
    @DisplayName("空值与非法 URL 拒绝")
    void invalidRejected() {
        assertThrows(IllegalArgumentException.class, () -> AccountEndpoint.requireSecure(null));
        assertThrows(IllegalArgumentException.class, () -> AccountEndpoint.requireSecure(""));
        assertThrows(IllegalArgumentException.class, () -> AccountEndpoint.requireSecure("www.aiworkdeck.com"));
    }
}
