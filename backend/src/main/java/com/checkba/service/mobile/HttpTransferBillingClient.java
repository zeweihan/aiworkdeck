package com.checkba.service.mobile;

import com.checkba.service.LangText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link TransferBillingClient} 的生产实现：POST {@code {base}/api/internal/transfer}，
 * 鉴权 header {@code X-Internal-Secret}，body {@code {action, accountId, bytes|ledgerId,
 * idempotencyKey, refId}}（dev-board#251，spec 2.4）。
 *
 * <p>base-url/secret 任一未配置（本地/桌面默认空）视为该服务器未开通跨设备传输计费，
 * 三个方法一律短路抛 DISABLED，不发请求——不静默放行、也不装作扣费成功。
 *
 * <p>网络失败（连不上/超时/中断，不含"连上了但业务报错"）charge/refund 各带同一幂等键
 * 重试一次再放弃；quote 是只读查询，失败直接报 UNAVAILABLE，不重试。
 */
@Component
@Slf4j
public class HttpTransferBillingClient implements TransferBillingClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String secret;
    private final ObjectMapper om;

    /** 固定 HTTP/1.1，理由与 HttpAccountTransport 逐字相同：明文地址走 h2c 升级会被 Next 开发服务器吞掉。 */
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public HttpTransferBillingClient(
            @Value("${mobile.transfer.billing.base-url:}") String baseUrl,
            @Value("${mobile.transfer.billing.secret:}") String secret,
            ObjectMapper om) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.om = om;
    }

    @Override
    public QuoteResult quote(String accountId, long bytes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "quote");
        body.put("accountId", accountId);
        body.put("bytes", bytes);
        JsonNode json = call(body);
        int credits = json.path("credits").asInt(0);
        Long balanceCents = json.hasNonNull("balanceCents") ? json.path("balanceCents").asLong() : null;
        return new QuoteResult(credits, balanceCents);
    }

    @Override
    public ChargeResult charge(String accountId, long bytes, String idempotencyKey, String refId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "charge");
        body.put("accountId", accountId);
        body.put("bytes", bytes);
        body.put("idempotencyKey", idempotencyKey);
        body.put("refId", refId);
        JsonNode json = callWithRetry(body);
        return new ChargeResult(json.path("credits").asInt(0),
                json.hasNonNull("ledgerId") ? json.path("ledgerId").asText() : null);
    }

    @Override
    public void refund(String accountId, String ledgerId, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "refund");
        body.put("accountId", accountId);
        body.put("ledgerId", ledgerId);
        body.put("idempotencyKey", idempotencyKey);
        callWithRetry(body);
    }

    // ==================== 内部 ====================

    private void requireConfigured() {
        if (baseUrl.isEmpty() || secret.isEmpty()) {
            throw new TransferBillingException(TransferBillingException.Kind.DISABLED,
                    LangText.of("跨设备传输未在此服务器开通", "Cross-device transfer is not enabled on this server"));
        }
    }

    /** quote 用：单次调用，网络失败不重试。 */
    private JsonNode call(Map<String, Object> body) {
        requireConfigured();
        try {
            return parse(send(writeJson(body)));
        } catch (NetworkFailure e) {
            log.warn("跨设备传输计费请求失败（网络）: {}", e.getCause() == null ? e.toString() : e.getCause().toString());
            throw unavailable();
        }
    }

    /** charge/refund 用：网络失败带同一幂等键（body 原样）重试一次。 */
    private JsonNode callWithRetry(Map<String, Object> body) {
        requireConfigured();
        String json = writeJson(body);
        try {
            return parse(send(json));
        } catch (NetworkFailure first) {
            try {
                return parse(send(json));
            } catch (NetworkFailure second) {
                log.warn("跨设备传输计费请求失败（网络，已重试一次）: {}",
                        second.getCause() == null ? second.toString() : second.getCause().toString());
                throw unavailable();
            }
        }
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return om.writeValueAsString(body);
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private HttpResponse<String> send(String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/internal/transfer"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new NetworkFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NetworkFailure(e);
        }
    }

    /** 2xx 按成功解析；409 no_credits 翻成 NO_CREDITS；其余一律 UNAVAILABLE（不回显上游细节给用户）。 */
    private JsonNode parse(HttpResponse<String> resp) {
        int status = resp.statusCode();
        if (status == 200) {
            try {
                return om.readTree(resp.body());
            } catch (Exception e) {
                log.warn("跨设备传输计费响应解析失败: body={}", resp.body());
                throw unavailable();
            }
        }
        if (status == 409) {
            try {
                JsonNode json = om.readTree(resp.body());
                if ("no_credits".equals(json.path("error").asText())) {
                    Integer required = json.hasNonNull("requiredCredits") ? json.path("requiredCredits").asInt()
                            : (json.hasNonNull("requiredCents") ? json.path("requiredCents").asInt() : null);
                    Long available = json.hasNonNull("availableCents") ? json.path("availableCents").asLong() : null;
                    throw new TransferBillingException(TransferBillingException.Kind.NO_CREDITS,
                            LangText.of("Credits 余额不足，请前往官网充值后重试",
                                    "Insufficient Credits balance. Please top up on the website and try again."),
                            required, available);
                }
            } catch (TransferBillingException e) {
                throw e;
            } catch (Exception ignored) {
                // 409 体解析不出来按未知错误处理，落到下面的 UNAVAILABLE
            }
        }
        log.warn("跨设备传输计费调用失败: status={}, body={}", status, resp.body());
        throw unavailable();
    }

    private TransferBillingException unavailable() {
        return new TransferBillingException(TransferBillingException.Kind.UNAVAILABLE,
                LangText.of("计费服务暂不可用，请稍后再试", "Billing service is temporarily unavailable, please try again later"));
    }

    /** send() 内部标记网络层失败（连不上/超时/中断），与"连上了但业务报错"区分开，只有前者会重试。 */
    private static class NetworkFailure extends RuntimeException {
        NetworkFailure(Throwable cause) {
            super(cause);
        }
    }
}
