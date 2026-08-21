package com.checkba.controller;

import com.checkba.model.entity.User;
import com.checkba.service.AuthAbuseGuard;
import com.checkba.service.UserService;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AwdkLoginService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 端点级锁定注册闸 / 登录锁定 / awdk-login 信封（插件云后端加固）。
 * 服务层语义各有专测（AuthAbuseGuardTest / AwdkLoginServiceTest），
 * 这里只验 AuthController 的接线：闸在业务动作之前、失败正确计数、信封 code/message 正确。
 */
class AuthControllerHardeningTest {

    private AuthAbuseGuard serverGuard(String registrationMode) {
        return new AuthAbuseGuard(false, registrationMode);
    }

    /** DB 会话服务（repository 打桩）：注册/登录成功路径要经它签发 sessionId。 */
    private static com.checkba.service.UserSessionService sessions() {
        return new com.checkba.service.UserSessionService(
                mock(com.checkba.repository.UserSessionRepository.class), 365);
    }

    private static MockHttpServletRequest http() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("9.9.9.9");
        return request;
    }

    private static AuthController.RegisterRequest registerRequest() {
        AuthController.RegisterRequest request = new AuthController.RegisterRequest();
        request.setUsername("alice");
        request.setPassword("pw123456");
        request.setDisplayName("Alice");
        return request;
    }

    private static User user(long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName(username);
        return user;
    }

    @Test
    @DisplayName("registration-mode=closed：注册返回业务错误，UserService 根本不被调用")
    void closedRegistrationShortCircuits() {
        UserService userService = mock(UserService.class);
        AuthController controller = new AuthController(
                userService, null, null, null, serverGuard("closed"), null, null, null, null, sessions(), false, null);

        Map<String, Object> result = controller.register(registerRequest(), http());

        assertEquals(1, result.get("code"));
        assertTrue(String.valueOf(result.get("message")).contains("未开放自助注册"));
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("registration-mode=open：注册照常成功")
    void openRegistrationStillWorks() {
        UserService userService = mock(UserService.class);
        when(userService.register(anyString(), anyString(), anyString()))
                .thenReturn(user(1L, "alice"));
        AuthController controller = new AuthController(
                userService, null, null, null, serverGuard("open"), null, null, null, null, sessions(), false, null);

        Map<String, Object> result = controller.register(registerRequest(), http());

        assertEquals(0, result.get("code"));
    }

    @Test
    @DisplayName("local-mode 回归：registration-mode=closed 也不影响注册")
    void localModeUnaffectedByClosedGate() {
        UserService userService = mock(UserService.class);
        when(userService.register(anyString(), anyString(), anyString()))
                .thenReturn(user(1L, "alice"));
        AuthAbuseGuard localGuard = new AuthAbuseGuard(true, "closed");
        AuthController controller = new AuthController(
                userService, null, null, null, localGuard, null, null, null, null, sessions(), false, null);

        Map<String, Object> result = controller.register(registerRequest(), http());

        assertEquals(0, result.get("code"));
    }

    @Test
    @DisplayName("登录连续失败 5 次后锁定：第 6 次不再触碰凭据校验")
    void loginLockoutAfterFiveFailures() {
        UserService userService = mock(UserService.class);
        when(userService.login(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("用户名或密码错误"));
        AuthController controller = new AuthController(
                userService, null, null, null, serverGuard("open"), null, null, null, null, sessions(), false, null);

        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("alice");
        request.setPassword("wrong");
        for (int i = 0; i < 5; i++) {
            Map<String, Object> result = controller.login(request, http());
            assertEquals(1, result.get("code"));
        }

        Map<String, Object> locked = controller.login(request, http());
        assertEquals(1, locked.get("code"));
        assertTrue(String.valueOf(locked.get("message")).contains("临时锁定"));
        verify(userService, times(5)).login(anyString(), anyString());
    }

    @Test
    @DisplayName("awdk-login 成功：返回 token/userId/username 信封")
    void awdkLoginSuccessEnvelope() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        when(awdkLoginService.login(anyString()))
                .thenReturn(new AwdkLoginService.BridgeSession("awdt_x", 7L, "awd_hanzewei"));
        AuthController controller = new AuthController(
                null, null, null, null, serverGuard("open"), awdkLoginService, null, null, null, sessions(), false, null);

        Map<String, Object> result = controller.awdkLogin(Map.of("key", "awdk_abc"), http());

        assertEquals(0, result.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("awdt_x", data.get("token"));
        assertEquals(7L, data.get("userId"));
        assertEquals("awd_hanzewei", data.get("username"));
    }

    @Test
    @DisplayName("awdk-login 开关关闭：业务错误信封，不像掉线")
    void awdkLoginDisabledEnvelope() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        when(awdkLoginService.login(any()))
                .thenThrow(new IllegalArgumentException("本服务器未开启账户桥接功能"));
        AuthController controller = new AuthController(
                null, null, null, null, serverGuard("open"), awdkLoginService, null, null, null, sessions(), false, null);

        Map<String, Object> result = controller.awdkLogin(Map.of("key", "awdk_abc"), http());

        assertEquals(1, result.get("code"));
        String message = String.valueOf(result.get("message"));
        assertTrue(message.contains("未开启账户桥接"));
        assertFalse(message.contains("登录"));
        assertFalse(message.contains("未授权"));
        assertFalse(message.contains("请先"));
    }

    @Test
    @DisplayName("awdk-login 无效 Key 连续 5 次后锁定：第 6 次不再出站")
    void awdkLoginLockoutAfterRepeatedInvalidKeys() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        when(awdkLoginService.login(anyString())).thenThrow(new AccountException(
                AccountException.Kind.UNAUTHORIZED, "账户 Key 无效或已被撤销，请到官网账户页重新生成"));
        AuthController controller = new AuthController(
                null, null, null, null, serverGuard("open"), awdkLoginService, null, null, null, sessions(), false, null);

        for (int i = 0; i < 5; i++) {
            assertEquals(1, controller.awdkLogin(Map.of("key", "awdk_bad"), http()).get("code"));
        }
        Map<String, Object> locked = controller.awdkLogin(Map.of("key", "awdk_bad"), http());
        assertEquals(1, locked.get("code"));
        assertTrue(String.valueOf(locked.get("message")).contains("临时锁定"));
        verify(awdkLoginService, times(5)).login(anyString());
    }

    // ==================== 账户登录（手机号/邮箱，匿名端点） ====================

    /** phoneLoginGuard 传 null：手机号补绑闸只管密码/邮箱那三条入口，账户登录不走它。 */
    private static AuthController controller(AwdkLoginService awdkLoginService, AuthAbuseGuard guard) {
        return new AuthController(
                null, null, null, null, guard, awdkLoginService, null, null, null, sessions(), false, null);
    }

    @Test
    @DisplayName("手机号登录成功：与 awdk-login 同一个 token/userId/username 信封")
    void accountLoginPhoneSuccessEnvelope() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        when(awdkLoginService.loginWithPhone("13800138000", "123456"))
                .thenReturn(new AwdkLoginService.BridgeSession("awdt_x", 7L, "awd_hanzewei"));
        AuthController controller = controller(awdkLoginService, serverGuard("open"));

        Map<String, Object> result = controller.accountLogin(
                Map.of("phone", "13800138000", "code", "123456"), http());

        assertEquals(0, result.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("awdt_x", data.get("token"));
        assertEquals(7L, data.get("userId"));
        assertEquals("awd_hanzewei", data.get("username"));
        verify(awdkLoginService, never()).loginWithPassword(any(), any());
    }

    @Test
    @DisplayName("没填手机号即走口令分支（国际站/存量账号）")
    void accountLoginFallsBackToPasswordBranch() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        when(awdkLoginService.loginWithPassword("hi@example.com", "pw12345678"))
                .thenReturn(new AwdkLoginService.BridgeSession("awdt_y", 8L, "awd_hi"));
        AuthController controller = controller(awdkLoginService, serverGuard("open"));

        Map<String, Object> result = controller.accountLogin(
                Map.of("account", "hi@example.com", "password", "pw12345678"), http());

        assertEquals(0, result.get("code"));
        verify(awdkLoginService, never()).loginWithPhone(any(), any());
    }

    @Test
    @DisplayName("验证码连续错 5 次后锁定：第 6 次不再出站到官网")
    void accountLoginLockoutAfterFiveWrongCodes() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        when(awdkLoginService.loginWithPhone(anyString(), anyString())).thenThrow(
                new AccountException(AccountException.Kind.UNAUTHORIZED, "验证码错误或已过期"));
        AuthController controller = controller(awdkLoginService, serverGuard("open"));
        Map<String, String> body = Map.of("phone", "13800138000", "code", "000000");

        for (int i = 0; i < 5; i++) {
            assertEquals(1, controller.accountLogin(body, http()).get("code"));
        }
        Map<String, Object> locked = controller.accountLogin(body, http());
        assertEquals(1, locked.get("code"));
        assertTrue(String.valueOf(locked.get("message")).contains("临时锁定"));
        verify(awdkLoginService, times(5)).loginWithPhone(anyString(), anyString());
    }

    @Test
    @DisplayName("补绑期已过（CONFLICT）不计失败：凭据本来就是对的，不该被锁在门外")
    void accountLoginConflictDoesNotCountAsFailure() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        when(awdkLoginService.loginWithPassword(anyString(), anyString())).thenThrow(
                new AccountException(AccountException.Kind.CONFLICT, "该账户尚未绑定手机号，且已超过绑定期限"));
        AuthController controller = controller(awdkLoginService, serverGuard("open"));
        Map<String, String> body = Map.of("account", "hi@example.com", "password", "pw12345678");

        for (int i = 0; i < 8; i++) {
            assertEquals(1, controller.accountLogin(body, http()).get("code"));
        }
        verify(awdkLoginService, times(8)).loginWithPassword(anyString(), anyString());
    }

    @Test
    @DisplayName("账户登录信封绝不带 4010（那是前端判定会话失效的专用码）")
    void accountLoginNeverEmits4010() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        when(awdkLoginService.loginWithPhone(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("本服务器未开启账户桥接功能"));
        AuthController controller = controller(awdkLoginService, serverGuard("open"));

        Map<String, Object> result = controller.accountLogin(
                Map.of("phone", "13800138000", "code", "123456"), http());

        assertEquals(1, result.get("code"));
        assertTrue(String.valueOf(result.get("message")).contains("未开启账户桥接"));
    }

    @Test
    @DisplayName("发验证码：失败也计入 IP 额度，否则无效手机号可以免费换来等量对官网出站")
    void accountLoginSendCodeCountsAttemptsNotOnlySuccesses() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        doThrow(new AccountException(AccountException.Kind.UNAUTHORIZED, "手机号格式不正确"))
                .when(awdkLoginService).sendLoginCode(anyString(), any());
        AuthController controller = controller(awdkLoginService, serverGuard("open"));
        Map<String, String> body = Map.of("phone", "not-a-phone");

        for (int i = 0; i < 20; i++) {
            assertEquals(1, controller.accountLoginSendCode(body, http()).get("code"));
        }
        Map<String, Object> throttled = controller.accountLoginSendCode(body, http());
        assertEquals(1, throttled.get("code"));
        assertTrue(String.valueOf(throttled.get("message")).contains("过于频繁"));
        verify(awdkLoginService, times(20)).sendLoginCode(anyString(), any());
    }

    @Test
    @DisplayName("发验证码成功：code=0 信封")
    void accountLoginSendCodeSuccessEnvelope() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        AuthController controller = controller(awdkLoginService, serverGuard("open"));

        Map<String, Object> result = controller.accountLoginSendCode(Map.of("phone", "13800138000"), http());

        assertEquals(0, result.get("code"));
        verify(awdkLoginService).sendLoginCode("13800138000", null);
    }

    @Test
    @DisplayName("发验证码：人机验证 token 原样透传——不传官网就是 403，插件端的滑块等于白滑")
    void accountLoginSendCodeForwardsCaptchaToken() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        AuthController controller = controller(awdkLoginService, serverGuard("open"));

        Map<String, Object> result = controller.accountLoginSendCode(
                Map.of("phone", "13800138000", "captchaToken", "verify-param-from-widget"), http());

        assertEquals(0, result.get("code"));
        verify(awdkLoginService).sendLoginCode("13800138000", "verify-param-from-widget");
    }

    @Test
    @DisplayName("控件参数端点是匿名的——云后端登录前没有会话，要会话就成了死循环")
    void accountLoginCaptchaConfigNeedsNoSession() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        when(awdkLoginService.captchaConfig())
                .thenReturn(Map.of("provider", "aliyun", "sceneId", "scene-1", "prefix", "px1"));
        AuthController controller = controller(awdkLoginService, serverGuard("open"));

        Map<String, Object> result = controller.accountLoginCaptchaConfig();

        assertEquals(0, result.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("aliyun", data.get("provider"));
        assertEquals("scene-1", data.get("sceneId"));
    }

    @Test
    @DisplayName("官网未启用人机验证时 provider 为空，调用方据此跳过控件直接发码")
    void accountLoginCaptchaConfigPassesThroughDisabled() {
        AwdkLoginService awdkLoginService = mock(AwdkLoginService.class);
        Map<String, Object> off = new java.util.HashMap<>();
        off.put("provider", null);
        when(awdkLoginService.captchaConfig()).thenReturn(off);
        AuthController controller = controller(awdkLoginService, serverGuard("open"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) controller.accountLoginCaptchaConfig().get("data");
        assertNull(data.get("provider"));
    }
}
