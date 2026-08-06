package com.checkba.controller.ai;

import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ai.OfficeBridgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Office 结果回传端点的会话归属校验：
 * 结果会以工具输出身份进入模型上下文，requestId 不是回传凭证——
 * 归属以后端挂起表登记的 conversationId 为准，且回传者必须可用该会话。
 */
class OfficeResultControllerTest {

    private OfficeBridgeService bridge;
    private ProjectAiMessageService messageService;
    private OfficeResultController controller;

    @BeforeEach
    void setUp() {
        bridge = mock(OfficeBridgeService.class);
        messageService = mock(ProjectAiMessageService.class);
        controller = new OfficeResultController(bridge, messageService);
    }

    private static OfficeResultController.OfficeResultPayload payload(String requestId, boolean ok) {
        OfficeResultController.OfficeResultPayload p = new OfficeResultController.OfficeResultPayload();
        p.setRequestId(requestId);
        p.setOk(ok);
        p.setData(java.util.Map.of("text", "hello"));
        return p;
    }

    @Test
    @DisplayName("归属校验通过：结果解锁挂起的 future")
    void acceptedWhenConversationUsable() {
        when(bridge.getPendingConversationId("req-1")).thenReturn("conv-1");
        when(messageService.canUseConversation(eq("conv-1"), any())).thenReturn(true);

        var resp = controller.receiveOfficeResult(payload("req-1", true), null);

        assertTrue(resp.isReceived());
        verify(bridge).completeOfficeAction(eq("req-1"), eq(true), any(), any());
    }

    @Test
    @DisplayName("无权会话：拒绝回传，不触碰挂起表")
    void rejectedWhenConversationNotUsable() {
        when(bridge.getPendingConversationId("req-1")).thenReturn("conv-1");
        when(messageService.canUseConversation(eq("conv-1"), any())).thenReturn(false);

        var resp = controller.receiveOfficeResult(payload("req-1", true), null);

        assertFalse(resp.isReceived());
        verify(bridge, never()).completeOfficeAction(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("未知/过期 requestId：拒绝回传（会话归属无从谈起）")
    void rejectedWhenRequestUnknown() {
        when(bridge.getPendingConversationId("req-gone")).thenReturn(null);

        var resp = controller.receiveOfficeResult(payload("req-gone", true), null);

        assertFalse(resp.isReceived());
        verify(bridge, never()).completeOfficeAction(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        verify(messageService, never()).canUseConversation(any(), any());
    }
}
