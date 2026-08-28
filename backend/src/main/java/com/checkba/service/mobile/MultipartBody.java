package com.checkba.service.mobile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 手搓 multipart/form-data 请求体——JDK HttpClient 没有内置 multipart 支持，
 * PULL 命令回传文件字节要用它拼 file 字段（dev-board#251 B 侧）。
 *
 * <p>只服务单文件字段这一个场景，不追求通用 multipart 构造器。{@link #filePublisher}
 * 把 header 字节、原始文件流、footer 字节用 {@link SequenceInputStream} 首尾相接，
 * 不把整份文件读进内存——传输上限虽然是 200MB，但没必要为此多占一份堆内存。
 */
final class MultipartBody {

    private MultipartBody() {
    }

    static String newBoundary() {
        return "----awd-" + UUID.randomUUID();
    }

    /**
     * 拼一个单文件字段的 BodyPublisher。fileStream 只会被读一次——上层遇到需要重试的情况
     * （比如鉴权被拒）不能重放这个 publisher，得整条命令留到下一轮重新打开一个新的流。
     */
    static HttpRequest.BodyPublisher filePublisher(String boundary, String fieldName,
                                                     String fileName, InputStream fileStream) {
        byte[] header = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\""
                + fileName.replace("\"", "") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        InputStream combined = new SequenceInputStream(
                new SequenceInputStream(new ByteArrayInputStream(header), fileStream),
                new ByteArrayInputStream(footer));
        return HttpRequest.BodyPublishers.ofInputStream(() -> combined);
    }
}
