// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiModelProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionAssistServiceTest {
    private final ChatModelFactory factory = mock(ChatModelFactory.class);
    private final TokenUsageService usage = mock(TokenUsageService.class);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile String answer = """
            {"model":"typesafe/jev-1.13-20260917","answers":{"decision":{
              "type":"choice","choice":"keep","confidence":0.97}},
              "usage":{"input_tokens":321,"output_tokens":0,"cost":0.000013482}}
            """;
    private volatile long delay;
    private HttpServer server;
    private ExecutorService serverPool;
    private DecisionAssistService service;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newCachedThreadPool();
        server.setExecutor(serverPool);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestedPath.set(exchange.getRequestURI().getPath());
            started.countDown();
            assertEquals("Bearer synthetic-test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            var body = new ObjectMapper().readTree(exchange.getRequestBody());
            assertEquals(DecisionAssistService.MODEL, body.path("model").asText());
            try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        when(factory.decisionCredentials("selected-model", AiModelProperties.Provider.AWD_CLOUD)).thenAnswer(invocation -> {
            assertEquals(9L, PlatformAiUserScope.current());
            return new ChatModelFactory.DecisionCredentials("synthetic-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", true);
        });
        service = new DecisionAssistService(factory, usage, new ObjectMapper(), Duration.ofMillis(500));
    }

    @AfterEach void close() { service.close(); server.stop(0); serverPool.shutdownNow(); }

    private DecisionAssistContext context(boolean enabled) {
        return new DecisionAssistContext(enabled, 7L, 9L, "synthetic-conversation", "selected-model",
                AiModelProperties.Provider.AWD_CLOUD);
    }
    private java.util.Optional<DecisionAssistService.Decision> choose(DecisionAssistContext context) {
        return service.choose(context, Map.of("request", "合成样例"), "只判断材料相关性",
                Map.of("keep", "相关或不确定", "omit", "确定无关"));
    }

    @Test void disabledIsZeroRequestsAndZeroCredentialOrBillingWork() {
        assertTrue(choose(context(false)).isEmpty());
        verifyNoInteractions(factory, usage);
        assertEquals(0, requests.get());
    }

    @Test void localRouteNeverSendsToCloud() {
        var local = new DecisionAssistContext(true, 7L, 9L, "local-conversation", "selected-model",
                AiModelProperties.Provider.OLLAMA);
        assertTrue(choose(local).isEmpty());
        verifyNoInteractions(factory, usage);
        assertEquals(0, requests.get());
    }

    @Test void validAnswerRecordsFrozenChannelAndRestoresIdentity() {
        assertEquals("keep", choose(context(true)).orElseThrow().choice());
        assertNull(PlatformAiUserScope.current());
        verify(usage).recordDecisionUsage(eq(7L), eq(9L), eq("synthetic-conversation"),
                eq("typesafe/jev-1.13-20260917"), eq(321), eq(0),
                eq(new java.math.BigDecimal("0.000013482")), eq(true));
        assertEquals(1, requests.get());
    }

    @Test void malformedOrUnknownAnswerFallsBackWithoutRetryButStillAccountsReceipt() {
        answer = answer.replace("\"keep\"", "\"unapproved\"");
        assertTrue(choose(context(true)).isEmpty());
        assertEquals(1, requests.get());
        verify(usage).recordDecisionUsage(any(), any(), any(), any(), anyInt(), anyInt(), any(), anyBoolean());
    }

    @Test void invalidConfidenceFallsBack() {
        answer = answer.replace("0.97", "1.01");
        assertTrue(choose(context(true)).isEmpty());
    }

    @Test void timeoutDoesNotRetryOrDeliverLateResult() throws Exception {
        delay = 1200;
        long start = System.nanoTime();
        assertTrue(choose(context(true)).isEmpty());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
        assertEquals(1, requests.get());
        verifyNoInteractions(usage);
    }

    @Test void cancellationClosesOwnInflightRequest() throws Exception {
        delay = 3000;
        var context = context(true);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> choose(context));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            context.cancel();
            assertTrue(result.get(500, TimeUnit.MILLISECONDS).isEmpty());
            assertTrue(choose(context).isEmpty());
        } finally { pool.shutdownNow(); }
        assertEquals(1, requests.get());
    }

    @Test void oversizedInputDoesNotResolveCredentials() {
        assertTrue(service.choose(context(true), Map.of("text", "字".repeat(40_000)), "test",
                Map.of("keep", "keep", "omit", "omit")).isEmpty());
        verifyNoInteractions(factory, usage);
    }

    @Test void proxyPrefixIsPreservedAndUnknownPathNeverSent() {
        when(factory.decisionCredentials(any(), any())).thenReturn(new ChatModelFactory.DecisionCredentials(
                "synthetic-test-key", "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/openrouter-relay/api/v1/", false));
        assertEquals("keep", choose(context(true)).orElseThrow().choice());
        assertEquals("/openrouter-relay/api/alpha/decisions", requestedPath.get());
        when(factory.decisionCredentials(any(), any())).thenReturn(new ChatModelFactory.DecisionCredentials(
                "synthetic-test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/unsupported", false));
        assertTrue(choose(context(true)).isEmpty());
        assertEquals(1, requests.get());
    }

    @Test void totalDeadlineIncludesCredentialResolutionAndRejectsLateSuccess() throws Exception {
        CountDownLatch release = new CountDownLatch(1), finished = new CountDownLatch(1);
        when(factory.decisionCredentials(any(), any())).thenAnswer(invocation -> {
            try { awaitIgnoringInterrupt(release); }
            finally { finished.countDown(); }
            return new ChatModelFactory.DecisionCredentials("synthetic-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", true);
        });
        var context = context(true);
        long start = System.nanoTime();
        try {
            assertTrue(choose(context).isEmpty());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
            assertTrue(context.isCancelled());
            assertEquals(0, requests.get());
        } finally { release.countDown(); }
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertTrue(choose(context).isEmpty());
        verifyNoInteractions(usage);
    }

    @Test void totalDeadlineAlsoIncludesSlowReceiptPersistence() throws Exception {
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return null;
        }).when(usage).recordDecisionUsage(any(), any(), any(), any(), anyInt(), anyInt(), any(), anyBoolean());
        var context = context(true);
        long start = System.nanoTime();
        try {
            assertTrue(choose(context).isEmpty());
            assertEquals(0, entered.getCount());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
            assertTrue(context.isCancelled());
        } finally { release.countDown(); }
    }

    @Test void cancelStopsBothWorkerAndHttpAndRegistrationAfterCancel() {
        var context = context(true);
        AtomicInteger cancelled = new AtomicInteger();
        context.track(cancelled::incrementAndGet);
        context.track(cancelled::incrementAndGet);
        context.cancel();
        assertEquals(2, cancelled.get());
        context.track(cancelled::incrementAndGet);
        assertEquals(3, cancelled.get());
    }

    @Test void saturatedWorkerQueueFallsBackWithoutStartingMoreCredentialRequests() throws Exception {
        service.close();
        service = new DecisionAssistService(factory, usage, new ObjectMapper(), Duration.ofSeconds(5));
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        when(factory.decisionCredentials(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return null;
        });
        var contexts = java.util.stream.IntStream.range(0, 6).mapToObj(i -> context(true)).toList();
        var workers = (ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(service, "decisions");
        var callers = Executors.newFixedThreadPool(6);
        try {
            try {
                for (var context : contexts) callers.submit(() -> choose(context));
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (workers.getQueue().size() < 4 && System.nanoTime() < deadline) Thread.onSpinWait();
                assertEquals(4, workers.getQueue().size());
                long start = System.nanoTime();
                assertTrue(choose(context(true)).isEmpty());
                assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 500);
                verify(factory, times(2)).decisionCredentials(any(), any());
                assertEquals(0, requests.get());
            } finally {
                contexts.forEach(DecisionAssistContext::cancel);
                release.countDown();
            }
        } finally { callers.shutdownNow(); }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try { latch.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
