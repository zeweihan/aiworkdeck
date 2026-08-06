package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ai.ConversationIssuanceService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * conversationId 服务端签发端点（安全审计遗留 + Office 插件 Phase D）。
 *
 * 契约（与插件端并行开发约定，勿改形状）：
 *   POST /api/agent/conversations  body {"projectId": 123}
 *   → 200 {"conversationId": "conv-<毫秒>-<16位随机base64url>"}
 *
 * 独立于 AiAgentController 成文件，避免与并行分支冲突。
 * 桌面前端现有「客户端自造 conv-毫秒 ID」流程在默认配置下不受影响；
 * 官方云开启 security.conversation-issuance-required 后，空会话必须先经此端点签发。
 */
@RestController
@RequestMapping("/api/agent/conversations")
@RequiredArgsConstructor
public class ConversationIssuanceController {

    private final ConversationIssuanceService conversationIssuanceService;
    private final ProjectMemberService projectMemberService;

    @PostMapping
    public ResponseEntity<Map<String, String>> issue(@RequestBody IssueRequest request,
                                                     @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(403).body(Map.of("error", "会话身份无效"));
        }
        Long projectId = request == null ? null : request.getProjectId();
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权访问该项目"));
        }
        String conversationId = conversationIssuanceService.issue(userId, projectId);
        return ResponseEntity.ok(Map.of("conversationId", conversationId));
    }

    @Data
    public static class IssueRequest {
        private Long projectId;
    }
}
