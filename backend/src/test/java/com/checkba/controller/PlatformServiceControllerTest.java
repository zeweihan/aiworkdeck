package com.checkba.controller;

import com.checkba.service.SystemSettingService;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.GatewayException;
import com.checkba.service.platform.PlatformGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 「平台服务」面板端点。
 *
 * <p>这里守的核心不是「字段对不对」，而是<b>网关挂了的时候设置页还能不能打开</b>——
 * 档位切换恰恰是用户在网关出问题时唯一的自救手段，把它锁在一次失败的网络请求后面
 * 等于在最需要逃生门的时候把门焊死。
 */
class PlatformServiceControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SystemSettingService settingsWith(Map<String, String> rows) {
        SystemSettingService svc = mock(SystemSettingService.class);
        when(svc.get(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return rows.containsKey(key) ? rows.get(key) : inv.getArgument(1);
        });
        return svc;
    }

    private record Fixture(PlatformServiceController controller, PlatformGatewayClient gateway) {}

    private static Fixture fixture(boolean localMode, boolean connected) {
        Map<String, String> rows = new HashMap<>();
        SystemSettingService settings = settingsWith(rows);
        ExternalProviderResolver resolver = new ExternalProviderResolver(settings, localMode);
        AccountService account = mock(AccountService.class);
        when(account.currentKeyOrNull()).thenReturn(connected ? "awdk_test" : null);
        PlatformGatewayClient gateway = mock(PlatformGatewayClient.class);
        return new Fixture(
                new PlatformServiceController(resolver, settings, account,
                        mock(MachineAccountGuard.class), gateway),
                gateway);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> services(Map<String, Object> response) {
        return (List<Map<String, Object>>) data(response).get("services");
    }

    private static Map<String, Object> serviceRow(Map<String, Object> response, String service) {
        return services(response).stream()
                .filter(s -> service.equals(s.get("service")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("清单里没有服务 " + service));
    }

    @Test
    @DisplayName("网关不可达时设置页照常打开，只把开放状态降级成「不知道」")
    void gatewayFailureDegradesOnlyThePricingSection() throws Exception {
        Fixture f = fixture(true, true);
        when(f.gateway().getPricing(anyInt()))
                .thenThrow(new GatewayException(GatewayException.Kind.GATEWAY_UNREACHABLE, "平台服务暂时不可用"));

        Map<String, Object> res = f.controller().list(null);

        assertEquals(0, res.get("code"));
        assertEquals(6, services(res).size(), "六项服务一个都不能少");
        assertEquals(Boolean.FALSE, data(res).get("pricingAvailable"));
        assertNull(data(res).get("balanceCents"));
        assertNull(data(res).get("pendingHoldCents"));
        // 「不知道」不等于「未开放」：一次网络抖动把六项全标成未开放，比不显示这个状态更糟
        for (Map<String, Object> s : services(res)) {
            assertNull(s.get("enabled"), s.get("service") + " 的 enabled 应为 null 而不是 false");
            assertNotNull(s.get("provider"), "档位必须照常给出——它是网关挂掉时唯一的自救手段");
        }
    }

    @Test
    @DisplayName("未连账户 / 非 local-mode 时压根不打网关")
    void skipsGatewayWhenItCannotApply() throws Exception {
        Fixture notConnected = fixture(true, false);
        notConnected.controller().list(null);
        verify(notConnected.gateway(), never()).getPricing(anyInt());

        Fixture serverMode = fixture(false, true);
        Map<String, Object> res = serverMode.controller().list(null);
        verify(serverMode.gateway(), never()).getPricing(anyInt());
        // 非 local-mode 恒 byok（决策 D5）
        assertEquals(Boolean.FALSE, data(res).get("platformAvailable"));
        assertEquals("byok", serviceRow(res, "search").get("provider"));
    }

    @Test
    @DisplayName("同一服务的多行定价按「有一行开着就算开」合并")
    void mergesEnabledAcrossPricingRows() throws Exception {
        Fixture f = fixture(true, true);
        when(f.gateway().getPricing(anyInt())).thenReturn(MAPPER.readTree("""
                {"pricing":[
                  {"service":"ocr","op":"recognize","enabled":false},
                  {"service":"ocr","op":"advanced","enabled":true},
                  {"service":"qichacha","op":"eci_info","enabled":false},
                  {"service":"search","op":"web","enabled":true}
                ],"balanceCents":12345,"pendingHoldCents":600}"""));

        Map<String, Object> res = f.controller().list(null);

        assertEquals(Boolean.TRUE, data(res).get("pricingAvailable"));
        assertEquals(12345, data(res).get("balanceCents"));
        // 设计 §4.6：这笔被预扣占住的钱必须可解释，否则用户会同时发现转写与 AI 对话都停了
        assertEquals(600, data(res).get("pendingHoldCents"));
        // 用户关心的是「这项功能能不能用」，不是某个具体 op 的开关
        assertEquals(Boolean.TRUE, serviceRow(res, "ocr").get("enabled"));
        assertEquals(Boolean.FALSE, serviceRow(res, "qichacha").get("enabled"));
        assertEquals(Boolean.TRUE, serviceRow(res, "search").get("enabled"));
        // 定价表里压根没提的服务同样是「不知道」，不是「未开放」
        assertNull(serviceRow(res, "pkulaw").get("enabled"));
    }
}
