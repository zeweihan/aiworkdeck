package com.checkba.service.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ai4j.openai4j.Json;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.ai4j.openai4j.chat.ChatCompletionChoice;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.ai4j.openai4j.chat.Delta;
import dev.ai4j.openai4j.shared.StreamOptions;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.InternalOpenAiHelper;
import dev.langchain4j.model.openai.OpenAiStreamingResponseBuilder;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容流式通道（OpenRouter BYOK / 平台通道，两者都打 OpenRouter）的自有实现。
 *
 * <p><b>为什么不再用 langchain4j 0.36 的 {@code OpenAiStreamingChatModel}</b>（dev-board#364）：
 * <ol>
 *   <li>思考型模型的 {@code delta.reasoning} 在 openai4j 0.23 的 {@code Delta} 里没有对应字段，
 *       反序列化那一刻就丢了，链路上游没有任何钩子能拿到它。要给用户实时看到思考过程、
 *       要让看门狗知道模型还活着，只能自己读这条流。</li>
 *   <li>它的 okhttp-sse 解析器会静默吞掉 SSE 注释行，而 OpenRouter 恰恰用
 *       {@code : OPENROUTER PROCESSING} 注释在模型静默期间保活——这是「上游还在跑」的唯一信号。</li>
 *   <li>它的传输层错误处理有一条 NPE 路径（{@code logResponses=true} 时
 *       {@code ResponseLoggingInterceptor.log(null response)}），历史上靠关掉日志开关绕过；
 *       自己持有 HTTP 层之后这条地雷自然消失。</li>
 * </ol>
 *
 * <p><b>刻意复用、不重写的部分</b>：消息与工具定义到 OpenAI 报文的转换
 * （{@link InternalOpenAiHelper#toOpenAiMessages} / {@link InternalOpenAiHelper#toTools}，
 * 含 ImageContent 多模态编组）、请求体序列化（openai4j 的 {@link Json}，snake_case + NON_NULL）、
 * 流式片段到 {@code Response<AiMessage>} 的组装（{@link OpenAiStreamingResponseBuilder}，
 * 含 tool_calls 按 index 拼接、usage、finish_reason）。这些是协议细节最多、最容易写错的地方，
 * 本类只负责 HTTP + SSE 行协议 + 把 reasoning/注释行多转发两条通道。
 *
 * <p>与 langchain4j 原实现保持一致的请求参数：{@code stream=true}、
 * {@code stream_options.include_usage=true}、{@code temperature=0.7}（0.36 的默认值）。
 * 错误语义也对齐：非 2xx 抛 {@link OpenAiHttpException}（{@code LlmErrorClassifier} 按状态码分类），
 * 连接失败等 IOException 原样回调 {@code onError}。
 *
 * <p>logRequests/logResponses 这类请求体物化的坑在本类不存在：请求体只序列化一次直接发出，
 * 响应按行消费不落整段字符串。
 */
public final class OpenRouterStreamingChatModel implements StreamingChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterStreamingChatModel.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    /** langchain4j 0.36 OpenAiStreamingChatModel 的默认温度，保持行为不变。 */
    private static final double DEFAULT_TEMPERATURE = 0.7;
    /** 只用于读 reasoning 与 error 两个 openai4j 不认识的字段；DTO 本身仍交给 openai4j 的注解解析。 */
    private static final ObjectMapper LENIENT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final OkHttpClient client;
    private final String endpoint;
    private final String apiKey;
    private final String modelName;
    /** 本模型是否需要显式提示缓存断点，见 {@link #requiresExplicitPromptCache}。 */
    private final boolean explicitPromptCache;

    public OpenRouterStreamingChatModel(String apiKey, String baseUrl, String modelName, Duration timeout) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.explicitPromptCache = requiresExplicitPromptCache(modelName);
        String base = baseUrl == null ? "" : baseUrl;
        this.endpoint = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/chat/completions";
        Duration t = timeout == null ? Duration.ofSeconds(60) : timeout;
        // 四个超时同值，与 openai4j 0.23 的 OpenAiClient 口径一致：callTimeout 是整通墙钟上限，
        // readTimeout 靠 OpenRouter 的保活注释刷新，不会在模型静默思考时误触发
        this.client = new OkHttpClient.Builder()
                .callTimeout(t)
                .connectTimeout(t)
                .readTimeout(t)
                .writeTimeout(t)
                .build();
    }

    public String modelName() {
        return modelName;
    }

    @Override
    public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        generate(messages, (List<ToolSpecification>) null, handler);
    }

    @Override
    public void generate(List<ChatMessage> messages, ToolSpecification toolSpecification,
                         StreamingResponseHandler<AiMessage> handler) {
        // 单工具强制调用形态本仓不用；按 langchain4j 的语义退化成「只提供这一个工具」
        generate(messages, toolSpecification == null ? null : List.of(toolSpecification), handler);
    }

    @Override
    public void generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications,
                         StreamingResponseHandler<AiMessage> handler) {
        // 不做显式缓存的通道要先把分界标记摘掉（在序列化之前，保住字节级一致）
        List<ChatMessage> outbound = explicitPromptCache ? messages : stripVolatileSeparator(messages);
        ChatCompletionRequest.Builder rb = ChatCompletionRequest.builder()
                .stream(true)
                .streamOptions(StreamOptions.builder().includeUsage(true).build())
                .model(modelName)
                .messages(InternalOpenAiHelper.toOpenAiMessages(outbound))
                .temperature(DEFAULT_TEMPERATURE);
        if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
            rb.tools(InternalOpenAiHelper.toTools(toolSpecifications, false));
        }
        // 非 Anthropic 一律走这一行的原样结果，请求体逐字节与改造前一致
        String body = Json.toJson(rb.build());
        if (explicitPromptCache) {
            body = markSystemForCaching(body);
        }

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .header("Accept", "text/event-stream")
                .header("User-Agent", "AI-WorkDeck")
                .post(RequestBody.create(body, JSON))
                .build();

        StreamSession session = new StreamSession(handler);
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                session.fail(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    ResponseBody rb0 = r.body();
                    if (!r.isSuccessful()) {
                        String errBody = rb0 == null ? "" : rb0.string();
                        session.fail(new OpenAiHttpException(r.code(), errBody));
                        return;
                    }
                    if (rb0 == null) {
                        session.fail(new IOException("empty response body from " + endpoint));
                        return;
                    }
                    session.consume(rb0.source());
                } catch (Throwable t) {
                    session.fail(t);
                }
            }
        });
    }

    // ==================== 提示缓存（Anthropic 显式断点） ====================

    /**
     * 该模型是否需要我们显式打提示缓存断点。
     *
     * <p>OpenRouter 的分工（2026-09-03 核对 https://openrouter.ai/docs/features/prompt-caching）：
     * OpenAI / Grok / Moonshot / Groq / DeepSeek / Z.AI / Gemini 2.5 <b>自动</b>做前缀缓存，
     * 请求体不需要任何标记；文档原文把 <b>Anthropic 与 Alibaba（Qwen）</b>并列为
     * 「require you to enable it on a per-message basis」——不打标记就一个 token 都不缓存。
     * 这两家都接。
     *
     * <p><b>Qwen 不是可选项</b>：{@code qwen/qwen3.7-flash} 是 {@code ai.auxModel} 与
     * {@code ai.subagentModel} 的默认值，子 Agent 拿它跑完整工具循环（同一段 system 重发几十遍），
     * 而且它是 {@link AllowedModels.Region#GLOBAL}——境内用户唯一够得着的那一档。
     * 白名单里两条 Claude 都是 INTERNATIONAL，境内根本连不上（403 region）。
     *
     * <p><b>判据是双份的</b>：{@link AllowedModels} 的 vendor 是语义锚点，但白名单只收 14 条精选，
     * 而 {@code ai.subagentModel} / {@code ai.auxModel} / 故障转移链都可能被配上白名单外的
     * {@code anthropic/*} / {@code qwen/*} id（带 {@code :beta} 后缀的变体、新发布还没进枚举的型号）。
     * 只认枚举会让那些请求静默按全价跑——多打一个无害的标记，远好过静默不缓存。
     */
    static boolean requiresExplicitPromptCache(String modelId) {
        if (modelId == null || modelId.isBlank()) return false;
        String id = modelId.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.startsWith("anthropic/") || id.startsWith("qwen/") || id.startsWith("alibaba/")) return true;
        AllowedModels m = AllowedModels.fromId(modelId);
        return m != null && (m.getVendor() == AllowedModels.Vendor.ANTHROPIC
                || m.getVendor() == AllowedModels.Vendor.ALIBABA);
    }

    /**
     * 把请求体里<b>第一条</b> system 消息从字符串改写成带
     * {@code "cache_control": {"type": "ephemeral"}} 的 text block。
     *
     * <p><b>按 {@link ContextAssemblerService#SYSTEM_VOLATILE_SEPARATOR} 拆两块</b>：
     * 标记之前（指令主体 + skill + 附件与活跃文档正文）是稳定段，打断点；
     * 标记之后（当前时间/阶段/任务 id/记忆）每轮都变，<b>不打断点</b>——把它包进缓存前缀，
     * 缓存就永远不命中，等于白做。标记本身在这里被吃掉，模型看不到。
     * 没有标记时退化成「整段 system 打一个断点」（旧行为，也是外部调用方的兜底）。
     *
     * <p>为什么要改写而不是构造：openai4j 0.23 的 {@code SystemMessage.content} 是
     * {@code String}（字节码实证），{@code Content} 也只有 type/text/imageUrl 三个字段，
     * 都塞不进 {@code cache_control}。在序列化<b>之后</b>补一刀是改动面最小的做法。
     *
     * <p><b>只打一个断点</b>：Anthropic 最多 4 个显式断点，本次预算全给 system 的稳定段——
     * 它每轮重发、体量最大（Office 插件会话把最长 20 万字符的正文内联进来）。
     * 历史消息的滚动断点是另一件事，不在本次范围。
     *
     * <p><b>短于最小长度不会报错，只是不缓存</b>：Anthropic 的最小可缓存前缀是
     * Sonnet 4.x / Opus 4-4.1 为 1024 token、Haiku 3.5 为 2048、Opus 4.5+ 与 Haiku 4.5 为 4096。
     * 短 prompt 上这个标记是纯粹的空操作，不必在这里判长度（判了反而要维护一张会腐烂的阈值表）。
     *
     * <p>任何解析/改写失败都原样返回：打不上标记只是不省钱，绝不能让本轮对话失败。
     */
    static String markSystemForCaching(String body) {
        try {
            JsonNode root = LENIENT.readTree(body);
            JsonNode messages = root.path("messages");
            if (!messages.isArray()) return body;
            for (JsonNode m : messages) {
                if (!(m instanceof ObjectNode msg)) continue;
                if (!"system".equals(msg.path("role").asText())) continue;
                JsonNode content = msg.get("content");
                // 已经是数组说明上游形态变了（或本方法被调了两次），不重复改写
                if (content == null || !content.isTextual() || content.asText().isEmpty()) return body;
                String text = content.asText();
                String sep = ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR;
                // 只按第一个标记切；正文里万一混进第二个标记，多出来的那段留在易变块里更安全
                int at = text.indexOf(sep);
                String stable = at < 0 ? text : text.substring(0, at);
                String tail = at < 0 ? null : text.substring(at + sep.length());

                ObjectNode cached = LENIENT.createObjectNode();
                cached.put("type", "text");
                cached.put("text", stable);
                cached.set("cache_control", LENIENT.createObjectNode().put("type", "ephemeral"));
                var blocks = LENIENT.createArrayNode().add(cached);
                if (tail != null && !tail.isEmpty()) {
                    ObjectNode volatileBlock = LENIENT.createObjectNode();
                    volatileBlock.put("type", "text");
                    volatileBlock.put("text", tail);
                    blocks.add(volatileBlock);
                }
                msg.set("content", blocks);
                return LENIENT.writeValueAsString(root);
            }
            return body;
        } catch (Exception e) {
            log.warn("Failed to mark system prompt for caching, sending request unmarked", e);
            return body;
        }
    }

    /**
     * 不做显式缓存的通道：把分界标记从 system 里摘掉，其余一切照旧。
     *
     * <p><b>必须在序列化之前摘</b>。序列化之后再用 Jackson 改写会顺带改掉排版
     * （openai4j 的 {@code Json} 开着 INDENT_OUTPUT），那样这些通道的请求体就不再与改造前
     * 逐字节一致了——而「不影响其它通道」正是这次改造唯一的硬护栏
     * （{@code OpenRouterPromptCacheTest.nonAnthropicStripsSeparatorAndStaysByteIdentical}）。
     *
     * <p>没有任何 system 带标记时返回原列表本身，不做多余的拷贝。
     */
    static List<ChatMessage> stripVolatileSeparator(List<ChatMessage> messages) {
        if (messages == null) return null;
        String sep = ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR;
        List<ChatMessage> out = null;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (!(m instanceof dev.langchain4j.data.message.SystemMessage sm)) continue;
            String text = sm.text();
            if (text == null || !text.contains(sep)) continue;
            if (out == null) out = new java.util.ArrayList<>(messages);
            out.set(i, dev.langchain4j.data.message.SystemMessage.from(text.replace(sep, "")));
        }
        return out == null ? messages : out;
    }

    /**
     * 从 usage 节点读缓存 token 数，没有任何缓存字段时返回 null。
     *
     * <p>两套字段名都认：OpenRouter 统一成 {@code prompt_tokens_details.cached_tokens} /
     * {@code cache_write_tokens}，而部分供应商直通时会露出 Anthropic 原生的
     * {@code cache_read_input_tokens} / {@code cache_creation_input_tokens}。
     * 这些字段 openai4j 0.23 的 {@code Usage} 一个都没有（只有 completion_tokens_details），
     * 所以只能在原始 JSON 上读。
     */
    record CacheUsage(int promptTokens, int cachedTokens, int cacheWriteTokens) {
    }

    static CacheUsage cacheUsageOf(JsonNode usage) {
        if (usage == null || !usage.isObject()) return null;
        JsonNode details = usage.path("prompt_tokens_details");
        JsonNode read = details.get("cached_tokens");
        if (read == null) read = usage.get("cache_read_input_tokens");
        JsonNode write = details.get("cache_write_tokens");
        if (write == null) write = usage.get("cache_creation_input_tokens");
        if (read == null && write == null) return null;
        return new CacheUsage(
                usage.path("prompt_tokens").asInt(0),
                read == null ? 0 : read.asInt(0),
                write == null ? 0 : write.asInt(0));
    }

    /** 一次调用的流状态：SSE 行协议 + 终态幂等。 */
    private static final class StreamSession {
        private final StreamingResponseHandler<AiMessage> handler;
        private final ReasoningStreamingHandler reasoningHandler;
        private final OpenAiStreamingResponseBuilder builder = new OpenAiStreamingResponseBuilder();
        private final AtomicBoolean settled = new AtomicBoolean(false);

        StreamSession(StreamingResponseHandler<AiMessage> handler) {
            this.handler = handler;
            this.reasoningHandler = handler instanceof ReasoningStreamingHandler rh ? rh : null;
        }

        void consume(BufferedSource source) throws IOException {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (settled.get()) return;
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        boolean done = dispatch(data.toString());
                        data.setLength(0);
                        if (done) return;
                    }
                    continue;
                }
                if (line.charAt(0) == ':') {
                    // SSE 注释行：OpenRouter 的 ": OPENROUTER PROCESSING" 保活
                    if (reasoningHandler != null) reasoningHandler.onKeepAlive();
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
                // event:/id:/retry: 行在 chat completions 流里不出现，忽略
            }
            if (data.length() > 0 && dispatch(data.toString())) return;
            // 没等到 [DONE] 就 EOF：与 okhttp-sse 的 onClosed 行为一致，按已收到的内容收尾
            complete();
        }

        /** 返回 true 表示流已终结（[DONE] 或错误），调用方停止读取。 */
        private boolean dispatch(String payload) {
            if ("[DONE]".equals(payload)) {
                complete();
                return true;
            }
            JsonNode root;
            try {
                root = LENIENT.readTree(payload);
            } catch (IOException e) {
                log.warn("Unparseable SSE chunk ignored: {}", abbreviate(payload));
                return false;
            }
            // OpenRouter 会在 HTTP 200 之后用 data 事件送上游错误（{"error":{"code":429,...}}），
            // 不认这一形态的话本轮会按空回复静默收尾
            JsonNode error = root.get("error");
            if (error != null && error.isObject() && !root.has("choices")) {
                int code = error.path("code").isInt() ? error.path("code").asInt() : 500;
                fail(new OpenAiHttpException(code, payload));
                return true;
            }
            ChatCompletionResponse chunk;
            try {
                chunk = LENIENT.readValue(payload, ChatCompletionResponse.class);
            } catch (IOException e) {
                log.warn("SSE chunk does not fit ChatCompletionResponse, ignored: {}", abbreviate(payload));
                return false;
            }
            builder.append(chunk);
            List<ChatCompletionChoice> choices = chunk.choices();
            if (choices != null && !choices.isEmpty()) {
                Delta delta = choices.get(0).delta();
                if (delta != null && delta.content() != null) {
                    handler.onNext(delta.content());
                }
            }
            if (reasoningHandler != null) {
                String reasoning = reasoningDeltaOf(root);
                if (reasoning != null && !reasoning.isEmpty()) {
                    reasoningHandler.onReasoning(reasoning);
                }
                // usage 只在最后一个 chunk（choices 为空）上出现，所以这里不会重复回调
                CacheUsage cache = cacheUsageOf(root.get("usage"));
                if (cache != null) {
                    reasoningHandler.onCacheUsage(
                            cache.promptTokens(), cache.cachedTokens(), cache.cacheWriteTokens());
                }
            }
            return false;
        }

        /** OpenRouter 统一成 {@code delta.reasoning}；各家原生兼容端点多用 {@code reasoning_content}。 */
        static String reasoningDeltaOf(JsonNode root) {
            JsonNode delta = root.path("choices").path(0).path("delta");
            if (delta.isMissingNode()) return null;
            JsonNode r = delta.get("reasoning");
            if (r == null || r.isNull()) r = delta.get("reasoning_content");
            return r == null || !r.isTextual() ? null : r.asText();
        }

        private void complete() {
            if (!settled.compareAndSet(false, true)) return;
            handler.onComplete(builder.build());
        }

        void fail(Throwable t) {
            if (!settled.compareAndSet(false, true)) return;
            handler.onError(t);
        }

        private static String abbreviate(String s) {
            return s.length() > 200 ? s.substring(0, 200) + "..." : s;
        }
    }
}
