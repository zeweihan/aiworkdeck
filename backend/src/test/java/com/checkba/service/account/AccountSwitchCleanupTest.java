// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.account;

import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.ai.PlatformCreditsGate;
import com.checkba.service.ai.PlatformUsageAccountant;
import com.checkba.service.entitlement.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 换账户后必须整套作废的机器级缓存清单——本用例只补 dev-board#183/#184 新加的一项：
 * /api/account/balance 的 profile/membership TTL 缓存也是账户级内容，必须挂进
 * {@link AccountSwitchCleanup}，不能只指望调用方自己记得清（同文件类注释「地雷 22」的教训）。
 */
class AccountSwitchCleanupTest {

    private AccountService accountService;
    private AccountSwitchCleanup cleanup;

    com.checkba.service.team.TeamUsageSettings teamUsageSettings;
    com.checkba.service.team.TeamSettingsCache teamSettingsCache;
    com.checkba.service.team.TeamProjectNameNotice teamProjectNameNotice;
    com.checkba.service.SystemSettingService systemSettingService;
    AccountIdentitySync identitySync;
    ChatModelFactory chatModelFactory;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        EntitlementService entitlementService = mock(EntitlementService.class);
        PlatformAiChannel platformAiChannel = mock(PlatformAiChannel.class);
        PlatformCreditsGate platformCreditsGate = mock(PlatformCreditsGate.class);
        PlatformUsageAccountant platformUsageAccountant = mock(PlatformUsageAccountant.class);
        chatModelFactory = mock(ChatModelFactory.class);
        teamUsageSettings = mock(com.checkba.service.team.TeamUsageSettings.class);
        teamSettingsCache = mock(com.checkba.service.team.TeamSettingsCache.class);
        teamProjectNameNotice = mock(com.checkba.service.team.TeamProjectNameNotice.class);
        systemSettingService = mock(com.checkba.service.SystemSettingService.class);
        identitySync = mock(AccountIdentitySync.class);
        cleanup = new AccountSwitchCleanup(accountService, entitlementService, platformAiChannel,
                platformCreditsGate, platformUsageAccountant, chatModelFactory,
                teamUsageSettings, teamSettingsCache, teamProjectNameNotice,
                systemSettingService, identitySync);
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

    @Test
    @DisplayName("换账户要一并作废团队上报台账与团队设置缓存（dev-board#496）")
    void accountSwitchResetsTeamUsageLedger() {
        cleanup.afterConnect();
        verify(teamUsageSettings).resetLedger();
        verify(teamSettingsCache).clear();

        cleanup.afterDisconnect();
        verify(teamUsageSettings, org.mockito.Mockito.times(2)).resetLedger();
        verify(teamSettingsCache, org.mockito.Mockito.times(2)).clear();
    }

    @Test
    @DisplayName("换账户要重新问一次项目名（C4）：上一个账户的同意不能覆盖新团队的听众")
    void accountSwitchResetsProjectNameNotice() {
        cleanup.afterConnect();
        verify(teamProjectNameNotice).reset();

        cleanup.afterDisconnect();
        verify(teamProjectNameNotice, org.mockito.Mockito.times(2)).reset();
    }

    // ==================== 换账户提示与断开回退（登录后置 §5.5，dev-board#1046） ====================

    @Test
    @DisplayName("第一次连账户：不提示「换了账户」，但记下这次的账户 id")
    void firstConnectRecordsWithoutFlag() {
        when(accountService.currentAccountIdOrNull()).thenReturn("acc_a");
        when(systemSettingService.get(AccountSwitchCleanup.KEY_LAST_ACCOUNT_ID, null)).thenReturn(null);

        assertFalse(cleanup.afterConnect());
        verify(systemSettingService).set(AccountSwitchCleanup.KEY_LAST_ACCOUNT_ID, "acc_a");
    }

    @Test
    @DisplayName("同一个账户重新登录（每次登录官网都换一把新 Key）：不提示")
    void sameAccountAgainIsNotASwitch() {
        when(accountService.currentAccountIdOrNull()).thenReturn("acc_a");
        when(systemSettingService.get(AccountSwitchCleanup.KEY_LAST_ACCOUNT_ID, null)).thenReturn("acc_a");

        assertFalse(cleanup.afterConnect());
        verify(systemSettingService, never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("换了一个账户：提示一次，并把记录改成新账户（下次同一账户不再提示）")
    void differentAccountFlagsOnce() {
        when(accountService.currentAccountIdOrNull()).thenReturn("acc_b");
        when(systemSettingService.get(AccountSwitchCleanup.KEY_LAST_ACCOUNT_ID, null)).thenReturn("acc_a");

        assertTrue(cleanup.afterConnect());
        verify(systemSettingService).set(AccountSwitchCleanup.KEY_LAST_ACCOUNT_ID, "acc_b");
    }

    @Test
    @DisplayName("官网没给 accountId（旧盘/契约漂移）：不猜，不提示也不覆盖旧记录")
    void unknownAccountIdNeverFlags() {
        when(accountService.currentAccountIdOrNull()).thenReturn(null);

        assertFalse(cleanup.afterConnect());
        verify(systemSettingService, never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("单机版断开账户：AI 供应商不降级（下一条消息由 4011 → 登录弹层承接，dev-board#1046）")
    void localModeDisconnectKeepsPlatformProvider() {
        org.springframework.test.util.ReflectionTestUtils.setField(cleanup, "localMode", true);

        org.junit.jupiter.api.Assertions.assertNull(cleanup.afterDisconnect());
        verify(chatModelFactory, never()).demotePlatformProvider();
    }

    @Test
    @DisplayName("团队服务器断开机器级账户：仍降级（原行为）并把回落值交给调用方")
    void serverModeDisconnectStillDemotes() {
        when(chatModelFactory.demotePlatformProvider()).thenReturn("OLLAMA");

        org.junit.jupiter.api.Assertions.assertEquals("OLLAMA", cleanup.afterDisconnect());
    }

    @Test
    @DisplayName("断开账户：本机身份行回退哨兵名与空头像；账户记录保留（下次登录才比得出换没换人）")
    void disconnectResetsIdentityButKeepsRecord() {
        cleanup.afterDisconnect();

        verify(identitySync).resetToLocal();
        verify(systemSettingService, never()).set(anyString(), anyString());
    }
}
