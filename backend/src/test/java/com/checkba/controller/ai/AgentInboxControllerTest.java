// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ai.AgentInboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentInboxControllerTest {
    private AgentInboxService inbox;
    private ProjectAiMessageService messages;
    private com.checkba.service.ai.AgentOrchestrator orchestrator;
    private AgentInboxController controller;

    @BeforeEach
    void setUp() {
        inbox = mock(AgentInboxService.class);
        messages = mock(ProjectAiMessageService.class);
        orchestrator = mock(com.checkba.service.ai.AgentOrchestrator.class);
        controller = new AgentInboxController(inbox, messages, orchestrator);
    }

    @Test
    void snapshotRequiresAuthenticatedConversationAccess() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("missing")).thenReturn(null);
            assertEquals(401, controller.get("conv-1", "missing").getStatusCode().value());

            auth.when(() -> AuthController.getUserIdFromSession("other")).thenReturn(8L);
            when(messages.canUseConversation("conv-1", 8L)).thenReturn(false);
            assertEquals(403, controller.get("conv-1", "other").getStatusCode().value());

            auth.when(() -> AuthController.getUserIdFromSession("mine")).thenReturn(7L);
            when(messages.canUseConversation("conv-1", 7L)).thenReturn(true);
            AgentInboxService.Snapshot snapshot = new AgentInboxService.Snapshot(List.of(), "run-1", "RUNNING");
            when(inbox.snapshot("conv-1")).thenReturn(snapshot);
            ResponseEntity<?> response = controller.get("conv-1", "mine");
            assertEquals(200, response.getStatusCode().value());
            assertEquals(snapshot, response.getBody());
        }
    }

    @Test
    void mutationsRequireRevisionAndMapStaleWritesToConflict() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("mine")).thenReturn(7L);
            when(messages.canUseConversation("conv-1", 7L)).thenReturn(true);

            AgentInboxController.EditRequest missing = new AgentInboxController.EditRequest();
            ResponseEntity<?> bad = controller.edit("conv-1", "m-1", missing, "mine");
            assertEquals(400, bad.getStatusCode().value());
            assertEquals("error", ((Map<?, ?>) bad.getBody()).get("status"));

            AgentInboxController.EditRequest stale = new AgentInboxController.EditRequest();
            stale.message = "edited";
            stale.expectedRevision = 1L;
            when(inbox.edit("conv-1", "m-1", "edited", null, null, 1L))
                    .thenThrow(new AgentInboxService.RevisionConflict("Inbox revision is stale"));
            assertEquals(409, controller.edit("conv-1", "m-1", stale, "mine").getStatusCode().value());

            when(inbox.delete("conv-1", "m-1", 1L))
                    .thenThrow(new AgentInboxService.RevisionConflict("Inbox revision is stale"));
            assertEquals(409, controller.delete("conv-1", "m-1", 1L, "mine").getStatusCode().value());
        }
    }

    @Test
    void explicitQueueToSteerTransitionStartsAnIdleConsumer() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("mine")).thenReturn(7L);
            when(messages.canUseConversation("conv-1", 7L)).thenReturn(true);
            when(inbox.submissionMode("conv-1", "m-1")).thenReturn(AgentInboxService.QUEUE);
            AgentInboxService.ItemView pending = new AgentInboxService.ItemView(
                    "m-1", "later", null, "steer", "pending", 0, 2,
                    "key", null, null, null, null);
            when(inbox.edit("conv-1", "m-1", null, "steer", null, 1L)).thenReturn(pending);
            when(inbox.view("m-1")).thenReturn(pending);
            AgentInboxController.EditRequest sendNow = new AgentInboxController.EditRequest();
            sendNow.submissionMode = "steer";
            sendNow.expectedRevision = 1L;

            assertEquals(200, controller.edit("conv-1", "m-1", sendNow, "mine").getStatusCode().value());
            verify(orchestrator).acceptInboxSubmission("m-1");
        }
    }
}
