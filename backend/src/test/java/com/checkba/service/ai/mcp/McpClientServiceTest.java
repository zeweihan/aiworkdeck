package com.checkba.service.ai.mcp;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * McpClientService 单元测试：配置路由 / 启用开关 / token 在线覆盖 / 传输协议分发。
 */
class McpClientServiceTest {

    /** 记录调用参数的假 Provider */
    static class RecordingProvider implements McpProvider {
        McpProperties.ServerConfig lastServer;
        String lastToken;
        String lastToolName;
        Map<String, Object> lastArgs;

        @Override
        public String transport() {
            return StreamableHttpMcpProvider.TRANSPORT;
        }

        @Override
        public String callTool(McpProperties.ServerConfig server, String token, String toolName, Map<String, Object> args) {
            this.lastServer = server;
            this.lastToken = token;
            this.lastToolName = toolName;
            this.lastArgs = args;
            return "provider-result";
        }
    }

    private McpProperties properties;
    private SystemSettingService systemSettingService;
    private RecordingProvider provider;
    private McpClientService service;

    private McpProperties.ServerConfig server(String name) {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName(name);
        config.setUrl("https://example.com/" + name);
        return config;
    }

    @BeforeEach
    void setUp() {
        properties = new McpProperties();
        systemSettingService = Mockito.mock(SystemSettingService.class);
        provider = new RecordingProvider();
        service = new McpClientService(properties, systemSettingService, List.of(provider));
    }

    @Test
    @DisplayName("未知服务器：返回错误文本，不触碰 Provider")
    void unknownServer() {
        String result = service.callTool("no-such-server", "some_tool", Map.of());
        assertEquals("Error: Unknown MCP server: no-such-server", result);
        assertNull(provider.lastToolName);
    }

    @Test
    @DisplayName("禁用的服务器：返回错误文本，不触碰 Provider")
    void disabledServer() {
        McpProperties.ServerConfig config = server("pkulaw-semantic");
        config.setEnabled(false);
        properties.getServers().add(config);

        String result = service.callTool("pkulaw-semantic", "search_article", Map.of("query", "合同"));
        assertEquals("Error: MCP server is disabled: pkulaw-semantic", result);
        assertNull(provider.lastToolName);
    }

    @Test
    @DisplayName("未支持的传输协议：返回错误文本")
    void unsupportedTransport() {
        McpProperties.ServerConfig config = server("future-server");
        config.setTransport("stdio");
        properties.getServers().add(config);

        String result = service.callTool("future-server", "some_tool", Map.of());
        assertEquals("Error: Unsupported MCP transport: stdio", result);
    }

    @Test
    @DisplayName("配置了 token-setting-key：系统设置在线覆盖，配置值作为默认值")
    void tokenSettingKeyOverride() {
        McpProperties.ServerConfig config = server("pkulaw-semantic");
        config.setToken("env-token");
        config.setTokenSettingKey("external.pkulaw.token");
        properties.getServers().add(config);
        when(systemSettingService.get("external.pkulaw.token", "env-token")).thenReturn("db-token");

        String result = service.callTool("pkulaw-semantic", "search_article", Map.of("query", "公司决议"));

        assertEquals("provider-result", result);
        assertEquals("db-token", provider.lastToken);
        assertEquals("search_article", provider.lastToolName);
        assertEquals("公司决议", provider.lastArgs.get("query"));
        assertSame(config, provider.lastServer);
    }

    @Test
    @DisplayName("未配置 token-setting-key：直接用配置 token，不查系统设置")
    void configuredTokenWithoutSettingKey() {
        McpProperties.ServerConfig config = server("other-mcp");
        config.setToken("static-token");
        properties.getServers().add(config);

        service.callTool("other-mcp", "any_tool", Map.of());

        assertEquals("static-token", provider.lastToken);
        verify(systemSettingService, never()).get(anyString(), anyString());
    }

    @Test
    @DisplayName("token 为 null：解析为空串而不是 NPE")
    void nullTokenBecomesEmpty() {
        McpProperties.ServerConfig config = server("no-token-mcp");
        config.setToken(null);
        properties.getServers().add(config);

        service.callTool("no-token-mcp", "any_tool", Map.of());

        assertEquals("", provider.lastToken);
    }
}
