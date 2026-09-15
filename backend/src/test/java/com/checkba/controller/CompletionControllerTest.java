// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.service.completion.CompletionService;
import com.checkba.service.completion.CompletionService.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CompletionControllerTest {
    private final CompletionService service = mock(CompletionService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CompletionController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void everyRouteRequiresSession() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(isNull())).thenReturn(null);
            mvc.perform(get("/api/projects/1/completion/entries/learned:1")).andExpect(jsonPath("$.code").value(4010));
            mvc.perform(get("/api/projects/1/completion")).andExpect(jsonPath("$.code").value(4010));
            mvc.perform(post("/api/projects/1/completion/learn").contentType(MediaType.APPLICATION_JSON)
                    .content("{}" )).andExpect(jsonPath("$.code").value(4010));
            mvc.perform(delete("/api/projects/1/completion/entries/learned:1")).andExpect(jsonPath("$.code").value(4010));
            mvc.perform(delete("/api/projects/1/completion/learned").param("scope", "user")).andExpect(jsonPath("$.code").value(4010));
            mvc.perform(post("/api/projects/1/completion/lookup").contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andExpect(jsonPath("$.code").value(4010));
            verifyNoInteractions(service);
        }
    }

    @Test
    void localGetNeverTouchesInsightPipelineAndUsesSessionUser() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(service.candidates(9L, 1L, "北京")).thenReturn(new Candidates(List.of(new Item("learned:7", "北京示例有限公司", "COMPANY", "learned", "project", 31L, null, 4, true)), 2000));
            mvc.perform(get("/api/projects/1/completion").header("X-Session-Id", "sess").param("prefix", "北京"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].text").value("北京示例有限公司"))
                    .andExpect(jsonPath("$.items[0].entityId").value(31)).andExpect(jsonPath("$.items[0].uses").value(4));
            verify(service, never()).lookupSelection(any(), any(), any(), any());
        }
    }

    @Test
    void onlyExplicitLookupCallsExternalSelectionEntryPoint() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            mvc.perform(post("/api/projects/1/completion/lookup").header("X-Session-Id", "sess").contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"kind":"COMPANY","text":"示例有限公司","userId":10}
                            """))
                    .andExpect(status().isOk());
            verify(service).lookupSelection(9L, 1L, "COMPANY", "示例有限公司");
        }
    }

    @Test
    void learnAndClearPassOnlyAuthenticatedScope() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(service.learn(eq(9L), eq(1L), any())).thenReturn(new LearnResult(1));
            mvc.perform(post("/api/projects/1/completion/learn").header("X-Session-Id", "sess").contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"entries":[{"text":"张三","kind":"PERSON"}],"scope":"user"}
                            """))
                    .andExpect(jsonPath("$.learned").value(1));
            verify(service).learn(9L, 1L, new LearnRequest(List.of(new LearnEntry("张三", "PERSON")), "user"));
            mvc.perform(delete("/api/projects/1/completion/entries/learned:7").header("X-Session-Id", "sess")).andExpect(status().isOk());
            mvc.perform(delete("/api/projects/1/completion/learned").header("X-Session-Id", "sess").param("scope", "user")).andExpect(status().isOk());
            verify(service).delete(9L, 1L, "learned:7"); verify(service).clear(9L, 1L, "user");
            verify(service, never()).lookupSelection(any(), any(), any(), any());
        }
    }
    @Test
    void localDetailRouteDoesNotCallLookup() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            mvc.perform(get("/api/projects/1/completion/entries/learned:7").header("X-Session-Id", "sess")).andExpect(status().isOk());
            verify(service).detail(9L, 1L, "learned:7");
            verify(service, never()).lookupSelection(any(), any(), any(), any());
        }
    }

}
