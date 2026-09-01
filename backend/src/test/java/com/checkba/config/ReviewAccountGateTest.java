package com.checkba.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核账号旁路。这是认证旁路，所以「关着的时候确实是关的」比「开着的时候能用」
 * 更值得测——前者错了是安全事故，后者错了只是审核员登不进去。
 */
class ReviewAccountGateTest {

    @Test
    @DisplayName("两项都空 = 关闭，任何标识与码都不放行")
    void disabledByDefault() {
        ReviewAccountGate gate = new ReviewAccountGate("", "");
        assertFalse(gate.enabled());
        assertFalse(gate.matches("appreview@example.com"));
        assertFalse(gate.accepts("appreview@example.com", "246813"));
    }

    @Test
    @DisplayName("只配了一半也算关闭，不存在半开状态")
    void halfConfiguredIsDisabled() {
        assertFalse(new ReviewAccountGate("appreview@example.com", "").enabled());
        assertFalse(new ReviewAccountGate("", "246813").enabled());
        assertFalse(new ReviewAccountGate(null, null).enabled());
    }

    @Test
    @DisplayName("码不是 6 位数字就拒绝启动")
    void refusesToStartOnMalformedCode() {
        assertThrows(IllegalStateException.class, () -> new ReviewAccountGate("a@b.com", "12345"));
        assertThrows(IllegalStateException.class, () -> new ReviewAccountGate("a@b.com", "abcdef"));
        assertThrows(IllegalStateException.class, () -> new ReviewAccountGate("a@b.com", "1234567"));
        assertDoesNotThrow(() -> new ReviewAccountGate("a@b.com", "123456"));
    }

    @Test
    @DisplayName("只对配置里列出的标识生效")
    void onlyMatchesConfiguredIdentity() {
        ReviewAccountGate gate = new ReviewAccountGate("appreview@example.com", "246813");
        assertTrue(gate.matches("appreview@example.com"));
        assertTrue(gate.matches("  AppReview@Example.com  "), "大小写与空白应被规范化");
        assertFalse(gate.matches("someoneelse@example.com"));
        assertFalse(gate.matches(""));
        assertFalse(gate.matches(null));
    }

    @Test
    @DisplayName("逗号分隔的多个标识共用同一个码：Apple 用邮箱、微信用手机号，两家并存")
    void matchesEveryConfiguredIdentity() {
        ReviewAccountGate gate = new ReviewAccountGate("appreview@example.com, 13800138000 ", "246813");
        assertTrue(gate.accepts("appreview@example.com", "246813"));
        assertTrue(gate.accepts("13800138000", "246813"), "加进来的手机号不该挤掉原有的邮箱");
        assertFalse(gate.accepts("13900000000", "246813"), "没列出的标识拿到码也不放行");
    }

    @Test
    @DisplayName("列表里的空项被丢弃，全空等于关闭")
    void ignoresBlankEntries() {
        ReviewAccountGate gate = new ReviewAccountGate("a@b.com,,  ,", "246813");
        assertTrue(gate.enabled());
        assertTrue(gate.matches("a@b.com"));
        assertFalse(gate.matches(""), "空标识永远不是审核账号");
        assertFalse(new ReviewAccountGate(" , ,", "246813").enabled());
    }

    @Test
    @DisplayName("认标识也要认码：标识对码错、码对标识错，都不放行")
    void requiresBothIdentityAndCode() {
        ReviewAccountGate gate = new ReviewAccountGate("13800000000", "246813");
        assertTrue(gate.accepts("13800000000", "246813"));
        assertTrue(gate.accepts("13800000000", " 246813 "));
        assertFalse(gate.accepts("13800000000", "000000"), "码错不该放行");
        assertFalse(gate.accepts("13900000000", "246813"), "别的号码拿到码也不该放行");
        assertFalse(gate.accepts("13800000000", null));
    }
}
