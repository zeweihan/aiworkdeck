package com.checkba.service.ai.mcp;

import com.checkba.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MCP 客户端门面：按「服务器名 → 配置 → 传输协议」路由工具调用。
 *
 * - 服务器列表来自配置（{@link McpProperties}，前缀 mcp.servers），不再硬编码；
 * - 协议细节（请求编码、响应解析）在 {@link McpProvider} 实现内；
 * - token 支持系统设置在线覆盖（配置项 token-setting-key，后台管理界面写入）。
 */
@Service
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

    private final McpProperties mcpProperties;
    private final SystemSettingService systemSettingService;
    private final Map<String, McpProvider> providersByTransport;

    public McpClientService(McpProperties mcpProperties,
                            SystemSettingService systemSettingService,
                            List<McpProvider> providers) {
        this.mcpProperties = mcpProperties;
        this.systemSettingService = systemSettingService;
        this.providersByTransport = providers.stream()
                .collect(Collectors.toMap(McpProvider::transport, Function.identity()));
    }

    /**
     * Call a tool on the specified MCP server.
     *
     * @param serverName The name of the server (e.g., "pkulaw-semantic")
     * @param toolName   The name of the tool to call
     * @param args       The arguments to pass to the tool
     * @return The result as a string
     */
    public String callTool(String serverName, String toolName, Map<String, Object> args) {
        log.info("MCP callTool: server={}, tool={}, args={}", serverName, toolName, args);

        McpProperties.ServerConfig server = mcpProperties.findByName(serverName);
        if (server == null) {
            return "Error: Unknown MCP server: " + serverName;
        }
        if (!server.isEnabled()) {
            return "Error: MCP server is disabled: " + serverName;
        }

        McpProvider provider = providersByTransport.get(server.getTransport());
        if (provider == null) {
            return "Error: Unsupported MCP transport: " + server.getTransport();
        }

        return provider.callTool(server, resolveToken(server), toolName, args);
    }

    /**
     * 用调用方自带的服务器配置调工具（规范 v2.8 P3：插件 manifest 声明的远程 MCP
     * 证据来源不在 mcp.servers 静态表里，宿主按声明现构 ServerConfig 走这里）。
     * token 解析与 provider 分发与静态表路径完全一致。
     */
    public String callTool(McpProperties.ServerConfig server, String toolName, Map<String, Object> args) {
        log.info("MCP callTool (ad-hoc): server={}, tool={}", server.getName(), toolName);
        if (!server.isEnabled()) {
            return "Error: MCP server is disabled: " + server.getName();
        }
        McpProvider provider = providersByTransport.get(server.getTransport());
        if (provider == null) {
            return "Error: Unsupported MCP transport: " + server.getTransport();
        }
        return provider.callTool(server, resolveToken(server), toolName, args);
    }

    /** token 解析：配置了 token-setting-key 时优先取系统设置（在线覆盖），否则用配置值 */
    private String resolveToken(McpProperties.ServerConfig server) {
        String configured = server.getToken() == null ? "" : server.getToken();
        if (StringUtils.hasText(server.getTokenSettingKey())) {
            return systemSettingService.get(server.getTokenSettingKey(), configured);
        }
        return configured;
    }
}
