package com.checkba.service.sms;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SmsAuthServiceTest {

    private static final SmsTransport OK_TRANSPORT =
            (url, body) -> new SmsTransport.Reply(200, "{\"Code\":\"OK\"}");

    private static SmsService enabledSms(SmsTransport transport) {
        return new SmsService(transport, true, "ak", "sk", "sign", "tpl");
    }

    private static User user(long id, String phone) {
        User u = new User();
        u.setId(id);
        u.setUsername("alice");
        u.setPhone(phone);
        return u;
    }

    @Test
    @DisplayName("local-mode 恒未启用；server 模式看 SmsService 配置")
    void activeGating() {
        UserRepository repo = mock(UserRepository.class);
        SmsCodeStore store = new SmsCodeStore();
        assertFalse(new SmsAuthService(enabledSms(OK_TRANSPORT), store, repo, true).active(),
                "local-mode 必须旁路");
        assertTrue(new SmsAuthService(enabledSms(OK_TRANSPORT), store, repo, false).active());
        SmsService disabled = new SmsService(OK_TRANSPORT, false, "ak", "sk", "sign", "tpl");
        assertFalse(new SmsAuthService(disabled, store, repo, false).active());
    }

    @Test
    @DisplayName("requiresCode：启用且已绑手机号才要求；存量未绑定用户不拦")
    void requiresCodeOnlyWithPhone() {
        SmsAuthService svc = new SmsAuthService(
                enabledSms(OK_TRANSPORT), new SmsCodeStore(), mock(UserRepository.class), false);
        assertTrue(svc.requiresCode(user(1, "13800000000")));
        assertFalse(svc.requiresCode(user(1, null)));
        assertFalse(svc.requiresCode(user(1, "")));
        assertFalse(svc.requiresCode(null));
    }

    @Test
    @DisplayName("登录码全流程：发码（脱敏返回）→ 核销放行；错码抛业务错误")
    void loginCodeRoundTrip() {
        AtomicReference<String> sentBody = new AtomicReference<>();
        SmsCodeStore store = new SmsCodeStore();
        SmsAuthService svc = new SmsAuthService(enabledSms((url, body) -> {
            sentBody.set(body);
            return new SmsTransport.Reply(200, "{\"Code\":\"OK\"}");
        }), store, mock(UserRepository.class), false);

        User alice = user(1, "13800000000");
        assertEquals("138****0000", svc.sendLoginCode(alice));
        assertNotNull(sentBody.get());

        assertThrows(IllegalArgumentException.class, () -> svc.verifyLoginCode(alice, "000000"));
        // 从请求体反解真码（6 位数字模板参数）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%22code%22%3A%22(\\d{6})%22")
                .matcher(sentBody.get());
        assertTrue(m.find(), "请求体应含验证码模板参数: " + sentBody.get());
        assertDoesNotThrow(() -> svc.verifyLoginCode(alice, m.group(1)));
    }

    @Test
    @DisplayName("发送失败回滚冷却：网关拒绝后可立即重试")
    void failedSendRollsBackCooldown() {
        SmsCodeStore store = new SmsCodeStore();
        AtomicReference<Boolean> fail = new AtomicReference<>(true);
        SmsAuthService svc = new SmsAuthService(enabledSms((url, body) ->
                fail.get() ? new SmsTransport.Reply(500, "boom")
                           : new SmsTransport.Reply(200, "{\"Code\":\"OK\"}")),
                store, mock(UserRepository.class), false);
        User alice = user(1, "13800000000");
        assertThrows(IllegalArgumentException.class, () -> svc.sendLoginCode(alice));
        fail.set(false);
        assertDoesNotThrow(() -> svc.sendLoginCode(alice), "失败的发送不该占用冷却期");
    }

    @Test
    @DisplayName("绑定：手机号被他人占用即拒（发码与确认两处都查）")
    void bindRejectsPhoneBoundByOther() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByPhone("13800000000")).thenReturn(Optional.of(user(99, "13800000000")));
        SmsAuthService svc = new SmsAuthService(enabledSms(OK_TRANSPORT), new SmsCodeStore(), repo, false);
        assertThrows(IllegalArgumentException.class, () -> svc.sendBindCode(1L, "13800000000"));
        assertThrows(IllegalArgumentException.class, () -> svc.confirmBind(1L, "13800000000", "123456"));
    }

    @Test
    @DisplayName("绑定确认：正码落库并返回脱敏号；同一用户重绑自己的号不算冲突")
    void confirmBindPersistsPhone() {
        UserRepository repo = mock(UserRepository.class);
        AtomicReference<String> sentBody = new AtomicReference<>();
        User alice = user(1, null);
        when(repo.findByPhone("13800000000")).thenReturn(Optional.of(alice)); // 自己占用自己：放行
        when(repo.findById(1L)).thenReturn(Optional.of(alice));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsAuthService svc = new SmsAuthService(enabledSms((url, body) -> {
            sentBody.set(body);
            return new SmsTransport.Reply(200, "{\"Code\":\"OK\"}");
        }), new SmsCodeStore(), repo, false);

        svc.sendBindCode(1L, "13800000000");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%22code%22%3A%22(\\d{6})%22")
                .matcher(sentBody.get());
        assertTrue(m.find());
        assertEquals("138****0000", svc.confirmBind(1L, "13800000000", m.group(1)));
        assertEquals("13800000000", alice.getPhone());
        verify(repo).save(alice);
    }

    @Test
    @DisplayName("手机号格式：非大陆手机号拒绝；空白剥离后再校验")
    void phoneFormatValidated() {
        SmsAuthService svc = new SmsAuthService(
                enabledSms(OK_TRANSPORT), new SmsCodeStore(), mock(UserRepository.class), false);
        assertThrows(IllegalArgumentException.class, () -> svc.sendBindCode(1L, "12345"));
        assertThrows(IllegalArgumentException.class, () -> svc.sendBindCode(1L, "23800000000"));
        assertThrows(IllegalArgumentException.class, () -> svc.sendBindCode(1L, null));
        assertDoesNotThrow(() -> svc.sendBindCode(1L, " 138 0000 0000 "));
    }

    @Test
    @DisplayName("未启用时绑定类操作一律业务错误，且文案不踩掉线三子串")
    void inactiveRejectsBindOperations() {
        SmsAuthService svc = new SmsAuthService(
                enabledSms(OK_TRANSPORT), new SmsCodeStore(), mock(UserRepository.class), true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.sendBindCode(1L, "13800000000"));
        assertFalse(e.getMessage().contains("登录"));
        assertFalse(e.getMessage().contains("未授权"));
        assertFalse(e.getMessage().contains("请先"));
        // 未启用时登录核销直接放行（requiresCode=false 的对偶行为）
        assertDoesNotThrow(() -> svc.verifyLoginCode(user(1, "13800000000"), null));
    }

    @Test
    @DisplayName("maskPhone：标准脱敏；异常长度回空串（Map.of 不收 null）")
    void maskPhoneShape() {
        assertEquals("138****0000", SmsAuthService.maskPhone("13800000000"));
        assertEquals("", SmsAuthService.maskPhone(null));
        assertEquals("", SmsAuthService.maskPhone("123"));
    }
}
