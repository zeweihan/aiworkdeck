package com.checkba.controller;

import com.checkba.model.entity.User;
import com.checkba.repository.UserSessionRepository;
import com.checkba.service.AuthAbuseGuard;
import com.checkba.service.UserService;
import com.checkba.service.UserSessionService;
import com.checkba.service.mail.MailAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 邮箱验证码端点的 AuthController 接线：限频闸与短信共用、login 场景不成为免锁定的
 * 密码试探口、免密登录必须计入失败锁定。服务层语义见 MailAuthServiceTest。
 */
class AuthControllerMailTest {

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
        user.setVerifiedEmail("alice@gmail.com");
        return user;
    }

    private static UserSessionService sessions() {
        return new UserSessionService(mock(UserSessionRepository.class));
    }

    private static AuthController controller(UserService userService, AuthAbuseGuard guard,
                                             MailAuthService mailAuthService) {
        return new AuthController(userService, null, null, null, guard, null, null,
                mailAuthService, null, sessions(), false, null);
    }

    private static AuthController.MailSendCodeRequest sendCodeRequest(String scene, String email) {
        AuthController.MailSendCodeRequest r = new AuthController.MailSendCodeRequest();
        r.setScene(scene);
        r.setUsername("alice");
        r.setPassword("pw123456");
        r.setEmail(email);
        return r;
    }

    private static AuthController.MailLoginRequest loginRequest(String email, String code) {
        AuthController.MailLoginRequest r = new AuthController.MailLoginRequest();
        r.setEmail(email);
        r.setCode(code);
        return r;
    }

    @Test
    @DisplayName("login 场景先过锁定闸再校验凭据，密码错要计一次失败——否则这是个免锁定的试探口")
    void loginSceneCountsPasswordFailures() {
        UserService userService = mock(UserService.class);
        when(userService.login(anyString(), anyString())).thenThrow(new IllegalArgumentException("用户名或密码错误"));
        AuthAbuseGuard guard = mock(AuthAbuseGuard.class);
        MailAuthService mail = mock(MailAuthService.class);

        Map<String, Object> result = controller(userService, guard, mail)
                .sendMailCode(sendCodeRequest("login", null), null, http());

        assertEquals(1, result.get("code"));
        verify(guard).checkCodeSendRate("9.9.9.9");
        verify(guard).checkLoginAttempt("9.9.9.9", "alice");
        verify(guard).recordLoginFailure("9.9.9.9", "alice");
        verify(guard, never()).recordCodeSend(anyString());
        verify(mail, never()).sendLoginCode(any());
    }

    @Test
    @DisplayName("login 场景密码对则发码，并记一次发送用于 IP 限频")
    void loginSceneSendsOnCorrectPassword() {
        UserService userService = mock(UserService.class);
        when(userService.login(anyString(), anyString())).thenReturn(boundUser());
        AuthAbuseGuard guard = mock(AuthAbuseGuard.class);
        MailAuthService mail = mock(MailAuthService.class);
        when(mail.sendLoginCode(any())).thenReturn("a***@gmail.com");

        Map<String, Object> result = controller(userService, guard, mail)
                .sendMailCode(sendCodeRequest("login", null), null, http());

        assertEquals(0, result.get("code"));
        assertEquals("a***@gmail.com", ((Map<?, ?>) result.get("data")).get("emailMasked"));
        verify(guard).recordCodeSend("9.9.9.9");
    }

    @Test
    @DisplayName("bind 场景没有会话就拒，不碰发信也不占限频额度")
    void bindSceneRequiresSession() {
        AuthAbuseGuard guard = mock(AuthAbuseGuard.class);
        MailAuthService mail = mock(MailAuthService.class);

        Map<String, Object> result = controller(mock(UserService.class), guard, mail)
                .sendMailCode(sendCodeRequest("bind", "new@qq.com"), null, http());

        assertEquals(4010, result.get("code"));
        assertEquals("未登录", result.get("message"));
        verify(mail, never()).sendBindCode(any(), anyString());
        verify(guard, never()).recordCodeSend(anyString());
    }

    @Test
    @DisplayName("绑定端点没有会话就拒")
    void bindEndpointRequiresSession() {
        MailAuthService mail = mock(MailAuthService.class);
        AuthController.MailBindRequest r = new AuthController.MailBindRequest();
        r.setEmail("new@qq.com");
        r.setCode("123456");

        Map<String, Object> result = controller(mock(UserService.class), mock(AuthAbuseGuard.class), mail)
                .bindEmail(r, null);

        assertEquals(4010, result.get("code"));
        verify(mail, never()).confirmBind(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("免密登录发码走同一把 IP 限频闸——否则换个端点就能绕过限频")
    void passwordlessSendSharesRateLimiter() {
        AuthAbuseGuard guard = mock(AuthAbuseGuard.class);
        MailAuthService mail = mock(MailAuthService.class);

        Map<String, Object> result = controller(mock(UserService.class), guard, mail)
                .mailLoginSendCode(loginRequest("alice@gmail.com", null), http());

        assertEquals(0, result.get("code"));
        verify(guard).checkCodeSendRate("9.9.9.9");
        verify(guard).recordCodeSend("9.9.9.9");
        verify(mail).sendSigninCode("alice@gmail.com");
    }

    @Test
    @DisplayName("免密登录验码失败必须计入锁定——单枚码的尝试上限管不住换码重来")
    void passwordlessFailureCountsTowardLockout() {
        AuthAbuseGuard guard = mock(AuthAbuseGuard.class);
        MailAuthService mail = mock(MailAuthService.class);
        when(mail.verifySigninCode(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("验证码错误或已过期"));

        Map<String, Object> result = controller(mock(UserService.class), guard, mail)
                .mailLoginVerify(loginRequest("Alice@Gmail.com", "000000"), http());

        assertEquals(1, result.get("code"));
        // 锁定键用规范化后的邮箱，否则改个大小写就是一个新的计数桶
        verify(guard).checkLoginAttempt("9.9.9.9", "alice@gmail.com");
        verify(guard).recordLoginFailure("9.9.9.9", "alice@gmail.com");
    }

    @Test
    @DisplayName("已被锁定时直接拒，不消费验证码")
    void passwordlessRespectsLockout() {
        AuthAbuseGuard guard = mock(AuthAbuseGuard.class);
        doThrow(new IllegalArgumentException("尝试过于频繁，请稍后再试"))
                .when(guard).checkLoginAttempt(anyString(), anyString());
        MailAuthService mail = mock(MailAuthService.class);

        Map<String, Object> result = controller(mock(UserService.class), guard, mail)
                .mailLoginVerify(loginRequest("alice@gmail.com", "123456"), http());

        assertEquals(1, result.get("code"));
        verify(mail, never()).verifySigninCode(anyString(), anyString());
    }

    @Test
    @DisplayName("免密登录成功才签发会话，且回包结构与密码登录一致")
    void passwordlessIssuesSessionOnSuccess() {
        AuthAbuseGuard guard = mock(AuthAbuseGuard.class);
        MailAuthService mail = mock(MailAuthService.class);
        when(mail.verifySigninCode(anyString(), anyString())).thenReturn(boundUser());

        Map<String, Object> result = controller(mock(UserService.class), guard, mail)
                .mailLoginVerify(loginRequest("alice@gmail.com", "123456"), http());

        assertEquals(0, result.get("code"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertNotNull(data.get("sessionId"));
        assertEquals("alice", ((Map<?, ?>) data.get("user")).get("username"));
        verify(guard).recordLoginSuccess("9.9.9.9", "alice@gmail.com");
    }
}
