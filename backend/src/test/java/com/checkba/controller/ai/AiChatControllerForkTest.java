// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectAiMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * fork 端点的「从此分叉」参数（dev-board#779 K18）。
 *
 * <p>端点原来只有一条整条复制的路（插件镜像会话「另起分支继续」）。K18 给它加上
 * 与回退同一套定位键：{@code untilMessageId}（历史回灌的气泡有主键）与
 * {@code untilClientRequestId}（本次会话内刚发出的气泡只有它）。
 *
 * <p>两条契约在这里钉死：① 不带 body 的老调用方一行不改还能用；
 * ② 定位不到 / 给了前端自造的 msg-… 串时回可读的 400，而不是 500 或
 * 「服务器内部错误」——与 K1 的回退端点同口径。
 */
@DisplayName("fork 端点：从此分叉的定位键契约")
class AiChatControllerForkTest {

    private ProjectAiMessageService messageService;
    private AiChatController controller;

    @BeforeEach
    void setUp() {
        messageService = mock(ProjectAiMessageService.class);
        controller = new AiChatController(
                messageService,
                mock(com.checkba.service.ai.AiDocxExportService.class),
                mock(com.checkba.service.ai.ChatModelFactory.class),
                mock(com.checkba.service.ai.ConversationFileChangeService.class),
                mock(com.checkba.repository.TokenUsageRepository.class),
                mock(com.checkba.service.ai.AgentRunStateService.class),
                mock(com.checkba.service.ai.PlatformAiChannel.class),
                new com.checkba.config.AiContextProperties());
        when(messageService.canUseConversation(anyString(), any())).thenReturn(true);
    }

    private ResponseEntity<?> fork(Map<String, String> body) {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(7L);
            return controller.forkConversation("conv-1", body, "sess");
        }
    }

    @SuppressWarnings("unchecked")
    private String newConversationIdOf(ResponseEntity<?> res) {
        Map<String, Object> envelope = (Map<String, Object>) res.getBody();
        return (String) ((Map<String, Object>) envelope.get("data")).get("conversationId");
    }

    @Test
    @DisplayName("不带 body：整条复制，老调用方（插件镜像「另起分支继续」）一行不改")
    void withoutABodyItStillForksTheWholeConversation() {
        when(messageService.forkConversation("conv-1", 7L)).thenReturn("conv-new");

        ResponseEntity<?> res = fork(null);

        assertEquals(200, res.getStatusCode().value());
        assertEquals("conv-new", newConversationIdOf(res));
        verify(messageService, never()).forkFromMessage(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("body 是空对象也算「不分叉」：api.js 现在恒发 {}，老路不能因此变形")
    void anEmptyBodyIsStillAWholeConversationFork() {
        when(messageService.forkConversation("conv-1", 7L)).thenReturn("conv-new");

        ResponseEntity<?> res = fork(Map.of());

        assertEquals("conv-new", newConversationIdOf(res));
        verify(messageService, never()).forkFromMessage(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("两个键都是空白串时同样不分叉（别把空串当成「定位不到」回 400）")
    void blankLocatorKeysAreTreatedAsAbsent() {
        when(messageService.forkConversation("conv-1", 7L)).thenReturn("conv-new");

        ResponseEntity<?> res = fork(Map.of("untilMessageId", "  ", "untilClientRequestId", ""));

        assertEquals(200, res.getStatusCode().value());
        assertEquals("conv-new", newConversationIdOf(res));
    }

    @Test
    @DisplayName("untilMessageId（数字串）：按主键截断分叉")
    void untilMessageIdBranchesAtThatRow() {
        when(messageService.forkFromMessage(eq("conv-1"), eq(7L), eq(42L), isNull())).thenReturn("conv-branch");

        ResponseEntity<?> res = fork(Map.of("untilMessageId", "42"));

        assertEquals(200, res.getStatusCode().value());
        assertEquals("conv-branch", newConversationIdOf(res));
    }

    @Test
    @DisplayName("untilClientRequestId：本次会话内刚发出、还没落库主键的那条也能分叉")
    void untilClientRequestIdBranchesAtThatRow() {
        when(messageService.forkFromMessage(eq("conv-1"), eq(7L), isNull(), eq("req-2"))).thenReturn("conv-branch");

        ResponseEntity<?> res = fork(Map.of("untilClientRequestId", "req-2"));

        assertEquals("conv-branch", newConversationIdOf(res));
    }

    @Test
    @DisplayName("前端自造的 msg-… 串：回 400 可读文案，不是 500 —— 与回退端点同口径")
    void nonNumericUntilMessageIdIsAReadable400() {
        ResponseEntity<?> res = fork(Map.of("untilMessageId", "msg-1790065781790-1"));

        assertEquals(400, res.getStatusCode().value());
        String body = String.valueOf(res.getBody());
        assertTrue(body.contains("定位") || body.contains("locate"), body);
        verify(messageService, never()).forkFromMessage(anyString(), any(), any(), any());
        verify(messageService, never()).forkConversation(anyString(), any());
    }

    @Test
    @DisplayName("定位不到（服务层抛可读异常）：400 带原文案，不落成 500")
    void unresolvableBranchPointBecomesA400() {
        when(messageService.forkFromMessage(anyString(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("无法定位这条消息，请刷新后重试"));

        ResponseEntity<?> res = fork(Map.of("untilClientRequestId", "req-never-sent"));

        assertEquals(400, res.getStatusCode().value());
        assertTrue(String.valueOf(res.getBody()).contains("无法定位这条消息"));
    }

    @Test
    @DisplayName("不是自己的会话：403，且一条消息都不复制")
    void foreignConversationIsRejected() {
        when(messageService.canUseConversation(anyString(), any())).thenReturn(false);

        ResponseEntity<?> res = fork(Map.of("untilMessageId", "42"));

        assertEquals(403, res.getStatusCode().value());
        verify(messageService, never()).forkFromMessage(anyString(), any(), any(), any());
        verify(messageService, never()).forkConversation(anyString(), any());
    }

    @Test
    @DisplayName("body 是普通 JSON 对象，反序列化不挑剔（两个键都是可空字符串）")
    void bodyDeserialisesAsPlainStrings() throws Exception {
        Map<?, ?> parsed = new ObjectMapper().readValue(
                "{\"untilMessageId\":\"msg-1\",\"untilClientRequestId\":\"req-2\"}", Map.class);
        assertEquals("msg-1", parsed.get("untilMessageId"));
        assertEquals("req-2", parsed.get("untilClientRequestId"));
    }
}
