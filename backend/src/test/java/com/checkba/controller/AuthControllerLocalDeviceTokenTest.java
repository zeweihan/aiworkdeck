package com.checkba.controller;

import com.checkba.model.entity.User;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * local-mode 会话签发设备令牌（POST /api/auth/device-token/issue-local）。
 *
 * 单机版免登、本机用户没有口令，走不了 /device-token 那条账号口令路；这条只在 local-mode 存在，
 * 团队服务器必须回业务错误——留了旁路等于绕过口令失败锁定与二次验证闸。
 */
class AuthControllerLocalDeviceTokenTest {

    private Object previousLocalIdentity;

    private static Field localIdentityField() throws Exception {
        Field field = AuthController.class.getDeclaredField("staticLocalIdentityService");
        field.setAccessible(true);
        return field;
    }

    /** 静态注册位是全局状态，用完必须还原，否则会污染同一 JVM 里的其它测试。 */
    @BeforeEach
    void rememberLocalIdentity() throws Exception {
        previousLocalIdentity = localIdentityField().get(null);
    }

    @AfterEach
    void restoreLocalIdentity() throws Exception {
        localIdentityField().set(null, previousLocalIdentity);
    }

    private static AuthController controller(UserService userService,
                                             DeviceTokenService deviceTokenService,
                                             boolean localMode) {
        // 会话服务（repository 打桩）：本端点走 local-mode 身份解析不碰它，构造器补位而已
        com.checkba.service.UserSessionService sessions = new com.checkba.service.UserSessionService(
                org.mockito.Mockito.mock(com.checkba.repository.UserSessionRepository.class));
        return new AuthController(userService, null, null, deviceTokenService,
                null, null, null, null, null, sessions, localMode, null);
    }

    private static User user(long id, String username, String displayName) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName(displayName);
        return user;
    }

    @Test
    @DisplayName("local-mode：无会话头也能用本机身份换到令牌，明文只在这一次返回")
    void localModeIssuesTokenForLocalUser() throws Exception {
        LocalIdentityService identity = mock(LocalIdentityService.class);
        when(identity.isLocalMode()).thenReturn(true);
        when(identity.localUserId()).thenReturn(42L);
        AuthController.registerLocalIdentityService(identity);

        UserService userService = mock(UserService.class);
        when(userService.getUserById(42L)).thenReturn(user(42L, "hanzewei", "韩泽伟"));
        DeviceTokenService deviceTokenService = mock(DeviceTokenService.class);
        when(deviceTokenService.issue(anyLong(), any()))
                .thenReturn(new DeviceTokenService.IssuedToken(7L, "awdt_plaintext"));

        Map<String, Object> result = controller(userService, deviceTokenService, true)
                .issueLocalDeviceToken(Map.of("name", "Word 插件"), null);

        assertEquals(0, result.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(7L, data.get("tokenId"));
        assertEquals("awdt_plaintext", data.get("token"));
        assertEquals(42L, data.get("userId"));
        assertEquals("hanzewei", data.get("username"));
        assertEquals("韩泽伟", data.get("displayName"));
        verify(deviceTokenService).issue(42L, "Word 插件");
    }

    @Test
    @DisplayName("团队服务器：拒绝签发且不触碰令牌服务，文案不像掉线")
    void serverModeRefuses() {
        DeviceTokenService deviceTokenService = mock(DeviceTokenService.class);

        Map<String, Object> result = controller(mock(UserService.class), deviceTokenService, false)
                .issueLocalDeviceToken(Map.of("name", "x"), "session_whatever");

        assertEquals(1, result.get("code"));
        String message = String.valueOf(result.get("message"));
        assertTrue(message.contains("账号密码"));
        assertFalse(message.contains("登录"));
        assertFalse(message.contains("未授权"));
        assertFalse(message.contains("请先"));
        verifyNoInteractions(deviceTokenService);
    }

    @Test
    @DisplayName("local-mode 但本机身份未就绪：业务错误，不签发")
    void localModeWithoutIdentityRefuses() throws Exception {
        LocalIdentityService identity = mock(LocalIdentityService.class);
        when(identity.isLocalMode()).thenReturn(true);
        when(identity.localUserId()).thenReturn(null);
        AuthController.registerLocalIdentityService(identity);

        DeviceTokenService deviceTokenService = mock(DeviceTokenService.class);

        Map<String, Object> result = controller(mock(UserService.class), deviceTokenService, true)
                .issueLocalDeviceToken(null, null);

        assertEquals(1, result.get("code"));
        String message = String.valueOf(result.get("message"));
        assertFalse(message.contains("登录"));
        assertFalse(message.contains("未授权"));
        assertFalse(message.contains("请先"));
        verifyNoInteractions(deviceTokenService);
    }
}
