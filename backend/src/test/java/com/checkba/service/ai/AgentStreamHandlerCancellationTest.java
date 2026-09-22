// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 流式 handler 的取消闸（计划 K4 ②；审计 C-03）。
 *
 * <p>okhttp 的 {@code call.cancel()} 不是瞬时的：在途的那一段响应体已经在本机缓冲里，
 * 取消之后 {@code onNext} 还会被调用几次。闸不加在 handler 上的话，这几段会继续
 * 打进用户的气泡、继续进恢复快照、继续往文档里流式写——用户点了停止，画面却还在动。
 */
class AgentStreamHandlerCancellationTest {

    private record Sent(String event, String payload) { }

    private static List<Sent> captureFrom(SseEmitterService sse) {
        List<Sent> sent = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            sent.add(new Sent(inv.getArgument(1, String.class), String.valueOf((Object) inv.getArgument(2))));
            return null;
        }).when(sse).send(any(), any(), any());
        return sent;
    }

    @Test
    @DisplayName("取消闸一关：onNext/onReasoning 既不上 SSE 也不进恢复快照与编辑器流")
    void cancelledHandlerStopsForwardingEverything() {
        SseEmitterService sse = mock(SseEmitterService.class);
        List<Sent> sent = captureFrom(sse);
        AtomicBoolean cancelled = new AtomicBoolean();
        StringBuilder recovery = new StringBuilder();
        StringBuilder editor = new StringBuilder();

        AgentStreamHandler h = new AgentStreamHandler(sse, "conv-cancel-gate",
                mock(TokenUsageService.class), "1", 1L, "deepseek/deepseek-v4-flash", 0L, () -> true);
        h.setCancellationCheck(cancelled::get);
        h.setOnToken(recovery::append);
        h.setOnEditorStream(editor::append);

        h.onNext("取消之前的正文。");
        h.onReasoning("取消之前的思考");
        assertTrue(sent.stream().anyMatch(s -> "text_delta".equals(s.event())),
                "取消之前的 token 必须照常转发：" + sent);
        assertEquals(1, sent.stream().filter(s -> "reasoning_delta".equals(s.event())).count());

        cancelled.set(true);
        int before = sent.size();
        for (int i = 0; i < 20; i++) {
            h.onNext("取消之后的第" + i + "段。");
            h.onReasoning("取消之后的思考" + i);
        }

        assertEquals(before, sent.size(),
                "取消之后仍在往 SSE 上发事件，用户点了停止画面却还在动：" + sent);
        assertFalse(recovery.toString().contains("取消之后"),
                "取消之后的 token 不该再进断线恢复快照：" + recovery);
        assertFalse(editor.toString().contains("取消之后"),
                "取消之后的 token 不该再往文档里流式写：" + editor);
    }

    @Test
    @DisplayName("不设取消闸的调用方（既有单测与回放评测）行为一字不变")
    void handlersWithoutACancellationCheckBehaveExactlyAsBefore() {
        SseEmitterService sse = mock(SseEmitterService.class);
        List<Sent> sent = captureFrom(sse);

        AgentStreamHandler h = new AgentStreamHandler(sse, "conv-no-gate",
                mock(TokenUsageService.class), "1", 1L, "deepseek/deepseek-v4-flash", 0L);
        h.onNext("照常转发。");

        assertTrue(sent.stream().anyMatch(s -> "text_delta".equals(s.event())), "默认闸必须是开着的：" + sent);
    }
}
