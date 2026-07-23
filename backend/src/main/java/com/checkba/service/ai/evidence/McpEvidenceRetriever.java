package com.checkba.service.ai.evidence;

import com.checkba.service.ai.mcp.McpClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * evidence.retrieve.v1 的 MCP 传输适配器：契约不变，MCP 只负责传输。
 *
 * 远端 MCP 工具须返回契约 JSON：{"items":[{evidence_id, source_uri, locator, ...}]}。
 * 缺 evidence_id/source_uri/locator 的条目直接丢弃并告警——适配层不替远端编造定位符。
 * 来源不可用/权限被拒（返回 "Error: ..."）时返回空列表，不炸编排主流程。
 *
 * 非 Spring Bean：由 {@link EvidenceRetrieverRegistry} 按 ai.evidence.mcp-sources 配置逐个实例化。
 */
@Slf4j
public class McpEvidenceRetriever implements EvidenceRetriever {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sourceId;
    private final String server;
    private final String tool;
    private final McpClientService mcpClientService;

    public McpEvidenceRetriever(String sourceId, String server, String tool, McpClientService mcpClientService) {
        this.sourceId = sourceId;
        this.server = server;
        this.tool = tool;
        this.mcpClientService = mcpClientService;
    }

    @Override
    public String sourceId() {
        return "mcp:" + sourceId;
    }

    @Override
    public List<EvidenceItem> retrieve(EvidenceQuery query) {
        Map<String, Object> args = new HashMap<>();
        args.put("workspace_id", query.workspaceId());
        args.put("query", query.query());
        if (query.asOf() != null) {
            args.put("as_of", query.asOf().toString());
        }
        if (!query.sourceFilters().isEmpty()) {
            args.put("source_filters", query.sourceFilters());
        }
        if (!query.accessContext().isEmpty()) {
            args.put("access_context", query.accessContext());
        }
        args.put("limit", query.limit());

        String raw = mcpClientService.callTool(server, tool, args);
        if (raw == null || raw.startsWith("Error:")) {
            log.warn("evidence.retrieve.v1[{}]: MCP 来源不可用: {}", sourceId(), raw);
            return List.of();
        }
        return parseItems(raw);
    }

    private List<EvidenceItem> parseItems(String raw) {
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (Exception e) {
            log.warn("evidence.retrieve.v1[{}]: 响应不是合法 JSON，丢弃: {}", sourceId(), e.getMessage());
            return List.of();
        }
        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray()) {
            log.warn("evidence.retrieve.v1[{}]: 响应缺少 items 数组，丢弃", sourceId());
            return List.of();
        }
        List<EvidenceItem> items = new ArrayList<>();
        for (JsonNode n : itemsNode) {
            try {
                items.add(new EvidenceItem(
                        text(n, "evidence_id"),
                        text(n, "source_uri"),
                        text(n, "content_hash"),
                        parseDateTime(text(n, "retrieved_at")),
                        parseDate(text(n, "effective_date")),
                        text(n, "locator"),
                        text(n, "excerpt"),
                        text(n, "mime_type"),
                        text(n, "access_policy"),
                        stringList(n.path("provenance")),
                        text(n, "supersedes"),
                        text(n, "revokes"),
                        null));
            } catch (IllegalArgumentException e) {
                // 契约硬约束触发（多为缺 locator）：丢弃该条，不编造
                log.warn("evidence.retrieve.v1[{}]: 丢弃不合契约的条目: {}", sourceId(), e.getMessage());
            }
        }
        return items;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(s.replace("Z", ""));
        } catch (DateTimeParseException e) {
            return LocalDateTime.now();
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
