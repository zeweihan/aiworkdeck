package com.checkba.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.ai4j.openai4j.Json;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.shared.StreamOptions;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.InternalOpenAiHelper;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anthropic 提示缓存（OpenRouter {@code cache_control: ephemeral}）的请求/响应契约。
 *
 * <p>编排器每轮都是一次无状态请求：system prompt（Office 插件会话里内联着最长 20 万字符的正文）
 * + 全部历史，整篇校对几十轮就把同一段 system 重发几十遍。Anthropic 系模型不做自动前缀缓存，
 * 必须在 content block 上显式打断点，否则每轮都按全价重新读一遍。
 *
 * <p>两条断言方向同等重要：
 * <ol>
 *   <li>Anthropic 模型的 system 必须变成带 {@code cache_control} 的 content block；</li>
 *   <li><b>其它模型的请求体逐字节不变</b>——这是「不影响其它通道」的护栏，
 *       用 {@code Json.toJson} 重建同一个请求做快照对比，改动波及全体模型时它会红。</li>
 * </ol>
 */
class OpenRouterPromptCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    /** 原始请求体，一个空白字符都不去掉——快照对比要的就是字节级。 */
    private volatile String rawRequestBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    private void serveStream(String sseBody) {
        server.createContext("/api/v1/chat/completions", ex -> {
            rawRequestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = sseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private static final String TRIVIAL_STREAM =
            "data: {\"id\":\"g\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";

    /** 收集终态 + 缓存用量的 handler。 */
    private static final class Collector implements ReasoningStreamingHandler {
        final AtomicReference<Response<AiMessage>> done = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final CountDownLatch settled = new CountDownLatch(1);
        volatile int promptTokens = -1;
        volatile int cachedTokens = -1;
        volatile int cacheWriteTokens = -1;
        volatile int cacheCallbacks = 0;

        @Override public void onNext(String token) { }
        @Override public void onCacheUsage(int prompt, int cached, int written) {
            promptTokens = prompt;
            cachedTokens = cached;
            cacheWriteTokens = written;
            cacheCallbacks++;
        }
        @Override public void onComplete(Response<AiMessage> r) { done.set(r); settled.countDown(); }
        @Override public void onError(Throwable t) { error.set(t); settled.countDown(); }

        void await() throws InterruptedException {
            assertTrue(settled.await(10, TimeUnit.SECONDS), "流必须在 10 秒内到达终态");
        }
    }

    private static final List<ChatMessage> MESSAGES = List.of(
            SystemMessage.from("你是一名律师助理。\n<file>合同正文……</file>"),
            UserMessage.from("这一条有什么风险？"));

    private Collector run(String modelId) throws Exception {
        serveStream(TRIVIAL_STREAM);
        OpenRouterStreamingChatModel model =
                new OpenRouterStreamingChatModel("sk-test", baseUrl(), modelId, Duration.ofSeconds(5));
        Collector c = new Collector();
        model.generate(MESSAGES, c);
        c.await();
        assertNull(c.error.get(), () -> "不该出错：" + c.error.get());
        return c;
    }

    @Test
    @DisplayName("Anthropic 模型：system 变成带 cache_control ephemeral 的单个 text block")
    void anthropicSystemCarriesCacheControl() throws Exception {
        run("anthropic/claude-sonnet-5");

        JsonNode root = MAPPER.readTree(rawRequestBody);
        JsonNode sys = root.path("messages").path(0);
        assertEquals("system", sys.path("role").asText(), rawRequestBody);

        JsonNode content = sys.path("content");
        assertTrue(content.isArray(), "system 必须是 content block 数组，字符串形态挂不上断点：" + rawRequestBody);
        assertEquals(1, content.size(), "本次只打一个断点");
        JsonNode block = content.get(0);
        assertEquals("text", block.path("type").asText());
        assertEquals("你是一名律师助理。\n<file>合同正文……</file>", block.path("text").asText(),
                "正文必须一字不改地搬进 block——改了就是另一段前缀，缓存永远不会命中");
        assertEquals("ephemeral", block.path("cache_control").path("type").asText(), rawRequestBody);

        // 用户消息不许被顺手改形态：Anthropic 只有 4 个断点，本次预算全给 system
        int firstMark = rawRequestBody.indexOf("cache_control");
        assertEquals(firstMark, rawRequestBody.lastIndexOf("cache_control"),
                "整个请求体里 cache_control 只许出现一次：" + rawRequestBody);
        JsonNode user = root.path("messages").path(1);
        assertEquals("user", user.path("role").asText());
    }

    @Test
    @DisplayName("白名单外的 anthropic/ 前缀模型也打断点——判据不能只认枚举")
    void unlistedAnthropicIdAlsoMarked() throws Exception {
        run("anthropic/claude-opus-4.6");
        JsonNode content = MAPPER.readTree(rawRequestBody).path("messages").path(0).path("content");
        assertTrue(content.isArray(), rawRequestBody);
        assertEquals("ephemeral", content.get(0).path("cache_control").path("type").asText());
    }

    @Test
    @DisplayName("非 Anthropic 模型：请求体与改造前逐字节一致（快照护栏）")
    void nonAnthropicRequestBodyIsByteIdentical() throws Exception {
        run("deepseek/deepseek-v4-flash");

        // 改造前的口径：Json.toJson(ChatCompletionRequest)，不经任何后处理
        String expected = Json.toJson(ChatCompletionRequest.builder()
                .stream(true)
                .streamOptions(StreamOptions.builder().includeUsage(true).build())
                .model("deepseek/deepseek-v4-flash")
                .messages(InternalOpenAiHelper.toOpenAiMessages(MESSAGES))
                .temperature(0.7)
                .build());

        assertEquals(expected, rawRequestBody,
                "非 Anthropic 通道的请求体一个字节都不许变");
        assertFalse(rawRequestBody.contains("cache_control"), rawRequestBody);
    }

    @Test
    @DisplayName("Ollama 之类的裸模型名不触发断点，也不炸")
    void bareModelNameIsNotMarked() throws Exception {
        run("llama3:latest");
        assertFalse(rawRequestBody.contains("cache_control"), rawRequestBody);
    }

    @Test
    @DisplayName("Qwen 也要显式断点——OpenRouter 文档把 Alibaba 与 Anthropic 并列为必须标记")
    void qwenAlsoCarriesCacheControl() throws Exception {
        // ai.auxModel / ai.subagentModel 的默认值，子 Agent 会拿它跑循环，且它是 Region.GLOBAL
        run("qwen/qwen3.7-flash");
        JsonNode content = MAPPER.readTree(rawRequestBody).path("messages").path(0).path("content");
        assertTrue(content.isArray(), rawRequestBody);
        assertEquals("ephemeral", content.get(0).path("cache_control").path("type").asText());
    }

    // ==================== 易变段分隔标记（跨轮次命中） ====================

    private static final String SEP = ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR;
    private static final String STABLE = "你是一名律师助理。\n<file>合同正文……</file>";
    private static final String VOLATILE = "# Current Context\n- **Current System Time**: 2026年09月03日 21:30:00 (星期四)";

    private static final List<ChatMessage> SPLIT_MESSAGES = List.of(
            SystemMessage.from(STABLE + SEP + VOLATILE),
            UserMessage.from("这一条有什么风险？"));

    private Collector runSplit(String modelId) throws Exception {
        serveStream(TRIVIAL_STREAM);
        OpenRouterStreamingChatModel model =
                new OpenRouterStreamingChatModel("sk-test", baseUrl(), modelId, Duration.ofSeconds(5));
        Collector c = new Collector();
        model.generate(SPLIT_MESSAGES, c);
        c.await();
        assertNull(c.error.get(), () -> "不该出错：" + c.error.get());
        return c;
    }

    @Test
    @DisplayName("有分隔标记时拆成两个 block：稳定块打断点、易变块不打，标记本身不留在报文里")
    void separatorSplitsSystemIntoCachedPrefixAndVolatileTail() throws Exception {
        runSplit("anthropic/claude-sonnet-5");

        JsonNode content = MAPPER.readTree(rawRequestBody).path("messages").path(0).path("content");
        assertTrue(content.isArray(), rawRequestBody);
        assertEquals(2, content.size(), "稳定块 + 易变块：" + rawRequestBody);

        JsonNode stable = content.get(0);
        assertEquals("text", stable.path("type").asText());
        assertEquals(STABLE, stable.path("text").asText(), "稳定块正文必须一字不差，否则前缀变了永远不命中");
        assertEquals("ephemeral", stable.path("cache_control").path("type").asText());

        JsonNode tail = content.get(1);
        assertEquals("text", tail.path("type").asText());
        assertEquals(VOLATILE, tail.path("text").asText());
        assertTrue(tail.path("text").asText().contains("Current System Time"),
                "每轮变化的时间戳必须落在易变块——留在稳定块里等于缓存永不命中");
        assertTrue(tail.path("cache_control").isMissingNode(), "易变块绝不能打断点：" + rawRequestBody);

        // 断点仍然只有一个，标记本身不该出现在发出去的报文里
        int first = rawRequestBody.indexOf("cache_control");
        assertEquals(first, rawRequestBody.lastIndexOf("cache_control"), rawRequestBody);
        assertFalse(rawRequestBody.contains("awd:volatile"), "分隔标记是内部约定，不发给模型：" + rawRequestBody);
    }

    @Test
    @DisplayName("非 Anthropic 通道：标记被摘掉，请求体仍与「system 本就是一整串」逐字节一致")
    void nonAnthropicStripsSeparatorAndStaysByteIdentical() throws Exception {
        runSplit("deepseek/deepseek-v4-flash");

        // 期望 = 把标记摘掉后的等价请求，走改造前那条 Json.toJson 口径
        String expected = Json.toJson(ChatCompletionRequest.builder()
                .stream(true)
                .streamOptions(StreamOptions.builder().includeUsage(true).build())
                .model("deepseek/deepseek-v4-flash")
                .messages(InternalOpenAiHelper.toOpenAiMessages(List.of(
                        SystemMessage.from(STABLE + VOLATILE),
                        UserMessage.from("这一条有什么风险？"))))
                .temperature(0.7)
                .build());

        assertEquals(expected, rawRequestBody, "非显式缓存通道的请求体一个字节都不许变");
        assertFalse(rawRequestBody.contains("awd:volatile"), rawRequestBody);
        assertFalse(rawRequestBody.contains("cache_control"), rawRequestBody);
    }

    @Test
    @DisplayName("usage 里的缓存命中/写入 token 数被读出来（openai4j 的 Usage 丢掉了这两个字段）")
    void cachedTokensAreParsedFromUsage() throws Exception {
        serveStream("data: {\"id\":\"g\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"id\":\"g\",\"choices\":[],\"usage\":{\"prompt_tokens\":10339,"
                + "\"completion_tokens\":60,\"total_tokens\":10399,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":10318,\"cache_write_tokens\":21}}}\n\n"
                + "data: [DONE]\n\n");
        OpenRouterStreamingChatModel model =
                new OpenRouterStreamingChatModel("sk-test", baseUrl(), "anthropic/claude-sonnet-5", Duration.ofSeconds(5));
        Collector c = new Collector();
        model.generate(MESSAGES, c);
        c.await();

        assertNull(c.error.get(), () -> "不该出错：" + c.error.get());
        assertEquals(1, c.cacheCallbacks, "带 usage 的 chunk 只有一个，回调也只该有一次");
        assertEquals(10339, c.promptTokens);
        assertEquals(10318, c.cachedTokens);
        assertEquals(21, c.cacheWriteTokens);
        // 既有的 TokenUsage 通道不受影响
        Response<AiMessage> r = c.done.get();
        assertNotNull(r.tokenUsage());
        assertEquals(10339, r.tokenUsage().inputTokenCount());
    }

    @Test
    @DisplayName("Anthropic 原生字段名（cache_read_input_tokens）也认")
    void anthropicNativeUsageFieldNamesAreAccepted() throws Exception {
        serveStream("data: {\"id\":\"g\",\"choices\":[],\"usage\":{\"prompt_tokens\":2000,"
                + "\"completion_tokens\":10,\"total_tokens\":2010,"
                + "\"cache_read_input_tokens\":1800,\"cache_creation_input_tokens\":200}}\n\n"
                + "data: [DONE]\n\n");
        OpenRouterStreamingChatModel model =
                new OpenRouterStreamingChatModel("sk-test", baseUrl(), "anthropic/claude-sonnet-5", Duration.ofSeconds(5));
        Collector c = new Collector();
        model.generate(MESSAGES, c);
        c.await();

        assertEquals(1800, c.cachedTokens);
        assertEquals(200, c.cacheWriteTokens);
    }

    @Test
    @DisplayName("没有缓存字段时不回调——免得日志里全是 cached=0 的噪音")
    void noCacheFieldsMeansNoCallback() throws Exception {
        serveStream("data: {\"id\":\"g\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,"
                + "\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
                + "data: [DONE]\n\n");
        OpenRouterStreamingChatModel model =
                new OpenRouterStreamingChatModel("sk-test", baseUrl(), "deepseek/deepseek-v4-flash", Duration.ofSeconds(5));
        Collector c = new Collector();
        model.generate(MESSAGES, c);
        c.await();
        assertEquals(0, c.cacheCallbacks);
    }
}
