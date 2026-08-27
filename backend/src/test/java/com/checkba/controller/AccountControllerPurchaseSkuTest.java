package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.AccountSwitchCleanup;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.account.SkuPurchaseException;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.entitlement.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * dev-board#187：应用内 SKU 购买代理（POST /api/account/purchase-sku）。
 *
 * 三条硬红线：白名单外（含市场付费项 skill:/plugin:）根本不许出站；
 * 官网 4xx 映射成 code=1 业务信封且带机器可读 reason，绝不 4xx/4010；
 * 成功路径必须同步刷新权益 + 作废余额缓存（前端点完立刻重查，两者都得是新值）。
 */
class AccountControllerPurchaseSkuTest {

    private AccountController controller;
    private AccountService accountService;
    private EntitlementService entitlementService;
    private final GlobalExceptionHandler globalHandler = new GlobalExceptionHandler();

    @BeforeEach
    void setUp() {
        AuthController.registerLocalIdentityService(null);
        accountService = mock(AccountService.class);
        entitlementService = mock(EntitlementService.class);
        MachineAccountGuard guard = mock(MachineAccountGuard.class); // requireMachineScope 默认放行（void no-op）
        controller = new AccountController(accountService, mock(PlatformAiChannel.class),
                mock(AccountSwitchCleanup.class), mock(TokenUsageRepository.class), guard,
                entitlementService);
    }

    // ==================== 白名单 ====================

    @Test
    @DisplayName("白名单外的 skuId（市场项/任意串/缺失）：业务错误拒绝，服务层零触碰")
    void nonWhitelistedSkuRejected() {
        for (Map<String, String> body : java.util.List.of(
                Map.of("skuId", "skill:some-paid-skill"),
                Map.of("skuId", "plugin:litigation-visual"),
                Map.of("skuId", "feature:app.unlocked"),
                Map.of("skuId", ""),
                Map.<String, String>of())) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.purchaseSku(body, null));
            assertEnvelopeIsBusinessError(e);
        }
        assertThrows(IllegalArgumentException.class, () -> controller.purchaseSku(null, null));
        verifyNoInteractions(accountService, entitlementService);
    }

    // ==================== 官网 4xx 映射 ====================

    @Test
    @DisplayName("insufficient_credits：code=1 业务信封 + reason，权益刷新与缓存清理都不发生")
    void insufficientCreditsMapsToBusinessEnvelope() {
        when(accountService.purchaseSku("feature:clipboard.unlimited")).thenThrow(
                new SkuPurchaseException(AccountException.Kind.CONFLICT, "insufficient_credits",
                        "账户 Credits 余额不足，充值后再试"));
        SkuPurchaseException e = assertThrows(SkuPurchaseException.class,
                () -> controller.purchaseSku(Map.of("skuId", "feature:clipboard.unlimited"), null));

        Map<String, Object> envelope = controller.handleSkuPurchaseException(e).getBody();
        assertEquals(1, envelope.get("code"), "官网 4xx 必须映射成 code=1 业务信封，不是 4xx/4010");
        assertEquals("insufficient_credits", envelope.get("reason"));
        assertMessageNotMistakenForLogout(String.valueOf(envelope.get("message")));
        // 没买成，权益/余额都没变，不该触发刷新与缓存作废
        verifyNoInteractions(entitlementService);
        verify(accountService, never()).clearBalanceCache();
    }

    @Test
    @DisplayName("already_owned：同样是业务信封，reason 可区分「已拥有」")
    void alreadyOwnedMapsToBusinessEnvelope() {
        when(accountService.purchaseSku("feature:stage.unlimited")).thenThrow(
                new SkuPurchaseException(AccountException.Kind.CONFLICT, "already_owned",
                        "该功能已拥有，无需重复购买；刷新权益即可使用"));
        SkuPurchaseException e = assertThrows(SkuPurchaseException.class,
                () -> controller.purchaseSku(Map.of("skuId", "feature:stage.unlimited"), null));
        Map<String, Object> envelope = controller.handleSkuPurchaseException(e).getBody();
        assertEquals(1, envelope.get("code"));
        assertEquals("already_owned", envelope.get("reason"));
        assertMessageNotMistakenForLogout(String.valueOf(envelope.get("message")));
    }

    // ==================== 成功路径 ====================

    @Test
    @DisplayName("购买成功：响应带 ok/feature/balanceCents，且同步触发权益刷新与余额缓存清理")
    void successRefreshesEntitlementsAndClearsBalanceCache() {
        when(accountService.purchaseSku("feature:clipboard.unlimited")).thenReturn(Map.of(
                "ok", true,
                "feature", "clipboard.unlimited",
                "priceCents", 1990,
                "balanceCents", 8010,
                "orderId", "o-1"));

        Map<String, Object> result =
                controller.purchaseSku(Map.of("skuId", "feature:clipboard.unlimited"), null);

        assertEquals(0, result.get("code"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertEquals(true, data.get("ok"));
        assertEquals("clipboard.unlimited", data.get("feature"));
        assertEquals(8010, data.get("balanceCents"));
        // 与 GET /api/entitlements?refresh=true 同一条刷新路
        verify(entitlementService).refreshQuietly();
        verify(accountService).clearBalanceCache();
    }

    // ==================== 内部 ====================

    private void assertEnvelopeIsBusinessError(IllegalArgumentException e) {
        assertMessageNotMistakenForLogout(e.getMessage());
        Map<String, Object> envelope = globalHandler.handleIllegalArgumentException(e).getBody();
        assertEquals(1, envelope.get("code"), "白名单拒绝必须是 code=1 业务信封，不是 4010/4xx");
    }

    /** 文案红线：不得撞上前端历史掉线判定的三个子串，也绝不带 4010。 */
    private static void assertMessageNotMistakenForLogout(String message) {
        assertNotNull(message);
        assertFalse(message.contains("登录"), message);
        assertFalse(message.contains("未授权"), message);
        assertFalse(message.contains("请先"), message);
        assertFalse(message.contains("4010"), message);
    }
}
