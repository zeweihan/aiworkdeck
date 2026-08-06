package com.checkba.service.ai;

import java.util.Locale;

/**
 * LLM 调用失败的分类与重试策略（对标 OpenHands：RATE_LIMITED 与 ERROR 分开计状态）。
 *
 * <p>分开的理由是退避窗口不同：429 的限流窗口按分钟计，用 5xx 那套 8/16/32s 退避会在
 * 窗口内连撞三次、把重试预算白白烧光（OpenHands PR#6557 的教训），所以限流走 30/60s。
 * 模型下线（404「No endpoints found」，PR#144 踩过）重试多少次都不会好，直接交给故障转移换模型。
 */
public final class LlmErrorClassifier {

    private LlmErrorClassifier() {
    }

    public enum Kind {
        /** 429 / rate limit：退避更长，盖过按分钟计的限流窗口 */
        RATE_LIMITED(2, 30),
        /** 5xx / 超时 / 断连：短退避快速重放 */
        TRANSIENT(3, 8),
        /** 模型下线或不可用（404）：重试无意义，直接换模型 */
        MODEL_UNAVAILABLE(0, 0),
        /** 参数/鉴权错误等：重放也不会好，且可能重复扣费探测 */
        FATAL(0, 0);

        private final int maxRetries;
        private final int baseDelaySeconds;

        Kind(int maxRetries, int baseDelaySeconds) {
            this.maxRetries = maxRetries;
            this.baseDelaySeconds = baseDelaySeconds;
        }

        public boolean retryable() {
            return maxRetries > 0;
        }

        public int maxRetries() {
            return maxRetries;
        }

        /** 第 attempt 次重试（从 1 起）的退避秒数：指数退避，限流 30/60，瞬时 8/16/32。 */
        public long retryDelaySeconds(int attempt) {
            if (!retryable()) return 0;
            int capped = Math.max(1, Math.min(attempt, maxRetries));
            return (long) baseDelaySeconds << (capped - 1);
        }

        /** 允许换模型继续：只有明确的 FATAL 不换（未知错误一律按 FATAL 处理，保守）。 */
        public boolean failoverable() {
            return this != FATAL;
        }

        /** 给用户看的一句话原因（中文，进 SSE 文本流）。 */
        public String userFacingReason() {
            return switch (this) {
                case RATE_LIMITED -> "触发了限流";
                case MODEL_UNAVAILABLE -> "当前不可用（可能已下线）";
                case TRANSIENT -> "连续多次响应失败";
                case FATAL -> "调用失败";
            };
        }
    }

    /**
     * 按异常链分类。就近优先：链上先出现的判定生效，避免外层包装异常的通用文案盖过内层状态码。
     */
    public static Kind classify(Throwable err) {
        for (Throwable t = err; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            // 结构化状态码优先：OpenAI 兼容通道（含 OpenRouter）的 message 是原始响应体，
            // 里面不一定出现「status code: NNN」字样，靠文本匹配会把 429 当未知错误直接终局
            if (t instanceof dev.ai4j.openai4j.OpenAiHttpException http) {
                return classifyStatusCode(http.code());
            }
            String msg = t.getMessage();
            if (msg != null) {
                Kind byMessage = classifyMessage(msg);
                if (byMessage != null) {
                    return byMessage;
                }
            }
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.io.InterruptedIOException
                    || t instanceof java.io.IOException) {
                return Kind.TRANSIENT;
            }
        }
        return Kind.FATAL;
    }

    /** HTTP 状态码到分类的映射（与文本匹配分支保持同一口径）。 */
    public static Kind classifyStatusCode(int code) {
        if (code == 429) return Kind.RATE_LIMITED;
        if (code == 404) return Kind.MODEL_UNAVAILABLE;
        if (code >= 500) return Kind.TRANSIENT;
        return Kind.FATAL;
    }

    private static Kind classifyMessage(String msg) {
        String m = msg.toLowerCase(Locale.ROOT);
        if (m.contains("status code: 429") || m.contains("rate limit") || m.contains("too many requests")) {
            return Kind.RATE_LIMITED;
        }
        // 模型下线：OpenRouter 用 404 + "No endpoints found" 表达，与「路径写错」无法区分，
        // 一律当作换模型信号——换掉之后即便原因是别的，也比原地重试有机会跑通
        if (m.contains("status code: 404") || m.contains("no endpoints found")
                || m.contains("model not found") || m.contains("is not a valid model")) {
            return Kind.MODEL_UNAVAILABLE;
        }
        if (m.contains("status code: 400") || m.contains("status code: 401")
                || m.contains("status code: 403")) {
            return Kind.FATAL;
        }
        if (m.contains("status code: 5") || m.contains("overloaded")
                || m.contains("timeout") || m.contains("timed out")
                || m.contains("connection reset") || m.contains("stream was reset")
                || m.contains("unexpected end of stream") || m.contains("canceled")) {
            return Kind.TRANSIENT;
        }
        return null;
    }
}
