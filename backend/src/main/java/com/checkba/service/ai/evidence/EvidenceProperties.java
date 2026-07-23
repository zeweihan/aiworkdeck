package com.checkba.service.ai.evidence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * evidence.retrieve.v1 的 MCP 来源配置（MCP 作为传输适配层接入证据契约）。
 *
 * <pre>
 * ai:
 *   evidence:
 *     mcp-sources:
 *       - source-id: caselaw
 *         server: some-mcp-server   # 必须已在 mcp.servers 里配置
 *         tool: retrieve_evidence   # 远端工具须按契约返回 {"items":[...]}
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "ai.evidence")
public class EvidenceProperties {

    private List<McpSource> mcpSources = new ArrayList<>();

    public List<McpSource> getMcpSources() { return mcpSources; }
    public void setMcpSources(List<McpSource> mcpSources) { this.mcpSources = mcpSources; }

    public static class McpSource {
        private String sourceId;
        private String server;
        private String tool;

        public String getSourceId() { return sourceId; }
        public void setSourceId(String sourceId) { this.sourceId = sourceId; }
        public String getServer() { return server; }
        public void setServer(String server) { this.server = server; }
        public String getTool() { return tool; }
        public void setTool(String tool) { this.tool = tool; }
    }
}
