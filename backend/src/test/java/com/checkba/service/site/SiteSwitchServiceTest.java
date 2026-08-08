package com.checkba.service.site;

import com.checkba.service.LicenseService;
import com.checkba.service.account.AccountService;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.ai.PlatformUsageAccountant;
import com.checkba.service.entitlement.EntitlementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 锁定切站清理表（双主站设计 §2.4）。
 *
 * 切站 = 换了一个完全不同的商业实体，旧站发来的一切都必须当场作废；
 * 唯独**试用码票据要留下**——它是内置公钥离线验签的，与站点无关，
 * 抹掉等于把一个只想换站看看的试用用户直接踢回未解锁页。
 */
class SiteSwitchServiceTest {

    private static final String CN = "https://www.aiworkdeck.com";
    private static final String INTL = "https://www.workdeck.ai";

    @TempDir
    Path tempDir;

    private SiteProfileService profiles() {
        SiteProperties p = new SiteProperties();
        p.setDefaultSite("cn");
        for (String[] row : new String[][]{{"cn", CN}, {"intl", INTL}}) {
            SiteProperties.Site s = new SiteProperties.Site();
            s.setEnabled(true);
            s.setDisplayName(row[0]);
            s.setBaseUrl(row[1]);
            p.getSites().put(row[0], s);
        }
        return new SiteProfileService(true, CN, "cn", tempDir.toString(), p);
    }

    private record Fixture(SiteSwitchService service, SiteProfileService profiles,
                           LicenseService license, AccountService account,
                           EntitlementService entitlements, PlatformAiChannel channel,
                           PlatformUsageAccountant accountant, ChatModelFactory models) {}

    private Fixture fixture(boolean accountConnected, boolean licenseWasAccountMode) {
        SiteProfileService profiles = profiles();
        LicenseService license = mock(LicenseService.class);
        AccountService account = mock(AccountService.class);
        EntitlementService entitlements = mock(EntitlementService.class);
        PlatformAiChannel channel = mock(PlatformAiChannel.class);
        PlatformUsageAccountant accountant = mock(PlatformUsageAccountant.class);
        ChatModelFactory models = mock(ChatModelFactory.class);
        when(license.deactivateAccountMode()).thenReturn(licenseWasAccountMode);
        when(account.isConnected()).thenReturn(accountConnected);
        when(models.demotePlatformProvider()).thenReturn(null);
        return new Fixture(
                new SiteSwitchService(profiles, license, account, entitlements, channel, accountant, models),
                profiles, license, account, entitlements, channel, accountant, models);
    }

    @Test
    @DisplayName("切到另一站：账户断开、权益缓存清空、平台密钥清空、对账基线重置、供应商降级")
    void switchingClearsEverythingFromTheOldSite() {
        Fixture f = fixture(true, true);

        Map<String, Object> result = f.service().switchTo("intl");

        assertEquals("intl", result.get("site"));
        assertEquals(true, result.get("changed"));
        assertEquals(true, result.get("licenseCleared"));
        assertEquals(true, result.get("accountCleared"));
        // 广场与统计上报在属性层固化，本次启动内不改指向
        assertEquals(true, result.get("restartRecommended"));

        verify(f.license()).deactivateAccountMode();
        verify(f.account()).disconnect();
        verify(f.entitlements()).clearAccountCache();
        verify(f.channel()).clearCache();
        verify(f.accountant()).resetBaseline();
        verify(f.models()).demotePlatformProvider();
        assertEquals("intl", f.profiles().currentSite());
        assertEquals(INTL, f.profiles().baseUrl());
    }

    @Test
    @DisplayName("试用码解锁的用户切站：授权票据原样保留，不会被踢回未解锁页")
    void trialTicketSurvivesTheSwitch() {
        Fixture f = fixture(false, false); // deactivateAccountMode 对 trial 返回 false

        Map<String, Object> result = f.service().switchTo("intl");

        assertEquals(false, result.get("licenseCleared"));
        assertEquals(false, result.get("accountCleared"));
        // 未连接账户时不该多此一举地调 disconnect
        verify(f.account(), never()).disconnect();
        // 但权益/密钥/基线仍要清：它们可能是上一次连接留下的残留
        verify(f.entitlements()).clearAccountCache();
        verify(f.channel()).clearCache();
    }

    @Test
    @DisplayName("切到当前站点是幂等的：什么都不清")
    void switchingToSameSiteIsNoop() {
        Fixture f = fixture(true, true);

        Map<String, Object> result = f.service().switchTo("cn");

        assertEquals(false, result.get("changed"));
        assertEquals(false, result.get("restartRecommended"));
        verifyNoInteractions(f.license(), f.entitlements(), f.channel(), f.accountant(), f.models());
        verify(f.account(), never()).disconnect();
    }

    @Test
    @DisplayName("未知站点：先拒绝，一个字节都不清")
    void unknownSiteIsRejectedBeforeAnyCleanup() {
        Fixture f = fixture(true, true);

        assertThrows(IllegalArgumentException.class, () -> f.service().switchTo("mars"));

        verifyNoInteractions(f.license(), f.entitlements(), f.channel(), f.accountant(), f.models());
        verify(f.account(), never()).disconnect();
        assertEquals("cn", f.profiles().currentSite());
    }

    @Test
    @DisplayName("供应商降级发生时把回落值报给前端，避免「显示选中、每条消息都报未连接账户」")
    void reportsProviderFallback() {
        Fixture f = fixture(true, true);
        when(f.models().demotePlatformProvider()).thenReturn("open-router");

        Map<String, Object> result = f.service().switchTo("intl");

        assertEquals("open-router", result.get("aiProviderFallback"));
    }
}
