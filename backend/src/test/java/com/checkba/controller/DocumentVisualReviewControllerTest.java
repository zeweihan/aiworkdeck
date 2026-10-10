// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.controller;

import com.checkba.service.insight.DocumentVisualReviewService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentVisualReviewControllerTest {
    final DocumentVisualReviewService service = mock(DocumentVisualReviewService.class);
    final DocumentVisualReviewController controller = new DocumentVisualReviewController(service);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    @Test void missingSessionNeverInvokesReview() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);
            assertThrows(IllegalArgumentException.class, () -> controller.review(1L, null, null));
            verifyNoInteractions(service);
        }
    }
    @Test void postUsesSessionIdentityAndReturnsActualCoverageAndRevision() throws Exception {
        var req = new DocumentVisualReviewService.Request(3L, "synthetic", 2, 6, true, 71L);
        when(service.review(9L, 1L, req)).thenReturn(new DocumentVisualReviewService.Result("仅已查看页面", List.of(2, 3), 3, false, 71));
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("test-session")).thenReturn(9L);
            mvc.perform(post("/api/projects/1/visual-review").header("X-Session-Id", "test-session")
                    .contentType(MediaType.APPLICATION_JSON).content("""
                    {"docFileId":3,"base64":"synthetic","startPage":2,"endPage":6,"confirmed":true,"revision":71,"userId":99}
                    """))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.checkedPages[0]").value(2))
                    .andExpect(jsonPath("$.checkedPages[1]").value(3)).andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.complete").value(false)).andExpect(jsonPath("$.revision").value(71));
            verify(service).review(9L, 1L, req);
        }
    }
}
