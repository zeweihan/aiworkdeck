package com.checkba.service.ai.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 服务器配置（Phase 3：MCP 标准化，服务器列表从硬编码外置到配置）。
 *
 * 配置前缀：mcp.servers，支持配置任意多个 MCP 服务器：
 *
 * <pre>
 * mcp:
 *   servers:
 *     - name: pkulaw-semantic
 *       url: https://apim-gateway.pkulaw.com/mcp-law-search-service
 *       token: ${PKULAW_TOKEN:}
 *       token-setting-key: external.pkulaw.token
 *       timeout-seconds: 60
 *       enabled: true
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    private List<ServerConfig> servers = new ArrayList<>();

    /** 按名称查找服务器配置，不存在返回 null */
    public ServerConfig findByName(String name) {
        if (name == null) return null;
        return servers.stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst()
                .orElse(null);
    }

    @Data
    public static class ServerConfig {
        /** 服务器唯一名称，工具层（如 LegalTools）用它路由 */
        private String name;
        /** MCP endpoint URL */
        private String url;
        /** 传输协议，对应 McpProvider.transport()；目前仅有 streamable-http */
        private String transport = StreamableHttpMcpProvider.TRANSPORT;
        /** 认证 token（Bearer），通常来自环境变量 */
        private String token = "";
        /** 系统设置里的 token 覆盖键（后台管理界面在线配置用），为空则直接用 token */
        private String tokenSettingKey;
        /** 单次请求超时（秒） */
        private int timeoutSeconds = 60;
        /** 启用开关 */
        private boolean enabled = true;
    }
}
