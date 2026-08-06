package com.checkba.service.account;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.DeviceToken;
import com.checkba.model.entity.User;
import com.checkba.repository.DeviceTokenRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 锁定 server 模式下账户/权益端点的机器级操作闸（插件云后端加固）：
 * - server 模式：非 admin 拒绝（业务错误，文案不像掉线），admin 放行，无会话报「未登录」；
 * - local-mode：一字不动，恒放行。
 */
class MachineAccountGuardTest {

    private DeviceTokenService deviceTokenService;
    private UserService userService;
    private AdminAccessService adminAccessService;

    private final Map<Long, User> usersById = new HashMap<>();
    private final Map<String, DeviceToken> tokensByHash = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        // 清掉其他测试可能泄漏进静态入口的 local-mode 身份（LocalIdentityService 构造时反向注册）
        AuthController.registerLocalIdentityService(null);

        usersById.clear();
        tokensByHash.clear();

        DeviceTokenRepository tokenRepository = mock(DeviceTokenRepository.class);
        when(tokenRepository.save(any(DeviceToken.class))).thenAnswer(inv -> {
            DeviceToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(seq.getAndIncrement());
            tokensByHash.put(t.getTokenHash(), t);
            return t;
        });
        when(tokenRepository.findByTokenHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(tokensByHash.get(inv.getArgument(0, String.class))));
        deviceTokenService = new DeviceTokenService(tokenRepository);

        userService = mock(UserService.class);
        when(userService.getUserById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0, Long.class);
            User user = usersById.get(id);
            if (user == null) throw new IllegalArgumentException("用户不存在: " + id);
            return user;
        });

        adminAccessService = new AdminAccessService(); // allow-all-users 默认 false = server 语义
    }

    private String sessionFor(String username) {
        User user = new User();
        user.setId(seq.getAndIncrement());
        user.setUsername(username);
        usersById.put(user.getId(), user);
        return deviceTokenService.issue(user.getId(), "test").plaintext();
    }

    private MachineAccountGuard guard(boolean localMode) {
        return new MachineAccountGuard(localMode, adminAccessService, userService);
    }

    @Test
    @DisplayName("server 模式：普通用户被业务错误拒绝，文案不含「登录/未授权/请先」")
    void serverModeRejectsNonAdmin() {
        String session = sessionFor("alice");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guard(false).requireMachineScope(session));
        assertTrue(e.getMessage().contains("仅管理员账号可操作"), e.getMessage());
        assertFalse(e.getMessage().contains("登录"), e.getMessage());
        assertFalse(e.getMessage().contains("未授权"), e.getMessage());
        assertFalse(e.getMessage().contains("请先"), e.getMessage());
    }

    @Test
    @DisplayName("server 模式：admin 放行")
    void serverModeAllowsAdmin() {
        String session = sessionFor("admin");
        assertDoesNotThrow(() -> guard(false).requireMachineScope(session));
    }

    @Test
    @DisplayName("server 模式：无会话报「未登录」（真掉线，本就该触发前端重新认证）")
    void serverModeRequiresSession() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guard(false).requireMachineScope(null));
        assertEquals("未登录", e.getMessage());
    }

    @Test
    @DisplayName("local-mode 回归：不查会话不查身份，恒放行")
    void localModeAlwaysPasses() {
        assertDoesNotThrow(() -> guard(true).requireMachineScope(null));
        assertDoesNotThrow(() -> guard(true).requireMachineScope("session_whatever"));
    }
}
