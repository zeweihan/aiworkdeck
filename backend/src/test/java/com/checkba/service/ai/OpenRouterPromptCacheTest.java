// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
 *   <li><b>其它模型的请求体在语义上不变</b>——这是「不影响其它通道」的护栏，
 *       用 {@code Json.toJson} 重建同一个请求，解析成树逐字段对比，改动波及全体模型时它会红。</li>
 * </ol>
 *
 * <p><b>为什么比的是树而不是字节</b>（dev-board#750）：openai4j 的 {@code Json} 开着
 * {@code INDENT_OUTPUT}，于是每个请求体都带着缩进，而工具 schema 嵌套很深——200 个工具的
 * 请求体里有 23.5% 是纯空白，每一轮都要重传一遍。现在发出去之前统一压成紧凑 JSON
 * （{@link OpenRouterStreamingChatModel#compact}），所以字节级快照已经不成立。
 * 护栏真正要守的是「字段一个不多一个不少、值一个没变」，这一点树对比守得住；
 * 「有没有被重新排版」另由 {@link #requestBodyCarriesNoIndentation} 单独钉住。</p>
 */
class OpenRouterPromptCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    /** 原始请求体，原样保留不做任何整形——「有没有缩进」这条断言要的就是原始字节。 */
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
    @DisplayName("非 Anthropic 模型：请求体与改造前逐字段一致（快照护栏）")
    void nonAnthropicRequestBodyIsSemanticallyIdentical() throws Exception {
        run("deepseek/deepseek-v4-flash");

        // 参照口径：Json.toJson(ChatCompletionRequest)，不经任何后处理
        String expected = Json.toJson(ChatCompletionRequest.builder()
                .stream(true)
                .streamOptions(StreamOptions.builder().includeUsage(true).build())
                .model("deepseek/deepseek-v4-flash")
                .messages(InternalOpenAiHelper.toOpenAiMessages(MESSAGES))
                .temperature(0.7)
                .build());

        assertEquals(MAPPER.readTree(expected), MAPPER.readTree(rawRequestBody),
                "非 Anthropic 通道的请求体不许有任何字段级变化");
        assertFalse(rawRequestBody.contains("cache_control"), rawRequestBody);
    }

    @Test
    @DisplayName("请求体不带缩进：工具 schema 上的空白是每轮都要重传的纯浪费（dev-board#750）")
    void requestBodyCarriesNoIndentation() throws Exception {
        run("deepseek/deepseek-v4-flash");

        // Json.toJson 的缩进形态是 `{\n  "` —— 出现它就说明压缩那一步被绕过了
        assertFalse(rawRequestBody.contains("\n"),
                "请求体里不该有换行（说明又回到了缩进 JSON）：" + rawRequestBody);

        String pretty = Json.toJson(ChatCompletionRequest.builder()
                .stream(true)
                .streamOptions(StreamOptions.builder().includeUsage(true).build())
                .model("deepseek/deepseek-v4-flash")
                .messages(InternalOpenAiHelper.toOpenAiMessages(MESSAGES))
                .temperature(0.7)
                .build());
        assertTrue(rawRequestBody.length() < pretty.length(),
                "压缩后必须比缩进版短：compact=" + rawRequestBody.length() + " pretty=" + pretty.length());
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
    @DisplayName("自动缓存通道：易变段拆成紧随其后的第二条 system，两条拼起来等于原文")
    void autoCachingChannelSplitsVolatileIntoSecondSystemMessage() throws Exception {
        runSplit("deepseek/deepseek-v4-flash");

        JsonNode messages = MAPPER.readTree(rawRequestBody).path("messages");
        assertEquals(3, messages.size(), "稳定 system + 易变 system + 用户消息：" + rawRequestBody);

        JsonNode stable = messages.get(0);
        assertEquals("system", stable.path("role").asText(), rawRequestBody);
        assertTrue(stable.path("content").isTextual(), "自动缓存通道不走 content block 形态");
        assertEquals(STABLE, stable.path("content").asText(),
                "第一条 system 必须一字不差地只含稳定段——多一个每轮变的字节，整个前缀缓存就全丢");
        assertFalse(stable.path("content").asText().contains("Current System Time"),
                "每轮变化的时间戳绝不能留在第一条 system 里：" + rawRequestBody);

        JsonNode tail = messages.get(1);
        assertEquals("system", tail.path("role").asText(), rawRequestBody);
        assertEquals(VOLATILE, tail.path("content").asText(), "第二条 system 就是易变段原文");

        assertEquals("user", messages.get(2).path("role").asText());

        // (a) 模型读到的文字一字不变，变的只是消息边界
        assertEquals(STABLE + VOLATILE,
                stable.path("content").asText() + tail.path("content").asText(),
                "两条拼起来必须与原来那一整串完全相同");

        assertFalse(rawRequestBody.contains("awd:volatile"), rawRequestBody);
        assertFalse(rawRequestBody.contains("cache_control"), rawRequestBody);
    }

    @Test
    @DisplayName("白名单之外的模型退回拼接——形态没验证过就不赌 400")
    void unknownModelKeepsTheSingleConcatenatedSystem() throws Exception {
        runSplit("some-vendor/unverified-model");

        JsonNode messages = MAPPER.readTree(rawRequestBody).path("messages");
        assertEquals(2, messages.size(), "退回旧形态：一条 system + 一条 user：" + rawRequestBody);
        assertEquals(STABLE + VOLATILE, messages.get(0).path("content").asText(),
                "摘掉标记后原样拼接，与改造前一致");
        assertFalse(rawRequestBody.contains("awd:volatile"), rawRequestBody);
    }

    @Test
    @DisplayName("Google 退回拼接：原生只有一个 system_instruction，且本机地域受限验不了")
    void googleKeepsTheSingleConcatenatedSystem() throws Exception {
        runSplit("google/gemini-3.6-flash");
        assertSingleConcatenatedSystem();
    }

    @Test
    @DisplayName("GPT 退回拼接：本机探针拿到的是 403 地域受限，属「未能验证」")
    void unverifiedGptKeepsTheSingleConcatenatedSystem() throws Exception {
        runSplit("openai/gpt-5.6-terra");
        assertSingleConcatenatedSystem();
    }

    /** 没被拆：一条 system（标记摘掉后原样拼接）+ 一条 user。 */
    private void assertSingleConcatenatedSystem() throws Exception {
        JsonNode messages = MAPPER.readTree(rawRequestBody).path("messages");
        assertEquals(2, messages.size(), "不该被拆成两条 system：" + rawRequestBody);
        assertEquals(STABLE + VOLATILE, messages.get(0).path("content").asText(), rawRequestBody);
        assertFalse(rawRequestBody.contains("awd:volatile"), rawRequestBody);
    }

    @Test
    @DisplayName("放行名单只含实测过的 id，且与显式缓存档不重叠")
    void verifiedListIsExactAndDisjointFromExplicitCache() {
        for (String ok : new String[]{"deepseek/deepseek-v4-flash", "deepseek/deepseek-v4-pro",
                "z-ai/glm-5.2", "moonshotai/kimi-k2.6", "moonshotai/kimi-k3",
                "bytedance-seed/seed-2.0-lite", "minimax/minimax-m3", "x-ai/grok-4.5"}) {
            assertTrue(OpenRouterStreamingChatModel.splitsVolatileSystem(ok), ok + " 应当放行");
        }
        // 实测 403（地域受限，验不了）、显式缓存档、白名单外的 id 一律不放行
        for (String no : new String[]{"openai/gpt-5.6-terra", "google/gemini-3.6-flash",
                "anthropic/claude-sonnet-5", "qwen/qwen3.7-flash",
                "some-vendor/unverified-model", "deepseek/deepseek-v4-flash:beta", null}) {
            assertFalse(OpenRouterStreamingChatModel.splitsVolatileSystem(no), no + " 不该放行");
        }
    }

    @Test
    @DisplayName("没有分隔标记（外部调用方 / 旧形态）时一条都不拆")
    void messagesWithoutSeparatorAreLeftAlone() throws Exception {
        run("deepseek/deepseek-v4-flash");

        JsonNode messages = MAPPER.readTree(rawRequestBody).path("messages");
        assertEquals(2, messages.size(), "没有标记就没有易变段，不该凭空多出一条 system：" + rawRequestBody);
        assertEquals("你是一名律师助理。\n<file>合同正文……</file>",
                messages.get(0).path("content").asText());
    }

    @Test
    @DisplayName("易变段为空时不拆（不发空 system 消息）")
    void emptyVolatileTailIsNotSplitOut() {
        String sep = ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR;
        List<ChatMessage> out = OpenRouterStreamingChatModel.splitVolatileSystem(
                List.of(SystemMessage.from(STABLE + sep), UserMessage.from("q")));
        assertEquals(2, out.size(), "空的易变段不该变成一条空 system 消息");
        assertEquals(STABLE, ((SystemMessage) out.get(0)).text());
    }

    @Test
    @DisplayName("两条正文相同的 system 各自按自己的标记拆，不会串到一起")
    void twoIdenticalSystemMessagesAreSplitIndependently() {
        String sep = ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR;
        SystemMessage same = SystemMessage.from("S" + sep + "V");
        List<ChatMessage> out = OpenRouterStreamingChatModel.splitVolatileSystem(
                List.of(same, same, UserMessage.from("q")));
        assertEquals(5, out.size(), "两条各拆成两条 + 用户消息：" + out);
        assertEquals("S", ((SystemMessage) out.get(0)).text());
        assertEquals("V", ((SystemMessage) out.get(1)).text());
        assertEquals("S", ((SystemMessage) out.get(2)).text());
        assertEquals("V", ((SystemMessage) out.get(3)).text());
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
