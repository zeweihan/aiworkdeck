// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectFileComparisonControllerTest {
    ProjectFileService files;
    ProjectMemberService members;
    MockMvc mvc;
    @BeforeEach void setup() {
        files = mock(ProjectFileService.class); members = mock(ProjectMemberService.class);
        var controller = new ProjectFileController(files, members, null, null,
                mock(com.checkba.service.ai.ProjectRagService.class), mock(com.checkba.service.ai.AutoTaggingService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return multipart("/api/projects/10/files/comparison")
                .file(new MockMultipartFile("file", "ignored.docx", "application/octet-stream", new byte[]{1,2}))
                .param("baseFileId", "1").param("revisedFileId", "2")
                .param("baseSha256", "a".repeat(64)).param("revisedSha256", "b".repeat(64));
    }
    void allow() {
        when(members.hasReadPermission(10L,7L)).thenReturn(true);
        when(members.hasWritePermission(10L,7L)).thenReturn(true);
    }
    @Test void unauthorizedAndReadOnlyNeverReadOrCreateFiles() throws Exception {
        try(var auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);
            mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(4010));
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            when(members.hasReadPermission(10L,7L)).thenReturn(true);
            mvc.perform(request().header("X-Session-Id","s")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
            verifyNoInteractions(files);
        }
    }
    @Test void staleSourceReturns409AndResultShapeIsProjectFile() throws Exception {
        try(var auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L); allow();
            when(files.createComparison(eq(10L),eq(1L),eq(2L),anyString(),anyString(),any(),eq(7L)))
                    .thenThrow(new ProjectFileService.ComparisonSourceChangedException());
            mvc.perform(request().header("X-Session-Id","s")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("SOURCE_CHANGED"));
            var result = new ProjectFile(); result.setId(8L); result.setName("comparison.docx");
            when(files.createComparison(eq(10L),eq(1L),eq(2L),anyString(),anyString(),any(),eq(7L))).thenReturn(result);
            mvc.perform(request().header("X-Session-Id","s")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(8)).andExpect(jsonPath("$.name").value("comparison.docx"));
        }
    }
}
