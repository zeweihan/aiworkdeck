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
    @DisplayName("不整体放宽 403：key 失效等仍是 FATAL，不许被带进换模型重试")
    void nonRegionForbiddenStaysFatal() {
        for (String msg : new String[]{
                "status code: 403 - Your API key has been disabled",
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
    @DisplayName("配额耗尽单列 QUOTA_EXHAUSTED：终局——不重试、不换模型（换哪个都是同一个没钱的账户）")
    void quotaExhaustionIsTerminal() {
        for (String msg : new String[]{
                "status code: 402 - Payment Required",
                "status code: 403 - Insufficient credits",
                "status code: 429 - You exceeded your current quota, please check your plan and billing details",
                "Insufficient Balance"}) {
            LlmErrorClassifier.Kind kind = LlmErrorClassifier.classify(new RuntimeException(msg));
            assertEquals(LlmErrorClassifier.Kind.QUOTA_EXHAUSTED, kind, msg);
            assertFalse(kind.retryable(), msg);
            assertFalse(kind.failoverable(), msg);
        }
        // 结构化通道：OpenRouter 余额耗尽是 402；429 载荷带配额语义时也要判成配额而不是限流
        assertEquals(LlmErrorClassifier.Kind.QUOTA_EXHAUSTED, LlmErrorClassifier.classify(
                new dev.ai4j.openai4j.OpenAiHttpException(402,
                        "{\"error\":{\"message\":\"Insufficient credits. Add more using https://openrouter.ai/credits\"}}")));
        assertEquals(LlmErrorClassifier.Kind.QUOTA_EXHAUSTED, LlmErrorClassifier.classify(
                new dev.ai4j.openai4j.OpenAiHttpException(429,
                        "{\"error\":{\"message\":\"quota exceeded for this billing cycle\"}}")));
    }

    @Test
    @DisplayName("配额判定先于限流：普通 429 仍是 RATE_LIMITED，带配额语义的 429 才是 QUOTA")
    void plainRateLimitStillRateLimited() {
        assertEquals(LlmErrorClassifier.Kind.RATE_LIMITED, LlmErrorClassifier.classify(
                new RuntimeException("status code: 429 - rate limit exceeded, retry shortly")));
    }

    @Test
    @DisplayName("上下文超窗单列 CONTEXT_OVERFLOW：不退避不换模型，交给编排器的强制压缩重试通道")
    void contextOverflowGetsDedicatedRecoveryChannel() {
        for (String msg : new String[]{
                "status code: 400 - This model's maximum context length is 131072 tokens. However, you requested 140000 tokens",
                "status code: 400 - {\"error\":{\"code\":\"context_length_exceeded\"}}",
                "status code: 400 - prompt is too long: 210000 tokens > 200000 maximum",
                "status code: 400 - input length and max_tokens exceed context limit"}) {
            LlmErrorClassifier.Kind kind = LlmErrorClassifier.classify(new RuntimeException(msg));
            assertEquals(LlmErrorClassifier.Kind.CONTEXT_OVERFLOW, kind, msg);
            assertFalse(kind.retryable(), "原样重发必然再撞同一个 400：" + msg);
            assertFalse(kind.failoverable(), "不走故障转移链，走压缩重试通道：" + msg);
        }
        // 结构化通道同一口径
        assertEquals(LlmErrorClassifier.Kind.CONTEXT_OVERFLOW, LlmErrorClassifier.classify(
                new dev.ai4j.openai4j.OpenAiHttpException(400,
                        "{\"error\":{\"message\":\"This model's maximum context length is 131072 tokens\"}}")));
        // 普通 400 仍是 FATAL：判据顺序反了会把参数错误也带进压缩重试
        assertEquals(LlmErrorClassifier.Kind.FATAL, LlmErrorClassifier.classify(
                new RuntimeException("status code: 400 - invalid request: unknown parameter")));
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
    @DisplayName("SSE 载荷标记：区域拒绝/配额耗尽/上下文超窗三类加前缀，前端靠 includes 命中")
    void machineReadableMarkersOnTaggedKinds() {
        String tagged = LlmErrorClassifier.taggedErrorMessage(
                LlmErrorClassifier.Kind.REGION_BLOCKED, "403 not available in your region");
        assertTrue(tagged.contains(LlmErrorClassifier.REGION_BLOCKED_MARKER));
        assertTrue(("Stream Error: " + tagged).contains(LlmErrorClassifier.REGION_BLOCKED_MARKER));

        assertTrue(LlmErrorClassifier.taggedErrorMessage(
                        LlmErrorClassifier.Kind.QUOTA_EXHAUSTED, "Insufficient credits")
                .contains(LlmErrorClassifier.QUOTA_EXHAUSTED_MARKER));
        assertTrue(LlmErrorClassifier.taggedErrorMessage(
                        LlmErrorClassifier.Kind.CONTEXT_OVERFLOW, "maximum context length exceeded")
                .contains(LlmErrorClassifier.CONTEXT_OVERFLOW_MARKER));

        assertEquals("boom", LlmErrorClassifier.taggedErrorMessage(
                LlmErrorClassifier.Kind.FATAL, "boom"), "其余分类不许引入噪声前缀");
        assertEquals("boom", LlmErrorClassifier.taggedErrorMessage(
                LlmErrorClassifier.Kind.RATE_LIMITED, "boom"), "其余分类不许引入噪声前缀");
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
