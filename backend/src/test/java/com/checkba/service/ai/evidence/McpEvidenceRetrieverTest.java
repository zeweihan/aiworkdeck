package com.checkba.service.ai.evidence;

import com.checkba.service.ai.mcp.McpClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * evidence.retrieve.v1 的 MCP 传输适配器契约测试：
 * MCP 只是传输——契约字段编解码、缺定位符丢弃、来源不可用降级、幂等重放。
 */
class McpEvidenceRetrieverTest {

    private McpClientService mcp;
    private McpEvidenceRetriever retriever;

    private static final String VALID_RESPONSE = """
            {"items":[
              {"evidence_id":"case-001","source_uri":"https://law.example/case/1",
               "content_hash":"abc123","effective_date":"2026-01-15",
               "locator":"para-12","excerpt":"判决要旨……","mime_type":"text/html",
               "access_policy":"public","provenance":["court-db","mcp:caselaw"],
               "supersedes":"case-000"}
            ]}""";

    @BeforeEach
    void setUp() {
        mcp = mock(McpClientService.class);
        retriever = new McpEvidenceRetriever("caselaw", "law-server", "retrieve_evidence", mcp);
    }

    private static EvidenceQuery query() {
        return new EvidenceQuery("project:7", "竞业限制", LocalDate.of(2026, 7, 1),
                List.of("court"), Map.of("userId", "1"), 5);
    }

    @Test
    void requestCarriesAllContractFields() {
        when(mcp.callTool(anyString(), anyString(), any())).thenReturn("{\"items\":[]}");

        retriever.retrieve(query());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mcp).callTool(eq("law-server"), eq("retrieve_evidence"), captor.capture());
        Map<String, Object> args = captor.getValue();
        assertEquals("project:7", args.get("workspace_id"));
        assertEquals("竞业限制", args.get("query"));
        assertEquals("2026-07-01", args.get("as_of"));
        assertEquals(List.of("court"), args.get("source_filters"));
        assertEquals(Map.of("userId", "1"), args.get("access_context"));
        assertEquals(5, args.get("limit"));
    }

    @Test
    void wellFormedResponseIsParsed() {
        when(mcp.callTool(anyString(), anyString(), any())).thenReturn(VALID_RESPONSE);

        List<EvidenceItem> items = retriever.retrieve(query());

        assertEquals(1, items.size());
        EvidenceItem item = items.get(0);
        assertEquals("case-001", item.evidenceId());
        assertEquals("para-12", item.locator());
        assertEquals("case-000", item.supersedes());
        assertEquals(LocalDate.of(2026, 1, 15), item.effectiveDate());
        assertEquals(List.of("court-db", "mcp:caselaw"), item.provenance());
        assertEquals("mcp:caselaw", retriever.sourceId());
    }

    @Test
    void missingLocator_itemIsDroppedNotFabricated() {
        when(mcp.callTool(anyString(), anyString(), any())).thenReturn("""
                {"items":[
                  {"evidence_id":"no-locator","source_uri":"https://x","excerpt":"没有定位符"},
                  {"evidence_id":"ok","source_uri":"https://y","locator":"p3","excerpt":"完整"}
                ]}""");

        List<EvidenceItem> items = retriever.retrieve(query());

        assertEquals(1, items.size());
        assertEquals("ok", items.get(0).evidenceId());
    }

    @Test
    void revokedAccess_errorResponseDegradesToEmpty() {
        when(mcp.callTool(anyString(), anyString(), any())).thenReturn("Error: MCP server is disabled: law-server");
        assertTrue(retriever.retrieve(query()).isEmpty());
    }

    @Test
    void malformedJsonDegradesToEmpty() {
        when(mcp.callTool(anyString(), anyString(), any())).thenReturn("<html>gateway timeout</html>");
        assertTrue(retriever.retrieve(query()).isEmpty());
    }

    @Test
    void idempotentReplay_sameIdsAndHashes() {
        when(mcp.callTool(anyString(), anyString(), any())).thenReturn(VALID_RESPONSE);

        List<EvidenceItem> first = retriever.retrieve(query());
        List<EvidenceItem> second = retriever.retrieve(query());

        assertEquals(
                first.stream().map(EvidenceItem::evidenceId).toList(),
                second.stream().map(EvidenceItem::evidenceId).toList());
        assertEquals(
                first.stream().map(EvidenceItem::contentHash).toList(),
                second.stream().map(EvidenceItem::contentHash).toList());
    }
}
