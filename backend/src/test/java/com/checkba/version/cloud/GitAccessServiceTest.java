// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.cloud;

import com.checkba.service.DeviceTokenService;
import com.checkba.service.ProjectMemberService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitAccessServiceTest {

    private DeviceTokenService tokens;
    private ProjectMemberService members;
    private GitAccessService svc;

    @BeforeEach
    void setUp() {
        tokens = mock(DeviceTokenService.class);
        members = mock(ProjectMemberService.class);
        svc = new GitAccessService(tokens, members);
    }

    private HttpServletRequest reqWith(String user, String token) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        if (token != null) {
            String cred = Base64.getEncoder().encodeToString(
                    (user + ":" + token).getBytes(StandardCharsets.UTF_8));
            when(req.getHeader("Authorization")).thenReturn("Basic " + cred);
        }
        return req;
    }

    @Test
    void missingCredentialsIs401() {
        GitAccessDeniedException e = assertThrows(GitAccessDeniedException.class,
                () -> svc.authorize(reqWith(null, null), 7L, false));
        assertEquals(401, e.statusCode());
    }

    @Test
    void memberCanReadNonMemberCannot() {
        when(tokens.resolve("awdt_x")).thenReturn(new DeviceTokenService.ResolvedToken(42L, 9L));
        when(members.hasReadPermission(7L, 42L)).thenReturn(true);
        when(members.isClient(7L, 42L)).thenReturn(false);
        // 设备维度必须一路带到调用方（协作事件靠它分「你在另一台电脑」与「同事」）
        DeviceTokenService.ResolvedToken who = svc.authorize(reqWith("u", "awdt_x"), 7L, false);
        assertEquals(42L, who.userId());
        assertEquals(9L, who.tokenId());

        when(members.hasReadPermission(7L, 42L)).thenReturn(false);
        assertEquals(403, assertThrows(GitAccessDeniedException.class,
                () -> svc.authorize(reqWith("u", "awdt_x"), 7L, false)).statusCode());
    }

    @Test
    void clientIsAlwaysDeniedAndReadOnlyCannotWrite() {
        when(tokens.resolve("awdt_x")).thenReturn(new DeviceTokenService.ResolvedToken(42L, 9L));
        when(members.hasReadPermission(7L, 42L)).thenReturn(true);
        when(members.isClient(7L, 42L)).thenReturn(true);
        assertEquals(403, assertThrows(GitAccessDeniedException.class,
                () -> svc.authorize(reqWith("u", "awdt_x"), 7L, false)).statusCode());

        when(members.isClient(7L, 42L)).thenReturn(false);
        when(members.hasWritePermission(7L, 42L)).thenReturn(false);
        assertEquals(403, assertThrows(GitAccessDeniedException.class,
                () -> svc.authorize(reqWith("u", "awdt_x"), 7L, true)).statusCode());
    }
}
