package com.checkba.service.ai.memory;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.service.ai.ChatModelFactory;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agentic 多轮召回检索器
 * 借鉴 EverMemOS 的 Agentic Retrieval 概念
 * 
 * 核心特性：
 * 1. 首次检索后评估结果充分性
 * 2. 若不足，使用 LLM 生成 2-3 个补充查询
 * 3. 并行执行补充检索
 * 4. 使用 RRF 融合所有结果
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgenticRetriever {

    private final MemoryManager memoryManager;
    private final ChatModelFactory chatModelFactory;
    // 查询扩展走辅助模型（便宜档）并落账：deep_search 一次可能多打好几轮，此前既不换模型也不记账
    private final com.checkba.service.ai.AuxModelResolver auxModelResolver;
    private final com.checkba.service.ai.TokenUsageService tokenUsageService;

    // 配置参数
    private static final int MIN_RESULTS_THRESHOLD = 3;  // 结果少于此数量时触发多轮召回
    private static final int MAX_SUPPLEMENTARY_QUERIES = 3;  // 最多生成的补充查询数
    private static final double MIN_RELEVANCE_SCORE = 0.3;  // 最低相关性阈值
    private static final int RETRIEVAL_TIMEOUT_SECONDS = 10;  // 检索超时时间
    
    /**
     * 查询生成提示词
     */
    private static final String QUERY_EXPANSION_PROMPT = """
        你是一个智能检索助手，需要为法律项目记忆检索生成补充查询。
        
        ## 任务
        用户的原始查询可能不够精确或完整，请生成 2-3 个补充查询来扩展检索范围。
        
        ## 生成规则
        1. 每个查询应该关注原始查询的不同方面
        2. 使用法律领域的专业术语
        3. 考虑同义词和相关概念
        4. 查询应该简洁明确（10-30字）
        
        ## 输出格式
        每行一个查询，不要编号，不要其他内容。
        
        ## 原始查询
        %s
        
        ## 已有结果摘要（如果结果不足，可以参考这些来生成更好的补充查询）
        %s
        """;

    /**
     * Agentic 检索
     * 如果首次检索结果不足，自动生成补充查询并融合结果
     * 
     * @param projectId 项目ID
     * @param query 原始查询
     * @param limit 期望的结果数量
     * @return 融合后的检索结果
     */
    public List<MemoryEntry> agenticRetrieve(Long projectId, String query, int limit) {
        log.info("Agentic retrieval started: projectId={}, query={}, limit={}", 
                projectId, query, limit);
        
        // 1. 首次混合检索
        List<MemoryEntry> initialResults = memoryManager.hybridSearch(projectId, query, limit);
        
        // 2. 评估结果充分性
        RetrievalAssessment assessment = assessResults(initialResults, query, limit);
        
        if (assessment.isSufficient()) {
            log.info("Initial retrieval sufficient: {} results, skipping agentic expansion", 
                    initialResults.size());
            return initialResults;
        }
        
        log.info("Initial retrieval insufficient ({}), triggering agentic expansion", 
                initialResults.size());
        
        // 3. 生成补充查询（projectId 带下去做记账归属）
        List<String> supplementaryQueries = generateSupplementaryQueries(
                query,
                assessment.getResultSummary(),
                projectId
        );
        
        if (supplementaryQueries.isEmpty()) {
            log.warn("No supplementary queries generated, returning initial results");
            return initialResults;
        }
        
        log.info("Generated {} supplementary queries", supplementaryQueries.size());
        
        // 4. 并行执行补充检索
        List<List<MemoryEntry>> allResults = new ArrayList<>();
        allResults.add(initialResults);
        
        List<List<MemoryEntry>> supplementaryResults = executeParallelRetrieval(
                projectId, 
                supplementaryQueries, 
                limit
        );
        allResults.addAll(supplementaryResults);
        
        // 5. RRF 融合所有结果
        List<MemoryEntry> fusedResults = memoryManager.rrfFusionMultiple(allResults, limit);
        
        log.info("Agentic retrieval completed: initial={}, supplementary queries={}, final={}",
                initialResults.size(), supplementaryQueries.size(), fusedResults.size());
        
        return fusedResults;
    }

    /**
     * 评估检索结果的充分性
     */
    private RetrievalAssessment assessResults(List<MemoryEntry> results, String query, int limit) {
        RetrievalAssessment assessment = new RetrievalAssessment();
        
        // 检查结果数量
        if (results.size() >= MIN_RESULTS_THRESHOLD && results.size() >= limit / 2) {
            assessment.setSufficient(true);
        }
        
        // 构建结果摘要（用于生成补充查询）
        if (!results.isEmpty()) {
            StringBuilder summary = new StringBuilder();
            for (int i = 0; i < Math.min(results.size(), 5); i++) {
                MemoryEntry entry = results.get(i);
                summary.append("- [").append(entry.getMemoryType()).append("] ");
                if (entry.getMemoryKey() != null) {
                    summary.append(entry.getMemoryKey()).append(": ");
                }
                String value = entry.getMemoryValue();
                if (value.length() > 100) {
                    value = value.substring(0, 100) + "...";
                }
                summary.append(value).append("\n");
            }
            assessment.setResultSummary(summary.toString());
        } else {
            assessment.setResultSummary("无相关结果");
        }
        
        return assessment;
    }

    /**
     * 使用 LLM 生成补充查询（辅助模型档，token 落 token_usage）
     */
    private List<String> generateSupplementaryQueries(String originalQuery, String resultSummary, Long projectId) {
        try {
            ChatLanguageModel model = chatModelFactory.getAuxChatModel();

            String prompt = String.format(QUERY_EXPANSION_PROMPT, originalQuery, resultSummary);

            var response = model.generate(
                    SystemMessage.from("你是一个专业的法律信息检索助手。"),
                    UserMessage.from(prompt)
            );
            recordUsage(response, projectId);

            String responseText = response.content().text();
            return parseQueries(responseText, originalQuery);
            
        } catch (Exception e) {
            log.error("Failed to generate supplementary queries: {}", e.getMessage());
            // 降级：使用简单的规则生成
            return generateFallbackQueries(originalQuery);
        }
    }

    /**
     * 查询扩展调用的 token 记账。
     *
     * <p>userId/conversationId 取工具执行上下文（{@code ToolContextHolder}，由 ToolRegistry 在
     * 分发 deep_search 时装填）；拿不到就退回平台身份作用域。记账失败绝不影响检索结果。
     */
    private void recordUsage(dev.langchain4j.model.output.Response<dev.langchain4j.data.message.AiMessage> response,
                            Long projectId) {
        if (response == null || response.tokenUsage() == null) {
            return;
        }
        try {
            com.checkba.service.ai.tools.ToolContext ctx =
                    com.checkba.service.ai.tools.ToolContextHolder.get();
            Long userId = ctx != null && ctx.userId() != null
                    ? ctx.userId()
                    : com.checkba.service.ai.PlatformAiUserScope.current();
            String conversationId = ctx != null ? ctx.conversationId() : null;
            tokenUsageService.recordUsage(projectId, userId,
                    auxModelResolver.auxModelId(), response.tokenUsage(), conversationId);
        } catch (Exception e) {
            log.warn("深度检索查询扩展 token 记账失败（不影响检索结果）: {}", e.getMessage());
        }
    }

    /**
     * 解析 LLM 返回的查询列表
     */
    private List<String> parseQueries(String responseText, String originalQuery) {
        List<String> queries = new ArrayList<>();
        
        String[] lines = responseText.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            // 移除可能的编号前缀
            trimmed = trimmed.replaceFirst("^\\d+[.、)\\s]+", "");
            trimmed = trimmed.replaceFirst("^[-•*]\\s*", "");
            
            if (!trimmed.isEmpty() && 
                trimmed.length() >= 5 && 
                trimmed.length() <= 100 &&
                !trimmed.equalsIgnoreCase(originalQuery)) {
                queries.add(trimmed);
            }
            
            if (queries.size() >= MAX_SUPPLEMENTARY_QUERIES) {
                break;
            }
        }
        
        return queries;
    }

    /**
     * 降级：使用规则生成备用查询
     */
    private List<String> generateFallbackQueries(String originalQuery) {
        List<String> fallback = new ArrayList<>();
        
        // 尝试提取关键词并重组
        String[] keywords = originalQuery.split("[\\s，,。、]+");
        if (keywords.length >= 2) {
            // 反转关键词顺序
            StringBuilder reversed = new StringBuilder();
            for (int i = keywords.length - 1; i >= 0; i--) {
                if (!keywords[i].isEmpty()) {
                    if (reversed.length() > 0) reversed.append(" ");
                    reversed.append(keywords[i]);
                }
            }
            if (reversed.length() > 0) {
                fallback.add(reversed.toString());
            }
        }
        
        // 添加法律相关的同义词扩展
        Map<String, String> synonyms = Map.of(
                "股权", "股份",
                "公司", "企业",
                "合同", "协议",
                "收购", "并购",
                "投资", "出资",
                "法律", "法规"
        );
        
        for (Map.Entry<String, String> entry : synonyms.entrySet()) {
            if (originalQuery.contains(entry.getKey())) {
                fallback.add(originalQuery.replace(entry.getKey(), entry.getValue()));
                break;
            }
        }
        
        return fallback;
    }

    /**
     * 并行执行补充检索
     */
    private List<List<MemoryEntry>> executeParallelRetrieval(Long projectId, 
                                                               List<String> queries, 
                                                               int limit) {
        List<List<MemoryEntry>> results = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(queries.size(), 3)
        );
        
        try {
            List<CompletableFuture<List<MemoryEntry>>> futures = new ArrayList<>();
            
            for (String query : queries) {
                CompletableFuture<List<MemoryEntry>> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return memoryManager.hybridSearch(projectId, query, limit);
                    } catch (Exception e) {
                        log.error("Supplementary retrieval failed for query '{}': {}", 
                                query, e.getMessage());
                        return Collections.emptyList();
                    }
                }, executor);
                
                futures.add(future);
            }
            
            // 等待所有检索完成（带超时）
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            );
            
            try {
                allOf.get(RETRIEVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("Some supplementary retrievals timed out");
            } catch (InterruptedException e) {
                log.warn("Supplementary retrieval interrupted");
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.error("Supplementary retrieval execution error: {}", e.getMessage());
            }
            
            // 收集已完成的结果
            for (CompletableFuture<List<MemoryEntry>> future : futures) {
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    try {
                        List<MemoryEntry> result = future.getNow(Collections.emptyList());
                        if (!result.isEmpty()) {
                            results.add(result);
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
            
        } finally {
            executor.shutdown();
        }
        
        return results;
    }

    /**
     * 轻量级检索（跳过 LLM 调用）
     * 对于延迟敏感的场景，使用纯混合检索
     */
    public List<MemoryEntry> lightweightRetrieve(Long projectId, String query, int limit) {
        log.info("Lightweight retrieval: projectId={}, query={}", projectId, query);
        return memoryManager.hybridSearch(projectId, query, limit);
    }

    /**
     * 检索评估结果
     */
    @Data
    private static class RetrievalAssessment {
        private boolean sufficient = false;
        private String resultSummary = "";
        private double averageRelevance = 0.0;
    }
}

