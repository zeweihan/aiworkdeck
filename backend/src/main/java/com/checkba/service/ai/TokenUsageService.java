package com.checkba.service.ai;

import com.checkba.repository.TokenUsageRepository;
// import dev.langchain4j.model.output.TokenUsage; // Removed to avoid collision, use FQN
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TokenUsageService.class);

    private final TokenUsageRepository tokenUsageRepository;
    private final ChatModelFactory chatModelFactory;
    private final PlatformUsageAccountant platformUsageAccountant;

    /**
     * 记录 Token 使用情况和成本。
     *
     * cost 分两套口径（Spec §3）：
     * <ul>
     *   <li>BYOK：按单价表本地估算，{@code costSource=estimate}——这是「本地统计」，不是账单；</li>
     *   <li>平台通道：cost 先留空，由 {@link PlatformUsageAccountant} 异步用 OpenRouter 的
     *       实际扣费补上，{@code costSource=platform}。平台的钱不允许用估算值顶替。</li>
     * </ul>
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

            boolean platformChannel = isPlatformChannel();
            if (platformChannel) {
                entity.setCost(null);
                entity.setCostSource(PlatformUsageAccountant.SOURCE_PLATFORM);
            } else {
                entity.setCost(calculateCost(modelId, promptTokens, completionTokens));
                entity.setCostSource(PlatformUsageAccountant.SOURCE_ESTIMATE);
            }

            tokenUsageRepository.save(entity);
            if (platformChannel) {
                // userId 决定这笔账查哪把 key 的累计消费（server 模式下密钥是 per-user 的）
                scheduleReconcile(entity.getId(), userId);
            }
            log.debug("Recorded usage for model {}: {} tokens, source={}",
                    modelId, totalTokens, entity.getCostSource());
        } catch (Exception e) {
            log.error("Failed to record token usage", e);
        }
    }

    /**
     * 对账必须等事务提交后再入队：id 在 flush 时就有了，但对账 worker 是另一条连接，
     * 提交前 findById 查不到这条记录会静默 no-op，该条的 cost 永久留空。
     */
    private void scheduleReconcile(Long tokenUsageId, Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            platformUsageAccountant.reconcileAsync(tokenUsageId, userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                platformUsageAccountant.reconcileAsync(tokenUsageId, userId);
            }
        });
    }

    /** 当前是否走平台通道。供应商解析失败按 BYOK 处理——记账问题不该拖垮对话。 */
    private boolean isPlatformChannel() {
        try {
            return chatModelFactory.resolveProvider()
                    == com.checkba.config.AiModelProperties.Provider.AWD_CLOUD;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * BYOK 本地估算单价 × token 数。
     *
     * <p><b>分档</b>：OpenRouter 对部分模型按输入长度分档涨价（本白名单里 4 个模型有分档），
     * 所以单价必须按本轮的 promptTokens 取档——只读首档会在长上下文下系统性低报。
     *
     * <p><b>已知偏差，不是 bug</b>：命中提示缓存的轮次估算值会偏高。多数模型的
     * {@code input_cache_read} 单价约为输入价的 1/10，但 langchain4j 0.36 的 TokenUsage
     * 只回 input/output 两个数，拿不到缓存命中的 token 数，无法把这一档建模进来。
     * 真花的钱以平台对账为准（{@link PlatformUsageAccountant}），这里的值只是本地统计。
     */
    private BigDecimal calculateCost(String modelId, int promptTokens, int completionTokens) {
        AllowedModels model = AllowedModels.fromId(modelId);
        if (model == null) {
            return BigDecimal.ZERO; // Unknown model, 0 cost
        }

        AllowedModels.PriceTier tier = model.priceTierFor(promptTokens);
        BigDecimal inputPrice = BigDecimal.valueOf(tier.inputPricePerM());
        BigDecimal outputPrice = BigDecimal.valueOf(tier.outputPricePerM());
        BigDecimal millions = BigDecimal.valueOf(1_000_000);

        BigDecimal inputCost = BigDecimal.valueOf(promptTokens).multiply(inputPrice).divide(millions, 10, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(completionTokens).multiply(outputPrice).divide(millions, 10, RoundingMode.HALF_UP);

        return inputCost.add(outputCost);
    }
}
