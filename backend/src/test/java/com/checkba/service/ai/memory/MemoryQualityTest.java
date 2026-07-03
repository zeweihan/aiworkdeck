package com.checkba.service.ai.memory;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.repository.ConversationSummaryRepository;
import com.checkba.repository.MemoryEntryRepository;
import com.checkba.repository.ProjectMemoryRepository;
import com.checkba.repository.UserMemoryRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 记忆质量测试（Phase 2）：
 * - 检索排序的时间衰减（受保护记忆不衰减）
 * - 检索命中更新 lastAccessedAt
 * - MemCell 写入前的向量相似度去重（保留 importance 更高者）
 */
class MemoryQualityTest {

    private MemoryEntryRepository memoryEntryRepository;
    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;
    private MemoryManager memoryManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        memoryEntryRepository = mock(MemoryEntryRepository.class);
        embeddingStore = (EmbeddingStore<TextSegment>) mock(EmbeddingStore.class);
        embeddingModel = mock(EmbeddingModel.class);

        memoryManager = new MemoryManager(
                memoryEntryRepository,
                mock(ConversationSummaryRepository.class),
                mock(ProjectMemoryRepository.class),
                mock(UserMemoryRepository.class),
                embeddingStore,
                embeddingModel);
        // 固定随机源：无抖动、不触发"偶然想起"，保证排序断言确定性
        memoryManager.setRandom(fixedRandom(0.5));

        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f, 0.3f})));
    }

    /** nextDouble 恒定、nextInt 恒为 0 的测试随机源 */
    private static java.util.Random fixedRandom(double d) {
        return new java.util.Random() {
            @Override public double nextDouble() { return d; }
            @Override public int nextInt(int bound) { return 0; }
        };
    }

    private static MemoryEntry entry(Long id, double importance, LocalDateTime lastAccessedAt, boolean isProtected) {
        MemoryEntry e = MemoryEntry.builder()
                .id(id)
                .projectId(1L)
                .memoryType("fact")
                .memoryKey("key-" + id)
                .memoryValue("value-" + id)
                .importanceScore(importance)
                .isProtected(isProtected)
                .build();
        e.setLastAccessedAt(lastAccessedAt);
        return e;
    }

    // ==================== 时间衰减 ====================

    @Test
    @DisplayName("时间衰减因子：刚命中不衰减，半衰期约减半，受保护记忆恒为 1.0")
    void timeDecayFactorBasics() {
        MemoryEntry fresh = entry(1L, 0.9, LocalDateTime.now(), false);
        assertEquals(1.0, memoryManager.timeDecayFactor(fresh), 0.001);

        MemoryEntry halfLife = entry(2L, 0.9, LocalDateTime.now().minusDays(30), false);
        assertEquals(0.5, memoryManager.timeDecayFactor(halfLife), 0.05);

        MemoryEntry protectedOld = entry(3L, 0.9, LocalDateTime.now().minusDays(365), true);
        assertEquals(1.0, memoryManager.timeDecayFactor(protectedOld), 0.001);
    }

    @Test
    @DisplayName("检索排序引入时间衰减：陈旧记忆排序下降，受保护记忆不受影响")
    void retrievalRankingAppliesTimeDecay() {
        // A: 高重要性但 90 天未命中（衰减后 0.9*0.125=0.1125）
        MemoryEntry staleImportant = entry(1L, 0.9, LocalDateTime.now().minusDays(90), false);
        // B: 中等重要性但刚命中（0.6）
        MemoryEntry freshModerate = entry(2L, 0.6, LocalDateTime.now(), false);
        // C: 高重要性且受保护，一年未命中也不衰减（0.9）
        MemoryEntry protectedOld = entry(3L, 0.9, LocalDateTime.now().minusDays(365), true);

        when(memoryEntryRepository.searchByKeywordAndType(any(), anyString(), any(), any()))
                .thenReturn(List.of(staleImportant, freshModerate, protectedOld));

        List<MemoryEntry> results = memoryManager.retrieveMemories(1L, "查询", null, 3);

        assertEquals(3, results.size());
        assertEquals(3L, results.get(0).getId(), "受保护记忆不衰减，应排第一");
        assertEquals(2L, results.get(1).getId(), "新鲜记忆应排在陈旧记忆之前");
        assertEquals(1L, results.get(2).getId(), "陈旧记忆衰减后应排最后");
    }

    @Test
    @DisplayName("检索命中后更新 lastAccessedAt")
    void retrievalTouchesLastAccessedAt() {
        MemoryEntry m = entry(9L, 0.8, LocalDateTime.now().minusDays(10), false);
        when(memoryEntryRepository.searchByKeywordAndType(any(), anyString(), any(), any()))
                .thenReturn(List.of(m));

        memoryManager.retrieveMemories(1L, "查询", null, 5);

        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(memoryEntryRepository).touchLastAccessedAt(idsCaptor.capture(), any(LocalDateTime.class));
        assertEquals(List.of(9L), idsCaptor.getValue());
    }

    // ==================== 拟人化排序：隐含重要性 / 复述效应 / 偶然想起 ====================

    @Test
    @DisplayName("隐含重要性：受保护与决策类记忆有显著性加成")
    void impliedSignificanceBoosts() {
        MemoryEntry plainFact = entry(1L, 0.5, LocalDateTime.now(), false);
        assertEquals(0.5, memoryManager.impliedSignificance(plainFact), 0.001);

        MemoryEntry protectedFact = entry(2L, 0.5, LocalDateTime.now(), true);
        assertEquals(0.65, memoryManager.impliedSignificance(protectedFact), 0.001,
                "受保护记忆应有 +0.15 加成");

        MemoryEntry decision = entry(3L, 0.5, LocalDateTime.now(), false);
        decision.setMemoryType(MemoryEntry.MemoryType.DECISION);
        assertEquals(0.6, memoryManager.impliedSignificance(decision), 0.001,
                "决策类记忆应有 +0.10 加成");
    }

    @Test
    @DisplayName("复述效应：被反复想起的记忆衰减更慢")
    void rehearsalSlowsDecay() {
        MemoryEntry rarelyRecalled = entry(1L, 0.8, LocalDateTime.now().minusDays(60), false);
        rarelyRecalled.setAccessCount(0);

        MemoryEntry oftenRecalled = entry(2L, 0.8, LocalDateTime.now().minusDays(60), false);
        oftenRecalled.setAccessCount(30);

        double rareFactor = memoryManager.timeDecayFactor(rarelyRecalled);
        double oftenFactor = memoryManager.timeDecayFactor(oftenRecalled);

        assertTrue(oftenFactor > rareFactor,
                "同样 60 天未命中，命中 30 次的记忆 (" + oftenFactor + ") 应比 0 次的 (" + rareFactor + ") 衰减更慢");
    }

    @Test
    @DisplayName("偶然想起：小概率把候选池中的冷门记忆带回结果")
    void serendipitySurfacesRandomMemory() {
        MemoryEntry top1 = entry(1L, 0.9, LocalDateTime.now(), false);
        MemoryEntry top2 = entry(2L, 0.8, LocalDateTime.now(), false);
        MemoryEntry cold = entry(3L, 0.3, LocalDateTime.now().minusDays(200), false);

        when(memoryEntryRepository.searchByKeywordAndType(any(), anyString(), any(), any()))
                .thenReturn(List.of(top1, top2, cold));

        // nextDouble=0.0 → 必触发偶然想起；nextInt=0 → 选中落选池第一条
        memoryManager.setRandom(fixedRandom(0.0));
        List<MemoryEntry> withSerendipity = memoryManager.retrieveMemories(1L, "查询", null, 2);
        assertTrue(withSerendipity.stream().anyMatch(m -> m.getId().equals(3L)),
                "触发偶然想起时，冷门记忆应进入结果");

        // nextDouble=0.5 → 不触发，按分数取前 2
        memoryManager.setRandom(fixedRandom(0.5));
        List<MemoryEntry> normal = memoryManager.retrieveMemories(1L, "查询", null, 2);
        assertEquals(List.of(1L, 2L), normal.stream().map(MemoryEntry::getId).toList());
    }

    // ==================== 语义去重 ====================

    private void mockStoreMatch(Long existingId, double relevanceScore) {
        TextSegment segment = TextSegment.from("dup",
                Metadata.from(Map.of("memoryId", String.valueOf(existingId), "projectId", "1")));
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(
                relevanceScore, "emb-1", Embedding.from(new float[]{0.1f, 0.2f, 0.3f}), segment);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(match)));
    }

    @Test
    @DisplayName("去重：已有条目 importance 更高时跳过写入")
    void dedupSkipsLowerImportanceDuplicate() {
        MemoryEntry existing = entry(5L, 0.9, LocalDateTime.now(), false);
        when(memoryEntryRepository.findById(5L)).thenReturn(Optional.of(existing));
        mockStoreMatch(5L, 0.98); // relevance 0.98 → cos 0.96 >= 0.95

        MemoryEntry candidate = entry(null, 0.5, null, false);
        MemoryEntry result = memoryManager.saveMemoryDeduplicated(candidate);

        assertEquals(5L, result.getId(), "应返回已有条目");
        verify(memoryEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("去重：新条目 importance 更高时覆盖旧条目（保留 importance 更高者）")
    void dedupReplacesWithHigherImportance() {
        MemoryEntry existing = entry(5L, 0.5, LocalDateTime.now(), false);
        when(memoryEntryRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(memoryEntryRepository.save(any(MemoryEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        mockStoreMatch(5L, 0.98);

        MemoryEntry candidate = entry(null, 0.9, null, false);
        candidate.setMemoryValue("更完整的新内容");
        MemoryEntry result = memoryManager.saveMemoryDeduplicated(candidate);

        assertEquals(5L, result.getId(), "应复用已有条目的 ID");
        assertEquals("更完整的新内容", result.getMemoryValue(), "内容应被新条目覆盖");
        assertEquals(0.9, result.getImportanceScore(), 0.001);
        verify(memoryEntryRepository).save(existing);
    }

    @Test
    @DisplayName("去重：无相似记忆时正常保存")
    void dedupSavesWhenNoDuplicate() {
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
        when(memoryEntryRepository.save(any(MemoryEntry.class))).thenAnswer(inv -> {
            MemoryEntry e = inv.getArgument(0);
            e.setId(100L);
            return e;
        });

        MemoryEntry candidate = entry(null, 0.7, null, false);
        MemoryEntry result = memoryManager.saveMemoryDeduplicated(candidate);

        assertEquals(100L, result.getId());
        verify(memoryEntryRepository).save(candidate);
    }

    @Test
    @DisplayName("去重：跨项目的相似记忆不参与去重")
    void dedupIgnoresOtherProjects() {
        TextSegment segment = TextSegment.from("dup",
                Metadata.from(Map.of("memoryId", "5", "projectId", "999")));
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(
                0.99, "emb-1", Embedding.from(new float[]{0.1f, 0.2f, 0.3f}), segment);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(match)));
        when(memoryEntryRepository.save(any(MemoryEntry.class))).thenAnswer(inv -> {
            MemoryEntry e = inv.getArgument(0);
            e.setId(101L);
            return e;
        });

        MemoryEntry candidate = entry(null, 0.7, null, false);
        MemoryEntry result = memoryManager.saveMemoryDeduplicated(candidate);

        assertEquals(101L, result.getId(), "其他项目的相似记忆不应拦截保存");
        verify(memoryEntryRepository).save(candidate);
    }
}
