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
                mock(com.checkba.repository.UserSessionRepository.class));
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
                userService, null, null, null, serverGuard("closed"), null, null, null, null, sessions(), false);

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
                userService, null, null, null, serverGuard("open"), null, null, null, null, sessions(), false);

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
                userService, null, null, null, localGuard, null, null, null, null, sessions(), false);

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
                userService, null, null, null, serverGuard("open"), null, null, null, null, sessions(), false);

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
                null, null, null, null, serverGuard("open"), awdkLoginService, null, null, null, sessions(), false);

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
                null, null, null, null, serverGuard("open"), awdkLoginService, null, null, null, sessions(), false);

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
                null, null, null, null, serverGuard("open"), awdkLoginService, null, null, null, sessions(), false);

        for (int i = 0; i < 5; i++) {
            assertEquals(1, controller.awdkLogin(Map.of("key", "awdk_bad"), http()).get("code"));
        }
        Map<String, Object> locked = controller.awdkLogin(Map.of("key", "awdk_bad"), http());
        assertEquals(1, locked.get("code"));
        assertTrue(String.valueOf(locked.get("message")).contains("临时锁定"));
        verify(awdkLoginService, times(5)).login(anyString());
    }
}
