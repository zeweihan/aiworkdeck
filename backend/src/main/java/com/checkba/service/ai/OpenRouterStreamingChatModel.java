package com.checkba.service.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public OpenRouterStreamingChatModel(String apiKey, String baseUrl, String modelName, Duration timeout) {
        this.apiKey = apiKey;
        this.modelName = modelName;
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
        ChatCompletionRequest.Builder rb = ChatCompletionRequest.builder()
                .stream(true)
                .streamOptions(StreamOptions.builder().includeUsage(true).build())
                .model(modelName)
                .messages(InternalOpenAiHelper.toOpenAiMessages(messages))
                .temperature(DEFAULT_TEMPERATURE);
        if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
            rb.tools(InternalOpenAiHelper.toTools(toolSpecifications, false));
        }
        String body = Json.toJson(rb.build());

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
