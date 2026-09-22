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
            AgentInboxService.ItemView edited = inbox.edit(conversationId, messageId, request.message,
                    request.submissionMode, request.position, request.expectedRevision);
            // 「立即发送」的判据是「目标模式是 steer 且当前没有活跃轮次」：没有活跃轮次时必须由
            // 这里起一条新轮次，否则这条插话没有任何人会来 claim 它；有活跃轮次时它会在那一轮的
            // 下一个工具边界被捞走，这里什么都不用做。
            //
            // 判据此前写的是「模式发生过 queue -> steer 的转变」，把**本来就是 steer** 的待处理项
            // 整个排除在外（dev-board#802）：那一轮若以取消 / 出错 / 待审批 / 待回答 / 无进展暂停
            // 收尾（这几种按设计都不 drain 队列），这条 steer 就永久卡在 pending 里——界面上只剩
            // 编辑 / 上移 / 下移 / 删除，没有任何办法把它发出去，而它看着像还会被处理。
            //
            // acceptInboxSubmission 自身幂等（进去先查 activeRuns，有就原样返回），
            // activeRunId 判空只是省掉一次无谓调用、并把意图写在脸上。
            boolean sendNow = request.submissionMode != null
                    && AgentInboxService.STEER.equals(AgentInboxService.normalizeMode(request.submissionMode));
            if (sendNow && orchestrator.activeRunId(conversationId) == null) {
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
