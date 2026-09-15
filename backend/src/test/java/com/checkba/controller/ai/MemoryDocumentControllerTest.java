// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ai.memory.document.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MemoryDocumentControllerTest {

    @Test
    void spacesUseStandardEnvelopeAndSessionIdentity() throws Exception {
        MemoryDocumentService service = mock(MemoryDocumentService.class);
        when(service.listSpaces(9L, 7L)).thenReturn(List.of(
                new MemorySpaceView("opaque", "project", "项目记忆", true, true, true, null)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemoryDocumentController(service)).build();
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("session-a")).thenReturn(9L);
            mvc.perform(get("/api/ai/memory/spaces").param("projectId", "7")
                            .header("X-Session-Id", "session-a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[0].id").value("opaque"))
                    .andExpect(jsonPath("$.data[0].scope").value("project"));
        }
    }

    @Test
    void staleWriteReturnsHttp409ReadableEnvelope() throws Exception {
        MemoryDocumentService service = mock(MemoryDocumentService.class);
        when(service.write(9L, "opaque", "remember.md", "new", 2))
                .thenThrow(new MemoryDocumentException(409, "记忆文件已发生变化，请刷新后重试"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemoryDocumentController(service)).build();
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("session-a")).thenReturn(9L);
            mvc.perform(put("/api/ai/memory/file").header("X-Session-Id", "session-a")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"spaceId\":\"opaque\",\"path\":\"remember.md\",\"content\":\"new\",\"expectedRevision\":2}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.message").value("记忆文件已发生变化，请刷新后重试"));
        }
    }

    @Test
    void downloadIsMarkdownAttachmentAfterAuthorization() throws Exception {
        MemoryDocumentService service = mock(MemoryDocumentService.class);
        when(service.download(9L, "opaque", "notes.md")).thenReturn("# Notes");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemoryDocumentController(service)).build();
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("session-a")).thenReturn(9L);
            mvc.perform(get("/api/ai/memory/download").header("X-Session-Id", "session-a")
                            .param("spaceId", "opaque").param("path", "notes.md"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"notes.md\""))
                    .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                    .andExpect(content().string("# Notes"));
        }
    }
}
