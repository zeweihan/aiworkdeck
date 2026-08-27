package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.AccountSwitchCleanup;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.ai.PlatformAiChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * dev-board#183/#184：桌面内嵌余额展示与充值新增的四个转发端点
 * （balance/membership/recharge/recharge/status）。
 *
 * 重点锁两条硬红线：参数校验失败（0/负/超上限/非整数）绝不能表现成 4xx/4010，
 * 未连接账户各端点必须回业务信封而不是让端点本身炸掉。TTL 缓存与换账户失效的行为
 * 在 {@code AccountServiceTest} 里锁（那边直接打桩 transport，能数出站请求次数）。
 */
class AccountControllerRechargeTest {

    private AccountController controller;
    private AccountService accountService;
    private final GlobalExceptionHandler globalHandler = new GlobalExceptionHandler();

    @BeforeEach
    void setUp() {
        AuthController.registerLocalIdentityService(null);
        accountService = mock(AccountService.class);
        MachineAccountGuard guard = mock(MachineAccountGuard.class); // requireMachineScope 默认放行（void no-op）
        controller = new AccountController(accountService, mock(PlatformAiChannel.class),
                mock(AccountSwitchCleanup.class), mock(TokenUsageRepository.class), guard,
                mock(com.checkba.service.entitlement.EntitlementService.class));
    }

    // ==================== recharge 参数校验：拒绝但绝不 4xx/4010 ====================

    @Test
    @DisplayName("amountCents 为 0：业务错误拒绝，服务层零触碰")
    void zeroAmountRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.recharge(Map.of("amountCents", 0), null));
        assertEnvelopeIsBusinessError(e);
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("amountCents 为负数：业务错误拒绝")
    void negativeAmountRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.recharge(Map.of("amountCents", -100), null));
        assertEnvelopeIsBusinessError(e);
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("amountCents 超过 1000000 分（1 万元）上限：业务错误拒绝")
    void overLimitAmountRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.recharge(Map.of("amountCents", 1_000_001), null));
        assertEnvelopeIsBusinessError(e);
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("amountCents 恰为上限（1000000 分）：放行，不是「大于上限才拒」写反成「大于等于」")
    void exactlyAtLimitAllowed() {
        when(accountService.createRecharge(eq(1_000_000L), anyString()))
                .thenReturn(Map.of("success", true, "present", "qrcode", "outTradeNo", "T1"));
        Map<String, Object> result = controller.recharge(Map.of("amountCents", 1_000_000), null);
        assertEquals(0, result.get("code"));
    }

    @Test
    @DisplayName("amountCents 非整数（带小数）：业务错误拒绝")
    void nonIntegerAmountRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.recharge(Map.of("amountCents", 100.5), null));
        assertEnvelopeIsBusinessError(e);
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("amountCents 缺失或不是数字：业务错误拒绝")
    void missingOrNonNumericAmountRejected() {
        assertEnvelopeIsBusinessError(assertThrows(IllegalArgumentException.class,
                () -> controller.recharge(Map.of(), null)));
        assertEnvelopeIsBusinessError(assertThrows(IllegalArgumentException.class,
                () -> controller.recharge(null, null)));
        assertEnvelopeIsBusinessError(assertThrows(IllegalArgumentException.class,
                () -> controller.recharge(Map.of("amountCents", "1000"), null)));
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("每次充值都生成新的 idempotencyKey（UUID）：两次点击必须是两笔订单")
    void idempotencyKeyIsFreshEachCall() {
        when(accountService.createRecharge(anyLong(), anyString()))
                .thenReturn(Map.of("success", true));
        controller.recharge(Map.of("amountCents", 100), null);
        controller.recharge(Map.of("amountCents", 100), null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(accountService, times(2)).createRecharge(eq(100L), captor.capture());
        List<String> keys = captor.getAllValues();
        assertNotEquals(keys.get(0), keys.get(1),
                "幂等键复用会让官网把第二笔当成第一笔的重放，第二笔充值就此消失");
    }

    // ==================== 未连接账户：各端点回业务信封，不是端点炸掉 ====================

    @Test
    @DisplayName("未连接账户：membership 端点回业务信封")
    void membershipNotConnectedIsBusinessEnvelope() {
        when(accountService.fetchMembership()).thenThrow(
                new AccountException(AccountException.Kind.NOT_CONNECTED, "尚未连接账户"));
        AccountException e = assertThrows(AccountException.class, () -> controller.membership(null));
        assertEquals(1, controller.handleAccountException(e).getBody().get("code"));
    }

    @Test
    @DisplayName("未连接账户：recharge 端点回业务信封")
    void rechargeNotConnectedIsBusinessEnvelope() {
        when(accountService.createRecharge(anyLong(), anyString())).thenThrow(
                new AccountException(AccountException.Kind.NOT_CONNECTED, "尚未连接账户"));
        AccountException e = assertThrows(AccountException.class,
                () -> controller.recharge(Map.of("amountCents", 100), null));
        assertEquals(1, controller.handleAccountException(e).getBody().get("code"));
    }

    @Test
    @DisplayName("未连接账户：recharge/status 端点回业务信封")
    void rechargeStatusNotConnectedIsBusinessEnvelope() {
        when(accountService.queryRecharge("T1")).thenThrow(
                new AccountException(AccountException.Kind.NOT_CONNECTED, "尚未连接账户"));
        AccountException e = assertThrows(AccountException.class,
                () -> controller.rechargeStatus("T1", null));
        assertEquals(1, controller.handleAccountException(e).getBody().get("code"));
    }

    @Test
    @DisplayName("未连接账户：balance 端点走 {connected:false} 分支，根本不是异常")
    void balanceNotConnectedReturnsBusinessState() {
        when(accountService.balanceSnapshot()).thenReturn(Map.of("connected", false));
        Map<String, Object> result = controller.balance(null);
        assertEquals(0, result.get("code"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertEquals(false, data.get("connected"));
    }

    @Test
    @DisplayName("outTradeNo 缺失：recharge/status 业务错误拒绝，服务层零触碰")
    void missingOutTradeNoRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.rechargeStatus(null, null));
        assertEnvelopeIsBusinessError(e);
        verifyNoInteractions(accountService);
    }

    // ==================== 内部 ====================

    private void assertEnvelopeIsBusinessError(IllegalArgumentException e) {
        assertNotEquals("未登录", e.getMessage(), "参数校验错误不能撞上鉴权失败的字面量，否则会被误判成 4010");
        assertNotEquals("请先登录", e.getMessage());
        Map<String, Object> envelope = globalHandler.handleIllegalArgumentException(e).getBody();
        assertEquals(1, envelope.get("code"), "参数校验失败必须是 code=1 业务信封，不是 4010/4xx");
    }
}
