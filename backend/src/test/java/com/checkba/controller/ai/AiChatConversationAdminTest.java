// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.service.ProjectAiMessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 会话删除/重命名端点（dev-board#148，Office 插件历史面板）的守卫回归：
 * 归属不过 403、进行中的会话拒删 409、标题长度闸、正常路径真的落到 service。
 */
class AiChatConversationAdminTest {

    private AiChatController controller(ProjectAiMessageService svc,
                                        com.checkba.service.ai.AgentRunStateService runState) {
        return new AiChatController(
                svc,
                mock(com.checkba.service.ai.AiDocxExportService.class),
                mock(com.checkba.service.ai.ChatModelFactory.class),
                mock(com.checkba.service.ai.ConversationFileChangeService.class),
                mock(com.checkba.repository.TokenUsageRepository.class),
                runState,
                mock(com.checkba.service.ai.PlatformAiChannel.class),
                new com.checkba.config.AiContextProperties());
    }

    @Test
    @DisplayName("删除：归属校验不过 → 403，不碰 service")
    void deleteForbiddenWhenNotOwner() {
        ProjectAiMessageService svc = mock(ProjectAiMessageService.class);
        when(svc.canUseConversation(any(), any())).thenReturn(false);
        var runState = mock(com.checkba.service.ai.AgentRunStateService.class);

        ResponseEntity<?> resp = controller(svc, runState).deleteConversation("c-x", null);

        assertEquals(403, resp.getStatusCode().value());
        verify(svc, never()).deleteConversation(any());
    }

    @Test
    @DisplayName("删除：会话仍在 RUNNING → 409 拒删")
    void deleteRefusedWhileRunning() {
        ProjectAiMessageService svc = mock(ProjectAiMessageService.class);
        when(svc.canUseConversation(any(), any())).thenReturn(true);
        var runState = mock(com.checkba.service.ai.AgentRunStateService.class);
        when(runState.statusName("c-run")).thenReturn("RUNNING");

        ResponseEntity<?> resp = controller(svc, runState).deleteConversation("c-run", null);

        assertEquals(409, resp.getStatusCode().value());
        verify(svc, never()).deleteConversation(any());
    }

    @Test
    @DisplayName("删除：归属过且空闲 → 200 且 service 真被调用")
    void deleteHappyPath() {
        ProjectAiMessageService svc = mock(ProjectAiMessageService.class);
        when(svc.canUseConversation(any(), any())).thenReturn(true);
        var runState = mock(com.checkba.service.ai.AgentRunStateService.class);
        when(runState.statusName(any())).thenReturn(null);

        ResponseEntity<?> resp = controller(svc, runState).deleteConversation("c-ok", null);

        assertEquals(200, resp.getStatusCode().value());
        verify(svc).deleteConversation("c-ok");
    }

    @Test
    @DisplayName("重命名：空标题/超 60 字符 → 400；正常标题落 updateConversationTitle")
    void renameValidation() {
        ProjectAiMessageService svc = mock(ProjectAiMessageService.class);
        when(svc.canUseConversation(any(), any())).thenReturn(true);
        var runState = mock(com.checkba.service.ai.AgentRunStateService.class);
        AiChatController c = controller(svc, runState);

        assertEquals(400, c.renameConversation("c-1", Map.of("title", "  "), null).getStatusCode().value());
        assertEquals(400, c.renameConversation("c-1", Map.of("title", "长".repeat(61)), null).getStatusCode().value());
        verify(svc, never()).updateConversationTitle(any(), any());

        assertEquals(200, c.renameConversation("c-1", Map.of("title", " 尽调要点讨论 "), null).getStatusCode().value());
        verify(svc).updateConversationTitle("c-1", "尽调要点讨论");
    }

    @Test
    @DisplayName("置顶：归属校验不过 → 403，不碰 service")
    void pinForbiddenWhenNotOwner() {
        ProjectAiMessageService svc = mock(ProjectAiMessageService.class);
        when(svc.canUseConversation(any(), any())).thenReturn(false);

        ResponseEntity<?> resp = controller(svc, mock(com.checkba.service.ai.AgentRunStateService.class))
                .pinConversation("c-x", Map.of("pinned", true), null);

        assertEquals(403, resp.getStatusCode().value());
        verify(svc, never()).updateConversationPinned(any(), anyBoolean());
    }

    @Test
    @DisplayName("置顶：true/false/缺字段 都落到 service，缺字段按取消置顶")
    void pinHappyPath() {
        ProjectAiMessageService svc = mock(ProjectAiMessageService.class);
        when(svc.canUseConversation(any(), any())).thenReturn(true);
        AiChatController c = controller(svc, mock(com.checkba.service.ai.AgentRunStateService.class));

        assertEquals(200, c.pinConversation("c-1", Map.of("pinned", true), null).getStatusCode().value());
        verify(svc).updateConversationPinned("c-1", true);

        assertEquals(200, c.pinConversation("c-1", Map.of("pinned", false), null).getStatusCode().value());
        verify(svc).updateConversationPinned("c-1", false);

        // 这条只改排序，没有破坏性；缺字段回 400 是无谓的摩擦
        assertEquals(200, c.pinConversation("c-1", null, null).getStatusCode().value());
        verify(svc, times(2)).updateConversationPinned("c-1", false);
    }
}
