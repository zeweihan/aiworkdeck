package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 失败分类与退避策略。
 *
 * <p>背景：加固一期只有「瞬时/非瞬时」二分，429 与 5xx 共用 8/16/32s 退避——限流窗口按分钟计，
 * 三次重试会在同一个窗口里连撞三次，等于白烧预算（OpenHands PR#6557 的教训）。
 */
class LlmErrorClassifierTest {

    @Test
    @DisplayName("429 单列为限流：退避 30/60s，比瞬时错误长得多")
    void rateLimitGetsLongerBackoff() {
        LlmErrorClassifier.Kind kind = LlmErrorClassifier.classify(
                new RuntimeException("status code: 429 - rate limit exceeded"));

        assertEquals(LlmErrorClassifier.Kind.RATE_LIMITED, kind);
        assertEquals(2, kind.maxRetries());
        assertEquals(30, kind.retryDelaySeconds(1));
        assertEquals(60, kind.retryDelaySeconds(2));
        assertTrue(kind.userFacingReason().contains("限流"), "文案要说限流，不能说服务不可用");
    }

    @Test
    @DisplayName("5xx/超时/断连是瞬时错误：退避 8/16/32s")
    void serverErrorsUseShortBackoff() {
        for (Throwable err : new Throwable[]{
                new RuntimeException("status code: 502 - bad gateway"),
                new RuntimeException("provider overloaded"),
                new java.net.SocketTimeoutException("timeout"),
                new java.io.IOException("unexpected end of stream")}) {
            LlmErrorClassifier.Kind kind = LlmErrorClassifier.classify(err);
            assertEquals(LlmErrorClassifier.Kind.TRANSIENT, kind, String.valueOf(err));
        }
        LlmErrorClassifier.Kind transient_ = LlmErrorClassifier.Kind.TRANSIENT;
        assertEquals(3, transient_.maxRetries());
        assertEquals(8, transient_.retryDelaySeconds(1));
        assertEquals(16, transient_.retryDelaySeconds(2));
        assertEquals(32, transient_.retryDelaySeconds(3));
    }

    @Test
    @DisplayName("模型下线 404：不重试，直接允许换模型（PR#144 的坑）")
    void modelOfflineGoesStraightToFailover() {
        LlmErrorClassifier.Kind kind = LlmErrorClassifier.classify(
                new RuntimeException("status code: 404 - No endpoints found for vendor/dead-model"));

        assertEquals(LlmErrorClassifier.Kind.MODEL_UNAVAILABLE, kind);
        assertFalse(kind.retryable(), "重试多少次模型也不会回来");
        assertTrue(kind.failoverable(), "必须允许切模型");
    }

    @Test
    @DisplayName("400/401/403 与未知错误一律 FATAL：既不重试也不切模型")
    void clientErrorsAreFatal() {
        for (String msg : new String[]{
                "status code: 400 - invalid request",
                "status code: 401 - no auth credentials found",
                "status code: 403 - This model is not available in your region"}) {
            LlmErrorClassifier.Kind kind = LlmErrorClassifier.classify(new RuntimeException(msg));
            assertEquals(LlmErrorClassifier.Kind.FATAL, kind, msg);
            assertFalse(kind.retryable(), msg);
            assertFalse(kind.failoverable(), msg);
        }
        assertEquals(LlmErrorClassifier.Kind.FATAL,
                LlmErrorClassifier.classify(new IllegalStateException("something odd")),
                "未知错误保守处理：不切模型（切了可能重复扣费探测）");
    }

    @Test
    @DisplayName("OpenAI 兼容通道的结构化状态码优先：message 是原始响应体，没有「status code」字样")
    void structuredStatusCodeWinsOverTextMatching() {
        Throwable err = new RuntimeException("wrapper",
                new dev.ai4j.openai4j.OpenAiHttpException(429,
                        "{\"error\":{\"message\":\"Provider returned error\",\"code\":429}}"));

        assertEquals(LlmErrorClassifier.Kind.RATE_LIMITED, LlmErrorClassifier.classify(err));
    }

    @Test
    @DisplayName("异常链就近优先：内层状态码不被外层包装的通用文案盖掉")
    void nearestCauseWins() {
        Throwable err = new RuntimeException("Error while streaming response",
                new RuntimeException("status code: 503 - service unavailable"));

        assertEquals(LlmErrorClassifier.Kind.TRANSIENT, LlmErrorClassifier.classify(err));
    }
}
