// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.model.entity.ProjectInvitation;
import com.checkba.model.entity.User;
import com.checkba.service.AuthAbuseGuard;
import com.checkba.service.ClientInvitationService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客户访问码登录接 AuthAbuseGuard（dev-board#1050）：案件库托管客户门户后，这是公网上
 * 唯一「凭一串码换会话」的匿名入口。按 IP 连错 5 次即锁，锁期内连正确的码也不放。
 */
class AuthClientLoginGuardTest {

    private static MockHttpServletRequest http(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(ip);
        return r;
    }

    private static AuthController.ClientLoginRequest req(String code, String name) {
        AuthController.ClientLoginRequest r = new AuthController.ClientLoginRequest();
        r.setAccessCode(code);
        r.setDisplayName(name);
        return r;
    }

    private AuthController controller(ClientInvitationService invitations, UserService users) {
        return new AuthController(users, invitations, null, null, new AuthAbuseGuard(false, "open"),
                null, null, null, null,
                new com.checkba.service.UserSessionService(mock(com.checkba.repository.UserSessionRepository.class), 7),
                false, null, mock(com.checkba.service.account.AccountDeletionService.class), null);
    }

    @Test
    @DisplayName("连错 5 次后锁定，正确的码也进不来；换一个 IP 不受影响")
    void wrongCodesLockTheIp() {
        ClientInvitationService invitations = mock(ClientInvitationService.class);
        when(invitations.validateCode(any())).thenThrow(new IllegalArgumentException("访问码无效"));
        ProjectInvitation good = new ProjectInvitation();
        good.setProjectId(1L);
        good.setRelatedUserId(10L);
        org.mockito.Mockito.doReturn(good).when(invitations).validateCode("GOOD");
        UserService users = mock(UserService.class);
        User u = new User();
        u.setId(10L);
        u.setUsername("client_x");
        u.setDisplayName("客户");
        u.setRole("CLIENT");
        u.setSubscriptionType("FREE");
        when(users.getUserById(10L)).thenReturn(u);
        AuthController c = controller(invitations, users);

        for (int i = 0; i < 5; i++) {
            assertEquals(1, c.clientLogin(req("BAD" + i, null), http("1.2.3.4")).get("code"));
        }
        Map<String, Object> locked = c.clientLogin(req("GOOD", null), http("1.2.3.4"));
        assertEquals(1, locked.get("code"));
        assertTrue(String.valueOf(locked.get("message")).contains("锁定")
                || String.valueOf(locked.get("message")).contains("locked"), String.valueOf(locked));
        assertEquals(0, c.clientLogin(req("GOOD", null), http("5.6.7.8")).get("code"));
    }

    @Test
    @DisplayName("带称呼登录走复用路径（同码同称呼同一个用户）")
    void displayNameGoesThroughReusePath() {
        ClientInvitationService invitations = mock(ClientInvitationService.class);
        ProjectInvitation inv = new ProjectInvitation();
        inv.setId(3L);
        inv.setProjectId(1L);
        inv.setRelatedUserId(10L);
        when(invitations.validateCode("GOOD")).thenReturn(inv);
        User u = new User();
        u.setId(11L);
        u.setUsername("client_inv3_x");
        u.setDisplayName("李四");
        u.setRole("CLIENT");
        u.setSubscriptionType("FREE");
        when(invitations.createClientUser(eq(inv), eq("李四"))).thenReturn(u);
        UserService users = mock(UserService.class);
        AuthController c = controller(invitations, users);
        assertEquals(0, c.clientLogin(req("GOOD", "李四"), http("1.1.1.1")).get("code"));
        verify(users, never()).getUserById(any());
    }
}
