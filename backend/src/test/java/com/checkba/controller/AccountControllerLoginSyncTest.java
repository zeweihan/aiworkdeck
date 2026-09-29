// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.account.AccountIdentitySync;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.AccountSwitchCleanup;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.service.team.TeamSettingsCache;
import com.checkba.service.team.TeamUsageSettings;
import com.checkba.service.team.TeamUsageUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 登录后置（dev-board#1046，设计 2026-09-29 §5.5）：
 * <ul>
 *   <li>{@code /login} 成功后立即同步一次身份（与 {@code /connect} 对齐），不等下一次 status；</li>
 *   <li>换了一个账户时回包带一次性的 {@code previousAccountDiffers:true}，没换人时回包形状不变；</li>
 *   <li>断开账户走 {@link AccountSwitchCleanup#afterDisconnect()}（身份行回退在那里）。</li>
 * </ul>
 */
class AccountControllerLoginSyncTest {

    AccountController controller;
    AccountService accountService;
    AccountIdentitySync identitySync;
    AccountSwitchCleanup cleanup;

    @BeforeEach
    void setUp() {
        AuthController.registerLocalIdentityService(null);
        accountService = mock(AccountService.class);
        identitySync = mock(AccountIdentitySync.class);
        cleanup = mock(AccountSwitchCleanup.class);
        controller = new AccountController(accountService, mock(PlatformAiChannel.class),
                cleanup, mock(TokenUsageRepository.class),
                mock(MachineAccountGuard.class), mock(EntitlementService.class),
                mock(TeamUsageSettings.class), mock(TeamUsageUploadService.class),
                mock(TeamSettingsCache.class), identitySync);
    }

    private static Map<String, Object> connected() {
        Map<String, Object> s = new HashMap<>();
        s.put("connected", true);
        s.put("isNewUser", false);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> envelope) {
        assertEquals(0, envelope.get("code"));
        return (Map<String, Object>) envelope.get("data");
    }

    @Test
    @DisplayName("/login 成功：立即做一次身份同步，而且在作废旧账户缓存之后")
    void loginSyncsIdentityRightAway() {
        when(accountService.loginWithPhone("13800000000", "123456")).thenReturn(connected());

        controller.login(Map.of("phone", "13800000000", "code", "123456"), null);

        var order = inOrder(cleanup, identitySync);
        order.verify(cleanup).afterConnect();
        order.verify(identitySync).refreshQuietly();
    }

    @Test
    @DisplayName("/login 换了账户：回包带 previousAccountDiffers:true")
    void loginFlagsAccountSwitch() {
        when(accountService.loginWithEmailCode("a@b.com", "654321")).thenReturn(connected());
        when(cleanup.afterConnect()).thenReturn(true);

        Map<String, Object> data = data(controller.login(Map.of("email", "a@b.com", "code", "654321"), null));

        assertEquals(Boolean.TRUE, data.get("previousAccountDiffers"));
        assertEquals(Boolean.TRUE, data.get("connected"));
    }

    @Test
    @DisplayName("/login 没换人：回包不带这个键（形状与改造前一致）")
    void loginWithoutSwitchKeepsShape() {
        when(accountService.loginWithPhone("13800000000", "123456")).thenReturn(connected());
        when(cleanup.afterConnect()).thenReturn(false);

        Map<String, Object> data = data(controller.login(Map.of("phone", "13800000000", "code", "123456"), null));

        assertFalse(data.containsKey("previousAccountDiffers"));
    }

    @Test
    @DisplayName("/connect（粘 Key）同样带换账户标志")
    void connectFlagsAccountSwitch() {
        when(accountService.connect("awdk_x")).thenReturn(connected());
        when(cleanup.afterConnect()).thenReturn(true);

        Map<String, Object> data = data(controller.connect(Map.of("key", "awdk_x"), null));

        assertEquals(Boolean.TRUE, data.get("previousAccountDiffers"));
        verify(identitySync).refreshQuietly();
    }

    @Test
    @DisplayName("/disconnect：走 afterDisconnect（本机身份行回退在那里做）")
    void disconnectRunsCleanup() {
        when(accountService.disconnect()).thenReturn(Map.of("connected", false));

        controller.disconnect(null);

        verify(cleanup).afterDisconnect();
    }
}
