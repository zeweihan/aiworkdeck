package com.checkba.service.ai.evidence;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.service.ai.memory.MemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * evidence.retrieve.v1 本地实现（记忆账本）的契约一致性测试。
 * 覆盖 RFC #14 讨论中点名的场景：文档变更 / 来源冲突 / 缺定位符 / 权限吊销 / 幂等重放。
 */
class MemoryEvidenceRetrieverTest {

    private MemoryManager memoryManager;
    private MemoryEvidenceRetriever retriever;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 6, 1, 10, 0);

    @BeforeEach
    void setUp() {
        memoryManager = mock(MemoryManager.class);
        when(memoryManager.findSupersededInfo(any())).thenReturn(Map.of());
        retriever = new MemoryEvidenceRetriever(memoryManager);
    }

    private static MemoryEntry entry(long id, String key, String value) {
        return MemoryEntry.builder()
                .id(id).projectId(7L).memoryType("fact")
                .memoryKey(key).memoryValue(value)
                .createdAt(T0)
                .build();
    }

    private static EvidenceQuery query() {
        return new EvidenceQuery("7", "股权转让", null, List.of(), Map.of("userId", "1"), 10);
    }

    @Test
    void changedDocument_supersededSignalIsCarried() {
        MemoryEntry old = entry(1, "尽调结论", "旧结论");
        when(memoryManager.retrieveMemories(eq(7L), anyString(), any(), anyInt()))
                .thenReturn(List.of(old));
        LocalDateTime newer = T0.plusDays(3);
        when(memoryManager.findSupersededInfo(any())).thenReturn(Map.of(1L, newer));

        List<EvidenceItem> items = retriever.retrieve(query());

        assertEquals(1, items.size());
        assertEquals(newer, items.get(0).supersededAt());
    }

    @Test
    void conflictingSources_bothReturnedWithDistinctHashes() {
        // 同一关键词两条内容相左的记录：契约要求都返回、不静默合并，由 claim_link 层处理矛盾
        when(memoryManager.retrieveMemories(eq(7L), anyString(), any(), anyInt()))
                .thenReturn(List.of(entry(1, "注册资本", "实缴 1000 万"), entry(2, "注册资本", "实缴 500 万")));

        List<EvidenceItem> items = retriever.retrieve(query());

        assertEquals(2, items.size());
        assertNotEquals(items.get(0).contentHash(), items.get(1).contentHash());
    }

    @Test
    void missingLocator_entryWithoutIdIsDropped() {
        MemoryEntry noId = MemoryEntry.builder()
                .projectId(7L).memoryType("fact").memoryKey("k").memoryValue("v").createdAt(T0).build();
        when(memoryManager.retrieveMemories(eq(7L), anyString(), any(), anyInt()))
                .thenReturn(List.of(noId, entry(2, "k2", "v2")));

        List<EvidenceItem> items = retriever.retrieve(query());

        assertEquals(1, items.size());
        assertEquals("memory_entry#2", items.get(0).locator());
        items.forEach(i -> assertNotNull(i.locator()));
    }

    @Test
    void revokedAccess_expiredEntriesAreExcluded() {
        MemoryEntry expired = entry(1, "旧授权", "已失效的内容");
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(memoryManager.retrieveMemories(eq(7L), anyString(), any(), anyInt()))
                .thenReturn(List.of(expired, entry(2, "有效", "有效内容")));

        List<EvidenceItem> items = retriever.retrieve(query());

        assertEquals(1, items.size());
        assertEquals("memory:2", items.get(0).evidenceId());
    }

    @Test
    void asOf_entriesCreatedLaterAreInvisible() {
        MemoryEntry later = entry(2, "后见之明", "时点之后才记录的内容");
        later.setCreatedAt(T0.plusDays(30));
        when(memoryManager.retrieveMemories(eq(7L), anyString(), any(), anyInt()))
                .thenReturn(List.of(entry(1, "k", "v"), later));

        EvidenceQuery asOfQuery = new EvidenceQuery(
                "7", "股权转让", T0.toLocalDate().plusDays(1), List.of(), Map.of(), 10);
        List<EvidenceItem> items = retriever.retrieve(asOfQuery);

        assertEquals(1, items.size());
        assertEquals("memory:1", items.get(0).evidenceId());
    }

    @Test
    void idempotentReplay_sameIdsAndHashes() {
        when(memoryManager.retrieveMemories(eq(7L), anyString(), any(), anyInt()))
                .thenReturn(List.of(entry(1, "k", "内容甲"), entry(2, "k2", "内容乙")));

        List<EvidenceItem> first = retriever.retrieve(query());
        List<EvidenceItem> second = retriever.retrieve(query());

        assertEquals(
                first.stream().map(EvidenceItem::evidenceId).toList(),
                second.stream().map(EvidenceItem::evidenceId).toList());
        assertEquals(
                first.stream().map(EvidenceItem::contentHash).toList(),
                second.stream().map(EvidenceItem::contentHash).toList());
    }

    @Test
    void invalidWorkspaceId_returnsEmptyNotThrow() {
        assertTrue(retriever.retrieve(new EvidenceQuery("not-a-project", "q", null, null, null, 5)).isEmpty());
        assertEquals(7L, MemoryEvidenceRetriever.parseWorkspaceId("project:7"));
    }
}
