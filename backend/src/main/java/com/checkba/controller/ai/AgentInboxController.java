// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ai.AgentInboxService;
import com.checkba.service.ai.AgentOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

/** Revision-checked management API for pending Agent input. */
@RestController
@RequestMapping("/api/agent/inbox")
public class AgentInboxController {
    private final AgentInboxService inbox;
    private final ProjectAiMessageService messageService;
    private final AgentOrchestrator orchestrator;

    public AgentInboxController(AgentInboxService inbox, ProjectAiMessageService messageService,
                                AgentOrchestrator orchestrator) {
        this.inbox = inbox;
        this.messageService = messageService;
        this.orchestrator = orchestrator;
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<?> get(@PathVariable String conversationId,
                                 @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        ResponseEntity<?> denied = authorize(conversationId, sessionId);
        return denied != null ? denied : ResponseEntity.ok(inbox.snapshot(conversationId));
    }

    @PatchMapping("/{conversationId}/{messageId}")
    public ResponseEntity<?> edit(@PathVariable String conversationId, @PathVariable String messageId,
                                  @RequestBody EditRequest request,
                                  @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        ResponseEntity<?> denied = authorize(conversationId, sessionId);
        if (denied != null) return denied;
        if (request.expectedRevision == null) return error(400, "expectedRevision is required");
        try {
            String previousMode = inbox.submissionMode(conversationId, messageId);
            AgentInboxService.ItemView edited = inbox.edit(conversationId, messageId, request.message,
                    request.submissionMode, request.position, request.expectedRevision);
            // "Send now" is an explicit queue -> steer transition. When no run is active this must
            // create one; with an active run the durable steer is picked up at its next boundary.
            if (AgentInboxService.QUEUE.equals(previousMode)
                    && request.submissionMode != null
                    && AgentInboxService.STEER.equals(AgentInboxService.normalizeMode(request.submissionMode))) {
                orchestrator.acceptInboxSubmission(messageId);
                edited = inbox.view(messageId);
            }
            return ResponseEntity.ok(edited);
        } catch (AgentInboxService.RevisionConflict e) {
            return error(409, e.getMessage());
        } catch (NoSuchElementException e) {
            return error(404, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    @DeleteMapping("/{conversationId}/{messageId}")
    public ResponseEntity<?> delete(@PathVariable String conversationId, @PathVariable String messageId,
                                    @RequestParam Long expectedRevision,
                                    @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        ResponseEntity<?> denied = authorize(conversationId, sessionId);
        if (denied != null) return denied;
        try {
            return ResponseEntity.ok(inbox.delete(conversationId, messageId, expectedRevision));
        } catch (AgentInboxService.RevisionConflict e) {
            return error(409, e.getMessage());
        } catch (NoSuchElementException e) {
            return error(404, e.getMessage());
        }
    }

    private ResponseEntity<?> authorize(String conversationId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) return error(401, "请先登录");
        if (!messageService.canUseConversation(conversationId, userId)) {
            return error(403, "无权操作该会话");
        }
        return null;
    }

    private static ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "error", "message", message));
    }

    public static class EditRequest {
        public String message;
        public String submissionMode;
        public Long position;
        public Long expectedRevision;
    }
}
