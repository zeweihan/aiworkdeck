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
    @DisplayName("400/401 与未知错误一律 FATAL：既不重试也不切模型")
    void clientErrorsAreFatal() {
        for (String msg : new String[]{
                "status code: 400 - invalid request",
                "status code: 401 - no auth credentials found"}) {
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
    @DisplayName("403 地域拒绝单列 REGION_BLOCKED：不重试但允许换模型，且候选要收窄成区域无关")
    void regionRejectionIsFailoverable() {
        for (String msg : new String[]{
                "status code: 403 - This model is not available in your region",
                "status code: 403 - {\"error\":{\"code\":\"unsupported_country_region_territory\"}}",
                "status code: 403 - Requests from your Region are not supported"}) {
            LlmErrorClassifier.Kind kind = LlmErrorClassifier.classify(new RuntimeException(msg));
            assertEquals(LlmErrorClassifier.Kind.REGION_BLOCKED, kind, msg);
            assertFalse(kind.retryable(), "同一网络里重试永远撞同一个 403");
            assertTrue(kind.failoverable(), "必须允许切模型，否则用户没有任何出路");
            assertTrue(kind.requiresRegionAgnosticFailover(), "候选必须过滤成区域无关模型");
        }
    }

    @Test
    @DisplayName("不整体放宽 403：key 失效/额度禁用仍是 FATAL，不许被带进换模型重试")
    void nonRegionForbiddenStaysFatal() {
        for (String msg : new String[]{
                "status code: 403 - Your API key has been disabled",
                "status code: 403 - Insufficient credits",
                "status code: 403 - Forbidden"}) {
            LlmErrorClassifier.Kind kind = LlmErrorClassifier.classify(new RuntimeException(msg));
            assertEquals(LlmErrorClassifier.Kind.FATAL, kind, msg);
            assertFalse(kind.failoverable(), msg);
        }
        // 结构化通道同一口径：403 但响应体没有地域语义
        assertEquals(LlmErrorClassifier.Kind.FATAL, LlmErrorClassifier.classify(
                new dev.ai4j.openai4j.OpenAiHttpException(403,
                        "{\"error\":{\"message\":\"User not found or key revoked\"}}")));
    }

    @Test
    @DisplayName("结构化 403 也走响应体判地域：message 里没有「status code」字样")
    void structuredForbiddenUsesResponseBody() {
        Throwable err = new RuntimeException("Error while streaming response",
                new dev.ai4j.openai4j.OpenAiHttpException(403,
                        "{\"error\":{\"message\":\"This model is not available in your region\"}}"));

        assertEquals(LlmErrorClassifier.Kind.REGION_BLOCKED, LlmErrorClassifier.classify(err));
    }

    @Test
    @DisplayName("上游改文案就退化成 FATAL：退化方向安全（不换模型、只是文案回英文原文）")
    void unrecognizedForbiddenWordingDegradesToFatal() {
        assertEquals(LlmErrorClassifier.Kind.FATAL, LlmErrorClassifier.classify(
                new RuntimeException("status code: 403 - geo restriction applies")));
    }

    @Test
    @DisplayName("SSE 载荷标记：只有区域拒绝加前缀，前端靠 includes 命中（前面还会拼 Stream Error:）")
    void regionMarkerOnlyOnRegionBlocked() {
        String tagged = LlmErrorClassifier.taggedErrorMessage(
                LlmErrorClassifier.Kind.REGION_BLOCKED, "403 not available in your region");
        assertTrue(tagged.contains(LlmErrorClassifier.REGION_BLOCKED_MARKER));
        assertTrue(("Stream Error: " + tagged).contains(LlmErrorClassifier.REGION_BLOCKED_MARKER));

        assertEquals("boom", LlmErrorClassifier.taggedErrorMessage(
                LlmErrorClassifier.Kind.FATAL, "boom"), "其余分类不许引入噪声前缀");
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
