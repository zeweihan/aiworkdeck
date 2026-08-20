package com.checkba.service.ai;

import java.util.Locale;

/**
 * LLM 调用失败的分类与重试策略（对标 OpenHands：RATE_LIMITED 与 ERROR 分开计状态）。
 *
 * <p>分开的理由是退避窗口不同：429 的限流窗口按分钟计，用 5xx 那套 8/16/32s 退避会在
 * 窗口内连撞三次、把重试预算白白烧光（OpenHands PR#6557 的教训），所以限流走 30/60s。
 * 模型下线（404「No endpoints found」，PR#144 踩过）重试多少次都不会好，直接交给故障转移换模型。
 *
 * <p>REGION_BLOCKED 是 403 的一个子类：OpenRouter 对国际模型在境内网络会返回
 * 403「This model is not available in your region」。以前它和 key 失效、额度禁用一起被归成
 * FATAL，而 FATAL 不允许故障转移，用户只能拿到一条英文的「执行中断」且没有任何重试入口。
 * 单列出来的前提是**不整体放宽 403**——key 失效/额度禁用也是 403，放宽会把它们带进换模型重试，
 * 变成重复扣费探测。
 */
public final class LlmErrorClassifier {

    /**
     * 区域拒绝的稳定标记，进 SSE error 载荷（见 {@link #taggedErrorMessage}）。
     *
     * <p>契约：前端 useAgentStream.js 用 includes 检测这个子串，命中就把上游英文原文换成中文引导。
     * 用 includes 而不是 startsWith，是因为 AgentOrchestrator 还会在前面拼「Stream Error: 」。
     */
    public static final String REGION_BLOCKED_MARKER = "AI_REGION_BLOCKED";

    /** 配额/余额耗尽的稳定标记（契约同 {@link #REGION_BLOCKED_MARKER}：前端 includes 检测换中文引导）。 */
    public static final String QUOTA_EXHAUSTED_MARKER = "AI_QUOTA_EXHAUSTED";

    /** 上下文超窗的稳定标记（契约同上；只在强制压缩重试也失败的终态才会出现在 error 载荷里）。 */
    public static final String CONTEXT_OVERFLOW_MARKER = "AI_CONTEXT_OVERFLOW";

    /**
     * 编排器内部一致性错误的稳定标记（契约同上）。
     *
     * <p>与上面三个不同，它不是 LLM 调用的错误分类——{@link #classify} 永远不会产出它，
     * 由 AgentOrchestrator 在 onComplete 回调的 catch 里直接拼上。存在的理由是那条路径
     * 原来把裸 Java 异常文本发给前端（「Callback Error: text cannot be null or blank」），
     * 用户既看不懂也无从处理。
     */
    public static final String INTERNAL_ERROR_MARKER = "AI_INTERNAL_ERROR";

    private LlmErrorClassifier() {
    }

    public enum Kind {
        /** 429 / rate limit：退避更长，盖过按分钟计的限流窗口 */
        RATE_LIMITED(2, 30),
        /** 5xx / 超时 / 断连：短退避快速重放 */
        TRANSIENT(3, 8),
        /** 模型下线或不可用（404）：重试无意义，直接换模型 */
        MODEL_UNAVAILABLE(0, 0),
        /** 地域拒绝（403 + 地域语义）：同一网络重试永远不会好，只能换到区域无关的模型 */
        REGION_BLOCKED(0, 0),
        /**
         * 配额/余额耗尽（402，或 4xx + 配额语义）：终局。很多服务商余额耗尽也回 429，
         * 但它不是限流——退避重试、换模型都是白烧（同一账户下换哪个模型都没钱），
         * 混进 RATE_LIMITED 会让用户盯着「限流等待中」干等两轮才拿到真实原因（对标 dsh：QUOTA 先于 429 判定）。
         */
        QUOTA_EXHAUSTED(0, 0),
        /**
         * 上下文超窗（400 + 上下文语义）：服务商已经证实消息栈装不下了。不退避重试
         * （原样重发必然再撞一次），也不走故障转移（备选模型窗口未必更大，且换模型不解决根因）；
         * 编排器对它有专用恢复通道——强制压缩消息栈、确实缩小了才同 depth 重放一次（对标 dsh context-overflow）。
         */
        CONTEXT_OVERFLOW(0, 0),
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

        /**
         * 允许换模型继续。FATAL 不换（未知错误保守处理）；配额耗尽不换（同一账户换模型照样没钱，
         * 换了只是多一次必败的扣费探测）；上下文超窗不换（走专用的压缩重试通道，不走故障转移链）。
         */
        public boolean failoverable() {
            return this != FATAL && this != QUOTA_EXHAUSTED && this != CONTEXT_OVERFLOW;
        }

        /**
         * 故障转移候选必须收窄成「区域无关」的模型（AllowedModels.Region.GLOBAL）。
         *
         * <p>区域拒绝下换一个同样是国际档的模型只会再撞一次 403，白花一次请求；
         * 接线在 AgentOrchestrator 挑候选的地方。
         */
        public boolean requiresRegionAgnosticFailover() {
            return this == REGION_BLOCKED;
        }

        /**
         * 给用户看的一句话原因（按应用语言二选一，进 SSE 文本流）。
         * enum 不是 Spring bean，语言经 {@link com.checkba.service.LangText} 静态桥取。
         * 英文措辞是谓语形态：拼进故障转移文案「Model "X" &lt;reason&gt;; …」要成句。
         */
        public String userFacingReason() {
            return switch (this) {
                case RATE_LIMITED -> com.checkba.service.LangText.of("触发了限流", "hit a rate limit");
                case MODEL_UNAVAILABLE -> com.checkba.service.LangText.of("当前不可用（可能已下线）",
                        "is currently unavailable (possibly retired)");
                case REGION_BLOCKED -> com.checkba.service.LangText.of("在当前网络环境不可用（服务商按地域拒绝）",
                        "is unavailable on the current network (rejected by the provider for this region)");
                case QUOTA_EXHAUSTED -> com.checkba.service.LangText.of("账户额度不足",
                        "ran out of account credit");
                case CONTEXT_OVERFLOW -> com.checkba.service.LangText.of("上下文超出模型窗口",
                        "exceeded the model's context window");
                case TRANSIENT -> com.checkba.service.LangText.of("连续多次响应失败",
                        "failed to respond several times in a row");
                case FATAL -> com.checkba.service.LangText.of("调用失败", "failed to be called");
            };
        }
    }

    /**
     * 给 SSE error 载荷加机器可读标记：地域拒绝/配额耗尽/上下文超窗三类的上游原文都是英文，
     * 原样拼给用户等于没有信息，前端据标记换成中文引导（useAgentStream.js 用 includes 检测）。
     *
     * <p>其余分类原样返回，避免给既有文案引入噪声。
     */
    public static String taggedErrorMessage(Kind kind, String raw) {
        String body = raw == null ? "" : raw;
        return switch (kind) {
            case REGION_BLOCKED -> REGION_BLOCKED_MARKER + ": " + body;
            case QUOTA_EXHAUSTED -> QUOTA_EXHAUSTED_MARKER + ": " + body;
            case CONTEXT_OVERFLOW -> CONTEXT_OVERFLOW_MARKER + ": " + body;
            default -> body;
        };
    }

    /**
     * 按异常链分类。就近优先：链上先出现的判定生效，避免外层包装异常的通用文案盖过内层状态码。
     */
    public static Kind classify(Throwable err) {
        for (Throwable t = err; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            // 结构化状态码优先：OpenAI 兼容通道（含 OpenRouter）的 message 是原始响应体，
            // 里面不一定出现「status code: NNN」字样，靠文本匹配会把 429 当未知错误直接终局
            if (t instanceof dev.ai4j.openai4j.OpenAiHttpException http) {
                // 带上响应体：403 要靠体内的地域语义才能和 key 失效/额度禁用区分开
                return classifyStatusCode(http.code(), http.getMessage());
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
        return classifyStatusCode(code, null);
    }

    /**
     * 带响应体的状态码映射。403 只有在体内出现地域语义时才收窄成 REGION_BLOCKED，
     * 其余 403（key 失效、额度禁用）保持 FATAL——它们换模型重试等于重复扣费探测。
     *
     * <p>配额判定先于 429：余额耗尽很多服务商也回 429（OpenRouter 则是 402），
     * 顺序反了会把「没钱了」当限流退避重试两轮、再换模型白探一次，用户最后才看到真实原因。
     */
    public static Kind classifyStatusCode(int code, String body) {
        if (code == 402) return Kind.QUOTA_EXHAUSTED;
        if (code >= 400 && code < 500 && looksLikeQuotaExhaustion(body)) return Kind.QUOTA_EXHAUSTED;
        if (code == 429) return Kind.RATE_LIMITED;
        if (code == 404) return Kind.MODEL_UNAVAILABLE;
        if (code == 403 && looksLikeRegionRejection(body)) return Kind.REGION_BLOCKED;
        if (code == 400 && looksLikeContextOverflow(body)) return Kind.CONTEXT_OVERFLOW;
        if (code >= 500) return Kind.TRANSIENT;
        return Kind.FATAL;
    }

    /**
     * 地域拒绝的文本判据。**这是文本匹配**：上游一改文案就会退化成 FATAL，
     * 退化方向是安全的（不换模型、不重试、只是错误文案回到英文原文），所以宁可漏判不可误判。
     *
     * <p>措辞与大小写在各家之间都不一样：OpenRouter 是「This model is not available in your region」，
     * OpenAI 系用错误码 unsupported_country_region_territory，所以多个子串择一命中。
     * 最后那条裸 "region" 只在状态码已经确定是 403 的前提下生效——最坏情况是多花一次换模型的请求
     * （候选还受 triedModels 去重约束），不会变成循环探测。
     */
    private static boolean looksLikeRegionRejection(String body) {
        if (body == null) return false;
        String b = body.toLowerCase(Locale.ROOT);
        return b.contains("not available in your region")
                || b.contains("not available in your country")
                || b.contains("unsupported_country_region_territory")
                || b.contains("country, region, or territory")
                || b.contains("unsupported region")
                || b.contains("region is not supported")
                || b.contains("region");
    }

    /**
     * 配额/余额耗尽的文本判据（对标 dsh isQuotaExceededError，措辞集合按各家实况收敛）。
     * 文本匹配的退化方向：上游改文案会退化成 RATE_LIMITED（429 时）或 FATAL，
     * 两者都不会造成额外扣费循环，只是文案不够准。
     */
    private static boolean looksLikeQuotaExhaustion(String body) {
        if (body == null) return false;
        String b = body.toLowerCase(Locale.ROOT);
        return b.contains("insufficient credits") || b.contains("insufficient quota")
                || b.contains("insufficient balance") || b.contains("insufficient_quota")
                || b.contains("quota exceeded") || b.contains("quota exhausted")
                || b.contains("exceeded your current quota")
                || b.contains("out of credits") || b.contains("credits exhausted")
                || b.contains("balance is insufficient") || b.contains("余额不足");
    }

    /**
     * 上下文超窗的文本判据（400 前提下才调用）。措辞按 OpenAI 系 / Anthropic 系 / OpenRouter 收敛，
     * 刻意不收裸 "tokens"——429 的 tokens-per-minute 限流文案会误伤（虽然状态码已挡住，仍防御性收紧）。
     */
    private static boolean looksLikeContextOverflow(String body) {
        if (body == null) return false;
        String b = body.toLowerCase(Locale.ROOT);
        return b.contains("context_length_exceeded")
                || b.contains("maximum context length")
                || b.contains("context window")
                || b.contains("prompt is too long")
                || b.contains("input is too long")
                || b.contains("exceed context limit")
                || b.contains("too many tokens");
    }

    private static Kind classifyMessage(String msg) {
        String m = msg.toLowerCase(Locale.ROOT);
        // 配额耗尽先于限流：余额耗尽很多服务商也回 429，但它是终局而非瞬时
        if (m.contains("status code: 402") || looksLikeQuotaExhaustion(m)) {
            return Kind.QUOTA_EXHAUSTED;
        }
        if (m.contains("status code: 429") || m.contains("rate limit") || m.contains("too many requests")) {
            return Kind.RATE_LIMITED;
        }
        // 模型下线：OpenRouter 用 404 + "No endpoints found" 表达，与「路径写错」无法区分，
        // 一律当作换模型信号——换掉之后即便原因是别的，也比原地重试有机会跑通
        if (m.contains("status code: 404") || m.contains("no endpoints found")
                || m.contains("model not found") || m.contains("is not a valid model")) {
            return Kind.MODEL_UNAVAILABLE;
        }
        // 地域拒绝先于通用 403 判定：顺序反了就永远命中不到（403 全被 FATAL 吃掉）
        if (m.contains("status code: 403") && looksLikeRegionRejection(m)) {
            return Kind.REGION_BLOCKED;
        }
        // 上下文超窗先于通用 400 判定（同上：顺序反了永远命中不到）
        if (m.contains("status code: 400") && looksLikeContextOverflow(m)) {
            return Kind.CONTEXT_OVERFLOW;
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
