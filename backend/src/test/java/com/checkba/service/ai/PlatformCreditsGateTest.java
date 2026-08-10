package com.checkba.service.ai;

import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 平台通道余额闸的判据。这道闸决定「没充值能不能用 AI」，两个方向都会出人命：
 * 放太松就是被白嫖，收太紧就是一断网谁都用不了。
 */
class PlatformCreditsGateTest {

    private AccountService accountService;
    private PlatformAiChannel channel;
    private PlatformCreditsGate gate;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        channel = mock(PlatformAiChannel.class);
        when(channel.usesMachineKey(any())).thenReturn(true);
        when(accountService.accountFingerprintOrNull()).thenReturn("acct-a");
        gate = new PlatformCreditsGate(accountService, channel);
    }

    private void websiteReports(Object creditsCents) {
        Map<String, Object> quota = new HashMap<>();
        quota.put("creditsCents", creditsCents);
        when(accountService.fetchAiUsage()).thenReturn(quota);
    }

    @Test
    @DisplayName("余额为 0：拦住，且第一条消息就拦得住（首次判定不能是异步的）")
    void zeroCreditsBlocksFirstCall() {
        websiteReports(0);

        AccountException e = assertThrows(AccountException.class, () -> gate.ensureCredits(1L));

        assertEquals(AccountException.Kind.CONFLICT, e.getKind());
        verify(accountService, times(1)).fetchAiUsage();
    }

    @Test
    @DisplayName("有余额：放行，且 60 秒内不再打官网（不给每条消息加一次往返）")
    void positiveCreditsPassAndAreCached() {
        websiteReports(1234);

        assertDoesNotThrow(() -> gate.ensureCredits(1L));
        assertDoesNotThrow(() -> gate.ensureCredits(1L));
        assertDoesNotThrow(() -> gate.ensureCredits(1L));

        verify(accountService, times(1)).fetchAiUsage();
    }

    @Test
    @DisplayName("官网不可达：放行。查不到不等于没钱——反过来判会让人一断网就用不了")
    void unreachableWebsiteDoesNotBlock() {
        when(accountService.fetchAiUsage())
                .thenThrow(new AccountException(AccountException.Kind.NETWORK, "无法连接服务器"));

        assertDoesNotThrow(() -> gate.ensureCredits(1L));
    }

    @Test
    @DisplayName("官网没给 creditsCents（旧版本）：放行，不拿缺字段当没钱")
    void missingFieldDoesNotBlock() {
        websiteReports(null);

        assertDoesNotThrow(() -> gate.ensureCredits(1L));
    }

    @Test
    @DisplayName("已知为 0 之后官网又不可达：放行。一次抖动不该把刚充完值的人锁住")
    void knownZeroIsNotRememberedAcrossAnOutage() {
        websiteReports(0);
        assertThrows(AccountException.class, () -> gate.ensureCredits(1L));

        gate.reset();
        when(accountService.fetchAiUsage())
                .thenThrow(new AccountException(AccountException.Kind.NETWORK, "无法连接服务器"));

        assertDoesNotThrow(() -> gate.ensureCredits(1L));
    }

    @Test
    @DisplayName("per-user 路径不归这道闸管：额度在官网签发 key 时已按人闸住，这里查也查不到")
    void perUserPathIsNotGatedHere() {
        when(channel.usesMachineKey(any())).thenReturn(false);
        websiteReports(0);

        assertDoesNotThrow(() -> gate.ensureCredits(1L));
        verify(accountService, never()).fetchAiUsage();
    }

    @Test
    @DisplayName("未连接账户不归这道闸管（那是 PlatformAiChannel 的 NOT_CONNECTED）")
    void disconnectedAccountIsSomeoneElsesError() {
        when(accountService.accountFingerprintOrNull()).thenReturn(null);
        websiteReports(0);

        assertDoesNotThrow(() -> gate.ensureCredits(1L));
        verify(accountService, never()).fetchAiUsage();
    }

    @Test
    @DisplayName("换账号：上一个账户的判定结果立刻作废，按新账户重新查")
    void switchingAccountsRechecks() {
        websiteReports(9999);
        assertDoesNotThrow(() -> gate.ensureCredits(1L));

        when(accountService.accountFingerprintOrNull()).thenReturn("acct-b");
        websiteReports(0);

        assertThrows(AccountException.class, () -> gate.ensureCredits(1L));
        verify(accountService, times(2)).fetchAiUsage();
    }

    @Test
    @DisplayName("文案红线：不得含「登录」「未授权」「请先」，否则前端判成掉线清会话")
    void messageIsNotMistakenForLogout() {
        websiteReports(0);

        AccountException e = assertThrows(AccountException.class, () -> gate.ensureCredits(1L));

        assertFalse(e.getMessage().contains("登录"));
        assertFalse(e.getMessage().contains("未授权"));
        assertFalse(e.getMessage().contains("请先"));
    }
}
