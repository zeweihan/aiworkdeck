package com.checkba.service.ai.evidence;

import com.checkba.plugin.api.evidence.EvidenceItem;
import com.checkba.plugin.api.evidence.EvidenceProvider;
import com.checkba.plugin.api.evidence.EvidenceProviderConformanceKit;
import com.checkba.plugin.api.evidence.EvidenceQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** conformance 执行器自检：合格实现全绿；违反幂等/前缀/抛异常的实现被点名。 */
class EvidenceProviderConformanceKitTest {

    private static EvidenceQuery query() {
        return new EvidenceQuery("1", "章程", null, List.of(), Map.of(), 5);
    }

    private static EvidenceItem item(String id, String hash) {
        return new EvidenceItem(id, "https://example.com", hash, null, null,
                "row#1", null, null, null, List.of(), null, null, null);
    }

    @Test
    @DisplayName("合格实现：零失败")
    void wellBehavedPasses() {
        EvidenceProvider good = new EvidenceProvider() {
            @Override public String sourceId() { return "demo.registry"; }
            @Override public List<EvidenceItem> retrieve(EvidenceQuery q) {
                if (!"1".equals(q.workspaceId())) return List.of();
                return List.of(item("e1", "h1"), item("e2", "h2"));
            }
        };
        assertEquals(List.of(), EvidenceProviderConformanceKit.run(good, query()));
    }

    @Test
    @DisplayName("不幂等 / 缺前缀 / 未知工作区抛异常：逐条点名")
    void violationsAreReported() {
        EvidenceProvider bad = new EvidenceProvider() {
            @Override public String sourceId() { return "noprefix"; }
            @Override public List<EvidenceItem> retrieve(EvidenceQuery q) {
                if (!"1".equals(q.workspaceId())) throw new IllegalStateException("boom");
                return List.of(item("e-" + UUID.randomUUID(), "h"));
            }
        };
        List<String> failures = EvidenceProviderConformanceKit.run(bad, query());
        assertTrue(failures.stream().anyMatch(f -> f.contains("前缀")), failures.toString());
        assertTrue(failures.stream().anyMatch(f -> f.contains("幂等")), failures.toString());
        assertTrue(failures.stream().anyMatch(f -> f.contains("未知工作区")), failures.toString());
    }
}
