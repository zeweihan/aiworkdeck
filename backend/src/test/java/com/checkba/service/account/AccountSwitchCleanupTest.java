package com.checkba.service.account;

import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.ai.PlatformCreditsGate;
import com.checkba.service.ai.PlatformUsageAccountant;
import com.checkba.service.entitlement.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 换账户后必须整套作废的机器级缓存清单——本用例只补 dev-board#183/#184 新加的一项：
 * /api/account/balance 的 profile/membership TTL 缓存也是账户级内容，必须挂进
 * {@link AccountSwitchCleanup}，不能只指望调用方自己记得清（同文件类注释「地雷 22」的教训）。
 */
class AccountSwitchCleanupTest {

    private AccountService accountService;
    private AccountSwitchCleanup cleanup;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        EntitlementService entitlementService = mock(EntitlementService.class);
        PlatformAiChannel platformAiChannel = mock(PlatformAiChannel.class);
        PlatformCreditsGate platformCreditsGate = mock(PlatformCreditsGate.class);
        PlatformUsageAccountant platformUsageAccountant = mock(PlatformUsageAccountant.class);
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        cleanup = new AccountSwitchCleanup(accountService, entitlementService, platformAiChannel,
                platformCreditsGate, platformUsageAccountant, chatModelFactory);
    }

    @Test
    @DisplayName("afterConnect：清空 balance/membership 缓存，换到的新账户不会先看到上一个账户的余额")
    void afterConnectClearsBalanceCache() {
        cleanup.afterConnect();
        verify(accountService).clearBalanceCache();
    }

    @Test
    @DisplayName("afterDisconnect：同样清空，断开后重连另一个账户不能吃上一个账户的缓存")
    void afterDisconnectClearsBalanceCache() {
        cleanup.afterDisconnect();
        verify(accountService).clearBalanceCache();
    }
}
