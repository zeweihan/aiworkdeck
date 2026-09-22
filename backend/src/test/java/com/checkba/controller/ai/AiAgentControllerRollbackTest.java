// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ai.AgentOrchestrator;
import com.checkba.service.ai.AgentRunStateService;
import com.checkba.service.ai.BackgroundTaskService;
import com.checkba.service.ai.ClientCapabilityService;
import com.checkba.service.ai.SseEmitterService;
import com.checkba.service.ai.TodoListService;
import com.checkba.service.ai.subagent.SubAgentService;
import com.checkba.service.ai.tools.PptxTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回退端点（dev-board#779 K1，审查 D-02）。
 *
 * <p>病灶：本次会话内新发出的气泡 id 是前端自造的 {@code msg-<毫秒>-<序号>}，而
 * {@code RollbackRequest.messageId} 声明成 Long——Jackson 在进 handler 之前就把请求拒了，
 * 用户看到「回退失败: 服务器内部错误」，而前端的 splice、回填输入框、刷新历史三步全部跳过。
 * 也就是说最想用这个按钮的时刻（刚发现自己问错了）它必定失灵。
 */
@DisplayName("回退端点：定位键契约与可读的失败")
class AiAgentControllerRollbackTest {

    private ProjectAiMessageService messageService;
    private AiAgentController controller;

    @BeforeEach
    void setUp() {
        messageService = mock(ProjectAiMessageService.class);
        controller = new AiAgentController(
                mock(SseEmitterService.class),
                mock(AgentOrchestrator.class),
                messageService,
                mock(BackgroundTaskService.class),
                mock(PptxTools.class),
                mock(TodoListService.class),
                mock(AgentRunStateService.class),
                mock(ProjectMemberService.class),
                mock(ClientCapabilityService.class),
                mock(SubAgentService.class),
                mock(com.checkba.service.ai.AgentInboxService.class));
    }

    private AiAgentController.RollbackRequest req(String conv, String messageId, String clientRequestId) {
        AiAgentController.RollbackRequest r = new AiAgentController.RollbackRequest();
        r.setConversationId(conv);
        r.setMessageId(messageId);
        r.setClientRequestId(clientRequestId);
        return r;
    }

    @Test
    @DisplayName("前端自造的 msg-… 串进得来，且答复是 400 可读文案而不是 500")
    void nonNumericMessageIdIsAReadable400() throws Exception {
        // 契约的第一半在 Jackson：messageId 必须能接住字符串，否则请求根本到不了 handler
        AiAgentController.RollbackRequest parsed = new ObjectMapper().readValue(
                "{\"conversationId\":\"conv-1\",\"messageId\":\"msg-1790065781790-1\"}",
                AiAgentController.RollbackRequest.class);
        assertEquals("msg-1790065781790-1", parsed.getMessageId());

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            when(messageService.isConversationOwnedBy("conv-1", 7L)).thenReturn(true);

            ResponseEntity<?> res = controller.rollbackHistory(parsed, "s");

            assertEquals(400, res.getStatusCode().value());
            String body = String.valueOf(res.getBody());
            assertFalse(body.contains("Internal Error"), "别把内部错误丢给用户：" + body);
            assertTrue(body.contains("定位"), "文案要说清是定位不到这条消息：" + body);
            verify(messageService, never()).rollbackWithArchive(anyString(), any(), any(), anyLong());
        }
    }

    @Test
    @DisplayName("数字 messageId 与 clientRequestId 都原样下推给服务，并回存档会话 id")
    void bothLocatorsReachTheServiceAndArchiveIdComesBack() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            when(messageService.isConversationOwnedBy("conv-1", 7L)).thenReturn(true);
            when(messageService.rollbackWithArchive(eq("conv-1"), eq(42L), eq("req-9"), eq(7L)))
                    .thenReturn("conv-archive-1");

            ResponseEntity<?> res = controller.rollbackHistory(req("conv-1", "42", "req-9"), "s");

            assertEquals(200, res.getStatusCode().value());
            assertTrue(String.valueOf(res.getBody()).contains("conv-archive-1"),
                    "前端要靠它告诉用户「原对话已存档」：" + res.getBody());
        }
    }

    @Test
    @DisplayName("只有 clientRequestId（本次会话内刚发出的消息）也能回退")
    void clientRequestIdAloneIsEnough() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            when(messageService.isConversationOwnedBy("conv-1", 7L)).thenReturn(true);
            when(messageService.rollbackWithArchive("conv-1", null, "req-9", 7L)).thenReturn("conv-archive-2");

            ResponseEntity<?> res = controller.rollbackHistory(req("conv-1", null, "req-9"), "s");

            assertEquals(200, res.getStatusCode().value());
        }
    }

    @Test
    @DisplayName("local-mode 不发 X-Session-Id：身份解析交给 AuthController，别在这里短路成 403")
    void missingSessionHeaderStillResolvesTheLocalUser() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            // local-mode 下 getUserIdFromSession(null) 返回本机用户——整个桌面端都走这条路
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(1L);
            when(messageService.isConversationOwnedBy("conv-1", 1L)).thenReturn(true);
            when(messageService.rollbackWithArchive("conv-1", 42L, null, 1L)).thenReturn("conv-archive-3");

            ResponseEntity<?> res = controller.rollbackHistory(req("conv-1", "42", null), null);

            assertEquals(200, res.getStatusCode().value(),
                    "桌面端不发 session 头，短路成 403 等于回退功能对整个桌面端都不存在");
        }
    }

    @Test
    @DisplayName("会话不归属：403 且一个字都不删")
    void foreignConversationIsRejected() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            when(messageService.isConversationOwnedBy("conv-1", 7L)).thenReturn(false);

            assertEquals(403, controller.rollbackHistory(req("conv-1", "42", null), "s").getStatusCode().value());
            verify(messageService, never()).rollbackWithArchive(anyString(), any(), any(), anyLong());
        }
    }
}
