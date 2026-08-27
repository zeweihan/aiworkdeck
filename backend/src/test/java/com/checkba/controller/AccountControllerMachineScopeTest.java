package com.checkba.controller;

import com.checkba.model.entity.DeviceToken;
import com.checkba.model.entity.User;
import com.checkba.repository.DeviceTokenRepository;
import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.UserService;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.AccountSwitchCleanup;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.ai.PlatformUsageAccountant;
import com.checkba.service.entitlement.EntitlementService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * server 模式下账户/权益端点的接线回归（插件云后端加固）：
 * 普通用户读不到机器级账户状态、断不开连接、看不到权益快照；admin 一切照旧。
 * local-mode 语义在 MachineAccountGuardTest 里单独锁定。
 */
class AccountControllerMachineScopeTest {

    private DeviceTokenService deviceTokenService;
    private AccountService accountService;
    private EntitlementService entitlementService;
    private AccountController accountController;
    private EntitlementController entitlementController;

    private final Map<Long, User> usersById = new HashMap<>();
    private final Map<String, DeviceToken> tokensByHash = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
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

        UserService userService = mock(UserService.class);
        when(userService.getUserById(anyLong())).thenAnswer(inv -> {
            User user = usersById.get(inv.getArgument(0, Long.class));
            if (user == null) throw new IllegalArgumentException("用户不存在");
            return user;
        });

        MachineAccountGuard guard =
                new MachineAccountGuard(false, new AdminAccessService(), userService);

        accountService = mock(AccountService.class);
        entitlementService = mock(EntitlementService.class);
        PlatformAiChannel platformAiChannel = mock(PlatformAiChannel.class);
        accountController = new AccountController(accountService, platformAiChannel,
                mock(AccountSwitchCleanup.class), mock(TokenUsageRepository.class), guard,
                entitlementService);
        entitlementController = new EntitlementController(entitlementService, guard);
    }

    private String sessionFor(String username) {
        User user = new User();
        user.setId(seq.getAndIncrement());
        user.setUsername(username);
        usersById.put(user.getId(), user);
        return deviceTokenService.issue(user.getId(), "test").plaintext();
    }

    @Test
    @DisplayName("普通用户：status/disconnect/entitlements 全被业务错误拒绝，服务层零触碰")
    void nonAdminRejectedEverywhere() {
        String session = sessionFor("alice");

        assertThrows(IllegalArgumentException.class, () -> accountController.status(session));
        assertThrows(IllegalArgumentException.class, () -> accountController.disconnect(session));
        assertThrows(IllegalArgumentException.class, () -> accountController.connect(Map.of(), session));
        assertThrows(IllegalArgumentException.class, () -> accountController.usage(session));
        assertThrows(IllegalArgumentException.class, () -> entitlementController.list(session, false));

        verifyNoInteractions(accountService);
        verifyNoInteractions(entitlementService);
    }

    @Test
    @DisplayName("admin：status 与 entitlements 照常返回")
    void adminStillWorks() {
        String session = sessionFor("admin");
        when(accountService.status()).thenReturn(Map.of("connected", false));
        when(entitlementService.snapshot()).thenReturn(Map.of());

        Map<String, Object> status = accountController.status(session);
        assertEquals(0, status.get("code"));

        Map<String, Object> entitlements = entitlementController.list(session, false);
        assertEquals(0, entitlements.get("code"));
    }
}
