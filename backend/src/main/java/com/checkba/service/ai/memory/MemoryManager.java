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
     * 根据关键词检索记忆
     */
    public List<MemoryEntry> retrieveMemories(Long projectId, String query, String memoryType, int limit) {
        log.info("Retrieving memories: projectId={}, query={}, type={}, limit={}",
                projectId, query, memoryType, limit);
        
        if (query == null || query.isBlank()) {
            // 没有查询时返回最重要的记忆
            return memoryEntryRepository.findTopImportantMemories(projectId, PageRequest.of(0, limit));
        }
        
        // 关键词搜索
        return memoryEntryRepository.searchByKeywordAndType(
                projectId, query, memoryType, PageRequest.of(0, limit));
    }

    /**
     * 语义检索记忆
     */
    public List<MemoryEntry> semanticSearch(Long projectId, String query, int limit) {
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
            return retrieveMemories(projectId, query, null, limit);
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
        List<MemoryEntry> keywordResults = retrieveMemories(projectId, query, null, fetchLimit);
        List<MemoryEntry> semanticResults = semanticSearch(projectId, query, fetchLimit);
        
        log.debug("Keyword results: {}, Semantic results: {}", keywordResults.size(), semanticResults.size());
        
        // 2. RRF 融合
        List<MemoryEntry> fusedResults = rrfFusion(keywordResults, semanticResults, limit);
        
        log.info("Hybrid search completed: {} results after RRF fusion", fusedResults.size());
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
        final int k = 60;  // RRF 常数
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, MemoryEntry> memoryMap = new HashMap<>();
        
        // 计算第一个列表的 RRF 分数
        for (int i = 0; i < list1.size(); i++) {
            MemoryEntry entry = list1.get(i);
            Long id = entry.getId();
            double score = 1.0 / (k + i + 1);  // rank 从 1 开始
            scores.merge(id, score, Double::sum);
            memoryMap.putIfAbsent(id, entry);
        }
        
        // 计算第二个列表的 RRF 分数
        for (int i = 0; i < list2.size(); i++) {
            MemoryEntry entry = list2.get(i);
            Long id = entry.getId();
            double score = 1.0 / (k + i + 1);
            scores.merge(id, score, Double::sum);
            memoryMap.putIfAbsent(id, entry);
        }
        
        // 按 RRF 分数降序排序并返回
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> memoryMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 多列表 RRF 融合（支持多个检索源）
     * 用于 Agentic 多轮召回时融合多个查询结果
     */
    public List<MemoryEntry> rrfFusionMultiple(List<List<MemoryEntry>> resultLists, int limit) {
        final int k = 60;
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, MemoryEntry> memoryMap = new HashMap<>();
        
        for (List<MemoryEntry> results : resultLists) {
            for (int i = 0; i < results.size(); i++) {
                MemoryEntry entry = results.get(i);
                Long id = entry.getId();
                double score = 1.0 / (k + i + 1);
                scores.merge(id, score, Double::sum);
                memoryMap.putIfAbsent(id, entry);
            }
        }
        
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> memoryMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
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

    /**
     * 保存或更新项目记忆
     */
    @Transactional
    public ProjectMemory saveProjectMemory(ProjectMemory projectMemory) {
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

    /**
     * 保存用户记忆
     */
    @Transactional
    public UserMemory saveUserMemory(UserMemory userMemory) {
        UserMemory existing = userMemoryRepository.findByUserId(userMemory.getUserId()).orElse(null);
        if (existing != null) {
            userMemory.setId(existing.getId());
            userMemory.setCreatedAt(existing.getCreatedAt());
        }
        return userMemoryRepository.save(userMemory);
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

