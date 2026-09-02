package com.checkba.service.ai.mcp;

import cn.hutool.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * "streamableHttp"（JSON-RPC over HTTP POST）传输实现。
 *
 * 为什么是手写实现而非官方 SDK / langchain4j-mcp：
 * - 官方 MCP Java SDK 的 SSE 传输与 PKULaw 网关的 endpoint 不兼容；
 * - langchain4j-mcp 模块最低版本为 1.0.0-alpha1，与当前 langchain4j 0.36.0 全家桶
 *   存在破坏性 API 差异，不值得为此整体升级。
 */
@Component
public class StreamableHttpMcpProvider implements McpProvider {

    public static final String TRANSPORT = "streamable-http";

    private static final Logger log = LoggerFactory.getLogger(StreamableHttpMcpProvider.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    public StreamableHttpMcpProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public String transport() {
        return TRANSPORT;
    }

    @Override
    public String callTool(McpProperties.ServerConfig server, String token, String toolName, Map<String, Object> args) {
        // 凭证为空就不发这一趟：空 Bearer 只会换回上游的 401 Missing Credentials，
        // 把「这台机器没配凭证」说成一次上游故障（见 NO_CREDENTIAL_PREFIX）
        if (token == null || token.isBlank()) {
            log.warn("MCP server 没有可用凭证，跳过调用: server={}, tool={}", server.getName(), toolName);
            return NO_CREDENTIAL_PREFIX + server.getName();
        }
        try {
            JSONObject params = new JSONObject();
            params.set("name", toolName);
            params.set("arguments", args);

            JSONObject requestBody = new JSONObject();
            requestBody.set("jsonrpc", "2.0");
            requestBody.set("method", "tools/call");
            requestBody.set("params", params);
            requestBody.set("id", UUID.randomUUID().toString());

            String jsonPayload = requestBody.toString();
            log.debug("MCP Payload: {}", jsonPayload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(server.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .timeout(Duration.ofSeconds(server.getTimeoutSeconds()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            // Log RAW response for debugging - Critical for diagnosing format mismatches
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("MCP Raw Response (Success): {}", responseBody);
            } else {
                log.error("MCP Raw Response (Error {}): {}", response.statusCode(), responseBody);
                return "Error calling MCP server (" + response.statusCode() + "): " + responseBody;
            }

            return McpResponseParser.parse(responseBody);

        } catch (Exception e) {
            log.error("MCP tool call failed: server={}, tool={}, error={}", server.getName(), toolName, e.getMessage(), e);
            return "Error calling MCP tool: " + e.getMessage();
        }
    }
}
