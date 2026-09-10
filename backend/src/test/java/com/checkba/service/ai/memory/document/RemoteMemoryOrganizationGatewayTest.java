// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.memory.document;

import com.checkba.model.entity.AccountBinding;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.service.account.AccountService;
import com.checkba.service.site.SiteProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteMemoryOrganizationGatewayTest {

    @Test
    void desktopUsesConnectedAccountBearerAndReturnsCanonicalOrgIds() {
        AccountService account = mock(AccountService.class);
        when(account.currentKeyOrNull()).thenReturn("awdk_secret");
        SiteProfileService site = mock(SiteProfileService.class);
        when(site.baseUrl()).thenReturn("https://accounts.example");
        RecordingTransport transport = new RecordingTransport(200,
                "{\"code\":200,\"data\":[{\"id\":\"team:t-1\",\"scope\":\"team\",\"label\":\"诉讼组\",\"readable\":true,\"writable\":false,\"available\":true,\"reason\":null},{\"id\":\"firm:f-1\",\"scope\":\"firm\",\"label\":\"京微所\",\"readable\":true,\"writable\":true,\"available\":true,\"reason\":null}]}");

        RemoteMemoryOrganizationGateway gateway = new RemoteMemoryOrganizationGateway(
                account, site, mock(AccountBindingRepository.class), transport, new ObjectMapper(),
                true, "", "");

        assertEquals("team:t-1", gateway.listSpaces(8L).get(0).id());
        assertEquals("Bearer awdk_secret", transport.headers.get("Authorization"));
        assertEquals("https://accounts.example/api/account/memory/spaces", transport.url);
        assertFalse(transport.headers.containsKey("X-Requester-Account-Id"));
    }

    @Test
    void serverDerivesRequesterHeaderFromBindingAndNeverFromSpaceId() {
        AccountBinding binding = new AccountBinding();
        binding.setExternalAccountId("account-real");
        binding.setUserId(41L);
        AccountBindingRepository bindings = mock(AccountBindingRepository.class);
        when(bindings.findByUserId(41L)).thenReturn(Optional.of(binding));
        RecordingTransport transport = new RecordingTransport(200,
                "{\"code\":200,\"data\":{\"path\":\"remember.md\",\"title\":\"团队记忆\",\"content\":\"# 团队记忆\",\"revision\":3,\"updatedAt\":\"2026-09-10T12:00:00\",\"writable\":false}}");
        RemoteMemoryOrganizationGateway gateway = new RemoteMemoryOrganizationGateway(
                mock(AccountService.class), mock(SiteProfileService.class), bindings, transport,
                new ObjectMapper(), false, "https://shared.example", "shared-secret");

        MemoryFileView file = gateway.read(41L, "team:attacker-chosen", "remember.md");

        assertEquals("# 团队记忆", file.content());
        assertEquals("account-real", transport.headers.get("X-Requester-Account-Id"));
        assertEquals("shared-secret", transport.headers.get("X-Internal-Secret"));
        assertEquals("team%3Aattacker-chosen", transport.url.substring(transport.url.indexOf("spaceId=") + 8,
                transport.url.indexOf("&path=")));
    }

    @Test
    void unconfiguredServerExposesUnavailableSpacesWithoutNetworkCall() {
        RecordingTransport transport = new RecordingTransport(500, "");
        RemoteMemoryOrganizationGateway gateway = new RemoteMemoryOrganizationGateway(
                mock(AccountService.class), mock(SiteProfileService.class), mock(AccountBindingRepository.class),
                transport, new ObjectMapper(), false, "", "");

        assertTrue(gateway.listSpaces(2L).stream().noneMatch(MemorySpaceView::available));
        assertNull(transport.url);
    }

    @Test
    void centralAuthorizationDenialIsPreservedForCallerSelectedWrongOrganization() {
        AccountBinding binding = new AccountBinding();
        binding.setExternalAccountId("account-member");
        binding.setUserId(51L);
        AccountBindingRepository bindings = mock(AccountBindingRepository.class);
        when(bindings.findByUserId(51L)).thenReturn(Optional.of(binding));
        RecordingTransport transport = new RecordingTransport(403,
                "{\"code\":403,\"message\":\"无权访问该团队记忆\"}");
        RemoteMemoryOrganizationGateway gateway = new RemoteMemoryOrganizationGateway(
                mock(AccountService.class), mock(SiteProfileService.class), bindings, transport,
                new ObjectMapper(), false, "https://shared.example", "shared-secret");

        MemoryDocumentException denied = assertThrows(MemoryDocumentException.class,
                () -> gateway.read(51L, "team:someone-elses-team", "remember.md"));

        assertEquals(403, denied.status());
        assertEquals("无权访问该团队记忆", denied.getMessage());
        assertEquals("account-member", transport.headers.get("X-Requester-Account-Id"));
    }

    @Test
    void organizationContentSearchUsesOneCentralQueryInsteadOfReadingEachFile() {
        AccountBinding binding = new AccountBinding();
        binding.setExternalAccountId("account-member");
        binding.setUserId(51L);
        AccountBindingRepository bindings = mock(AccountBindingRepository.class);
        when(bindings.findByUserId(51L)).thenReturn(Optional.of(binding));
        RecordingTransport transport = new RecordingTransport(200,
                "{\"code\":200,\"data\":[{\"path\":\"topics/private-style.md\",\"title\":\"写作\",\"revision\":4,\"updatedAt\":\"2026-09-10T12:00:00\",\"writable\":false}]}");
        RemoteMemoryOrganizationGateway gateway = new RemoteMemoryOrganizationGateway(
                mock(AccountService.class), mock(SiteProfileService.class), bindings, transport,
                new ObjectMapper(), false, "https://shared.example", "shared-secret");

        List<MemoryFileView> matches = gateway.search(51L, "team:t-1", "只在正文出现", 10);

        assertEquals(List.of("topics/private-style.md"), matches.stream().map(MemoryFileView::path).toList());
        assertTrue(transport.url.contains("/search?"));
        assertTrue(transport.url.contains("query=%E5%8F%AA%E5%9C%A8%E6%AD%A3%E6%96%87%E5%87%BA%E7%8E%B0"));
    }

    private static final class RecordingTransport implements MemoryHttpTransport {
        private final Reply reply;
        private String url;
        private Map<String, String> headers = Map.of();

        private RecordingTransport(int status, String body) { this.reply = new Reply(status, body); }

        @Override
        public Reply send(String method, String url, Map<String, String> headers, String body) {
            this.url = url;
            this.headers = headers;
            return reply;
        }
    }
}
