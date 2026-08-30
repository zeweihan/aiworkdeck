package com.checkba.service.ai.evidence;

import com.checkba.plugin.api.evidence.EvidenceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件 SPI Provider 适配器（规范 v2.8 P3）：字段映射 / 禁用静默 / 异常与超时降级 /
 * null 条目丢弃。公开 record 的三必填由构造器强制，适配层不需要重复测。
 */
class PluginSpiEvidenceRetrieverTest {

    private static com.checkba.plugin.api.evidence.EvidenceItem publicItem(String id) {
        return new com.checkba.plugin.api.evidence.EvidenceItem(
                id, "https://example.com/doc", "hash-" + id, null, null,
                "para#3", "摘录", "text/plain", "project", List.of("step1"), null, null, null);
    }

    private static EvidenceProvider provider(String sourceId, List<com.checkba.plugin.api.evidence.EvidenceItem> items) {
        return new EvidenceProvider() {
            @Override public String sourceId() { return sourceId; }
            @Override public List<com.checkba.plugin.api.evidence.EvidenceItem> retrieve(
                    com.checkba.plugin.api.evidence.EvidenceQuery query) { return items; }
        };
    }

    private static EvidenceQuery query() {
        return new EvidenceQuery("42", "劳动合同", null, List.of(), Map.of("userId", "7"), 10);
    }

    @Test
    @DisplayName("字段逐一映射到内部 EvidenceItem；null 条目跳过")
    void mapsFields() {
        var items = new java.util.ArrayList<com.checkba.plugin.api.evidence.EvidenceItem>();
        items.add(publicItem("e1"));
        items.add(null);
        var r = new PluginSpiEvidenceRetriever("demo.src", provider("demo.src", items), () -> true);
        List<EvidenceItem> out = r.retrieve(query());
        assertEquals(1, out.size());
        EvidenceItem it = out.get(0);
        assertEquals("e1", it.evidenceId());
        assertEquals("https://example.com/doc", it.sourceUri());
        assertEquals("para#3", it.locator());
        assertEquals("hash-e1", it.contentHash());
        assertEquals(List.of("step1"), it.provenance());
        assertEquals("demo.src", r.sourceId());
    }

    @Test
    @DisplayName("插件禁用：不调 provider，直接空列表")
    void disabledIsSilent() {
        EvidenceProvider boom = new EvidenceProvider() {
            @Override public String sourceId() { return "demo.src"; }
            @Override public List<com.checkba.plugin.api.evidence.EvidenceItem> retrieve(
                    com.checkba.plugin.api.evidence.EvidenceQuery query) {
                throw new AssertionError("禁用时不应触达 provider");
            }
        };
        var r = new PluginSpiEvidenceRetriever("demo.src", boom, () -> false);
        assertEquals(List.of(), r.retrieve(query()));
    }

    @Test
    @DisplayName("provider 抛异常 / 返回 null：空列表降级，不炸编排")
    void degradesOnFailure() {
        EvidenceProvider throwing = provider("demo.src", null);
        assertEquals(List.of(), new PluginSpiEvidenceRetriever("demo.src", throwing, () -> true).retrieve(query()));
        EvidenceProvider boom = new EvidenceProvider() {
            @Override public String sourceId() { return "demo.src"; }
            @Override public List<com.checkba.plugin.api.evidence.EvidenceItem> retrieve(
                    com.checkba.plugin.api.evidence.EvidenceQuery query) {
                throw new IllegalStateException("upstream down");
            }
        };
        assertEquals(List.of(), new PluginSpiEvidenceRetriever("demo.src", boom, () -> true).retrieve(query()));
    }

    @Test
    @DisplayName("超时：按空列表降级")
    void degradesOnTimeout() {
        EvidenceProvider slow = new EvidenceProvider() {
            @Override public String sourceId() { return "demo.src"; }
            @Override public List<com.checkba.plugin.api.evidence.EvidenceItem> retrieve(
                    com.checkba.plugin.api.evidence.EvidenceQuery query) {
                try { Thread.sleep(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return List.of(publicItem("late"));
            }
        };
        var r = new PluginSpiEvidenceRetriever("demo.src", slow, () -> true, 100);
        assertEquals(List.of(), r.retrieve(query()));
    }
}
