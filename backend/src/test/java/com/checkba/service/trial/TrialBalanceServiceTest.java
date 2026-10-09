// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.trial;

import com.checkba.model.entity.TrialBalance;
import com.checkba.repository.TrialBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 试用计量 v0.1.1 spike：未发放 / 已发放剩余天与次 / 幂等 grant / 先到为准 / 只读。 */
class TrialBalanceServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-09T00:00:00Z");

    private TrialBalanceRepository repo;
    private final Map<Long, TrialBalance> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        rows.clear();
        repo = mock(TrialBalanceRepository.class);
        when(repo.findByUserId(anyLong())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0, Long.class))));
        when(repo.saveAndFlush(any(TrialBalance.class))).thenAnswer(inv -> {
            TrialBalance b = inv.getArgument(0);
            if (b.getId() == null) b.setId((long) rows.size() + 1);
            rows.put(b.getUserId(), b);
            return b;
        });
    }

    private TrialBalanceService at(Instant now) {
        return new TrialBalanceService(repo, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void notGranted_returnsNoneWithFullQuota_andDoesNotWrite() {
        Map<String, Object> b = at(T0).balance(7L);
        assertEquals(false, b.get("granted"));
        assertEquals("none", b.get("status"));
        assertEquals(14, b.get("remainingDays"));
        assertEquals(80, b.get("remainingCalls"));
        assertEquals("min_of_days_or_calls", b.get("policy"));
        assertEquals("以协议为准", b.get("termsNotice"));
        verify(repo, never()).save(any());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void grant_setsFourteenDayWindowFromGrantTime_andEightyCalls() {
        TrialBalance g = at(T0).grant(7L, "cn");
        assertEquals(T0, g.getTrialStartedAt());
        assertEquals(T0.plus(Duration.ofDays(14)), g.getTrialEndsAt());
        assertEquals(80, g.getCallsQuota());
        assertEquals(0, g.getCallsUsed());
        assertEquals("active", g.getStatus());
        assertEquals("cn", g.getRegion());

        Map<String, Object> b = at(T0.plus(Duration.ofDays(3))).balance(7L);
        assertEquals(true, b.get("granted"));
        assertEquals("active", b.get("status"));
        assertEquals(11, b.get("remainingDays"));
        assertEquals(80, b.get("remainingCalls"));
    }

    @Test
    void grant_isIdempotent_doesNotResetClockOrCalls() {
        at(T0).grant(7L, "cn");
        rows.get(7L).setCallsUsed(5);
        TrialBalance again = at(T0.plus(Duration.ofDays(5))).grant(7L, "intl");
        assertEquals(T0, again.getTrialStartedAt());
        assertEquals(5, again.getCallsUsed());
        assertEquals("cn", again.getRegion());
        verify(repo, times(1)).saveAndFlush(any());
    }

    @Test
    void grant_concurrentInsertLoser_readsBackWinner() {
        TrialBalance winner = new TrialBalance();
        winner.setUserId(9L);
        winner.setTrialStartedAt(T0);
        when(repo.findByUserId(9L)).thenReturn(Optional.empty(), Optional.of(winner));
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_trial_balance_user"));
        assertSame(winner, at(T0.plusSeconds(1)).grant(9L, "cn"));
    }

    @Test
    void remainingDays_roundsUp_andIsZeroAtExpiry() {
        Instant end = T0.plus(Duration.ofDays(14));
        assertEquals(14, TrialBalanceService.remainingDays(end, T0));
        assertEquals(14, TrialBalanceService.remainingDays(end, T0.plusSeconds(3600)));
        assertEquals(1, TrialBalanceService.remainingDays(end, end.minusSeconds(1)));
        assertEquals(0, TrialBalanceService.remainingDays(end, end));
        assertEquals(0, TrialBalanceService.remainingDays(end, end.plusSeconds(10)));
    }

    @Test
    void daysExpireFirst_statusExpired_evenWithCallsLeft() {
        at(T0).grant(7L, "cn");
        rows.get(7L).setCallsUsed(10);
        Map<String, Object> b = at(T0.plus(Duration.ofDays(14))).balance(7L);
        assertEquals("expired_days", b.get("status"));
        assertEquals(0, b.get("remainingDays"));
        assertEquals(70, b.get("remainingCalls"));
    }

    @Test
    void callsExhaustFirst_statusExhausted_evenWithDaysLeft() {
        at(T0).grant(7L, "cn");
        rows.get(7L).setCallsUsed(80);
        Map<String, Object> b = at(T0.plus(Duration.ofDays(2))).balance(7L);
        assertEquals("exhausted_calls", b.get("status"));
        assertEquals(0, b.get("remainingCalls"));
        assertEquals(12, b.get("remainingDays"));
    }

    @Test
    void storedTerminalStatus_winsOverLaterExpiry() {
        at(T0).grant(7L, "cn");
        rows.get(7L).setCallsUsed(80);
        rows.get(7L).setStatus("exhausted_calls");
        Map<String, Object> b = at(T0.plus(Duration.ofDays(20))).balance(7L);
        assertEquals("exhausted_calls", b.get("status"));
    }

    @Test
    void converted_staysConverted() {
        at(T0).grant(7L, "cn");
        rows.get(7L).setStatus("converted");
        assertEquals("converted", at(T0.plus(Duration.ofDays(30))).balance(7L).get("status"));
    }

    @Test
    void balance_isReadOnly_neverWritesEvenWhenExpired() {
        at(T0).grant(7L, "cn");
        clearInvocations(repo);
        at(T0.plus(Duration.ofDays(15))).balance(7L);
        verify(repo, never()).save(any());
        verify(repo, never()).saveAndFlush(any());
        assertEquals("active", rows.get(7L).getStatus());
    }
}
