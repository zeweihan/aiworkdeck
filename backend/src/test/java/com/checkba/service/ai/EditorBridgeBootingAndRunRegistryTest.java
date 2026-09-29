// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * dev-board#1016 / #1017 在桥这一层的三份契约：
 * <ol>
 *   <li>前端回「编辑器仍在启动」（code=EDITOR_BOOTING）时，桥回执是可重试的等待，而不是普通失败；</li>
 *   <li>component_required 发出后给编排器留一个一次性的「等组件」标记；</li>
 *   <li>本轮生成物登记：同一轮同一目标可查回，新一轮清空。</li>
 * </ol>
 */
class EditorBridgeBootingAndRunRegistryTest {

    private static EditorBridgeService bridge(SseEmitterService sse) {
        return new EditorBridgeService(sse, new ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));
    }

    /** 下发一条命令，拿到 requestId 后按给定形状回执，返回桥给工具的结果。 */
    private static String roundTrip(Object data, String error) throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        BlockingQueue<String> dispatched = new LinkedBlockingQueue<>();
        doAnswer(inv -> {
            dispatched.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());
        EditorBridgeService svc = bridge(sse);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> call = pool.submit(() -> {
                svc.setCurrentConversationId("conv-boot");
                return svc.executeEditorCommand("get_document_text", Map.of());
            });
            String payload = dispatched.poll(5, TimeUnit.SECONDS);
            assertNotNull(payload, "命令没能下发");
            String requestId = (String) new ObjectMapper().readValue(payload, Map.class).get("requestId");
            assertTrue(svc.completeEditorAction(requestId, "conv-boot", false, data, error));
            return call.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("前端按 {error,code:EDITOR_BOOTING,retryable} 回执：桥回可重试的等待，工具输出可被编排器识别")
    void bootingCodeInDataBecomesRetryableWait() throws Exception {
        String out = roundTrip(Map.of("error", "editor still booting", "code", "EDITOR_BOOTING", "retryable", true),
                "editor still booting");

        assertTrue(out.contains("\"error\""), "这一步确实没执行，面板不该打绿勾：" + out);
        assertTrue(out.contains("\"code\": \"EDITOR_BOOTING\""), out);
        assertTrue(out.contains("\"retryable\": true"), out);
        assertTrue(out.contains("重试同一步"), out);
        assertTrue(out.contains("不要改用新建文件"), out);
        assertTrue(EditorBridgeService.isEditorBootingOutput(out));
    }

    @Test
    @DisplayName("只回了一句带码的错误串（旧形状）也认")
    void bootingCodeInErrorStringAlsoRecognised() throws Exception {
        String out = roundTrip(null, "EDITOR_BOOTING: editor not ready yet");
        assertTrue(EditorBridgeService.isEditorBootingOutput(out), out);
    }

    @Test
    @DisplayName("普通失败不被误认成启动中")
    void ordinaryFailureIsNotBooting() throws Exception {
        String out = roundTrip(null, "match index out of range");
        assertFalse(EditorBridgeService.isEditorBootingOutput(out), out);
    }

    @Test
    @DisplayName("isEditorBootingOutput：认被包进另一段 JSON（引号转义）的回执，不认正文里碰巧出现的这个词")
    void bootingOutputDetectionIsShapeBased() {
        assertTrue(EditorBridgeService.isEditorBootingOutput(
                "Error opening file: {\"error\": \"x\", \"code\": \"EDITOR_BOOTING\", \"retryable\": true}"));
        assertTrue(EditorBridgeService.isEditorBootingOutput(
                "{\"result\":\"{\\\"error\\\":\\\"x\\\",\\\"code\\\":\\\"EDITOR_BOOTING\\\"}\"}"));
        assertFalse(EditorBridgeService.isEditorBootingOutput("第三段写着 EDITOR_BOOTING 这个词"));
        assertFalse(EditorBridgeService.isEditorBootingOutput(null));
    }

    @Test
    @DisplayName("component_required 发出后留一次性「等组件」标记；新一轮清空")
    void componentWaitIsOneShotAndClearedPerRun() {
        EditorBridgeService svc = bridge(mock(SseEmitterService.class));
        svc.setCurrentConversationId("conv-c");
        assertFalse(svc.consumeComponentWait("conv-c"), "没发过就不该有标记");

        svc.sendComponentRequiredAction("pptx-runtime", "pptx-service", null, 165, List.of(), "pdf_to_word");
        assertTrue(svc.consumeComponentWait("conv-c"));
        assertFalse(svc.consumeComponentWait("conv-c"), "读后即清");
        assertFalse(svc.consumeComponentWait("conv-other"), "别的会话不受影响");

        svc.sendComponentRequiredAction("pptx-runtime", "pptx-service", null, 165, List.of(), "pdf_to_word");
        svc.clearForNewRun("conv-c");
        assertFalse(svc.consumeComponentWait("conv-c"), "新一轮开始时残留标记要清掉");
    }

    @Test
    @DisplayName("本轮生成物登记：同轮同目标可查回，不同目标互不影响，新一轮清空")
    void generatedRegistryIsScopedToTheRun() {
        EditorBridgeService svc = bridge(mock(SseEmitterService.class));
        svc.setCurrentConversationId("conv-g");
        String key = EditorBridgeService.newDocxKey(null, "精简版");
        assertNull(svc.generatedInRun(key));

        svc.noteGenerated(key, 42L);
        assertEquals(42L, svc.generatedInRun(key));
        assertEquals(42L, svc.generatedInRun(EditorBridgeService.newDocxKey(0L, "精简版.DOCX")),
                "缺 .docx、大小写、根目录的 0 与 null 都应归一成同一个目标");
        assertNull(svc.generatedInRun(EditorBridgeService.newDocxKey(7L, "精简版.docx")), "换了文件夹就是另一个目标");

        svc.clearForNewRun("conv-g");
        assertNull(svc.generatedInRun(key), "跨轮（用户说「重新生成」）仍然新建");

        svc.noteGenerated(key, 43L);
        svc.forgetGenerated(key);
        assertNull(svc.generatedInRun(key));
    }
}
