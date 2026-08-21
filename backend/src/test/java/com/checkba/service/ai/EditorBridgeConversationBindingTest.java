package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * 编辑器结果回传的鉴权键必须是「这个 requestId 属于哪个会话」，
 * 而不是调用方自己报的 conversationId。
 *
 * <p>病灶：pendingRequests 只按 requestId 存 future，完全不记会话；
 * 而控制器校验的是 payload 里客户端自报的 conversationId（只要是他自己的会话就放行）。
 * 两把钥匙不是同一把——拿到别的会话的 requestId 就能把伪造内容作为工具结果
 * 塞进那一轮的 Agent 循环，而结果会被当成可信的文档内容驱动后续改文档动作。
 */
class EditorBridgeConversationBindingTest {

    private EditorBridgeService bridge() {
        return new EditorBridgeService(
                mock(SseEmitterService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));
    }

    /** 起一轮编辑器命令并把它的 requestId 捞出来（SSE 载荷里带着）。 */
    private String startCommandAndCaptureRequestId(EditorBridgeService svc, String conversationId,
                                                   SseEmitterService sse, ExecutorService pool) throws Exception {
        java.util.concurrent.BlockingQueue<String> payloads = new java.util.concurrent.LinkedBlockingQueue<>();
        org.mockito.Mockito.doAnswer(inv -> {
            payloads.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());

        pool.submit(() -> {
            svc.setCurrentConversationId(conversationId);
            svc.executeEditorCommand("get_selection", Map.of());
        });
        String payload = payloads.poll(5, TimeUnit.SECONDS);
        assertTrue(payload != null && payload.contains("requestId"), "没抓到 client_action 载荷: " + payload);
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload).get("requestId").asText();
    }

    @Test
    @DisplayName("别的会话回传同一个 requestId 必须被拒；本会话回传照常兑现")
    void resultsAreBoundToTheIssuingConversation() throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        EditorBridgeService svc = new EditorBridgeService(sse,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            String requestId = startCommandAndCaptureRequestId(svc, "conv-A", sse, pool);

            assertFalse(svc.completeEditorAction(requestId, "conv-B", true, Map.of("text", "伪造"), null),
                    "requestId 属于 conv-A，conv-B 不该能兑现它");
            assertTrue(svc.completeEditorAction(requestId, "conv-A", true, Map.of("text", "真结果"), null),
                    "本会话回传要照常生效");
        } finally {
            pool.shutdownNow();
        }
    }
}
