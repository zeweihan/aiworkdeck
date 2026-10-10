// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.trial;

import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 规格 §6 验收：只有成功 + 有正文 + 走云端的回合上报；本机不扣减，余额以账户站回包为准。 */
class TrialTurnMeterTest {

    private AccountService account;
    private TrialBalanceService cache;
    private TrialTurnMeter meter;

    @BeforeEach
    void setUp() {
        account = mock(AccountService.class);
        cache = mock(TrialBalanceService.class);
        when(account.isConnected()).thenReturn(true);
        meter = new TrialTurnMeter(account, cache, Runnable::run,
                Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void completedWithTextOnPlatform_reportsOnce_withRunIdAsTurnId_andCachesSiteBalance() {
        Map<String, Object> bal = Map.of("granted", true, "callsUsed", 1);
        when(account.reportTrialTurn(anyMap())).thenReturn(Map.of("counted", true, "balance", bal));
        assertTrue(meter.onTurnEnded("run-1", 7L, "finished", true, true));
        verify(account).reportTrialTurn(argThat(m -> "run-1".equals(m.get("turnId"))
                && "completed".equals(m.get("outcome")) && Boolean.TRUE.equals(m.get("hasAssistantResult"))));
        verify(account).clearTrialCache();
        verify(cache).cacheFromAccount(7L, bal);
    }

    @Test
    void awaitingInput_countsAsCompleted() {
        when(account.reportTrialTurn(anyMap())).thenReturn(Map.of());
        assertTrue(meter.onTurnEnded("run-2", 7L, "awaiting_input", true, true));
    }

    @Test
    void notCounted_failedCancelledPausedNoTextLocalOrDisconnected() {
        assertFalse(meter.onTurnEnded("r", 7L, "error", true, true));
        assertFalse(meter.onTurnEnded("r", 7L, "cancelled", true, true));
        assertFalse(meter.onTurnEnded("r", 7L, "paused", true, true));
        assertFalse(meter.onTurnEnded("r", 7L, "awaiting_approval", true, true));
        assertFalse(meter.onTurnEnded("r", 7L, "finished", false, true), "无可展示正文不计");
        assertFalse(meter.onTurnEnded("r", 7L, "finished", true, false), "纯本地/BYOK 不计");
        when(account.isConnected()).thenReturn(false);
        assertFalse(meter.onTurnEnded("r", 7L, "finished", true, true));
        verify(account, never()).reportTrialTurn(anyMap());
    }

    @Test
    void reportFailure_isSwallowed_andNeverWritesLocalBalance() {
        when(account.reportTrialTurn(anyMap())).thenThrow(
                new AccountException(AccountException.Kind.NETWORK, "down"));
        assertDoesNotThrow(() -> meter.onTurnEnded("run-3", 7L, "finished", true, true));
        verifyNoInteractions(cache);
    }

    @Test
    void scope_marksOnlyInsideRun_andRestores() {
        AtomicBoolean flag = new AtomicBoolean();
        TrialTurnScope.markPlatformUse(); // 作用域外 no-op
        TrialTurnScope.run(flag, () -> {
            TrialTurnScope.markPlatformUse();
            TrialTurnScope.markPlatformUse(); // 同轮多跳仍只是一个布尔
        });
        assertTrue(flag.get());
        AtomicBoolean other = new AtomicBoolean();
        TrialTurnScope.markPlatformUse();
        assertFalse(other.get());
    }
}
