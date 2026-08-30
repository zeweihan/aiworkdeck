package com.checkba.service.ai.evidence;

import com.checkba.service.ai.mcp.McpClientService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

        // ==== 插件外部来源（规范 v2.8 P3）====
        EvidenceRetriever ext = stub("p1.registry");
        assertTrue(registry.registerExternal(ext));
        assertSame(ext, registry.find("p1.registry"));
        assertEquals(3, registry.all().size());

        // 重复 sourceId（与外部或内置冲突）拒绝，先到先得
        assertFalse(registry.registerExternal(stub("p1.registry")));
        assertFalse(registry.registerExternal(stub("memory")));
        assertSame(ext, registry.find("p1.registry"));

        registry.unregisterExternal("p1.registry");
        assertNull(registry.find("p1.registry"));
        assertTrue(registry.registerExternal(stub("p2.x")));
        registry.clearExternal();
        assertEquals(2, registry.all().size());
    }

    private static EvidenceRetriever stub(String sourceId) {
        return new EvidenceRetriever() {
            @Override public String sourceId() { return sourceId; }
            @Override public List<EvidenceItem> retrieve(EvidenceQuery query) { return List.of(); }
        };
    }
}
