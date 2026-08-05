package com.checkba.service.account;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** {@link AccountTransport} 的生产实现：JDK HttpClient，连接与响应各 5 秒超时。 */
@Component
@Slf4j
public class HttpAccountTransport implements AccountTransport {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * 固定 HTTP/1.1。JDK HttpClient 默认 HTTP_2，对明文地址会先发 h2c 升级请求——
     * Node/Next 的开发服务器收到后直接不回字节，客户端报「header parser received no bytes」，
     * 在上层看只是一句「无法连接服务器」，排查成本极高（本地联调实测踩到）。
     * 这里全是几 KB 的 JSON 往返，HTTP/2 没有任何收益，不值得为它留这个坑。
     */
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
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
            // 上层只会给用户一句「无法连接服务器」，具体死因必须留在日志里，否则无从排查。
            // URL 可以打，Key 只在 header 里，不会随异常信息泄露
            log.debug("账户请求失败 {} {}: {}", method, url, e.toString());
            return new Reply(Reply.NETWORK_FAILURE, null);
        }
    }
}
