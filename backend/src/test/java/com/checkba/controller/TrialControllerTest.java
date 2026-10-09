// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.service.trial.TrialBalanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** GET /api/trial/balance 接线：无会话 401；有会话按 userId 取余额，信封 {code:0,data}。 */
class TrialControllerTest {

    @BeforeEach
    @AfterEach
    void resetAuth() {
        AuthController.registerLocalIdentityService(null);
    }

    @Test
    void noSession_returns401_andDoesNotTouchService() {
        TrialBalanceService svc = mock(TrialBalanceService.class);
        ResponseEntity<Map<String, Object>> r = new TrialController(svc).balance(null);
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        assertEquals(1, r.getBody().get("code"));
        verify(svc, never()).balance(anyLong());
    }

    @Test
    void localMode_resolvesLocalUser_andWrapsEnvelope() {
        com.checkba.service.LocalIdentityService local = mock(com.checkba.service.LocalIdentityService.class);
        when(local.isLocalMode()).thenReturn(true);
        when(local.localUserId()).thenReturn(42L);
        AuthController.registerLocalIdentityService(local);

        TrialBalanceService svc = mock(TrialBalanceService.class);
        when(svc.balance(42L)).thenReturn(Map.of("status", "none", "remainingCalls", 80));
        ResponseEntity<Map<String, Object>> r = new TrialController(svc).balance(null);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(0, r.getBody().get("code"));
        assertEquals(Map.of("status", "none", "remainingCalls", 80), r.getBody().get("data"));
        verify(svc).balance(42L);
    }
}
