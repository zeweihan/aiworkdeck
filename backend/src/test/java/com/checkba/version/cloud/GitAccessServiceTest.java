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
        when(tokens.resolveUserId("awdt_x")).thenReturn(42L);
        when(members.hasReadPermission(7L, 42L)).thenReturn(true);
        when(members.isClient(7L, 42L)).thenReturn(false);
        assertEquals(42L, svc.authorize(reqWith("u", "awdt_x"), 7L, false));

        when(members.hasReadPermission(7L, 42L)).thenReturn(false);
        assertEquals(403, assertThrows(GitAccessDeniedException.class,
                () -> svc.authorize(reqWith("u", "awdt_x"), 7L, false)).statusCode());
    }

    @Test
    void clientIsAlwaysDeniedAndReadOnlyCannotWrite() {
        when(tokens.resolveUserId("awdt_x")).thenReturn(42L);
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
