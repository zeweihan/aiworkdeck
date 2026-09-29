// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 首字节前排队换家（dev-board#1061）：真通道 + 真 AgentStreamHandler + 可注入时钟。
 *
 * <p>第一次请求只回响应头和一行 {@code : OPENROUTER PROCESSING} 就挂着不动（排在供应商的队里）；
 * 时钟拨过 31 秒后看门狗判定触发，第一次请求被掐、带 ignore（或换排序）的第二次请求发出去并正常出字。
 */
class OpenRouterProviderRequeueTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch keepAliveSent = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 第一次请求会挂住处理线程，必须多线程，否则第二次请求进不来
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private void serve(String firstProviderHeader) {
        server.createContext("/api/v1/chat/completions", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            if (bodies.size() == 1) {
                if (firstProviderHeader != null) ex.getResponseHeaders().add("X-Provider-Name", firstProviderHeader);
                ex.sendResponseHeaders(200, 0);
                OutputStream os = ex.getResponseBody();
                try {
                    os.write(": OPENROUTER PROCESSING\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    keepAliveSent.countDown();
                    release.await(20, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 客户端掐断后写失败是预期
                } finally {
                    try { os.close(); } catch (IOException ignored) { }
                }
                return;
            }
            byte[] ok = ("data: {\"id\":\"g\",\"provider\":\"Together\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"这是保密协议。\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(ok); }
        });
    }

    private record Setup(AgentStreamHandler handler, SseEmitterService sse, AtomicLong clock,
                         AtomicReference<Response<AiMessage>> done, AtomicReference<Throwable> error,
                         CountDownLatch settled) { }

    private Setup startRound() throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        AgentStreamHandler h = new AgentStreamHandler(sse, "conv-requeue", mock(TokenUsageService.class),
                "1", 1L, "moonshotai/kimi-k3", 0L);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        h.setClockForTest(clock::get);
        AtomicReference<Response<AiMessage>> done = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch settled = new CountDownLatch(1);
        h.setOnComplete(r -> { done.set(r); settled.countDown(); });
        h.setOnError(e -> { error.set(e); settled.countDown(); });
        // 看门狗时限给大，免得真实的 5 秒轮询在测试里插一脚
        h.armInactivityWatchdog(3600, 3600);
        OpenRouterStreamingChatModel model = new OpenRouterStreamingChatModel("sk-test",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", "moonshotai/kimi-k3",
                Duration.ofSeconds(30), 32, null,
                new OpenRouterStreamingChatModel.ProviderRouting("latency", List.of("fp8", "unknown"), true, 30));
        AgentOrchestrator.startGeneration(model, List.of(UserMessage.from("这是什么文件？")), null, h);
        assertTrue(keepAliveSent.await(10, TimeUnit.SECONDS), "第一次请求应只收到保活");
        // 等保活那一行被客户端读到
        long deadline = System.currentTimeMillis() + 5000;
        while (!h.checkKeepAliveSeenForTest() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(h.checkKeepAliveSeenForTest());
        return new Setup(h, sse, clock, done, error, settled);
    }

    @Test
    @DisplayName("只收到保活 31 秒：掐掉本次、带 ignore 重发一次，第二次正常出字后不再重发")
    void keepAliveOnlyRequeuesOnceIgnoringTheQueuedProvider() throws Exception {
        serve("Wafer");
        Setup s = startRound();

        s.clock.addAndGet(29_000_000_000L);
        assertFalse(s.handler.checkProviderRequeue(), "不到 30 秒不换家");
        s.clock.addAndGet(2_000_000_000L);
        assertTrue(s.handler.checkProviderRequeue(), "31 秒只有保活应当换家");

        assertTrue(s.settled.await(10, TimeUnit.SECONDS), "第二次请求应正常收尾");
        assertNull(s.error.get(), () -> "掐第一次请求换来的 IOException 不许漏给编排器：" + s.error.get());
        assertEquals("这是保密协议。", s.done.get().content().text());
        assertEquals(2, bodies.size());
        JsonNode first = MAPPER.readTree(bodies.get(0));
        JsonNode second = MAPPER.readTree(bodies.get(1));
        assertFalse(first.path("provider").has("ignore"));
        assertEquals("Wafer", second.path("provider").path("ignore").path(0).asText(), bodies.get(1));
        assertEquals("latency", second.path("provider").path("sort").asText());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(s.sse).send(eq("conv-requeue"), eq("provider_retry"), payload.capture());
        assertEquals("{\"from\":\"Wafer\"}", String.valueOf(payload.getValue()));

        s.clock.addAndGet(100_000_000_000L);
        assertFalse(s.handler.checkProviderRequeue(), "一轮只换一次家");
        Thread.sleep(200);
        assertEquals(2, bodies.size(), "第二次有首字节后不再重发");
    }

    @Test
    @DisplayName("供应商名拿不到：换一档排序重发，前端事件报 unknown")
    void unknownProviderRequeuesWithAnotherSort() throws Exception {
        serve(null);
        Setup s = startRound();

        s.clock.addAndGet(31_000_000_000L);
        assertTrue(s.handler.checkProviderRequeue());
        assertTrue(s.settled.await(10, TimeUnit.SECONDS));
        assertNull(s.error.get());
        JsonNode second = MAPPER.readTree(bodies.get(1));
        assertEquals("throughput", second.path("provider").path("sort").asText(), bodies.get(1));
        assertFalse(second.path("provider").has("ignore"));
        verify(s.sse).send(eq("conv-requeue"), eq("provider_retry"), eq("{\"from\":\"unknown\"}"));
    }

    @Test
    @DisplayName("没有配供应商路由的通道：不绑换家动作，31 秒也不重发")
    void noRoutingMeansNoRequeue() throws Exception {
        serve(null);
        SseEmitterService sse = mock(SseEmitterService.class);
        AgentStreamHandler h = new AgentStreamHandler(sse, "conv-requeue", mock(TokenUsageService.class),
                "1", 1L, "deepseek/deepseek-v4-flash", 0L);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        h.setClockForTest(clock::get);
        h.setOnError(e -> { });
        h.armInactivityWatchdog(3600, 3600);
        Runnable cancel = AgentOrchestrator.startGeneration(new OpenRouterStreamingChatModel("sk-test",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", "deepseek/deepseek-v4-flash",
                Duration.ofSeconds(30)), List.of(UserMessage.from("hi")), null, h);
        assertTrue(keepAliveSent.await(10, TimeUnit.SECONDS));
        clock.addAndGet(31_000_000_000L);
        assertFalse(h.checkProviderRequeue());
        assertEquals(1, bodies.size());
        cancel.run();
    }

    @Test
    @DisplayName("换家之后点停止：掐的是重发出去的那一次")
    void cancelAfterRequeueHitsTheCurrentCall() throws Exception {
        serve("Wafer");
        SseEmitterService sse = mock(SseEmitterService.class);
        AgentStreamHandler h = new AgentStreamHandler(sse, "conv-requeue", mock(TokenUsageService.class),
                "1", 1L, "moonshotai/kimi-k3", 0L);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        h.setClockForTest(clock::get);
        CountDownLatch settled = new CountDownLatch(1);
        h.setOnComplete(r -> settled.countDown());
        h.setOnError(e -> settled.countDown());
        h.armInactivityWatchdog(3600, 3600);
        OpenRouterStreamingChatModel model = new OpenRouterStreamingChatModel("sk-test",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", "moonshotai/kimi-k3",
                Duration.ofSeconds(30), 32, null,
                new OpenRouterStreamingChatModel.ProviderRouting("latency", List.of(), true, 30));
        Runnable cancel = AgentOrchestrator.startGeneration(model, List.of(UserMessage.from("hi")), null, h);
        assertTrue(keepAliveSent.await(10, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 5000;
        while (!h.checkKeepAliveSeenForTest() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        cancel.run();
        clock.addAndGet(31_000_000_000L);
        // 已取消的在途请求不再换家
        h.checkProviderRequeue();
        Thread.sleep(200);
        assertEquals(1, bodies.size(), "停止之后不许再发请求");
        verify(sse, org.mockito.Mockito.never()).send(any(), eq("provider_retry"), any());
    }
}
