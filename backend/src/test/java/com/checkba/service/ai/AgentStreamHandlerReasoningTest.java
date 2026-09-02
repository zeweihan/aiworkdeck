package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 思考增量在编排层的两条契约（dev-board#364）：
 * <ol>
 *   <li>转发成 SSE {@code reasoning_delta}，且不进正文（不落库、不回喂模型）；</li>
 *   <li>思考型模型静默几分钟是正常的——思考增量与保活注释都要刷新看门狗，
 *       否则 60 秒首字节时限会把正在思考的模型掐掉、按瞬时错误重放，白烧一轮思考 token。</li>
 * </ol>
 */
class AgentStreamHandlerReasoningTest {

    private static AgentStreamHandler handler(SseEmitterService sse, AtomicReference<Throwable> sink, CountDownLatch fired) {
        AgentStreamHandler h = new AgentStreamHandler(sse, "conv-reasoning-test",
                mock(TokenUsageService.class), "1", 1L, "moonshotai/kimi-k3", 0L);
        h.setOnError(err -> {
            sink.set(err);
            fired.countDown();
        });
        return h;
    }

    @Test
    @DisplayName("思考增量转发成 reasoning_delta 事件，不进 text_delta、不算作已流出正文")
    void reasoningIsForwardedAsItsOwnEvent() {
        SseEmitterService sse = mock(SseEmitterService.class);
        AgentStreamHandler h = handler(sse, new AtomicReference<>(), new CountDownLatch(1));

        h.onReasoning("先核对\"第三条\"");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sse).send(eq("conv-reasoning-test"), eq("reasoning_delta"), payload.capture());
        assertEquals("{\"content\":\"先核对\\\"第三条\\\"\"}", String.valueOf(payload.getValue()),
                "思考文本要经过 JSON 转义，引号/换行不能把事件打坏");
        verify(sse, never()).send(anyString(), eq("text_delta"), anyString());
        assertTrue(h.hasStreamedReasoning());
        assertFalse(h.hasStreamedTokens(),
                "可重放判定只看正文：思考卡重放一遍无害，正文重放才会让用户看到重复内容");
    }

    @Test
    @DisplayName("只有思考增量、还没有正文时，看门狗改用停滞时限而不是首字节时限")
    void reasoningKeepsWatchdogFromFiringOnFirstTokenBudget() throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch fired = new CountDownLatch(1);
        AgentStreamHandler h = handler(mock(SseEmitterService.class), err, fired);

        h.armInactivityWatchdog(1, 3600);
        h.onReasoning("模型在想");

        assertFalse(fired.await(8, TimeUnit.SECONDS),
                "思考型模型正在吐 reasoning 却按「零字节」被掐——这就是 K3 被 60 秒看门狗反复打断的病灶");
    }

    @Test
    @DisplayName("保活注释刷新看门狗：上游只发 keep-alive 也不许按死流终止")
    void keepAliveRefreshesWatchdog() throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch fired = new CountDownLatch(1);
        AgentStreamHandler h = handler(mock(SseEmitterService.class), err, fired);

        // 首字节时限 2s，但每 500ms 来一次保活：idle 永远到不了 2s
        h.armInactivityWatchdog(2, 3600);
        long until = System.currentTimeMillis() + 6500;
        while (System.currentTimeMillis() < until) {
            h.onKeepAlive();
            if (fired.await(500, TimeUnit.MILLISECONDS)) break;
        }
        assertFalse(fired.getCount() == 0,
                "OpenRouter 的 \": OPENROUTER PROCESSING\" 是上游还在跑的证据，看门狗必须认它");
    }
}
