package com.checkba.service.ai.memory;

import com.checkba.model.entity.*;
import com.checkba.repository.*;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆管理器
 * 统一管理三层记忆的读写操作
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemoryManager {

    /** 检索排序的时间衰减半衰期（天）：未被检索命中的记忆每过 30 天权重减半（受保护记忆不衰减） */
    private static final double DECAY_HALF_LIFE_DAYS = 30.0;

    /** 语义去重的余弦相似度阈值：cos >= 0.95 视为重复 */
    private static final double DEDUP_COSINE_THRESHOLD = 0.95;

    // ---- 拟人化检索排序参数 ----
    // 人的记忆不是纯时间序：重要的事、反复想起的事记得更牢，偶尔还会随机想起冷门片段。

    /** 隐含重要性：受保护记忆（法律关键信息）的显著性加成，类似"闪光灯记忆"不易遗忘 */
    private static final double PROTECTED_SIGNIFICANCE_BOOST = 0.15;

    /** 隐含重要性：按记忆类型的显著性加成（决策/结论比普通事实更"刻骨铭心"） */
    private static final Map<String, Double> TYPE_SIGNIFICANCE_BOOST = Map.of(
            MemoryEntry.MemoryType.DECISION, 0.10,
            MemoryEntry.MemoryType.CONCLUSION, 0.08,
            MemoryEntry.MemoryType.REFERENCE, 0.05,
            MemoryEntry.MemoryType.PREFERENCE, 0.03);

    /** 随机抖动幅度：检索分数乘以 [1-ratio, 1+ratio] 的随机因子，模拟回忆的不确定性 */
    private static final double JITTER_RATIO = 0.05;

    /** "偶然想起"概率：以小概率用候选池中的冷门记忆替换末位结果（不知道哪条旧记忆什么时候有用） */
    private static final double SERENDIPITY_PROBABILITY = 0.1;

    /** 随机源（测试可注入固定行为） */
    private Random random = new Random();

    void setRandom(Random random) {
        this.random = random;
    }

    private final MemoryEntryRepository memoryEntryRepository;
    private final ConversationSummaryRepository conversationSummaryRepository;
    private final ProjectMemoryRepository projectMemoryRepository;
    private final UserMemoryRepository userMemoryRepository;
    
    @Qualifier("memoryEmbeddingStore")
    private final EmbeddingStore<TextSegment> memoryEmbeddingStore;
    
    private final EmbeddingModel embeddingModel;

    // ==================== 记忆条目操作 ====================

    /**
     * 保存记忆条目
     */
    @Transactional
    public MemoryEntry saveMemory(MemoryEntry entry) {
        log.info("Saving memory: projectId={}, type={}, key={}, protected={}",
                entry.getProjectId(), entry.getMemoryType(), entry.getMemoryKey(), entry.getIsProtected());
        
        MemoryEntry saved = memoryEntryRepository.save(entry);
        
        // 同时创建向量嵌入用于语义检索
        try {
            String textToEmbed = buildEmbeddingText(entry);
            Embedding embedding = embeddingModel.embed(textToEmbed).content();
            
            TextSegment segment = TextSegment.from(textToEmbed);
            segment.metadata().put("memoryId", saved.getId().toString());
            segment.metadata().put("projectId", String.valueOf(entry.getProjectId()));
            segment.metadata().put("memoryType", entry.getMemoryType());
            if (entry.getScope() != null) {
                segment.metadata().put("scope", entry.getScope());
            }
            
            memoryEmbeddingStore.add(embedding, segment);
            log.debug("Memory embedding created for id={}", saved.getId());
        } catch (Exception e) {
            log.warn("Failed to create embedding for memory id={}: {}", saved.getId(), e.getMessage());
        }
        
        return saved;
    }

    /**
     * 带语义去重的保存（MemCell 写入路径）。
     *
     * 写入前用向量相似度检索近重复记忆（cos >= {@value #DEDUP_COSINE_THRESHOLD} 视为重复，
     * LangChain4j 的 relevanceScore = (1 + cos) / 2，故转换后过滤）：
     * - 已有条目 importance 更高（或相等）→ 跳过写入，返回已有条目
     * - 新条目 importance 更高 → 用新内容覆盖已有条目（保留 importance 更高者）
     * - 无重复或去重检查失败 → 正常走 saveMemory
     */
    @Transactional
    public MemoryEntry saveMemoryDeduplicated(MemoryEntry entry) {
        try {
            String textToEmbed = buildEmbeddingText(entry);
            Embedding embedding = embeddingModel.embed(textToEmbed).content();

            double minRelevance = (1.0 + DEDUP_COSINE_THRESHOLD) / 2.0;
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embedding)
                    .maxResults(5)
                    .minScore(minRelevance)
                    .build();
            List<EmbeddingMatch<TextSegment>> matches = memoryEmbeddingStore.search(request).matches();

            for (EmbeddingMatch<TextSegment> match : matches) {
                String pid = match.embedded().metadata().getString("projectId");
                if (pid == null || !pid.equals(String.valueOf(entry.getProjectId()))) {
                    continue;
                }
                String memoryIdStr = match.embedded().metadata().getString("memoryId");
                if (memoryIdStr == null) {
                    continue;
                }
                Optional<MemoryEntry> existingOpt = memoryEntryRepository.findById(Long.parseLong(memoryIdStr));
                if (existingOpt.isEmpty()) {
                    continue;
                }
                MemoryEntry existing = existingOpt.get();
                double newImportance = entry.getImportanceScore() != null ? entry.getImportanceScore() : 0.5;
                double oldImportance = existing.getImportanceScore() != null ? existing.getImportanceScore() : 0.5;

                if (newImportance > oldImportance) {
                    // 新条目更重要：以新内容覆盖旧条目
                    existing.setMemoryType(entry.getMemoryType());
                    existing.setMemoryKey(entry.getMemoryKey());
                    existing.setMemoryValue(entry.getMemoryValue());
                    existing.setImportanceScore(entry.getImportanceScore());
                    if (Boolean.TRUE.equals(entry.getIsProtected())) {
                        existing.setIsProtected(true);
                    }
                    log.info("Memory dedup: updated existing id={} with higher-importance content (score={})",
                            existing.getId(), match.score());
                    return memoryEntryRepository.save(existing);
                }

                log.info("Memory dedup: skipped near-duplicate of id={} (score={})", existing.getId(), match.score());
                return existing;
            }
        } catch (Exception e) {
            log.warn("Memory dedup check failed, falling back to plain save: {}", e.getMessage());
        }
        return saveMemory(entry);
    }

    /**
     * 构建用于嵌入的文本
     */
    private String buildEmbeddingText(MemoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.getMemoryKey() != null) {
            sb.append(entry.getMemoryKey()).append(": ");
        }
        sb.append(entry.getMemoryValue());
        if (entry.getMemoryType() != null) {
            sb.append(" [").append(entry.getMemoryType()).append("]");
        }
        return sb.toString();
    }

    /**
     * 根据关键词检索记忆（排序引入时间衰减因子，命中后更新 lastAccessedAt）
     */
    public List<MemoryEntry> retrieveMemories(Long projectId, String query, String memoryType, int limit) {
        log.info("Retrieving memories: projectId={}, query={}, type={}, limit={}",
                projectId, query, memoryType, limit);

        List<MemoryEntry> ranked = rankWithTimeDecay(
                fetchKeywordCandidates(projectId, query, memoryType, limit * 3), limit);
        touchMemories(ranked);
        return ranked;
    }

    /**
     * 检测"已被更新"的条目（证据账本的更新信号）：
     * 同一项目内存在相同 memoryKey 且 createdAt 更晚的记录时，视为该条目已有更新版本。
     * 返回 entryId → 最新版本的创建时间；无更新版本的条目不出现在结果中。
     * 检测失败时返回已算出的部分结果，不影响主流程。
     */
    public Map<Long, LocalDateTime> findSupersededInfo(List<MemoryEntry> entries) {
        Map<Long, LocalDateTime> result = new HashMap<>();
        Map<Long, List<MemoryEntry>> byProject = entries.stream()
                .filter(e -> e.getProjectId() != null && e.getMemoryKey() != null && e.getId() != null)
                .collect(Collectors.groupingBy(MemoryEntry::getProjectId));
        for (Map.Entry<Long, List<MemoryEntry>> group : byProject.entrySet()) {
            List<String> keys = group.getValue().stream()
                    .map(MemoryEntry::getMemoryKey).distinct().collect(Collectors.toList());
            List<MemoryEntry> sameKeyEntries;
            try {
                sameKeyEntries = memoryEntryRepository.findByProjectIdAndMemoryKeyIn(group.getKey(), keys);
            } catch (Exception e) {
                log.warn("Superseded check failed for project {}: {}", group.getKey(), e.getMessage());
                continue;
            }
            Map<String, LocalDateTime> latestByKey = new HashMap<>();
            for (MemoryEntry e : sameKeyEntries) {
                if (e.getCreatedAt() == null || e.getMemoryKey() == null) continue;
                latestByKey.merge(e.getMemoryKey(), e.getCreatedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
            for (MemoryEntry e : group.getValue()) {
                LocalDateTime latest = latestByKey.get(e.getMemoryKey());
                if (latest != null && e.getCreatedAt() != null && latest.isAfter(e.getCreatedAt())) {
                    result.put(e.getId(), latest);
                }
            }
        }
        return result;
    }

    /**
     * 将检索结果格式化为证据账本（含记录时间/来源/更新信号），供上下文注入与记忆工具复用。
     */
    public String formatAsEvidenceLedger(List<MemoryEntry> entries) {
        return MemoryEvidenceFormatter.format(entries, findSupersededInfo(entries), LocalDate.now());
    }

    /**
     * 关键词候选检索（内部使用：不做衰减排序，不更新 lastAccessedAt）
     */
    private List<MemoryEntry> fetchKeywordCandidates(Long projectId, String query, String memoryType, int fetchLimit) {
        if (query == null || query.isBlank()) {
            // 没有查询时返回最重要的记忆
            return memoryEntryRepository.findTopImportantMemories(projectId, PageRequest.of(0, fetchLimit));
        }

        // 关键词搜索
        return memoryEntryRepository.searchByKeywordAndType(
                projectId, query, memoryType, PageRequest.of(0, fetchLimit));
    }

    /**
     * 语义检索记忆（排序引入时间衰减因子，命中后更新 lastAccessedAt）
     */
    public List<MemoryEntry> semanticSearch(Long projectId, String query, int limit) {
        List<MemoryEntry> ranked = rankWithTimeDecay(
                semanticSearchInternal(projectId, query, limit), limit);
        touchMemories(ranked);
        return ranked;
    }

    /**
     * 语义检索（内部使用：不做衰减排序，不更新 lastAccessedAt）
     */
    private List<MemoryEntry> semanticSearchInternal(Long projectId, String query, int limit) {
        log.info("Semantic search: projectId={}, query={}, limit={}", projectId, query, limit);

        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(limit * 2)  // 多取一些以便过滤
                    .minScore(0.5)  // 最低相似度阈值
                    .build();
            
            List<EmbeddingMatch<TextSegment>> matches = memoryEmbeddingStore.search(request).matches();
            
            // 过滤出属于当前项目的记忆
            List<Long> memoryIds = matches.stream()
                    .filter(match -> {
                        String pid = match.embedded().metadata().getString("projectId");
                        return pid != null && pid.equals(String.valueOf(projectId));
                    })
                    .map(match -> Long.parseLong(match.embedded().metadata().getString("memoryId")))
                    .limit(limit)
                    .collect(Collectors.toList());
            
            if (memoryIds.isEmpty()) {
                return Collections.emptyList();
            }
            
            return memoryEntryRepository.findAllById(memoryIds);
        } catch (Exception e) {
            log.error("Semantic search failed: {}", e.getMessage(), e);
            // 降级到关键词搜索
            return fetchKeywordCandidates(projectId, query, null, limit);
        }
    }

    /**
     * 获取项目的受保护记忆（法律关键信息）
     */
    public List<MemoryEntry> getProtectedMemories(Long projectId) {
        return memoryEntryRepository.findByProjectIdAndIsProtectedTrue(projectId);
    }

    // ==================== 作用域记忆操作 ====================

    /**
     * 获取用户级记忆（跨项目生效：偏好、常用表达、行文习惯）
     */
    public List<MemoryEntry> retrieveUserMemories(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return memoryEntryRepository.findByUserIdAndScope(
                userId, MemoryEntry.MemoryScope.USER, PageRequest.of(0, limit));
    }

    /**
     * 检索通用知识记忆（跨用户跨项目的领域知识）
     */
    public List<MemoryEntry> retrieveGlobalKnowledge(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        return memoryEntryRepository.searchByScopeAndKeyword(
                MemoryEntry.MemoryScope.GLOBAL, keyword, PageRequest.of(0, limit));
    }

    /**
     * 获取绑定到某个文件的记忆（如"该合同的审查结论"）
     */
    public List<MemoryEntry> retrieveFileMemories(Long sourceFileId) {
        if (sourceFileId == null) {
            return Collections.emptyList();
        }
        return memoryEntryRepository.findBySourceFileIdOrderByImportanceScoreDesc(sourceFileId);
    }

    /**
     * 获取绑定到某个对话的记忆（scope=conversation）。
     *
     * 与 {@link #retrieveFileMemories} 同理：query_memory/search_knowledge_base/deep_search
     * 此前完全不认 scope，file/conversation 作用域保存的记忆只能靠关键词/语义检索"运气好"才捞得到——
     * 这里提供一条确定性的按 scope 取值通路，供工具层在调用方明确指定 scope 时兜底合并进结果。
     */
    public List<MemoryEntry> retrieveConversationMemories(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Collections.emptyList();
        }
        return memoryEntryRepository.findByConversationIdOrderByCreatedAtDesc(conversationId);
    }

    // ==================== RRF 混合检索 ====================

    /**
     * RRF 混合检索
     * 并行执行关键词检索和语义检索，使用 Reciprocal Rank Fusion 算法融合结果
     * 
     * @param projectId 项目ID
     * @param query 查询文本
     * @param limit 返回结果数量
     * @return 融合后的记忆列表，按 RRF 分数降序排列
     */
    public List<MemoryEntry> hybridSearch(Long projectId, String query, int limit) {
        log.info("Hybrid search (RRF): projectId={}, query={}, limit={}", projectId, query, limit);
        
        if (query == null || query.isBlank()) {
            return memoryEntryRepository.findTopImportantMemories(projectId, PageRequest.of(0, limit));
        }
        
        // 1. 并行执行关键词检索和语义检索
        int fetchLimit = limit * 3;  // 多取一些用于融合
        List<MemoryEntry> keywordResults = fetchKeywordCandidates(projectId, query, null, fetchLimit);
        List<MemoryEntry> semanticResults = semanticSearchInternal(projectId, query, fetchLimit);

        log.debug("Keyword results: {}, Semantic results: {}", keywordResults.size(), semanticResults.size());

        // 2. RRF 融合（分数带时间衰减因子）
        List<MemoryEntry> fusedResults = rrfFusion(keywordResults, semanticResults, limit);

        log.info("Hybrid search completed: {} results after RRF fusion", fusedResults.size());
        touchMemories(fusedResults);
        return fusedResults;
    }

    /**
     * RRF 混合检索（带类型过滤）
     */
    public List<MemoryEntry> hybridSearch(Long projectId, String query, String memoryType, int limit) {
        log.info("Hybrid search with type filter: projectId={}, query={}, type={}, limit={}", 
                projectId, query, memoryType, limit);
        
        if (query == null || query.isBlank()) {
            if (memoryType != null && !memoryType.isEmpty()) {
                return memoryEntryRepository.findByProjectIdAndMemoryTypeOrderByImportanceScoreDesc(projectId, memoryType)
                        .stream().limit(limit).collect(Collectors.toList());
            }
            return memoryEntryRepository.findTopImportantMemories(projectId, PageRequest.of(0, limit));
        }
        
        // 执行混合检索
        List<MemoryEntry> allResults = hybridSearch(projectId, query, limit * 2);
        
        // 按类型过滤
        if (memoryType != null && !memoryType.isEmpty() && !memoryType.equalsIgnoreCase("all")) {
            return allResults.stream()
                    .filter(m -> memoryType.equalsIgnoreCase(m.getMemoryType()))
                    .limit(limit)
                    .collect(Collectors.toList());
        }
        
        return allResults.stream().limit(limit).collect(Collectors.toList());
    }

    /**
     * Reciprocal Rank Fusion (RRF) 算法
     * RRF score = Σ 1 / (k + rank_i)
     * k 是常数，通常设为 60
     * 
     * @param list1 第一个检索结果列表（关键词检索）
     * @param list2 第二个检索结果列表（语义检索）
     * @param limit 返回结果数量
     * @return 融合后的结果列表
     */
    private List<MemoryEntry> rrfFusion(List<MemoryEntry> list1, List<MemoryEntry> list2, int limit) {
        return rrfFusionMultiple(List.of(list1, list2), limit);
    }

    /**
     * 多列表 RRF 融合（支持多个检索源）
     * 用于 Agentic 多轮召回时融合多个查询结果
     * 最终分数 = RRF 分数 × 隐含重要性 × 时间衰减 × 随机抖动（受保护记忆不衰减）
     */
    public List<MemoryEntry> rrfFusionMultiple(List<List<MemoryEntry>> resultLists, int limit) {
        final int k = 60;
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, MemoryEntry> memoryMap = new HashMap<>();

        for (List<MemoryEntry> results : resultLists) {
            for (int i = 0; i < results.size(); i++) {
                MemoryEntry entry = results.get(i);
                Long id = entry.getId();
                double score = 1.0 / (k + i + 1);  // rank 从 1 开始
                scores.merge(id, score, Double::sum);
                memoryMap.putIfAbsent(id, entry);
            }
        }

        // 应用拟人化因子：隐含重要性 × 时间衰减 × 随机抖动
        for (Map.Entry<Long, Double> e : scores.entrySet()) {
            MemoryEntry m = memoryMap.get(e.getKey());
            e.setValue(e.getValue() * impliedSignificance(m) * timeDecayFactor(m) * jitterFactor());
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> memoryMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ==================== 拟人化检索排序与命中更新 ====================

    /**
     * 计算记忆的时间衰减因子：0.5 ^ (距上次命中天数 / (半衰期 × 记忆强度))。
     * - 受保护记忆（法律关键信息）不衰减，恒为 1.0（"闪光灯记忆"）
     * - 复述效应：命中次数越多记忆强度越高，衰减越慢——就像多年后仍记得反复想起过的时刻
     */
    double timeDecayFactor(MemoryEntry entry) {
        if (entry == null) return 1.0;
        if (Boolean.TRUE.equals(entry.getIsProtected())) return 1.0;
        LocalDateTime ref = entry.getLastAccessedAt() != null ? entry.getLastAccessedAt()
                : (entry.getUpdatedAt() != null ? entry.getUpdatedAt() : entry.getCreatedAt());
        if (ref == null) return 1.0;
        double days = java.time.Duration.between(ref, LocalDateTime.now()).toHours() / 24.0;
        if (days <= 0) return 1.0;
        int accessCount = entry.getAccessCount() != null ? entry.getAccessCount() : 0;
        double stability = 1.0 + Math.log1p(accessCount);
        return Math.pow(0.5, days / (DECAY_HALF_LIFE_DAYS * stability));
    }

    /**
     * 隐含重要性（implied significance）：显式 importance 之外，
     * 从记忆自身特征推断的显著性加成（受保护、类型）。
     */
    double impliedSignificance(MemoryEntry entry) {
        double score = entry.getImportanceScore() != null ? entry.getImportanceScore() : 0.5;
        if (Boolean.TRUE.equals(entry.getIsProtected())) {
            score += PROTECTED_SIGNIFICANCE_BOOST;
        }
        if (entry.getMemoryType() != null) {
            score += TYPE_SIGNIFICANCE_BOOST.getOrDefault(entry.getMemoryType().toLowerCase(), 0.0);
        }
        return score;
    }

    /**
     * 检索综合分 = 隐含重要性 × 时间衰减 × 随机抖动。
     */
    private double retrievalScore(MemoryEntry entry) {
        return impliedSignificance(entry) * timeDecayFactor(entry) * jitterFactor();
    }

    /** 随机抖动因子：[1 - JITTER_RATIO, 1 + JITTER_RATIO] */
    private double jitterFactor() {
        return 1.0 + (random.nextDouble() * 2 - 1) * JITTER_RATIO;
    }

    /**
     * 拟人化排序并截取前 limit 条：
     * 按综合分排序后，以小概率把候选池中一条"冷门"记忆替换进末位（偶然想起）。
     */
    private List<MemoryEntry> rankWithTimeDecay(List<MemoryEntry> candidates, int limit) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        // 预计算每条综合分（含随机抖动/时间衰减，逐条只算一次）。retrievalScore 每次调用返回不同值，
        // 直接用作比较器 key 会在排序中被多次调用得到不同结果，违反 Comparator 契约 →
        // TimSort 抛 IllegalArgumentException（候选多时）或排序错乱（候选少时）。
        java.util.Map<MemoryEntry, Double> scoreCache = new java.util.IdentityHashMap<>();
        for (MemoryEntry e : candidates) scoreCache.put(e, retrievalScore(e));
        List<MemoryEntry> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble((MemoryEntry e) -> scoreCache.get(e)).reversed())
                .collect(Collectors.toList());

        List<MemoryEntry> top = new ArrayList<>(ranked.subList(0, Math.min(limit, ranked.size())));

        // 偶然想起：候选池有落选者时，小概率用其中随机一条替换末位
        if (ranked.size() > top.size() && !top.isEmpty()
                && random.nextDouble() < SERENDIPITY_PROBABILITY) {
            List<MemoryEntry> rest = ranked.subList(top.size(), ranked.size());
            MemoryEntry lucky = rest.get(random.nextInt(rest.size()));
            top.set(top.size() - 1, lucky);
            log.debug("Serendipity recall: surfaced memory id={} into results", lucky.getId());
        }
        return top;
    }

    /**
     * 检索命中后更新 lastAccessedAt（失败不影响检索主流程）。
     */
    private void touchMemories(List<MemoryEntry> hits) {
        if (hits == null || hits.isEmpty()) return;
        try {
            List<Long> ids = hits.stream()
                    .map(MemoryEntry::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!ids.isEmpty()) {
                memoryEntryRepository.touchLastAccessedAt(ids, LocalDateTime.now());
            }
        } catch (Exception e) {
            log.warn("Failed to touch lastAccessedAt for memories: {}", e.getMessage());
        }
    }

    /**
     * 删除过期记忆
     */
    @Transactional
    public void cleanupExpiredMemories() {
        log.info("Cleaning up expired memories...");
        memoryEntryRepository.deleteExpiredMemories(LocalDateTime.now());
    }

    // ==================== 对话摘要操作 ====================

    /**
     * 获取对话摘要
     */
    public Optional<ConversationSummary> getConversationSummary(String conversationId) {
        return conversationSummaryRepository.findByConversationId(conversationId);
    }

    /**
     * 更新对话摘要
     */
    @Transactional
    public ConversationSummary updateConversationSummary(String conversationId, 
                                                          String summaryText,
                                                          List<String> keyPoints,
                                                          List<String> legalReferences,
                                                          List<String> mentionedEntities,
                                                          List<String> pendingTasks,
                                                          int tokenCount,
                                                          int messageCount,
                                                          Long lastMessageId) {
        log.info("Updating conversation summary: conversationId={}, tokenCount={}, messageCount={}",
                conversationId, tokenCount, messageCount);
        
        ConversationSummary summary = conversationSummaryRepository
                .findByConversationId(conversationId)
                .orElse(ConversationSummary.builder()
                        .conversationId(conversationId)
                        .build());
        
        summary.setSummaryText(summaryText);
        summary.setKeyPoints(keyPoints);
        summary.setLegalReferences(legalReferences);
        summary.setMentionedEntities(mentionedEntities);
        summary.setPendingTasks(pendingTasks);
        summary.setTokenCount(tokenCount);
        summary.setMessageCount(messageCount);
        summary.setLastMessageId(lastMessageId);
        
        return conversationSummaryRepository.save(summary);
    }

    /**
     * 简化版更新摘要
     */
    @Transactional
    public void updateConversationSummary(String conversationId, String summaryText) {
        ConversationSummary summary = conversationSummaryRepository
                .findByConversationId(conversationId)
                .orElse(ConversationSummary.builder()
                        .conversationId(conversationId)
                        .build());
        
        summary.setSummaryText(summaryText);
        conversationSummaryRepository.save(summary);
    }

    // ==================== 项目记忆操作 ====================

    /**
     * 获取项目记忆
     */
    public Optional<ProjectMemory> getProjectMemory(Long projectId) {
        return projectMemoryRepository.findByProjectId(projectId);
    }

    /** find-then-insert 竞态的进程内互斥锁，见 {@link #saveProjectMemory}。 */
    private final Object projectMemorySaveLock = new Object();

    /**
     * 保存或更新项目记忆。
     *
     * <p><b>并发红线</b>：{@code project_id} 上有 {@code unique} 约束，而这里是先查后写的
     * find-then-insert：两个并发线程（同一项目的两轮对话几乎同时跑完记忆管线）都可能在对方提交前
     * 读到"还没有这一行"，于是都走 INSERT，后提交的那个直接撞唯一约束抛异常，
     * 它这一轮抽取出的字段（legalRefs/parties/transactionAmount 等）被整体丢弃——不是"没有变化"，
     * 是"这次更新完全没发生"。不能加 {@code @Version} 列做乐观锁（不引入新的数据库列），
     * 这里改用进程内锁把"查 + 决定 insert/update + 写"整段收窄成互斥：同一时刻只有一个线程能
     * 穿过去，后来者一定能看到前者已经写完的行，从而落到 UPDATE 分支而不是再抢一次 INSERT。
     * 代价是同一 JVM 内对本方法的调用退化为串行（调用频率低——每轮对话一次，可接受）；
     * 多实例横向扩容时锁不跨进程，但这已经把审计条目里描述的竞态窗口从"一次完整的 DB 往返"
     * 收窄到可忽略的量级。
     */
    @Transactional
    public ProjectMemory saveProjectMemory(ProjectMemory projectMemory) {
        synchronized (projectMemorySaveLock) {
            log.info("Saving project memory for projectId={}", projectMemory.getProjectId());

            ProjectMemory existing = projectMemoryRepository
                    .findByProjectId(projectMemory.getProjectId())
                    .orElse(null);

            if (existing != null) {
                // 更新现有记录
                projectMemory.setId(existing.getId());
                projectMemory.setCreatedAt(existing.getCreatedAt());
            }

            return projectMemoryRepository.save(projectMemory);
        }
    }

    /**
     * 更新项目记忆的特定字段
     */
    @Transactional
    public void updateProjectField(Long projectId, String field, String value) {
        log.info("Updating project field: projectId={}, field={}", projectId, field);
        
        ProjectMemory pm = projectMemoryRepository.findByProjectId(projectId)
                .orElse(ProjectMemory.builder().projectId(projectId).build());
        
        switch (field.toLowerCase()) {
            case "projectname" -> pm.setProjectName(value);
            case "projecttype" -> pm.setProjectType(value);
            case "listedcompany" -> pm.setListedCompany(value);
            case "targetcompany" -> pm.setTargetCompany(value);
            case "transactionstructure" -> pm.setTransactionStructure(value);
            case "transactionamount" -> {
                try {
                    pm.setTransactionAmount(new java.math.BigDecimal(value.replaceAll("[^\\d.]", "")));
                } catch (Exception e) {
                    log.warn("Failed to parse transaction amount: {}", value);
                }
            }
            default -> log.warn("Unknown project field: {}", field);
        }
        
        projectMemoryRepository.save(pm);
    }

    // ==================== 用户记忆操作 ====================

    /**
     * 获取用户记忆
     */
    public Optional<UserMemory> getUserMemory(Long userId) {
        return userMemoryRepository.findByUserId(userId);
    }

    /** find-then-insert 竞态的进程内互斥锁，同 {@link #projectMemorySaveLock}，理由见 {@link #saveProjectMemory}。 */
    private final Object userMemorySaveLock = new Object();

    /**
     * 保存用户记忆。同 {@link #saveProjectMemory}，{@code user_id} 上也是 unique 约束 +
     * find-then-insert，一样会被并发撞出丢更新，一样用进程内锁收窄竞态窗口。
     */
    @Transactional
    public UserMemory saveUserMemory(UserMemory userMemory) {
        synchronized (userMemorySaveLock) {
            UserMemory existing = userMemoryRepository.findByUserId(userMemory.getUserId()).orElse(null);
            if (existing != null) {
                userMemory.setId(existing.getId());
                userMemory.setCreatedAt(existing.getCreatedAt());
            }
            return userMemoryRepository.save(userMemory);
        }
    }

    /**
     * 更新用户偏好
     */
    @Transactional
    public void updateUserPreference(Long userId, String key, String value) {
        UserMemory um = userMemoryRepository.findByUserId(userId)
                .orElse(UserMemory.builder().userId(userId).preferences(new HashMap<>()).build());
        
        if (um.getPreferences() == null) {
            um.setPreferences(new HashMap<>());
        }
        um.getPreferences().put(key, value);
        
        userMemoryRepository.save(um);
    }

    // ==================== 统计与监控 ====================

    /**
     * 获取项目记忆统计
     */
    public Map<String, Object> getMemoryStats(Long projectId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalMemories", memoryEntryRepository.countByProjectId(projectId));
        stats.put("typeBreakdown", memoryEntryRepository.countByProjectIdGroupByType(projectId));
        stats.put("hasProjectMemory", projectMemoryRepository.existsByProjectId(projectId));
        return stats;
    }
}

