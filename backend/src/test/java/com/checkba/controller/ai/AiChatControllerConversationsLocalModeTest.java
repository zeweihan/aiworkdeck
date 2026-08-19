package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.ProjectAiMessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GET /api/ai/conversations 在 local-mode 下不带 X-Session-Id 头的回归。
 *
 * 根因：getConversations 此前把 userId 解析包在 `if (sessionId != null)` 里，
 * local-mode 免登请求本就不带 session 头，于是 userId 恒为 null，
 * 历史会话列表恒空——即便 local-mode 下 AuthController.getUserIdFromSession(null)
 * 本能正确解析出本机用户。修复后改为无条件调用 getUserIdFromSession，
 * 与 ProjectOverviewController.requireRead 的口径一致。
 */
class AiChatControllerConversationsLocalModeTest {

    private Object previousLocalIdentity;

    private static Field localIdentityField() throws Exception {
        Field field = AuthController.class.getDeclaredField("staticLocalIdentityService");
        field.setAccessible(true);
        return field;
    }

    /** 静态注册位是全局状态，用完必须还原，否则会污染同一 JVM 里的其它测试。 */
    @BeforeEach
    void rememberLocalIdentity() throws Exception {
        previousLocalIdentity = localIdentityField().get(null);
    }

    @AfterEach
    void restoreLocalIdentity() throws Exception {
        localIdentityField().set(null, previousLocalIdentity);
    }

    @Test
    @DisplayName("local-mode + 请求不带 X-Session-Id 头：按本机用户返回已有会话，不再恒空")
    void localModeWithoutSessionHeaderReturnsLocalUserConversations() throws Exception {
        LocalIdentityService identity = mock(LocalIdentityService.class);
        when(identity.isLocalMode()).thenReturn(true);
        when(identity.localUserId()).thenReturn(99L);
        AuthController.registerLocalIdentityService(identity);

        ProjectAiMessageService projectAiMessageService = mock(ProjectAiMessageService.class);
        List<Map<String, Object>> existingConversations = List.of(
                new java.util.HashMap<>(Map.of("conversationId", "c-1", "title", "既有会话")));
        when(projectAiMessageService.listConversations(eq(42L), eq(99L)))
                .thenReturn(existingConversations);

        com.checkba.service.ai.AgentRunStateService agentRunStateService =
                mock(com.checkba.service.ai.AgentRunStateService.class);
        when(agentRunStateService.statusName(any())).thenReturn(null);

        AiChatController controller = new AiChatController(
                mock(com.checkba.service.ai.AiAssistantService.class),
                projectAiMessageService,
                mock(com.checkba.service.ai.AiDocxExportService.class),
                mock(com.checkba.service.ai.ChatModelFactory.class),
                mock(com.checkba.service.ai.ConversationFileChangeService.class),
                mock(com.checkba.repository.TokenUsageRepository.class),
                agentRunStateService,
                mock(com.checkba.service.ai.PlatformAiChannel.class));

        List<Map<String, Object>> result = controller.getConversations(42L, null);

        assertFalse(result.isEmpty(), "local-mode 下无 session 头也应能拿到本机用户名下的已有会话");
        assertEquals("c-1", result.get(0).get("conversationId"));
        verify(projectAiMessageService).listConversations(42L, 99L);
    }
}
