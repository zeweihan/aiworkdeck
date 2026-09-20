// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 官方案件库内部口的出站客户端（dev-board#720，spec §7.1）：插件云后端经 127.0.0.1 向 case 实例
 * POST {@code /api/internal/ref/list} 与 {@code /api/internal/ref/read}，鉴权头 {@code X-Internal-Secret}。
 *
 * <p>形状与超时照 {@code HttpAccountDirectoryClient}：固定 HTTP/1.1（明文地址走 h2c 升级会被某些
 * 反代吞掉）、不跟随重定向、不重试——律师就在窗格前面等着，失败让他再问一次比闷头等两个超时周期强。
 *
 * <p>base-url / secret 任一为空 = 本服务器没有案件库（国际站与自建服务器的默认态），
 * {@link #configured()} 回 false，{@link CaseLibrarySource} 据此整块缺席，<b>一次请求都不发</b>。
 *
 * <p>失败分两档，绝不能混：案件库那侧说得出原因的（{@code code != 0} + message，例如「你没有读取
 * 权限」「这份文件超过 50MB」）抛 {@link CaseRefException}，原样转述给律师；传输故障、裸 404
 * （密钥未配 / 不符 / nginx 兜底）、响应解析不了一律抛普通 {@link IOException}，上层只说「暂时无法
 * 访问」——把这些说成业务原因会引着律师去改一个根本没问题的权限。
 *
 * <p>红线：参考材料正文只在内存里过一遍，日志只记路径与长度，绝不记正文。
 */
@Component
@Slf4j
public class CaseRefClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final String LIST_PATH = "/api/internal/ref/list";
    private static final String READ_PATH = "/api/internal/ref/read";

    private final String baseUrl;
    private final String secret;
    private final ObjectMapper om;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public CaseRefClient(@Value("${ref.case.base-url:}") String baseUrl,
                         @Value("${ref.internal.secret:}") String secret,
                         ObjectMapper om) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.om = om;
    }

    /** 本服务器是否连着案件库（base-url 与 secret 都配了）。 */
    public boolean configured() {
        return !baseUrl.isEmpty() && !secret.isEmpty();
    }

    /**
     * 该官网账号在案件库里能看到的文件。
     *
     * @param keyword 文件名关键字，可为 null（不过滤）
     */
    public List<Entry> list(String externalAccountId, String keyword) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalAccountId", externalAccountId);
        if (keyword != null && !keyword.isBlank()) {
            body.put("keyword", keyword);
        }
        JsonNode json = call(LIST_PATH, body);
        List<Entry> out = new ArrayList<>();
        for (JsonNode n : json.path("entries")) {
            out.add(new Entry(n.path("remoteProjectId").asLong(), text(n, "projectName"),
                    text(n, "path"), text(n, "name")));
        }
        return out;
    }

    /** 案件库里某个案卷最新一版里那份文件的纯文本。 */
    public String read(String externalAccountId, long remoteProjectId, String path) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalAccountId", externalAccountId);
        body.put("remoteProjectId", remoteProjectId);
        body.put("path", path);
        return text(call(READ_PATH, body), "text");
    }

    // ==================== 内部 ====================

    private JsonNode call(String path, Map<String, Object> body) throws IOException {
        if (!configured()) {
            // 上层本该先看 configured()；真走到这里也不能静默放行
            throw new IOException("本服务器未配置 ref.case.base-url / ref.internal.secret");
        }
        String json = om.writeValueAsString(body);
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("案件库请求被中断", e);
        }
        if (resp.statusCode() != 200) {
            // 裸 404 = 案件库未配密钥 / 密钥不符 / nginx 兜底；这不是"没这个文件"
            log.warn("案件库内部口调用失败: path={}, status={}", path, resp.statusCode());
            throw new IOException("案件库返回 " + resp.statusCode());
        }
        JsonNode node;
        try {
            node = om.readTree(resp.body());
        } catch (Exception e) {
            log.warn("案件库响应解析失败: path={}", path);
            throw new IOException("案件库响应无法解析", e);
        }
        int code = node.path("code").asInt(-1);
        if (code != 0) {
            String message = text(node, "message");
            throw new CaseRefException(message == null || message.isBlank()
                    ? "案件库没能读取这份文件，请稍后再试。" : message);
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asText();
    }

    /**
     * 案件库里的一份文件。
     *
     * @param remoteProjectId 案件库那一侧的案卷 id（本机数字 id 与它毫无关系）
     */
    public record Entry(long remoteProjectId, String projectName, String path, String name) {
    }

    /** 案件库那侧说得出原因的失败：message 是写给律师看的完整句子，可原样转述。 */
    public static class CaseRefException extends IOException {
        public CaseRefException(String message) {
            super(message);
        }
    }
}
