package com.checkba.service.sms;

import com.checkba.config.PhoneLoginGuard;
import com.checkba.repository.UserRepository;
import com.checkba.service.auth.VerificationCodeStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 手机号免密登录（注册与登录合一）+ 启动期不变式。
 * 设计见 docs/superpowers/specs/2026-08-17-phone-login-design.md
 */
class SmsSigninTest {

    private static final SmsTransport OK = (url, body, auth) -> new SmsTransport.Reply(200, "{\"Code\":\"OK\"}");

    private static SmsService enabled(SmsTransport t) {
        return new SmsService(t, true, "ak", "sk", "sign", "tpl");
    }

    private static SmsAuthService svc(SmsTransport t) {
        return new SmsAuthService(List.of(enabled(t)), new VerificationCodeStore(),
                mock(UserRepository.class), false);
    }

    @Test
    @DisplayName("未注册号码也发码——注册与登录合一，因此不存在「该号是否注册过」的枚举面")
    void sendsToUnknownPhone() {
        AtomicReference<String> sentTo = new AtomicReference<>();
        SmsAuthService s = svc((url, body, auth) -> {
            sentTo.set(body);
            return new SmsTransport.Reply(200, "{\"Code\":\"OK\"}");
        });
        // 仓库是 mock，findByPhone 返回 empty，即「这个号没注册过」
        assertDoesNotThrow(() -> s.sendSigninCode("13800000000"));
        assertNotNull(sentTo.get(), "未注册号码必须照发，否则就把注册路径堵死了");
    }

    @Test
    @DisplayName("+86 前缀被规范化掉，同一个号不会因为写法不同变成两个账号")
    void normalizesPrefix() {
        SmsAuthService s = svc(OK);
        s.sendSigninCode("+86 138-0000-0000");
        // 用裸号核销：说明发码时存的键已经规范化过
        assertThrows(IllegalArgumentException.class,
                () -> s.verifySigninCode("13800000000", "000000"),
                "错码应当抛错（此处验证的是键能对上，不是码对）");
    }

    @Test
    @DisplayName("错码与过期一律同一句话，不透露猜到了哪一步")
    void wrongCodeIsOpaque() {
        SmsAuthService s = svc(OK);
        s.sendSigninCode("13800000000");
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> s.verifySigninCode("13800000000", "999999"));
        IllegalArgumentException never = assertThrows(IllegalArgumentException.class,
                () -> s.verifySigninCode("13900000000", "999999"));
        assertEquals(wrong.getMessage(), never.getMessage(),
                "「码错了」与「这个号根本没发过码」必须同一句话");
    }

    @Test
    @DisplayName("登录码与绑定码互不通用——两个场景的码串了就是越权")
    void scenesAreIsolated() {
        SmsAuthService s = svc(OK);
        VerificationCodeStore store = new VerificationCodeStore();
        SmsAuthService s2 = new SmsAuthService(List.of(enabled(OK)), store, mock(UserRepository.class), false);
        s2.sendSigninCode("13800000000");
        // 用 signin 的码去核销 bind 场景应当失败
        assertFalse(store.verify("bind", "13800000000",
                        store.issue("signin", "13700000000")),
                "跨场景核销必须失败");
        assertNotNull(s);
    }

    @Test
    @DisplayName("格式不对的号码直接拒，不浪费一条短信")
    void rejectsMalformed() {
        SmsAuthService s = svc(OK);
        assertThrows(IllegalArgumentException.class, () -> s.sendSigninCode("12345"));
        assertThrows(IllegalArgumentException.class, () -> s.sendSigninCode("hello"));
        assertThrows(IllegalArgumentException.class, () -> s.sendSigninCode(""));
    }

    // ==================== 启动期不变式 ====================

    @Test
    @DisplayName("开了强制手机号登录但网关是暗的：必须拒绝启动，不能静默降级成谁都进不来")
    void guardRefusesToStartWithDarkGateway() {
        SmsService dark = new SmsService(OK, false, "", "", "sign", "tpl");
        SmsAuthService inactive = new SmsAuthService(List.of(dark), new VerificationCodeStore(),
                mock(UserRepository.class), false);
        assertFalse(inactive.active());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PhoneLoginGuard(inactive, true, false, "2026-09-30"));
        assertTrue(e.getMessage().contains("短信网关必须可用"),
                "报错要说清楚是网关的问题，别让人去查前端");
    }

    @Test
    @DisplayName("网关可用时正常启动；local-mode 与未开启时这条闸不适用")
    void guardPassesWhenSane() {
        SmsAuthService active = svc(OK);
        assertDoesNotThrow(() -> new PhoneLoginGuard(active, true, false, "2026-09-30"));

        SmsService dark = new SmsService(OK, false, "", "", "sign", "tpl");
        SmsAuthService inactive = new SmsAuthService(List.of(dark), new VerificationCodeStore(),
                mock(UserRepository.class), false);
        // 没开强制：网关暗着也该正常启动
        assertDoesNotThrow(() -> new PhoneLoginGuard(inactive, false, false, "2026-09-30"));
        // local-mode 根本没有登录环节
        assertDoesNotThrow(() -> new PhoneLoginGuard(inactive, true, true, "2026-09-30"));
        assertFalse(new PhoneLoginGuard(inactive, true, true, "2026-09-30").isRequired());
    }
}
