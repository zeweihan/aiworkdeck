package com.checkba.service.account;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** {@link AccountTransport} 的生产实现：JDK HttpClient，连接与响应各 5 秒超时。 */
@Component
public class HttpAccountTransport implements AccountTransport {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public Reply send(String method, String url, String bearerKey, String jsonBody) {
        try {
            HttpRequest.BodyPublisher body = jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .method(method, body);
            if (jsonBody != null) {
                builder.header("Content-Type", "application/json");
            }
            if (bearerKey != null) {
                builder.header("Authorization", "Bearer " + bearerKey);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(Reply.NETWORK_FAILURE, null);
        } catch (Exception e) {
            // 异常信息里可能夹带 URL，但绝不会有 Key（Key 只在 header 里），可安全丢弃
            return new Reply(Reply.NETWORK_FAILURE, null);
        }
    }
}
