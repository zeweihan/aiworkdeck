package com.checkba.service.ai.evidence;

import com.checkba.service.ai.mcp.McpClientService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class EvidenceRetrieverRegistryTest {

    @Test
    void beanRetrieversAndConfiguredMcpSourcesAreRegistered() {
        EvidenceRetriever local = new EvidenceRetriever() {
            @Override public String sourceId() { return "memory"; }
            @Override public List<EvidenceItem> retrieve(EvidenceQuery query) { return List.of(); }
        };
        EvidenceProperties props = new EvidenceProperties();
        EvidenceProperties.McpSource src = new EvidenceProperties.McpSource();
        src.setSourceId("caselaw");
        src.setServer("law-server");
        src.setTool("retrieve_evidence");
        EvidenceProperties.McpSource broken = new EvidenceProperties.McpSource();
        broken.setSourceId("no-server");
        props.setMcpSources(List.of(src, broken));

        EvidenceRetrieverRegistry registry =
                new EvidenceRetrieverRegistry(List.of(local), props, mock(McpClientService.class));

        assertEquals(2, registry.all().size());
        assertNotNull(registry.find("memory"));
        assertNotNull(registry.find("mcp:caselaw"));
        assertNull(registry.find("mcp:no-server"));
        assertEquals("evidence.retrieve.v1", registry.find("memory").contractVersion());
    }
}
