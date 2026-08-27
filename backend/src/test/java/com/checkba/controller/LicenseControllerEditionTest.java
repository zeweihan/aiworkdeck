package com.checkba.controller;

import com.checkba.service.LicenseService;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.AccountSwitchCleanup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 授权展示口径：授权票据（license.json 的 mode）与账户连接（account.json）是两条独立状态。
 * 「先用试用码解锁、后连账户」的用户 mode 永远停在 trial，只读 mode 的界面会一直显示试用版。
 * 这里钉住组合规则本身，以及它**不回写** LicenseService 落盘状态。
 */
class LicenseControllerEditionTest {

    private static LicenseService license(boolean localMode, Map<String, Object> status) {
        LicenseService service = mock(LicenseService.class);
        when(service.isLocalMode()).thenReturn(localMode);
        when(service.status()).thenReturn(status);
        return service;
    }

    private static Map<String, Object> unlocked(String mode, String plan) {
        return Map.of("unlocked", true, "mode", mode, "plan", plan);
    }

    @Test
    @DisplayName("试用码解锁 + 账户已连接 → 正式版（本 PR 修的现象）")
    void trialTicketWithConnectedAccountIsPaid() {
        LicenseService licenseService = license(true, unlocked("trial", "trial"));
        AccountService accountService = mock(AccountService.class);
        when(accountService.isConnected()).thenReturn(true);
        LicenseController controller = new LicenseController(
                licenseService, accountService, mock(AccountSwitchCleanup.class),
                com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com"),
                mock(com.checkba.service.SystemSettingService.class));

        Map<String, Object> status = controller.status();

        assertEquals("paid", status.get("edition"));
        assertEquals(true, status.get("accountConnected"));
        // 组合只发生在展示层：票据本身一个字都不许改
        assertEquals("trial", status.get("mode"));
        verify(licenseService, never()).activate(anyString());
        verify(licenseService, never()).deactivate();
    }

    @Test
    @DisplayName("试用码解锁 + 未连账户 → 试用版")
    void trialTicketWithoutAccountStaysTrial() {
        AccountService accountService = mock(AccountService.class);
        when(accountService.isConnected()).thenReturn(false);
        LicenseController controller = new LicenseController(
                license(true, unlocked("trial", "trial")), accountService, mock(AccountSwitchCleanup.class),
                com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com"),
                mock(com.checkba.service.SystemSettingService.class));

        Map<String, Object> status = controller.status();

        assertEquals("trial", status.get("edition"));
        assertEquals(false, status.get("accountConnected"));
    }

    @Test
    @DisplayName("账户 Key 解锁 → 正式版（哪怕账户状态一时读不出来）")
    void accountTicketIsPaid() {
        AccountService accountService = mock(AccountService.class);
        when(accountService.isConnected()).thenThrow(new RuntimeException("凭据文件损坏"));
        LicenseController controller = new LicenseController(
                license(true, unlocked("account", "paid")), accountService, mock(AccountSwitchCleanup.class),
                com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com"),
                mock(com.checkba.service.SystemSettingService.class));

        Map<String, Object> status = controller.status();

        assertEquals("paid", status.get("edition"));
        assertEquals(false, status.get("accountConnected"));
    }

    @Test
    @DisplayName("未解锁 → none")
    void lockedIsNone() {
        AccountService accountService = mock(AccountService.class);
        when(accountService.isConnected()).thenReturn(false);
        LicenseController controller = new LicenseController(
                license(true, Map.of("unlocked", false, "mode", "none", "plan", "none")),
                accountService, mock(AccountSwitchCleanup.class),
                com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com"),
                mock(com.checkba.service.SystemSettingService.class));

        assertEquals("none", controller.status().get("edition"));
    }

    @Test
    @DisplayName("团队服务器：恒正式版，且不去读机器级账户状态（该位不对匿名请求暴露）")
    void serverModeIsPaidAndNeverReadsAccount() {
        AccountService accountService = mock(AccountService.class);
        LicenseController controller = new LicenseController(
                license(false, unlocked("account", "paid")), accountService, mock(AccountSwitchCleanup.class),
                com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com"),
                mock(com.checkba.service.SystemSettingService.class));

        Map<String, Object> status = controller.status();

        assertEquals("paid", status.get("edition"));
        assertEquals(false, status.get("accountConnected"));
        verify(accountService, never()).isConnected();
    }

    // ==================== activate：账户连接失败要可见 ====================

    @Test
    @DisplayName("awdk_ 解锁但账户连接失败：附 accountNotice，且提示不像掉线")
    void activateSurfacesAccountConnectFailure() {
        LicenseService licenseService = license(true, unlocked("account", "paid"));
        when(licenseService.activate(anyString())).thenReturn(unlocked("account", "paid"));
        AccountService accountService = mock(AccountService.class);
        when(accountService.connect(anyString())).thenThrow(
                new AccountException(AccountException.Kind.NETWORK, "无法连接账户服务器"));
        LicenseController controller = new LicenseController(
                licenseService, accountService, mock(AccountSwitchCleanup.class),
                com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com"),
                mock(com.checkba.service.SystemSettingService.class));

        ResponseEntity<Map<String, Object>> response =
                controller.activate(Map.of("code", "awdk_abcdef"));

        assertTrue(response.getStatusCode().is2xxSuccessful(), "连接失败不回滚解锁");
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("unlocked"));
        assertEquals(false, body.get("accountConnected"));
        String notice = String.valueOf(body.get("accountNotice"));
        assertTrue(notice.contains("账户连接未完成"));
        assertFalse(notice.contains("登录"));
        assertFalse(notice.contains("未授权"));
        assertFalse(notice.contains("请先"));
    }

    @Test
    @DisplayName("awdk_ 解锁且账户连接成功：accountConnected=true，无提示")
    void activateReportsAccountConnected() {
        LicenseService licenseService = license(true, unlocked("account", "paid"));
        when(licenseService.activate(anyString())).thenReturn(unlocked("account", "paid"));
        AccountService accountService = mock(AccountService.class);
        AccountSwitchCleanup accountSwitchCleanup = mock(AccountSwitchCleanup.class);
        LicenseController controller = new LicenseController(
                licenseService, accountService, accountSwitchCleanup,
                com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com"),
                mock(com.checkba.service.SystemSettingService.class));

        Map<String, Object> body = controller.activate(Map.of("code", "awdk_abcdef")).getBody();

        assertNotNull(body);
        assertEquals(true, body.get("accountConnected"));
        assertNull(body.get("accountNotice"));
        // 解锁页换账号必须走与设置页同一套作废动作。只刷权益、不清平台密钥/余额判定/用量基线的话，
        // 没充值的新账号会接着花上一个账号的 OpenRouter 额度
        verify(accountSwitchCleanup).afterConnect();
    }

    @Test
    @DisplayName("试用码解锁：不碰账户连接，也不附账户字段")
    void activateTrialCodeLeavesAccountAlone() {
        LicenseService licenseService = license(true, unlocked("trial", "trial"));
        when(licenseService.activate(anyString())).thenReturn(unlocked("trial", "trial"));
        AccountService accountService = mock(AccountService.class);
        LicenseController controller = new LicenseController(
                licenseService, accountService, mock(AccountSwitchCleanup.class),
                com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com"),
                mock(com.checkba.service.SystemSettingService.class));

        Map<String, Object> body = controller.activate(Map.of("code", "AWD-T-XXXX")).getBody();

        assertNotNull(body);
        assertNull(body.get("accountNotice"));
        assertNull(body.get("accountConnected"));
        verify(accountService, never()).connect(anyString());
    }
}
