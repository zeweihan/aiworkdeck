// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.tmeet;

import com.checkba.service.tmeet.dto.TmeetAuthStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedConstruction;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TmeetCliServiceTest {

    private static final String CLI_PATH = "/test-only/tmeet";

    @Test
    void testResolveCliPath() {
        TmeetCliService service = new TmeetCliService("");
        String path = service.resolveCliPath();
        assertNotNull(path);
        assertFalse(path.isBlank());
    }

    @ParameterizedTest
    @CsvSource({"true,0,true", "true,1,false", "false,0,false"})
    void testIsCliAvailable(boolean finished, int exitCode, boolean expected) throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(finished);
        when(process.exitValue()).thenReturn(exitCode);
        try (var builders = mockCommands(process)) {
            assertEquals(expected, service().isCliAvailable());
            assertEquals(1, builders.constructed().size());
            verify(process).waitFor(5, TimeUnit.SECONDS);
            if (!finished) verify(process, never()).exitValue();
        }
    }

    @Test
    void unavailableCliDoesNotQueryAuth() throws Exception {
        try (var builders = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            when(builder.redirectErrorStream(true)).thenReturn(builder);
            when(builder.start()).thenThrow(new IOException("CLI missing"));
        })) {
            TmeetAuthStatus status = service().getAuthStatus();
            assertFalse(status.isCliAvailable());
            assertFalse(status.isLoggedIn());
            assertEquals(CLI_PATH, status.getCliPath());
            assertTrue(status.getMessage().contains("未找到或无法执行"));
            assertEquals(1, builders.constructed().size());
        }
    }

    @Test
    void testGetAuthStatus() throws Exception {
        Process version = successfulVersion();
        Process auth = authProcess("""
                Logged in
                UserName: Test User
                OpenId: test-open-id
                AccessToken: expires in 1 hour
                RefreshToken: expires in 30 days
                """, 0);
        try (var builders = mockCommands(version, auth)) {
            TmeetAuthStatus status = service().getAuthStatus();
            assertTrue(status.isCliAvailable());
            assertTrue(status.isLoggedIn());
            assertEquals(CLI_PATH, status.getCliPath());
            assertEquals("Test User", status.getUserName());
            assertEquals("test-open-id", status.getOpenId());
            assertEquals("expires in 1 hour", status.getAccessTokenExpiry());
            assertEquals("expires in 30 days", status.getRefreshTokenExpiry());
            assertEquals("已登录", status.getMessage());
            assertEquals(2, builders.constructed().size());
            verify(auth).waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void loggedOutCliRemainsAvailable() throws Exception {
        try (var builders = mockCommands(successfulVersion(), authProcess("Not logged in", 1))) {
            TmeetAuthStatus status = service().getAuthStatus();
            assertTrue(status.isCliAvailable());
            assertFalse(status.isLoggedIn());
            assertNull(status.getUserName());
            assertNull(status.getOpenId());
            assertEquals("未登录腾讯会议账号", status.getMessage());
            assertEquals(2, builders.constructed().size());
        }
    }

    @Test
    void authTimeoutReportsFailureWithoutLoggingIn() throws Exception {
        Process auth = authProcess("", 0);
        when(auth.waitFor(10, TimeUnit.SECONDS)).thenReturn(false);
        try (var builders = mockCommands(successfulVersion(), auth)) {
            TmeetAuthStatus status = service().getAuthStatus();
            assertTrue(status.isCliAvailable());
            assertFalse(status.isLoggedIn());
            assertTrue(status.getMessage().contains("执行 tmeet 超时"));
            assertEquals(2, builders.constructed().size());
            verify(auth).destroyForcibly();
        }
    }

    private TmeetCliService service() {
        TmeetCliService service = spy(new TmeetCliService(""));
        doReturn(CLI_PATH).when(service).resolveCliPath();
        return service;
    }

    private Process successfulVersion() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        return process;
    }

    private Process authProcess(String output, int exitCode) throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(10, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(exitCode);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return process;
    }

    // Mock only the OS process boundary: run the real availability and auth parsing logic,
    // without starting an installed CLI or reading the developer's credentials.
    private MockedConstruction<ProcessBuilder> mockCommands(Process... processes) {
        return mockConstruction(ProcessBuilder.class, (builder, context) -> {
            int index = context.getCount() - 1;
            Object args = context.arguments().get(0);
            List<?> command = args instanceof String[] array ? List.of(array) : (List<?>) args;
            assertEquals(index == 0 ? List.of(CLI_PATH, "--version") : List.of(CLI_PATH, "auth", "status"), command);
            assertTrue(index < processes.length, "Unexpected CLI command: " + command);
            when(builder.redirectErrorStream(true)).thenReturn(builder);
            when(builder.environment()).thenReturn(new HashMap<>());
            when(builder.start()).thenReturn(processes[index]);
        });
    }
}
