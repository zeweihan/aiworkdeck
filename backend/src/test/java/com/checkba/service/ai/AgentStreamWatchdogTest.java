package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 流式看门狗的两条时限必须分开：从头到尾零字节走短的首字节时限，中途断供走长的停滞时限。
 *
 * <p>为什么不能只用一个值：停滞时限要照顾"模型生成长工具参数时中间静默几十秒"这种正常情况，
 * 所以必须给到 180s；但"从头到尾一个字节都没有"没有这种正当理由，
 * 让用户干等满 180s 纯粹是白等（AGENT 模式下的原始现象就是"点了发送三分钟没反应"）。
 */
class AgentStreamWatchdogTest {

    private AgentStreamHandler handlerWithErrorSink(AtomicReference<Throwable> sink, CountDownLatch fired) {
        AgentStreamHandler handler = new AgentStreamHandler(
                mock(SseEmitterService.class), "conv-watchdog-test",
                mock(TokenUsageService.class), "1", 1L, "deepseek/deepseek-v4-flash", 0L);
        handler.setOnError(err -> {
            sink.set(err);
            fired.countDown();
        });
        return handler;
    }

    @Test
    @DisplayName("一个 token 都没到过时走首字节时限，不必等满停滞时限")
    void zeroTokenRoundUsesFirstTokenBudget() throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch fired = new CountDownLatch(1);
        AgentStreamHandler handler = handlerWithErrorSink(err, fired);

        // 首字节 1s / 停滞 3600s：只有按首字节时限判定才可能在用例超时前触发
        handler.armInactivityWatchdog(1, 3600);

        assertTrue(fired.await(20, TimeUnit.SECONDS),
                "零 token 的轮次必须按首字节时限终止，否则用户要陪着等满整个停滞时限");
        assertNotNull(err.get());
        assertTrue(err.get() instanceof java.util.concurrent.TimeoutException,
                "必须是 TimeoutException——LlmErrorClassifier 据此归入 TRANSIENT 走退避重试");
    }

    @Test
    @DisplayName("已经吐过 token 后改用停滞时限，不会被首字节时限误杀")
    void startedStreamKeepsLongInactivityBudget() throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch fired = new CountDownLatch(1);
        AgentStreamHandler handler = handlerWithErrorSink(err, fired);

        handler.armInactivityWatchdog(1, 3600);
        handler.onNext("已经在吐了");

        assertFalse(fired.await(12, TimeUnit.SECONDS),
                "流已经开始了就该按停滞时限判定；用首字节时限会把正在生成长工具参数的正常轮次误杀");
        assertTrue(handler.hasStreamedTokens());
    }
}
