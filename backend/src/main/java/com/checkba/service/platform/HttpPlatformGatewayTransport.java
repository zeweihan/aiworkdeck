package com.checkba.service.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** {@link PlatformGatewayTransport} 的生产实现：JDK HttpClient，响应超时由调用方按服务给。 */
@Component
@Slf4j
public class HttpPlatformGatewayTransport implements PlatformGatewayTransport {

    /** 连接超时与响应超时分开：连不上是快速失败，慢的是上游在干活。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 固定 HTTP/1.1，理由与 {@code HttpAccountTransport} 逐字相同：
     * JDK HttpClient 默认 HTTP_2，对明文地址会先发 h2c 升级请求，Next 开发服务器
     * 收到后直接不回字节，上层只看到一句「无法连接服务器」，本地联调必踩。
     */
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public Reply send(String method, String url, String bearerKey, String idempotencyKey,
                      String jsonBody, int timeoutSeconds) {
        try {
            HttpRequest.BodyPublisher body = jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .method(method, body);
            if (jsonBody != null) {
                builder.header("Content-Type", "application/json");
            }
            if (bearerKey != null) {
                builder.header("Authorization", "Bearer " + bearerKey);
            }
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(Reply.NETWORK_FAILURE, null);
        } catch (Exception e) {
            // 上层只给用户一句「服务暂时不可用」，具体死因必须留在日志里。
            // URL 可以打，Key 只在 header 里，不会随异常信息泄露。
            log.debug("网关请求失败 {} {}: {}", method, url, e.toString());
            return new Reply(Reply.NETWORK_FAILURE, null);
        }
    }
}
