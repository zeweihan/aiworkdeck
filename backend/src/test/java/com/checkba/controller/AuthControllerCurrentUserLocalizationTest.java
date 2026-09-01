package com.checkba.controller;

import com.checkba.model.entity.User;
import com.checkba.service.AdminAccessService;
import com.checkba.service.AppLanguageService;
import com.checkba.service.LangText;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GET /api/auth/me 在 local-mode 下的 displayName 本地化（v0.30.0 发版前修复）。
 *
 * 单机免登下本机用户的 displayName 库里存的是中文哨兵值
 * {@link LocalIdentityService#LOCAL_DISPLAY_NAME}，界面语言切到英文后这个字段不能原样吐出
 * 中文——工作台顶栏「Lead: {name}」、设置页个人区（AdminPane.vue 的 userInfo.displayName）
 * 都直接渲染这个接口返回的字段。
 */
class AuthControllerCurrentUserLocalizationTest {

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
        LangText.reset();
    }

    private static AuthController controller(UserService userService, boolean localMode) {
        // 会话服务：local-mode 身份解析不碰它，构造器补位而已（同 AuthControllerLocalDeviceTokenTest）
        com.checkba.service.UserSessionService sessions = new com.checkba.service.UserSessionService(
                mock(com.checkba.repository.UserSessionRepository.class), 365);
        AdminAccessService adminAccessService = mock(AdminAccessService.class);
        return new AuthController(userService, null, adminAccessService, null,
                null, null, null, null, null, sessions, localMode, null);
    }

    private static User user(long id, String username, String displayName) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        return u;
    }

    @Test
    @DisplayName("local-mode + 英文界面：本机用户哨兵值 displayName 输出为 Local user")
    void localModeEnglish_sentinelDisplayName_localizedToEnglish() {
        LocalIdentityService identity = mock(LocalIdentityService.class);
        when(identity.isLocalMode()).thenReturn(true);
        when(identity.localUserId()).thenReturn(1L);
        AuthController.registerLocalIdentityService(identity);

        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);

        UserService userService = mock(UserService.class);
        when(userService.getUserById(1L))
                .thenReturn(user(1L, "admin", LocalIdentityService.LOCAL_DISPLAY_NAME));

        Map<String, Object> result = controller(userService, true).getCurrentUser(null);

        assertEquals(0, result.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("Local user", data.get("displayName"));
        assertNotEquals(LocalIdentityService.LOCAL_DISPLAY_NAME, data.get("displayName"),
                "英文界面下绝不能吐出中文哨兵值");
    }

    @Test
    @DisplayName("local-mode + 中文界面：本机用户哨兵值 displayName 保持中文")
    void localModeChinese_sentinelDisplayName_staysChinese() {
        LocalIdentityService identity = mock(LocalIdentityService.class);
        when(identity.isLocalMode()).thenReturn(true);
        when(identity.localUserId()).thenReturn(1L);
        AuthController.registerLocalIdentityService(identity);
        // 未注册 AppLanguageService：LangText 回退中文，与既有默认态一致

        UserService userService = mock(UserService.class);
        when(userService.getUserById(1L))
                .thenReturn(user(1L, "admin", LocalIdentityService.LOCAL_DISPLAY_NAME));

        Map<String, Object> result = controller(userService, true).getCurrentUser(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(LocalIdentityService.LOCAL_DISPLAY_NAME, data.get("displayName"));
    }

    @Test
    @DisplayName("真实用户：英文界面下 displayName 原样返回，一个字都不能动")
    void realUserDisplayName_neverLocalized() {
        LocalIdentityService identity = mock(LocalIdentityService.class);
        when(identity.isLocalMode()).thenReturn(true);
        when(identity.localUserId()).thenReturn(2L);
        AuthController.registerLocalIdentityService(identity);

        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);

        UserService userService = mock(UserService.class);
        when(userService.getUserById(2L)).thenReturn(user(2L, "hanzewei", "韩泽伟"));

        Map<String, Object> result = controller(userService, true).getCurrentUser(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("韩泽伟", data.get("displayName"));
    }
}
