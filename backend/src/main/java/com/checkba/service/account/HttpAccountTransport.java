// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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

    /**
     * multipart/form-data 出站（头像上传，spec 2026-09-10 §5）。
     *
     * <p>JDK HttpClient 没有 multipart 编码器，这里手工拼一份**单部件**报文。
     * 分隔符用随机串：内容是用户挑的图片二进制，固定分隔符万一在图片里出现，
     * 服务端会在半截处截断，表现成一张莫名其妙损坏的头像。
     */
    @Override
    public Reply sendMultipart(String method, String url, String bearerKey, Multipart part) {
        try {
            String boundary = "----awdk" + java.util.UUID.randomUUID().toString().replace("-", "");
            String filename = part.filename() == null || part.filename().isBlank() ? "avatar" : part.filename();
            // 文件名里的引号/换行会破坏头部结构；只留一个安全的形态
            filename = filename.replaceAll("[\r\n\"\\\\]", "_");
            String contentType = part.contentType() == null || part.contentType().isBlank()
                    ? "application/octet-stream" : part.contentType();
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + part.fieldName() + "\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n";
            String tail = "\r\n--" + boundary + "--\r\n";
            byte[] body = concat(head.getBytes(StandardCharsets.UTF_8),
                    part.content() == null ? new byte[0] : part.content(),
                    tail.getBytes(StandardCharsets.UTF_8));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            if (bearerKey != null) {
                builder.header("Authorization", "Bearer " + bearerKey);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(Reply.NETWORK_FAILURE, null);
        } catch (Exception e) {
            log.debug("账户 multipart 请求失败 {} {}: {}", method, url, e.toString());
            return new Reply(Reply.NETWORK_FAILURE, null);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
