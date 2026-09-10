// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

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
 * {@link AccountDirectoryClient} 的生产实现：POST {@code {base}/api/internal/collab-directory}，
 * 鉴权 header {@code X-Internal-Secret}，body
 * {@code {requesterAccountId, identifier | candidateAccountId}}（spec 2026-09-10 §4）。
 *
 * <p>形状与超时逐条照 {@code HttpTransferBillingClient}：同机回环直连 Next、固定 HTTP/1.1
 * （明文地址走 h2c 升级会被 Next 开发服务器吞掉）、15s/10s 超时、不跟随重定向。
 *
 * <p>base-url / secret 任一为空 = 本服务器没开通名录（自建服务器、桌面单机的默认态），
 * {@link #configured()} 回 false，上层维持"只查本库"的今天行为，<b>一次请求都不发</b>。
 *
 * <p><b>不重试</b>：这是一次只读查询，律师就在界面前面等着；失败让他自己再点一次，
 * 比在请求里闷头等两个超时周期强（同 quote 的处理）。
 */
@Component
@Slf4j
public class HttpAccountDirectoryClient implements AccountDirectoryClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String secret;
    private final ObjectMapper om;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public HttpAccountDirectoryClient(
            @Value("${collab.directory.base-url:}") String baseUrl,
            @Value("${collab.directory.secret:}") String secret,
            ObjectMapper om) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.om = om;
    }

    @Override
    public boolean configured() {
        return !baseUrl.isEmpty() && !secret.isEmpty();
    }

    @Override
    public DirectoryReply lookupByIdentifier(String requesterAccountId, String identifier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requesterAccountId", requesterAccountId);
        body.put("identifier", identifier);
        return call(body);
    }

    @Override
    public DirectoryReply lookupByAccountId(String requesterAccountId, String candidateAccountId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requesterAccountId", requesterAccountId);
        body.put("candidateAccountId", candidateAccountId);
        return call(body);
    }

    // ==================== 内部 ====================

    private DirectoryReply call(Map<String, Object> body) {
        if (!configured()) {
            // 上层本该先看 configured()；真走到这里也不能静默放行
            throw new DirectoryUnavailableException("本服务器未配置 collab.directory.base-url / secret");
        }
        String json;
        try {
            json = om.writeValueAsString(body);
        } catch (Exception e) {
            throw new DirectoryUnavailableException("名录请求体序列化失败", e);
        }
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/internal/collab-directory"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("同事名录请求失败（网络）: {}", e.toString());
            throw new DirectoryUnavailableException("名录不可达", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirectoryUnavailableException("名录请求被中断", e);
        }
        if (resp.statusCode() != 200) {
            // 裸 404 = 官网未配 secret / 密钥不符 / nginx 兜底；400 = 请求体不合法（编程错误）。
            // 两者都不是"没找到这个人"，都得让上层红字。
            log.warn("同事名录调用失败: status={}, body={}", resp.statusCode(), resp.body());
            throw new DirectoryUnavailableException("名录返回 " + resp.statusCode());
        }
        JsonNode json2;
        try {
            json2 = om.readTree(resp.body());
        } catch (Exception e) {
            log.warn("同事名录响应解析失败: body={}", resp.body());
            throw new DirectoryUnavailableException("名录响应无法解析", e);
        }
        return parse(json2);
    }

    private static DirectoryReply parse(JsonNode json) {
        OrgMembership requester = membership(json.get("requester"));
        if (!json.path("found").asBoolean(false)) {
            return new DirectoryReply(false, null, requester, OrgMembership.NONE);
        }
        JsonNode acc = json.get("account");
        String accountId = text(acc, "accountId");
        if (accountId == null || accountId.isBlank()) {
            // found 却没有 accountId 就建不出桥接用户；这是上游坏了，不是"没这个人"
            throw new DirectoryUnavailableException("名录回了 found 却缺 accountId");
        }
        DirectoryAccount account = new DirectoryAccount(accountId,
                text(acc, "username"), text(acc, "displayName"), text(acc, "phone"));
        return new DirectoryReply(true, account, requester, membership(json.get("candidate")));
    }

    private static OrgMembership membership(JsonNode node) {
        String teamId = text(node, "teamId");
        String firmId = text(node, "firmId");
        return teamId == null && firmId == null ? OrgMembership.NONE : new OrgMembership(teamId, firmId);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String v = node.get(field).asText();
        return v == null || v.isBlank() ? null : v;
    }
}
