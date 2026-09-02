package com.checkba.service.ai.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证为空时<b>一个字节都不发出去</b>（dev-board#395）。
 *
 * <p>病灶：打包态的桌面端没有 {@code PKULAW_TOKEN}，旧实现照样发一个
 * {@code Authorization: Bearer }（空 token），换回上游的
 * {@code 401 900902 Missing Credentials}——依据窗格里显示成一句像是上游故障的报错，
 * 用户会以为「过会儿再试就好了」，而真相是这台机器本来就没有这项凭证。
 */
class StreamableHttpMcpProviderCredentialTest {

    /** 收到的请求（含 Authorization 头）。空凭证那一趟这里必须是空的。 */
    private static final List<String> HITS = new ArrayList<>();

    private static McpProperties.ServerConfig server(String url) {
        McpProperties.ServerConfig c = new McpProperties.ServerConfig();
        c.setName("pkulaw-semantic");
        c.setUrl(url);
        c.setTimeoutSeconds(5);
        return c;
    }

    private HttpServer start() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", exchange -> {
            HITS.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        http.start();
        return http;
    }

    @Test
    @DisplayName("token 为空：不发请求（自然也没有 Authorization 头），返回可读的「没有凭证」")
    void blankTokenSendsNothing() throws Exception {
        HttpServer http = start();
        try {
            String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
            StreamableHttpMcpProvider provider = new StreamableHttpMcpProvider();

            String out = provider.callTool(server(url), "  ", "get_article", Map.of("title", "公司法"));

            assertEquals(McpProvider.NO_CREDENTIAL_PREFIX + "pkulaw-semantic", out);
            assertTrue(out.startsWith("Error"), "失败判据仍是 Error 前缀（调用方照此判）：" + out);
            assertTrue(HITS.isEmpty(), "空凭证时一个请求都不该发出去，实际发了：" + HITS);

            // 有凭证那一趟照旧：这条用例不能靠「provider 整个不工作」蒙混过关
            String ok = provider.callTool(server(url), "real-token", "get_article", Map.of());
            assertEquals("ok", ok);
            assertEquals(List.of("Bearer real-token"), HITS);
        } finally {
            http.stop(0);
            HITS.clear();
        }
    }
}
