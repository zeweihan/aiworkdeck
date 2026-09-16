// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.ai;

import com.checkba.config.AiModelProperties;
import com.checkba.service.SystemSettingService;
import com.checkba.service.telemetry.TelemetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WritingModelTransportTest {
    HttpServer server;
    ExecutorService executor;
    AtomicReference<String> body = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicInteger requests = new AtomicInteger();
    static final String SSE = "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"建议\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n";
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor); server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    OpenRouterStreamingChatModel model(String name, Duration timeout) { return new OpenRouterStreamingChatModel("synthetic-key", url(), name, timeout); }
    void serve() {
        server.createContext("/chat/completions", ex -> {
            requests.incrementAndGet(); body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = SSE.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (var out = ex.getResponseBody()) { out.write(bytes); }
        });
    }
    static class Collector implements StreamingResponseHandler<AiMessage> {
        CountDownLatch done = new CountDownLatch(1), first = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger terminal = new AtomicInteger();
        public void onNext(String token) { first.countDown(); }
        public void onComplete(Response<AiMessage> r) { terminal.incrementAndGet(); done.countDown(); }
        public void onError(Throwable t) { error.set(t); terminal.incrementAndGet(); done.countDown(); }
        void await() throws InterruptedException { assertTrue(done.await(5, TimeUnit.SECONDS)); }
    }
    @Test void writingAddsOutputCapAndRetainsUsageAndCaching() throws Exception {
        serve(); Collector c = new Collector();
        var call = model("qwen/qwen3.7-flash", Duration.ofSeconds(3)).generateCancellable(
                List.of(SystemMessage.from("synthetic system"), UserMessage.from("synthetic source")), 512, c);
        assertNotNull(call); c.await(); assertNull(c.error.get());
        var json = new ObjectMapper().readTree(body.get());
        assertEquals(512, json.path("max_tokens").asInt());
        assertTrue(json.path("stream_options").path("include_usage").asBoolean());
        assertEquals("ephemeral", json.path("messages").get(0).path("content").get(0).path("cache_control").path("type").asText());
        assertEquals("Bearer synthetic-key", auth.get()); assertFalse(json.has("tools"));
    }
    @Test void defaultBodyIsByteForByteIdenticalAfterWritingCall() throws Exception {
        serve(); var m = model("openai/gpt-4o", Duration.ofSeconds(3));
        List<ChatMessage> messages = List.of(UserMessage.from("synthetic"));
        var tools = List.of(ToolSpecification.builder().name("example").description("synthetic").build());
        Collector first = new Collector(); m.generate(messages, tools, first); first.await();
        String baseline = body.get(); assertFalse(new ObjectMapper().readTree(baseline).has("max_tokens"));
        Collector writing = new Collector(); m.generateCancellable(messages, 256, writing); writing.await();
        assertEquals(256, new ObjectMapper().readTree(body.get()).path("max_tokens").asInt());
        Collector last = new Collector(); m.generate(messages, tools, last); last.await();
        assertEquals(baseline, body.get());
        assertTrue(new ObjectMapper().readTree(body.get()).path("tools").isArray());
    }
    @Test void invalidCapIsRejectedBeforeAnyHttp() {
        serve(); var m = model("openai/gpt-4o", Duration.ofSeconds(3));
        for (int cap : new int[]{-1, 0, 255, 4097}) assertThrows(IllegalArgumentException.class,
                () -> m.generateCancellable(List.of(UserMessage.from("synthetic")), cap, new Collector()));
        assertEquals(0, requests.get());
    }
    @Test void cancellationClosesHttpStreamAndSettlesOnce() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        server.createContext("/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes(); ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                out.write(SSE.substring(0, SSE.indexOf("data: [DONE]")).getBytes(StandardCharsets.UTF_8)); out.flush();
                for (int i = 0; i < 200; i++) {
                    Thread.sleep(10); out.write((":" + "x".repeat(8192) + "\n\n").getBytes(StandardCharsets.UTF_8)); out.flush();
                }
            } catch (java.io.IOException e) { disconnected.countDown(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Collector c = new Collector(); var call = model("openai/gpt-4o", Duration.ofSeconds(5))
                .generateCancellable(List.of(UserMessage.from("synthetic")), 1024, c);
        assertTrue(c.first.await(3, TimeUnit.SECONDS)); call.cancel(); c.await();
        assertTrue(call.isCanceled()); assertNotNull(c.error.get());
        assertTrue(disconnected.await(3, TimeUnit.SECONDS), "Server must observe socket close, not merely discarded output");
        assertEquals(1, c.terminal.get());
    }
    @Test void wallClockDeadlineStopsHttp() throws Exception {
        server.createContext("/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            ex.close();
        });
        Collector c = new Collector(); long start = System.nanoTime();
        model("openai/gpt-4o", Duration.ofMillis(150)).generateCancellable(List.of(UserMessage.from("synthetic")), 512, c);
        c.await(); assertNotNull(c.error.get()); assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
    }

    ChatModelFactory factory(String provider, AiModelProperties props, PlatformAiChannel platform) {
        return factory(provider, props, platform, props.getOpenRouter().getBaseUrl());
    }
    ChatModelFactory factory(String provider, AiModelProperties props, PlatformAiChannel platform, String byokUrl) {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(settings.get(eq("ai.activeProvider"), any())).thenReturn(provider);
        when(settings.get(eq(ChatModelFactory.SETTING_OPENROUTER_BASE_URL), any())).thenReturn(byokUrl);
        return new ChatModelFactory(props, settings, platform, mock(PlatformCreditsGate.class), mock(PlatformUsageAccountant.class),
                new AuxModelResolver(settings, AllowedModels.QWEN_3_7_FLASH.getModelId()), mock(TelemetryService.class));
    }
    @Test void factoryUsesRequestScopedByokAndValidatedAuxiliaryModel() throws Exception {
        serve(); AiModelProperties props = new AiModelProperties(); props.getOpenRouter().setApiKey("synthetic-byok"); props.getOpenRouter().setBaseUrl(url());
        var f = factory("OPENROUTER", props, mock(PlatformAiChannel.class));
        var m = f.getWritingModel(Duration.ofSeconds(2));
        assertNotSame(m, f.getWritingModel(Duration.ofSeconds(2))); assertEquals(AllowedModels.QWEN_3_7_FLASH.getModelId(), m.modelName());
        Collector c = new Collector(); m.generateCancellable(List.of(UserMessage.from("synthetic")), 512, c); c.await();
        assertEquals("Bearer synthetic-byok", auth.get());
    }
    @Test void factoryUsesPlatformCredential() throws Exception {
        serve(); AiModelProperties props = new AiModelProperties(); props.getOpenRouter().setBaseUrl(url()); props.getOpenRouter().setApiKey("wrong-byok");
        var platform = mock(PlatformAiChannel.class); when(platform.apiKey()).thenReturn("synthetic-platform");
        var m = factory("AWD_CLOUD", props, platform).getWritingModel(Duration.ofSeconds(2));
        Collector c = new Collector(); m.generateCancellable(List.of(UserMessage.from("synthetic")), 512, c); c.await();
        assertEquals("Bearer synthetic-platform", auth.get());
    }
    @Test void platformNeverSendsKeyToByokCustomAddress() throws Exception {
        serve(); AiModelProperties props = new AiModelProperties(); props.getOpenRouter().setBaseUrl(url());
        var platform = mock(PlatformAiChannel.class); when(platform.apiKey()).thenReturn("synthetic-platform");
        var m = factory("AWD_CLOUD", props, platform, "http://127.0.0.1:1/untrusted").getWritingModel(Duration.ofSeconds(2));
        Collector c = new Collector(); m.generateCancellable(List.of(UserMessage.from("synthetic")), 4096, c); c.await();
        assertNull(c.error.get()); assertEquals("Bearer synthetic-platform", auth.get());
        assertEquals(4096, new ObjectMapper().readTree(body.get()).path("max_tokens").asInt());
    }
    @Test void ollamaIsExplicitlyUnsupportedNeverFallsBack() {
        var platform = mock(PlatformAiChannel.class);
        var f = factory("OLLAMA", new AiModelProperties(), platform);
        assertThrows(com.checkba.exception.FeatureNotConfiguredException.class, () -> f.getWritingModel(Duration.ofSeconds(2)));
        verify(platform, never()).apiKey();
    }
    @Test void expiredBudgetRejected() {
        var f = factory("OPENROUTER", new AiModelProperties(), mock(PlatformAiChannel.class));
        for (Duration d : new Duration[]{Duration.ZERO, Duration.ofMillis(-1)}) assertThrows(IllegalArgumentException.class, () -> f.getWritingModel(d));
        assertThrows(IllegalArgumentException.class, () -> f.getWritingModel(null));
    }
    @Test void emptyOrReasoningOnlyStreamsAlwaysReachOneErrorTerminal() throws Exception {
        AtomicReference<String> response=new AtomicReference<>();
        server.createContext("/chat/completions",ex -> {
            ex.getRequestBody().readAllBytes(); byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200,bytes.length==0?-1:bytes.length);
            try(var out=ex.getResponseBody()) { if(bytes.length>0) out.write(bytes); }
        });
        for(String sse:List.of("", "data: [DONE]\n\n",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":256,\"total_tokens\":266}}\n\ndata: [DONE]\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":null,\"reasoning\":\"synthetic thought\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n")) {
            response.set(sse); Collector c=new Collector();
            model("qwen/qwen3.7-flash",Duration.ofSeconds(2)).generateCancellable(List.of(UserMessage.from("synthetic")),256,c);
            c.await(); assertNotNull(c.error.get()); assertEquals(1,c.terminal.get());
            var empty=assertInstanceOf(OpenRouterStreamingChatModel.EmptyResponseException.class,c.error.get());
            if(sse.contains("prompt_tokens")) { assertEquals(10,empty.tokenUsage().inputTokenCount()); assertEquals(256,empty.tokenUsage().outputTokenCount()); }
            else assertNull(empty.tokenUsage());
        }
    }
    @Test void boundedWritingDisablesOnlyVerifiedFlashReasoningAndPreservesOtherModels() throws Exception {
        serve();
        for(String name:List.of("qwen/qwen3.7-flash","qwen/qwen3.8-max-0902","qwen/qwen3.7-max","alibaba/test","openai/gpt-4o")) {
            var m=model(name,Duration.ofSeconds(2)); Collector bounded=new Collector();
            m.generateCancellable(List.of(UserMessage.from("synthetic")),512,bounded); bounded.await();
            var request=new ObjectMapper().readTree(body.get());
            if(name.equals("qwen/qwen3.7-flash")) { assertTrue(request.has("reasoning")); assertFalse(request.path("reasoning").path("enabled").asBoolean(true)); }
            else assertFalse(request.has("reasoning"), "Unverified or mandatory-reasoning models retain defaults: " + name);
            Collector normal=new Collector(); m.generate(List.of(UserMessage.from("synthetic")),normal); normal.await();
            assertFalse(new ObjectMapper().readTree(body.get()).has("reasoning"));
        }
    }
    @Test void boundedWritingNeverLogsMalformedProviderContent() throws Exception {
        String secret = "SYNTHETIC_PRIVATE_MATERIAL_042";
        server.createContext("/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            String response = "data: {" + secret + "\n\ndata: {\"choices\":\"" + secret + "\"}\n\n" + SSE;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (var out = ex.getResponseBody()) { out.write(bytes); }
        });
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenRouterStreamingChatModel.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            var m = model("openai/gpt-4o", Duration.ofSeconds(2));
            Collector bounded = new Collector();
            m.generateCancellable(List.of(UserMessage.from("synthetic")), 512, bounded); bounded.await();
            assertNull(bounded.error.get());
            assertEquals(2, logs.list.size());
            assertTrue(logs.list.stream().noneMatch(event -> event.getFormattedMessage().contains(secret)));
            logs.list.clear();
            Collector normal = new Collector(); m.generate(List.of(UserMessage.from("synthetic")), normal); normal.await();
            assertNull(normal.error.get());
            assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains(secret)), "Existing normal chat diagnostics remain unchanged");
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

}
