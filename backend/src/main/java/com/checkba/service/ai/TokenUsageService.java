package com.checkba.service.ai;

import com.checkba.repository.TokenUsageRepository;
// import dev.langchain4j.model.output.TokenUsage; // Removed to avoid collision, use FQN
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TokenUsageService.class);

    private final TokenUsageRepository tokenUsageRepository;

    /**
     * 记录 Token 使用情况和成本
     */
    @Transactional
    public void recordUsage(Long projectId, Long userId, String modelId, dev.langchain4j.model.output.TokenUsage usage, String conversationId) {
        if (usage == null) return;

        try {
            com.checkba.model.entity.TokenUsage entity = new com.checkba.model.entity.TokenUsage();
            entity.setProjectId(projectId);
            entity.setUserId(userId);
            // model 列非空约束：空 modelId（走供应商默认模型的请求）落 "default"，
            // 否则 save 失败会把整个流式事务标记 rollback-only，对话在收尾时报错
            entity.setModel((modelId == null || modelId.isBlank()) ? "default" : modelId);
            entity.setConversationId(conversationId);
            
            int promptTokens = usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
            int completionTokens = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
            int totalTokens = usage.totalTokenCount() != null ? usage.totalTokenCount() : (promptTokens + completionTokens);

            entity.setPromptTokens(promptTokens);
            entity.setCompletionTokens(completionTokens);
            entity.setTotalTokens(totalTokens);

            // Calculate Cost
            BigDecimal cost = calculateCost(modelId, promptTokens, completionTokens);
            entity.setCost(cost);

            tokenUsageRepository.save(entity);
            log.debug("Recorded usage for model {}: {} tokens, ${}", modelId, totalTokens, cost);
        } catch (Exception e) {
            log.error("Failed to record token usage", e);
        }
    }

    private BigDecimal calculateCost(String modelId, int promptTokens, int completionTokens) {
        AllowedModels model = AllowedModels.fromId(modelId);
        if (model == null) {
            return BigDecimal.ZERO; // Unknown model, 0 cost
        }

        BigDecimal inputPrice = BigDecimal.valueOf(model.getInputPricePerM());
        BigDecimal outputPrice = BigDecimal.valueOf(model.getOutputPricePerM());
        BigDecimal millions = BigDecimal.valueOf(1_000_000);

        BigDecimal inputCost = BigDecimal.valueOf(promptTokens).multiply(inputPrice).divide(millions, 10, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(completionTokens).multiply(outputPrice).divide(millions, 10, RoundingMode.HALF_UP);

        return inputCost.add(outputCost);
    }
}
