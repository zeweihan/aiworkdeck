package com.checkba.service.ai;

import com.sun.net.httpserver.HttpServer;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.FinishReason;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自有流式客户端的协议契约（dev-board#364）。
 *
 * <p>假服务端回放的是 2026-09-02 对 OpenRouter {@code moonshotai/kimi-k3} 真机探测抓到的
 * 片段形状：思考期间每个 chunk 都是 {@code content:""} + {@code reasoning:"…"} +
 * {@code reasoning_details:[…]}，前面夹着 {@code : OPENROUTER PROCESSING} 注释行；
 * 正文开始后 reasoning 变 null；最后一个 chunk 只带 usage、choices 为空。
 * 旧实现（langchain4j 0.36 / openai4j 0.23）对这条流的行为是：思考全部丢掉、
 * 注释行吞掉、只往 handler 灌一串空字符串——这就是「思考 281 秒前端零提示」的成因。
 */
class OpenRouterStreamingChatModelTest {

    private HttpServer server;
    private volatile String lastRequestBody;
    private volatile String lastAuthHeader;

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

    /** 注册一条 200 + text/event-stream 的回放；body 里的 "\n" 原样写出。 */
    private void serveStream(String sseBody) {
        serve(200, "text/event-stream", sseBody);
    }

    private void serve(int status, String contentType, String body) {
        server.createContext("/api/v1/chat/completions", ex -> {
            // openai4j 的 Json 开了 INDENT_OUTPUT（"stream" : true），断言前去掉空白
            lastRequestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", "");
            lastAuthHeader = ex.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private static String chunk(String deltaJson, String finish) {
        return "data: {\"id\":\"gen-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                + "\"delta\":" + deltaJson + ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"")
                + "}]}\n\n";
    }

    /** 收集三条通道的测试 handler。 */
    private static final class Collector implements ReasoningStreamingHandler {
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final AtomicInteger keepAlives = new AtomicInteger();
        final AtomicInteger emptyTokens = new AtomicInteger();
        final AtomicReference<Response<AiMessage>> done = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final CountDownLatch settled = new CountDownLatch(1);

        @Override public void onNext(String token) {
            if (token.isEmpty()) emptyTokens.incrementAndGet();
            content.append(token);
        }
        @Override public void onReasoning(String d) { reasoning.append(d); }
        @Override public void onKeepAlive() { keepAlives.incrementAndGet(); }
        @Override public void onComplete(Response<AiMessage> r) { done.set(r); settled.countDown(); }
        @Override public void onError(Throwable t) { error.set(t); settled.countDown(); }

        void await() throws InterruptedException {
            assertTrue(settled.await(10, TimeUnit.SECONDS), "流必须在 10 秒内到达终态");
        }
    }

    @Test
    @DisplayName("思考增量走 onReasoning、正文走 onNext、保活注释走 onKeepAlive，三条通道互不串")
    void reasoningContentAndKeepAliveAreSeparated() throws Exception {
        serveStream(""
                + ": OPENROUTER PROCESSING\n\n"
                + ": OPENROUTER PROCESSING\n\n"
                + chunk("{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":\"先看\","
                        + "\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"先看\"}]}", null)
                + chunk("{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":\"条款\","
                        + "\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"条款\"}]}", null)
                + chunk("{\"role\":\"assistant\",\"content\":\"修订\",\"reasoning\":null}", null)
                + chunk("{\"role\":\"assistant\",\"content\":\"完成\"}", null)
                + chunk("{\"role\":\"assistant\",\"content\":\"\"}", "stop")
                + "data: {\"id\":\"gen-1\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
                + "data: [DONE]\n\n");

        OpenRouterStreamingChatModel model = new OpenRouterStreamingChatModel(
                "sk-test", baseUrl(), "moonshotai/kimi-k3", Duration.ofSeconds(5));
        Collector c = new Collector();
        model.generate(List.of(UserMessage.from("修订这份合同")), c);
        c.await();

        assertNull(c.error.get(), () -> "不该出错：" + c.error.get());
        assertEquals("先看条款", c.reasoning.toString(), "reasoning 字段必须逐段转发，旧实现在这里是空串");
        assertEquals("修订完成", c.content.toString(), "正文只含 content，思考文本一个字都不许混进去");
        assertTrue(c.keepAlives.get() >= 2, "SSE 注释行是「上游还在跑」的信号，必须转发给看门狗");
        Response<AiMessage> r = c.done.get();
        assertNotNull(r);
        assertEquals("修订完成", r.content().text());
        assertEquals(FinishReason.STOP, r.finishReason());
        assertNotNull(r.tokenUsage());
        assertEquals(10, r.tokenUsage().inputTokenCount());
        assertEquals(5, r.tokenUsage().outputTokenCount());

        assertEquals("Bearer sk-test", lastAuthHeader);
        assertTrue(lastRequestBody.contains("\"stream\":true"), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"stream_options\""), "与 langchain4j 一致：要 usage 就得带 stream_options");
        assertTrue(lastRequestBody.contains("\"include_usage\":true"), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"model\":\"moonshotai/kimi-k3\""), lastRequestBody);
        assertFalse(lastRequestBody.contains("\"tools\""), "没传工具就不该出现 tools 字段");
    }

    @Test
    @DisplayName("工具调用增量按 index 拼装成 ToolExecutionRequest，工具定义随请求下发")
    void toolCallsAreAssembled() throws Exception {
        serveStream(""
                + chunk("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"pa\"}}]}", null)
                + chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":\\\"a.docx\\\"}\"}}]}", null)
                + chunk("{}", "tool_calls")
                + "data: [DONE]\n\n");

        OpenRouterStreamingChatModel model = new OpenRouterStreamingChatModel(
                "sk-test", baseUrl(), "deepseek/deepseek-v4-flash", Duration.ofSeconds(5));
        Collector c = new Collector();
        ToolSpecification spec = ToolSpecification.builder().name("read_file").description("read a file").build();
        model.generate(List.of(UserMessage.from("读 a.docx")), List.of(spec), c);
        c.await();

        assertNull(c.error.get(), () -> "不该出错：" + c.error.get());
        Response<AiMessage> r = c.done.get();
        assertTrue(r.content().hasToolExecutionRequests());
        assertEquals("read_file", r.content().toolExecutionRequests().get(0).name());
        assertEquals("{\"path\":\"a.docx\"}", r.content().toolExecutionRequests().get(0).arguments());
        assertEquals(FinishReason.TOOL_EXECUTION, r.finishReason());
        assertTrue(lastRequestBody.contains("\"tools\":[{\"type\":\"function\""), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"name\":\"read_file\""), lastRequestBody);
    }

    @Test
    @DisplayName("不实现 ReasoningStreamingHandler 的旧 handler 照常拿正文，思考增量静默跳过")
    void plainHandlerStillGetsContent() throws Exception {
        serveStream(""
                + chunk("{\"content\":\"\",\"reasoning\":\"想一下\"}", null)
                + chunk("{\"content\":\"答案\"}", "stop")
                + "data: [DONE]\n\n");
        OpenRouterStreamingChatModel model = new OpenRouterStreamingChatModel(
                "sk-test", baseUrl(), "deepseek/deepseek-v4-flash", Duration.ofSeconds(5));
        StringBuilder content = new StringBuilder();
        CountDownLatch settled = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        model.generate(List.of(UserMessage.from("hi")), new StreamingResponseHandler<AiMessage>() {
            @Override public void onNext(String token) { content.append(token); }
            @Override public void onComplete(Response<AiMessage> response) { settled.countDown(); }
            @Override public void onError(Throwable error) { err.set(error); settled.countDown(); }
        });
        assertTrue(settled.await(10, TimeUnit.SECONDS));
        assertNull(err.get());
        assertEquals("答案", content.toString());
    }

    @Test
    @DisplayName("非 2xx 映射成 OpenAiHttpException 并带状态码——LlmErrorClassifier 靠它把 429 归入限流")
    void httpErrorKeepsStatusCode() throws Exception {
        serve(429, "application/json", "{\"error\":{\"message\":\"Rate limit exceeded\",\"code\":429}}");
        OpenRouterStreamingChatModel model = new OpenRouterStreamingChatModel(
                "sk-test", baseUrl(), "deepseek/deepseek-v4-flash", Duration.ofSeconds(5));
        Collector c = new Collector();
        model.generate(List.of(UserMessage.from("hi")), c);
        c.await();

        assertNull(c.done.get(), "HTTP 错误绝不能按成功收尾");
        assertTrue(c.error.get() instanceof OpenAiHttpException, String.valueOf(c.error.get()));
        assertEquals(429, ((OpenAiHttpException) c.error.get()).code());
        assertEquals(LlmErrorClassifier.Kind.RATE_LIMITED, LlmErrorClassifier.classify(c.error.get()));
    }

    @Test
    @DisplayName("HTTP 200 里用 data 事件送来的上游错误也要当错误，不许按空回复静默收尾")
    void inStreamErrorObjectIsSurfaced() throws Exception {
        serveStream("data: {\"error\":{\"message\":\"Provider returned error\",\"code\":502}}\n\n");
        OpenRouterStreamingChatModel model = new OpenRouterStreamingChatModel(
                "sk-test", baseUrl(), "deepseek/deepseek-v4-flash", Duration.ofSeconds(5));
        Collector c = new Collector();
        model.generate(List.of(UserMessage.from("hi")), c);
        c.await();

        assertNull(c.done.get());
        assertTrue(c.error.get() instanceof OpenAiHttpException, String.valueOf(c.error.get()));
        assertEquals(502, ((OpenAiHttpException) c.error.get()).code());
        assertEquals(LlmErrorClassifier.Kind.TRANSIENT, LlmErrorClassifier.classify(c.error.get()));
    }
}
