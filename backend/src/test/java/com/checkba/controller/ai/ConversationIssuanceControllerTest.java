package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ai.ConversationIssuanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * conversationId 签发端点：身份 + 项目读权限双闸，通过后才签发。
 * 端点契约（与 Office 插件端并行开发约定）：POST /api/agent/conversations
 * body {projectId} → {"conversationId": "..."}。
 */
@ExtendWith(MockitoExtension.class)
class ConversationIssuanceControllerTest {

    @Mock
    private ConversationIssuanceService issuanceService;
    @Mock
    private ProjectMemberService projectMemberService;

    @InjectMocks
    private ConversationIssuanceController controller;

    private static ConversationIssuanceController.IssueRequest request(Long projectId) {
        ConversationIssuanceController.IssueRequest r = new ConversationIssuanceController.IssueRequest();
        r.setProjectId(projectId);
        return r;
    }

    @Test
    void 项目成员可签发_返回约定形状() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(true);
            when(issuanceService.issue(7L, 42L)).thenReturn("conv-1754400000000-abcdefghijklmnop");

            ResponseEntity<Map<String, String>> resp = controller.issue(request(42L), "sess");

            assertEquals(200, resp.getStatusCode().value());
            assertEquals("conv-1754400000000-abcdefghijklmnop", resp.getBody().get("conversationId"));
        }
    }

    @Test
    void 身份无效时拒绝_且文案不含认证类字眼() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            ResponseEntity<Map<String, String>> resp = controller.issue(request(42L), null);

            assertEquals(403, resp.getStatusCode().value());
            String error = resp.getBody().get("error");
            // services/api.js 用「登录/未授权/请先」子串判定未登录并清会话，误触会连坐整个前端会话
            assertFalse(error.contains("登录") || error.contains("未授权") || error.contains("请先"),
                    "失败文案不得含认证类字眼，实际：" + error);
            verify(issuanceService, never()).issue(any(), any());
        }
    }

    @Test
    void 非项目成员不可签发() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(false);

            ResponseEntity<Map<String, String>> resp = controller.issue(request(42L), "sess");

            assertEquals(403, resp.getStatusCode().value());
            verify(issuanceService, never()).issue(any(), any());
        }
    }

    @Test
    void 缺projectId直接拒绝() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);

            ResponseEntity<Map<String, String>> resp = controller.issue(request(null), "sess");

            assertEquals(403, resp.getStatusCode().value());
            verify(issuanceService, never()).issue(any(), any());
        }
    }
}
