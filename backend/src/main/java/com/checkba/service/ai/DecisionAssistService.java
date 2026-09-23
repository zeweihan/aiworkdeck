// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiModelProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/** Bounded, optional semantic decisions. No retries, user text logging, or generative-model fallback. */
@Service
public class DecisionAssistService {
    public static final String MODEL = "typesafe/jev-1.13";
    private static final int MAX_REQUEST_BYTES = 96_000;
    private static final int MAX_RESPONSE_BYTES = 64_000;
    private final ChatModelFactory factory;
    private final TokenUsageService usage;
    private final ObjectMapper mapper;
    private final OkHttpClient http;
    private final long timeoutNanos;
    private final ThreadPoolExecutor decisions = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), task -> {
                Thread thread = new Thread(task, "decision-assist");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @org.springframework.beans.factory.annotation.Autowired
    public DecisionAssistService(ChatModelFactory factory, TokenUsageService usage, ObjectMapper mapper) {
        this(factory, usage, mapper, Duration.ofMillis(2500));
    }

    DecisionAssistService(ChatModelFactory factory, TokenUsageService usage, ObjectMapper mapper, Duration timeout) {
        this.factory = factory;
        this.usage = usage;
        this.mapper = mapper;
        this.timeoutNanos = timeout.toNanos();
        this.http = new OkHttpClient.Builder().callTimeout(timeout).connectTimeout(timeout)
                .readTimeout(timeout).retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).build();
    }

    public record Decision(String choice, double confidence) {}

    public Optional<Decision> choose(DecisionAssistContext context, Map<String, ?> state,
                                     String instructions, Map<String, String> criteria) {
        // No credential resolution, provisioning, cost or outbound request when consent is absent.
        if (context == null || !context.enabled() || context.isCancelled()
                || (context.expectedChannel() != AiModelProperties.Provider.AWD_CLOUD
                    && context.expectedChannel() != AiModelProperties.Provider.OPENROUTER)) return Optional.empty();
        long deadline = System.nanoTime() + timeoutNanos;
        FutureTask<Optional<Decision>> task = new FutureTask<>(() ->
            PlatformAiUserScope.call(context.userId(), () -> {
                if (context.isCancelled()) return Optional.empty();
                try { return request(context, state, instructions, criteria); }
                catch (Exception ignored) { return Optional.empty(); }
            }));
        Runnable cancelTask = () -> task.cancel(true);
        context.track(cancelTask);
        try {
            if (context.isCancelled()) return Optional.empty();
            decisions.execute(task);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            Optional<Decision> result = task.get(remaining, TimeUnit.NANOSECONDS);
            return context.isCancelled() ? Optional.empty() : result;
        } catch (InterruptedException interrupted) {
            context.cancel();
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (TimeoutException timedOut) {
            context.cancel();
            return Optional.empty();
        } catch (RejectedExecutionException | ExecutionException | CancellationException ignored) {
            // Provider bodies/exceptions may include user content or credentials. Deliberately do not log them.
            task.cancel(true);
            return Optional.empty();
        } finally {
            context.clear(cancelTask);
            if (task.isCancelled()) decisions.remove(task);
        }
    }

    private Optional<Decision> request(DecisionAssistContext context, Map<String, ?> state,
                                       String instructions, Map<String, String> criteria) throws Exception {
        if (criteria == null || criteria.size() < 2 || criteria.size() > 20) return Optional.empty();
        byte[] body = mapper.writeValueAsBytes(Map.of("model", MODEL, "state", state,
                "questions", Map.of("decision", Map.of("type", "choice", "instructions", instructions,
                        "criteria", criteria))));
        if (body.length > MAX_REQUEST_BYTES || context.isCancelled()) return Optional.empty();
        ChatModelFactory.DecisionCredentials credentials =
                factory.decisionCredentials(context.modelId(), context.expectedChannel());
        if (credentials == null || credentials.apiKey() == null || credentials.apiKey().isBlank()
                || context.isCancelled()) return Optional.empty();
        HttpUrl base = HttpUrl.get(credentials.baseUrl());
        // Retain a reverse proxy's prefix; unknown routes are not safe to guess.
        String path = base.encodedPath().replaceAll("/+$", "");
        if (!path.endsWith("/api/v1")) return Optional.empty();
        HttpUrl endpoint = base.newBuilder()
                .encodedPath(path.substring(0, path.length() - "/api/v1".length()) + "/api/alpha/decisions")
                .query(null).fragment(null).build();
        Call call = http.newCall(new Request.Builder().url(endpoint)
                .header("Authorization", "Bearer " + credentials.apiKey())
                .post(RequestBody.create(body, MediaType.get("application/json"))).build());
        Runnable cancel = call::cancel;
        context.track(cancel);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) return Optional.empty();
            byte[] bytes = response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) return Optional.empty();
            JsonNode root = mapper.readTree(bytes);
            recordUsage(root, context, credentials.platform());
            if (context.isCancelled()) return Optional.empty();
            JsonNode answer = root.path("answers").path("decision");
            String choice = answer.path("choice").asText("");
            JsonNode confidence = answer.path("confidence");
            if (!"choice".equals(answer.path("type").asText()) || !criteria.containsKey(choice)
                    || !confidence.isNumber() || !Double.isFinite(confidence.asDouble())
                    || confidence.asDouble() < 0 || confidence.asDouble() > 1) return Optional.empty();
            return Optional.of(new Decision(choice, confidence.asDouble()));
        } finally {
            context.clear(cancel);
        }
    }

    private void recordUsage(JsonNode response, DecisionAssistContext context, boolean platform) {
        JsonNode receipt = response.path("usage");
        JsonNode input = receipt.path("input_tokens"), output = receipt.path("output_tokens");
        JsonNode cost = receipt.path("cost");
        if (!input.isIntegralNumber() || !input.canConvertToInt() || input.asInt() < 0
                || !output.isIntegralNumber() || !output.canConvertToInt() || output.asInt() < 0
                || (long) input.asInt() + output.asInt() > Integer.MAX_VALUE) return;
        BigDecimal amount = cost.isNumber() ? cost.decimalValue() : null;
        if (amount != null && amount.signum() < 0) amount = null;
        String model = response.path("model").asText(MODEL);
        if (!model.startsWith(MODEL) || model.length() > 100) model = MODEL;
        try {
            usage.recordDecisionUsage(context.projectId(), context.userId(), context.conversationId(), model,
                    input.asInt(), output.asInt(), amount, platform);
        } catch (Exception ignored) { /* Billing failure must not fail the user's conversation. */ }
    }

    @PreDestroy
    void close() {
        for (Runnable queued : decisions.shutdownNow()) {
            if (queued instanceof Future<?> future) future.cancel(true);
        }
        http.connectionPool().evictAll();
    }
}
