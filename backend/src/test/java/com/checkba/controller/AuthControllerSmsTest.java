package com.checkba.controller;

import com.checkba.model.entity.User;
import com.checkba.service.AuthAbuseGuard;
import com.checkba.service.UserService;
import com.checkba.repository.UserRepository;
import com.checkba.service.auth.SecondFactorService;
import com.checkba.service.sms.SmsAuthService;
import com.checkba.service.totp.TotpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 登录短信二次验证的 AuthController 接线：4005 信封、验证码核销挡在会话签发之前、
 * send-code 的 login 场景不成为免锁定的密码试探口。服务层语义见 SmsAuthServiceTest。
 */
class AuthControllerSmsTest {

    private static MockHttpServletRequest http() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("9.9.9.9");
        return request;
    }

    private static User boundUser() {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setDisplayName("Alice");
        user.setRole("USER");
        user.setSubscriptionType("FREE");
        user.setPhone("13800000000");
        return user;
    }

    private static AuthController.LoginRequest loginRequest(String smsCode) {
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("alice");
        request.setPassword("pw123456");
        request.setSmsCode(smsCode);
        return request;
    }

    private static AuthController controller(UserService userService, AuthAbuseGuard guard,
                                             SmsAuthService smsAuthService) {
        // 短信分支的接线测试：二次验证协调层用真实实现（TOTP 未启用、邮箱未绑 → 判定落到短信）
        SecondFactorService secondFactor = new SecondFactorService(
                new TotpService(), mock(com.checkba.service.mail.MailAuthService.class),
                smsAuthService, mock(UserRepository.class));
        // DB 会话服务（repository 打桩）：登录成功路径要经它签发 sessionId
        com.checkba.service.UserSessionService sessions = new com.checkba.service.UserSessionService(
                mock(com.checkba.repository.UserSessionRepository.class));
        return new AuthController(userService, null, null, null, guard, null, smsAuthService,
                mock(com.checkba.service.mail.MailAuthService.class), secondFactor, sessions, false);
    }

    @Test
    @DisplayName("需要短信验证且缺码：返回 4005 + 脱敏手机号，不签发会话")
    void missingCodeReturns4005WithoutSession() {
        UserService userService = mock(UserService.class);
        when(userService.login("alice", "pw123456")).thenReturn(boundUser());
        SmsAuthService sms = mock(SmsAuthService.class);
        when(sms.requiresCode(any())).thenReturn(true);

        AuthController controller = controller(userService, new AuthAbuseGuard(false, "open"), sms);
        Map<String, Object> result = controller.login(loginRequest(null), http());

        assertEquals(AuthController.CODE_SMS_REQUIRED, result.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("smsRequired"));
        assertEquals("138****0000", data.get("phoneMasked"));
        assertFalse(data.containsKey("sessionId"), "缺码不得签发会话");
        verify(sms, never()).verifyLoginCode(any(), anyString());
    }

    @Test
    @DisplayName("带正确验证码：核销通过后正常登录")
    void correctCodeLogsIn() {
        UserService userService = mock(UserService.class);
        when(userService.login("alice", "pw123456")).thenReturn(boundUser());
        SmsAuthService sms = mock(SmsAuthService.class);
        when(sms.requiresCode(any())).thenReturn(true);

        AuthController controller = controller(userService, new AuthAbuseGuard(false, "open"), sms);
        Map<String, Object> result = controller.login(loginRequest("123456"), http());

        assertEquals(0, result.get("code"));
        verify(sms).verifyLoginCode(any(), eq("123456"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertNotNull(data.get("sessionId"));
    }

    @Test
    @DisplayName("错码：业务错误信封，不签发会话，且计入失败锁定")
    void wrongCodeFailsAndCounts() {
        UserService userService = mock(UserService.class);
        when(userService.login("alice", "pw123456")).thenReturn(boundUser());
        SmsAuthService sms = mock(SmsAuthService.class);
        when(sms.requiresCode(any())).thenReturn(true);
        doThrow(new IllegalArgumentException("验证码错误或已过期"))
                .when(sms).verifyLoginCode(any(), eq("000000"));

        AuthAbuseGuard guard = new AuthAbuseGuard(false, "open");
        AuthController controller = controller(userService, guard, sms);
        for (int i = 0; i < 5; i++) {
            Map<String, Object> result = controller.login(loginRequest("000000"), http());
            assertEquals(1, result.get("code"));
        }
        // 连续错码后触发同一套失败锁定：锁定文案（不再是错码文案）
        Map<String, Object> locked = controller.login(loginRequest("000000"), http());
        assertEquals(1, locked.get("code"));
        assertTrue(String.valueOf(locked.get("message")).contains("锁定"));
    }

    @Test
    @DisplayName("未启用/未绑定（requiresCode=false）：登录行为与从前一字不差")
    void notRequiredKeepsLegacyBehavior() {
        UserService userService = mock(UserService.class);
        when(userService.login("alice", "pw123456")).thenReturn(boundUser());
        SmsAuthService sms = mock(SmsAuthService.class);
        when(sms.requiresCode(any())).thenReturn(false);

        AuthController controller = controller(userService, new AuthAbuseGuard(false, "open"), sms);
        Map<String, Object> result = controller.login(loginRequest(null), http());
        assertEquals(0, result.get("code"));
        verify(sms, never()).verifyLoginCode(any(), any());
    }

    @Test
    @DisplayName("send-code login 场景：凭据错误计入失败锁定（不是免锁定的密码试探口）")
    void sendCodeLoginSceneCountsCredentialFailures() {
        UserService userService = mock(UserService.class);
        when(userService.login(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("用户名或密码错误"));
        SmsAuthService sms = mock(SmsAuthService.class);

        AuthAbuseGuard guard = new AuthAbuseGuard(false, "open");
        AuthController controller = controller(userService, guard, sms);
        AuthController.SmsSendCodeRequest request = new AuthController.SmsSendCodeRequest();
        request.setScene("login");
        request.setUsername("alice");
        request.setPassword("wrong");

        for (int i = 0; i < 5; i++) {
            Map<String, Object> result = controller.sendSmsCode(request, null, http());
            assertEquals(1, result.get("code"));
        }
        // 第 6 次：还没碰 UserService 就被锁定闸拦下
        clearInvocations(userService);
        Map<String, Object> locked = controller.sendSmsCode(request, null, http());
        assertEquals(1, locked.get("code"));
        assertTrue(String.valueOf(locked.get("message")).contains("锁定"));
        verify(userService, never()).login(anyString(), anyString());
    }

    @Test
    @DisplayName("send-code login 场景：凭据正确即发码并返回脱敏手机号")
    void sendCodeLoginSceneSends() {
        UserService userService = mock(UserService.class);
        when(userService.login("alice", "pw123456")).thenReturn(boundUser());
        SmsAuthService sms = mock(SmsAuthService.class);
        when(sms.sendLoginCode(any())).thenReturn("138****0000");

        AuthController controller = controller(userService, new AuthAbuseGuard(false, "open"), sms);
        AuthController.SmsSendCodeRequest request = new AuthController.SmsSendCodeRequest();
        request.setScene("login");
        request.setUsername("alice");
        request.setPassword("pw123456");

        Map<String, Object> result = controller.sendSmsCode(request, null, http());
        assertEquals(0, result.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("138****0000", data.get("phoneMasked"));
    }

    @Test
    @DisplayName("send-code bind 场景：无会话即业务错误，不发短信")
    void sendCodeBindSceneRequiresSession() {
        SmsAuthService sms = mock(SmsAuthService.class);
        AuthController controller = controller(mock(UserService.class),
                new AuthAbuseGuard(false, "open"), sms);
        AuthController.SmsSendCodeRequest request = new AuthController.SmsSendCodeRequest();
        request.setScene("bind");
        request.setPhone("13800000000");

        Map<String, Object> result = controller.sendSmsCode(request, "session_not_exist", http());
        assertEquals(1, result.get("code"));
        verify(sms, never()).sendBindCode(any(), anyString());
    }

    @Test
    @DisplayName("device-token 与 /login 同一道短信闸：缺码 4005")
    void deviceTokenGetsSameSmsGate() {
        UserService userService = mock(UserService.class);
        when(userService.login("alice", "pw123456")).thenReturn(boundUser());
        SmsAuthService sms = mock(SmsAuthService.class);
        when(sms.requiresCode(any())).thenReturn(true);

        AuthController controller = controller(userService, new AuthAbuseGuard(false, "open"), sms);
        Map<String, Object> result = controller.issueDeviceToken(
                Map.of("username", "alice", "password", "pw123456"), http());
        assertEquals(AuthController.CODE_SMS_REQUIRED, result.get("code"));
    }
}
