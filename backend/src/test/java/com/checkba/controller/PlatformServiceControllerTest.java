package com.checkba.controller;

import com.checkba.service.SystemSettingService;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.meeting.MeetingRecordingNotice;
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

    /** 读写都落进同一张 map：档位、阈值、告知确认全是 system_setting 里的行，写完要读得到。 */
    private static SystemSettingService settingsWith(Map<String, String> rows) {
        SystemSettingService svc = mock(SystemSettingService.class);
        when(svc.get(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return rows.containsKey(key) ? rows.get(key) : inv.getArgument(1);
        });
        doAnswer(inv -> rows.put(inv.getArgument(0), inv.getArgument(1)))
                .when(svc).set(anyString(), any());
        doAnswer(inv -> {
            rows.putAll(inv.<Map<String, String>>getArgument(0));
            return null;
        }).when(svc).setMany(any());
        return svc;
    }

    private record Fixture(PlatformServiceController controller, PlatformGatewayClient gateway,
                           Map<String, String> rows) {}

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
                gateway, rows);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> enabled(Map<String, Object> response) {
        return (Map<String, Boolean>) data(response).get("enabled");
    }

    @Test
    @DisplayName("列表端点一次出站请求都不发——官网挂着时这一页仍是切回自备 Key 的入口")
    void listNeverGoesOutbound() throws Exception {
        Fixture f = fixture(true, true);

        Map<String, Object> res = f.controller().list(null);

        // 这一条是本端点存在的全部理由：把它的可用性挂在远程往返上，
        // 等于在最需要逃生门的时候把门焊死
        verify(f.gateway(), never()).getPricing(anyInt());
        verify(f.gateway(), never()).getMonthlyUsage(anyInt());
        assertEquals(0, res.get("code"));
        assertEquals(6, services(res).size(), "六项服务一个都不能少");
        for (Map<String, Object> s : services(res)) {
            assertNotNull(s.get("provider"), "档位必须照常给出——它是网关挂掉时唯一的自救手段");
        }
    }

    @Test
    @DisplayName("网关不可达时远端那一段整体降级成「不知道」，而不是「未开放 / 余额为零」")
    void gatewayFailureDegradesTheRemoteSectionOnly() throws Exception {
        Fixture f = fixture(true, true);
        when(f.gateway().getPricing(anyInt()))
                .thenThrow(new GatewayException(GatewayException.Kind.GATEWAY_UNREACHABLE, "平台服务暂时不可用"));

        Map<String, Object> res = f.controller().remote(null);

        assertEquals(0, res.get("code"), "取不到也回 code=0 + 空载荷，让前端只写一处兜底");
        assertEquals(Boolean.FALSE, data(res).get("pricingAvailable"));
        assertNull(data(res).get("balanceCents"));
        assertNull(data(res).get("pendingHoldCents"));
        // 「不知道」不等于「未开放」：一次网络抖动把六项全标成未开放，比不显示这个状态更糟
        assertTrue(enabled(res).isEmpty(), "开放状态表应为空而不是一片 false");
    }

    @Test
    @DisplayName("未连账户 / 非 local-mode 时压根不打网关")
    void skipsGatewayWhenItCannotApply() throws Exception {
        Fixture notConnected = fixture(true, false);
        notConnected.controller().remote(null);
        verify(notConnected.gateway(), never()).getPricing(anyInt());

        Fixture serverMode = fixture(false, true);
        serverMode.controller().remote(null);
        verify(serverMode.gateway(), never()).getPricing(anyInt());

        // 非 local-mode 恒 byok（决策 D5）
        Map<String, Object> res = serverMode.controller().list(null);
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

        Map<String, Object> res = f.controller().remote(null);

        assertEquals(Boolean.TRUE, data(res).get("pricingAvailable"));
        assertEquals(12345, data(res).get("balanceCents"));
        // 设计 §4.6：这笔被预扣占住的钱必须可解释，否则用户会同时发现转写与 AI 对话都停了
        assertEquals(600, data(res).get("pendingHoldCents"));
        // 用户关心的是「这项功能能不能用」，不是某个具体 op 的开关
        assertEquals(Boolean.TRUE, enabled(res).get("ocr"));
        assertEquals(Boolean.FALSE, enabled(res).get("qichacha"));
        assertEquals(Boolean.TRUE, enabled(res).get("search"));
        // 定价表里压根没提的服务同样是「不知道」，不是「未开放」
        assertNull(enabled(res).get("pkulaw"));
    }

    // ==================== 本月分服务用量 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> usage(Map<String, Object> response) {
        return (Map<String, Object>) data(response).get("usage");
    }

    @Test
    @DisplayName("用量取不到时整段给 null，绝不退化成一堆 0")
    void unknownUsageIsNullNotZero() throws Exception {
        Fixture f = fixture(true, true);
        when(f.gateway().getPricing(anyInt())).thenReturn(MAPPER.readTree("{\"pricing\":[]}"));
        // 官网还没上这条端点时就是这个形态（404 → MALFORMED）
        when(f.gateway().getMonthlyUsage(anyInt()))
                .thenThrow(new GatewayException(GatewayException.Kind.MALFORMED, "平台服务返回了预期外的状态（404）"));

        Map<String, Object> res = f.controller().remote(null);

        assertEquals(0, res.get("code"), "用量取不到只降级这一段");
        // 「不知道」不等于「零」：显示 0 会让刚跑完一场两小时转写的用户以为账没记上
        assertNull(usage(res), "用量取不到时必须是 null，前端据此显示「—」");
    }

    @Test
    @DisplayName("单价表都没取到就不再问用量——同一个主机，只会把超时再等一遍")
    void skipsUsageWhenPricingAlreadyFailed() throws Exception {
        Fixture f = fixture(true, true);
        when(f.gateway().getPricing(anyInt()))
                .thenThrow(new GatewayException(GatewayException.Kind.GATEWAY_UNREACHABLE, "平台服务暂时不可用"));

        f.controller().remote(null);

        verify(f.gateway(), never()).getMonthlyUsage(anyInt());
    }

    @Test
    @DisplayName("用量按服务分组回给前端，同时给出统计月份")
    void usageIsGroupedByService() throws Exception {
        Fixture f = fixture(true, true);
        when(f.gateway().getPricing(anyInt())).thenReturn(MAPPER.readTree("{\"pricing\":[]}"));
        when(f.gateway().getMonthlyUsage(anyInt())).thenReturn(MAPPER.readTree("""
                {"month":"2026-08","totalCents":1860,
                 "services":[{"service":"asr","cents":1200,"calls":3},
                             {"service":"search","cents":660,"calls":44}]}"""));

        Map<String, Object> res = f.controller().remote(null);

        assertEquals("2026-08", usage(res).get("month"));
        assertEquals(1860, usage(res).get("totalCents"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> byService = (Map<String, Integer>) usage(res).get("services");
        assertEquals(1200, byService.get("asr"));
        assertEquals(660, byService.get("search"));
        // 本月没花过的服务不在表里——它与「查不到」是两回事，前端对前者显示 0、对后者显示「—」
        assertNull(byService.get("ocr"));
    }

    @Test
    @DisplayName("未连账户时压根不问用量")
    void skipsUsageWhenNotConnected() throws Exception {
        Fixture f = fixture(true, false);
        f.controller().remote(null);
        verify(f.gateway(), never()).getMonthlyUsage(anyInt());
    }

    // ==================== 花费闸门的两个阈值 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> budget(Map<String, Object> response) {
        return (Map<String, Object>) data(response).get("budget");
    }

    @Test
    @DisplayName("阈值默认是 0（不启用），写完读得到")
    void budgetRoundTrips() throws Exception {
        Fixture f = fixture(true, false);

        assertEquals(0, budget(f.controller().list(null)).get("taskLimitCents"));
        assertEquals(0, budget(f.controller().list(null)).get("lowBalanceCents"));

        Map<String, Object> saved = f.controller().setBudget(null,
                Map.of("taskLimitCents", 5000, "lowBalanceCents", 2000));

        assertEquals(0, saved.get("code"));
        assertEquals(5000, budget(f.controller().list(null)).get("taskLimitCents"));
        assertEquals(2000, budget(f.controller().list(null)).get("lowBalanceCents"));
    }

    @Test
    @DisplayName("取值非法直接拒，不静默回落成「不限制」")
    void rejectsInvalidBudgetInsteadOfFallingBack() {
        Fixture f = fixture(true, false);
        f.controller().setBudget(null, Map.of("taskLimitCents", 5000, "lowBalanceCents", 2000));

        for (Object bad : List.of("-1", "abc", "")) {
            Map<String, Object> res = f.controller().setBudget(null,
                    Map.of("taskLimitCents", bad, "lowBalanceCents", 0));
            assertEquals(1, res.get("code"), "非法取值 " + bad + " 应被拒");
            // 业务失败绝不能带 4010：那个码前端判掉线并清会话
            assertNotEquals(4010, res.get("code"));
        }
        // 被拒的那次不许留下半截写入
        assertEquals(5000, budget(f.controller().list(null)).get("taskLimitCents"));
    }

    // ==================== 平台档转写的单独告知 ====================

    @Test
    @DisplayName("告知默认未确认，确认后带上版本号；撤回后回到未确认")
    void asrNoticeAcknowledgementRoundTrips() {
        Fixture f = fixture(true, false);

        assertEquals(Boolean.FALSE, data(f.controller().asrNotice(null)).get("acknowledged"),
                "默认必须是「没确认过」——预先勾选的同意在个保法下无效");
        assertFalse(String.valueOf(data(f.controller().asrNotice(null)).get("body")).isBlank(),
                "告知正文由服务端给，文本与版本号才不会各改各的");

        Map<String, Object> after = f.controller().acknowledgeAsrNotice(null, Map.of("acknowledged", true));
        assertEquals(Boolean.TRUE, data(after).get("acknowledged"));
        assertEquals(MeetingRecordingNotice.VERSION, data(after).get("version"));

        Map<String, Object> withdrawn = f.controller().acknowledgeAsrNotice(null, Map.of("acknowledged", false));
        assertEquals(Boolean.FALSE, data(withdrawn).get("acknowledged"));
    }

    @Test
    @DisplayName("请求体缺 acknowledged 字段按「没确认」处理，绝不默认成 true")
    void missingFieldNeverCountsAsAcknowledged() {
        Fixture f = fixture(true, false);
        Map<String, Object> res = f.controller().acknowledgeAsrNotice(null, Map.of());
        assertEquals(Boolean.FALSE, data(res).get("acknowledged"),
                "「客户端忘了传就算确认」是预先勾选的服务端版本");
    }
}
