// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.service.account.AccountService;
import com.checkba.service.trial.TrialBalanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** GET /api/trial/balance：权威取自账户站（同 /api/account/balance 代理），本地只缓存 + 兜底展示。 */
class TrialControllerTest {

    @BeforeEach
    @AfterEach
    void resetAuth() {
        AuthController.registerLocalIdentityService(null);
    }

    private void localUser(long id) {
        com.checkba.service.LocalIdentityService local = mock(com.checkba.service.LocalIdentityService.class);
        when(local.isLocalMode()).thenReturn(true);
        when(local.localUserId()).thenReturn(id);
        AuthController.registerLocalIdentityService(local);
    }

    @Test
    void noSession_returns401_andDoesNotTouchAnything() {
        TrialBalanceService svc = mock(TrialBalanceService.class);
        AccountService acc = mock(AccountService.class);
        ResponseEntity<Map<String, Object>> r = new TrialController(svc, acc).balance(null);
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        verifyNoInteractions(svc, acc);
    }

    @Test
    void notConnected_returnsConnectedFalse_noLocalAuthority() {
        localUser(42L);
        TrialBalanceService svc = mock(TrialBalanceService.class);
        AccountService acc = mock(AccountService.class);
        when(acc.trialSnapshot()).thenReturn(Map.of("connected", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) new TrialController(svc, acc).balance(null).getBody().get("data");
        assertEquals(false, data.get("connected"));
        assertEquals("以协议为准", data.get("termsNotice"));
        verify(svc, never()).balance(anyLong());
    }

    @Test
    void connected_returnsSiteValuesAsAuthoritative_andWritesCache() {
        localUser(42L);
        TrialBalanceService svc = mock(TrialBalanceService.class);
        AccountService acc = mock(AccountService.class);
        Map<String, Object> site = Map.of("granted", true, "status", "active", "remainingDays", 9, "remainingCalls", 61);
        when(acc.trialSnapshot()).thenReturn(Map.of("connected", true, "available", true, "trial", site));
        ResponseEntity<Map<String, Object>> r = new TrialController(svc, acc).balance(null);
        assertEquals(0, r.getBody().get("code"));
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");
        assertEquals(61, data.get("remainingCalls"));
        assertEquals(9, data.get("remainingDays"));
        assertEquals(true, data.get("authoritative"));
        assertEquals("account", data.get("source"));
        verify(svc).cacheFromAccount(42L, site);
    }

    @Test
    void siteUnreachable_fallsBackToCache_markedStaleNonAuthoritative() {
        localUser(42L);
        TrialBalanceService svc = mock(TrialBalanceService.class);
        AccountService acc = mock(AccountService.class);
        when(acc.trialSnapshot()).thenReturn(Map.of("connected", true, "available", false));
        when(svc.balance(42L)).thenReturn(Map.of("remainingCalls", 50, "authoritative", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) new TrialController(svc, acc).balance(null).getBody().get("data");
        assertEquals(50, data.get("remainingCalls"));
        assertEquals(false, data.get("authoritative"));
        assertEquals(true, data.get("stale"));
        assertEquals(false, data.get("available"));
    }
}
