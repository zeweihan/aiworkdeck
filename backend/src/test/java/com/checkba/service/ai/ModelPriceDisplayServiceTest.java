// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型选择器「实付价」折算口径（dev-board#853）。
 *
 * <p>守四件事：① 折算系数 = 官网汇率 × 毛利乘数；② 旧版官网缺 currency 时按汇率推断、判不出就不猜；
 * ③ 取不到一律返回 null（调用方退回标价），<b>不编造汇率</b>；④ 官网慢时本次请求不被拖住。
 */
@DisplayName("模型价格显示口径")
class ModelPriceDisplayServiceTest {

    private static Map<String, Object> usage(Object rate, Object margin, Object currency) {
        Map<String, Object> m = new HashMap<>();
        m.put("configured", true);
        m.put("creditsCents", 1000);
        if (rate != null) m.put("exchangeRate", rate);
        if (margin != null) m.put("marginMultiplier", margin);
        if (currency != null) m.put("currency", currency);
        return m;
    }

    private static AccountService connected(String owner) {
        AccountService account = mock(AccountService.class);
        when(account.isConnected()).thenReturn(true);
        when(account.accountFingerprintOrNull()).thenReturn(owner);
        return account;
    }

    // ==================== parse ====================

    @Test
    @DisplayName("官网给了 currency：原样采用，factor = 汇率 × 毛利乘数，来源与更新时间透传")
    void parseReportedCurrency() {
        Map<String, Object> u = usage(7.1, 1.2, "CNY");
        u.put("exchangeRateSource", "live");
        u.put("exchangeRateUpdatedAt", "2026-09-23T02:00:00Z");
        ModelPriceDisplayService.ChargedRate r = ModelPriceDisplayService.parse(u);
        assertNotNull(r);
        assertEquals("CNY", r.currency());
        assertEquals("reported", r.currencyBasis());
        assertEquals(7.1 * 1.2, r.factor(), 1e-12);
        assertEquals("live", r.rateSource());
        assertEquals("2026-09-23T02:00:00Z", r.rateUpdatedAt());
    }

    @Test
    @DisplayName("旧版官网缺 currency：汇率约等于 1 判 USD、大于 3 判 CNY，中间地带不猜")
    void inferCurrencyWhenMissing() {
        ModelPriceDisplayService.ChargedRate usd = ModelPriceDisplayService.parse(usage(1, 1.2, null));
        assertEquals("USD", usd.currency());
        assertEquals("inferred", usd.currencyBasis());
        assertNull(usd.rateSource(), "旧版官网没有来源字段，不许补一个");
        assertNull(usd.rateUpdatedAt());

        assertEquals("CNY", ModelPriceDisplayService.parse(usage(7.3, 1.2, null)).currency());
        assertEquals("USD", ModelPriceDisplayService.parse(usage(1.04, 1.2, null)).currency());
        assertNull(ModelPriceDisplayService.parse(usage(2.0, 1.2, null)), "判不出币种就退回标价");
        assertNull(ModelPriceDisplayService.parse(usage(1.2, 1.2, null)));
    }

    @Test
    @DisplayName("字段缺失、非正数、不认识的币种、未知来源：一律不编造")
    void refusesToFabricate() {
        assertNull(ModelPriceDisplayService.parse(null));
        assertNull(ModelPriceDisplayService.parse(usage(null, 1.2, "CNY")), "缺汇率");
        assertNull(ModelPriceDisplayService.parse(usage(7.1, null, "CNY")), "缺毛利乘数");
        assertNull(ModelPriceDisplayService.parse(usage(0, 1.2, "CNY")));
        assertNull(ModelPriceDisplayService.parse(usage(-7.1, 1.2, "CNY")));
        assertNull(ModelPriceDisplayService.parse(usage("7.1", 1.2, "CNY")), "字符串不是数");
        assertNull(ModelPriceDisplayService.parse(usage(7.1, 1.2, "EUR")), "官网给了我们不认识的币种，不猜");

        Map<String, Object> odd = usage(7.1, 1.2, "cny");
        odd.put("exchangeRateSource", "guess");
        ModelPriceDisplayService.ChargedRate r = ModelPriceDisplayService.parse(odd);
        assertEquals("CNY", r.currency(), "大小写不敏感");
        assertNull(r.rateSource(), "来源只认 live/manual/default");
    }

    @Test
    @DisplayName("usageAvailable=false 但汇率字段还在：汇率照样可用（官网在上游不可达时仍回这两个字段）")
    void usageUnavailableButRatePresent() {
        Map<String, Object> u = usage(7.1, 1.2, "CNY");
        u.put("usageAvailable", false);
        assertNotNull(ModelPriceDisplayService.parse(u));

        Map<String, Object> bare = new HashMap<>();
        bare.put("usageAvailable", false);
        assertNull(ModelPriceDisplayService.parse(bare));
    }

    // ==================== currentRate ====================

    @Test
    @DisplayName("未连接账户：不出站，直接退回标价")
    void notConnectedNeverCallsOut() {
        AccountService account = mock(AccountService.class);
        when(account.isConnected()).thenReturn(false);
        assertNull(new ModelPriceDisplayService(account, 1000).currentRate());
        verify(account, never()).fetchAiUsage();
    }

    @Test
    @DisplayName("取不到 ai-usage：退回标价（null）")
    void fetchFailureFallsBackToList() {
        AccountService account = connected("owner-a");
        when(account.fetchAiUsage()).thenThrow(new AccountException(AccountException.Kind.NETWORK, "down"));
        assertNull(new ModelPriceDisplayService(account, 1000).currentRate());
    }

    @Test
    @DisplayName("成功结果缓存 10 分钟：连开两次下拉只打一次官网")
    void successIsCached() {
        AccountService account = connected("owner-a");
        when(account.fetchAiUsage()).thenReturn(usage(7.1, 1.2, "CNY"));
        ModelPriceDisplayService svc = new ModelPriceDisplayService(account, 1000);
        assertEquals("CNY", svc.currentRate().currency());
        assertEquals("CNY", svc.currentRate().currency());
        verify(account, times(1)).fetchAiUsage();
    }

    @Test
    @DisplayName("换账户：旧账户的结果作废，重新取")
    void ownerChangeInvalidates() {
        AccountService account = connected("owner-a");
        when(account.fetchAiUsage()).thenReturn(usage(7.1, 1.2, "CNY"), usage(1, 1.2, "USD"));
        ModelPriceDisplayService svc = new ModelPriceDisplayService(account, 1000);
        assertEquals("CNY", svc.currentRate().currency());
        when(account.accountFingerprintOrNull()).thenReturn("owner-b");
        assertEquals("USD", svc.currentRate().currency());
        verify(account, times(2)).fetchAiUsage();
    }

    @Test
    @DisplayName("官网慢：本次请求最多等 waitMs 就退回标价，后台取完写进缓存，下次打开就有实付价")
    void slowWebsiteDoesNotBlock() throws Exception {
        AccountService account = connected("owner-a");
        CountDownLatch release = new CountDownLatch(1);
        when(account.fetchAiUsage()).thenAnswer(inv -> {
            // 模拟官网 5 秒超时那一档的慢响应；测试结束前一定会放行
            release.await(10, TimeUnit.SECONDS);
            return usage(7.1, 1.2, "CNY");
        });
        ModelPriceDisplayService svc = new ModelPriceDisplayService(account, 150);

        long t0 = System.nanoTime();
        assertNull(svc.currentRate(), "等不到就退回标价，不许编一个");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(elapsedMs < 1000, "模型目录被官网拖住了 " + elapsedMs + "ms");

        // 慢的那次还在路上时再开一次下拉：不许再发第二个请求
        assertNull(svc.currentRate());
        release.countDown();

        long deadline = System.currentTimeMillis() + 5000;
        ModelPriceDisplayService.ChargedRate r = null;
        while (r == null && System.currentTimeMillis() < deadline) {
            r = svc.currentRate();
            if (r == null) Thread.sleep(20);
        }
        assertNotNull(r, "后台那次取完没有写进缓存");
        assertEquals("CNY", r.currency());
        verify(account, times(1)).fetchAiUsage();
    }
}
