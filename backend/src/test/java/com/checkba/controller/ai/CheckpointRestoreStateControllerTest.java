// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ai.DocumentCheckpointService;
import com.checkba.service.ai.EditorBridgeService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckpointRestoreStateControllerTest {
    @Test
    void writeStateRequiresConversationAccessAndRejectsIdentityMismatch() {
        var checkpoints = mock(DocumentCheckpointService.class);
        var messages = mock(ProjectAiMessageService.class);
        var controller = new EditorResultController(mock(EditorBridgeService.class), checkpoints, messages);
        try (var auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("session")).thenReturn(7L);
            assertEquals(403, controller.checkpointRestoreState("conv", 1L, "restore", "session").getStatusCode().value());
            verifyNoInteractions(checkpoints);
            when(messages.canUseConversation("conv", 7L)).thenReturn(true);
            when(checkpoints.restoreMayWrite("conv", 1L, "restore")).thenReturn(true);
            assertEquals(Map.of("mayWrite", true), controller.checkpointRestoreState("conv", 1L, "restore", "session").getBody());
            when(checkpoints.restoreMayWrite("conv", 1L, "restore")).thenReturn(false);
            assertEquals(Map.of("mayWrite", false), controller.checkpointRestoreState("conv", 1L, "restore", "session").getBody());
            when(checkpoints.restoreMayWrite("conv", 2L, "restore")).thenThrow(new IllegalArgumentException("mismatch"));
            assertEquals(400, controller.checkpointRestoreState("conv", 2L, "restore", "session").getStatusCode().value());
        }
    }
}
