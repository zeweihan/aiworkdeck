package com.checkba.service.ai.memory;

import com.checkba.model.entity.ConversationSummary;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.ConversationSummarizer;
import dev.langchain4j.data.message.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 记忆写入管线（记忆层的"写侧"）。
 *
 * 在每轮 Agent 循环结束后由编排器异步触发，负责：
 * 1. 对话摘要 / Episode 生成（消息数 >= EPISODE_THRESHOLD 时）
 * 2. 项目级记忆提取（正则提取法律引用、金额、日期、当事人——低成本，每轮执行）
 * 3. MemCell 原子记忆提取（LLM 提取——有成本，消息数 >= MEMCELL_THRESHOLD 时执行）
 *
 * 历史背景：此逻辑原以 ContextAssemblerService.postConversationUpdate 存在但从未被调用，
 * 现拆分为独立服务并接入编排循环，使记忆的"读侧"（ContextAssembler）与"写侧"解耦。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemoryPipelineService {

    /** 触发 Episode 摘要生成的最小消息数 */
    private static final int EPISODE_THRESHOLD = 15;
    /** 触发 LLM MemCell 提取的最小消息数（控制每轮对话的 LLM 成本） */
    private static final int MEMCELL_THRESHOLD = 4;

    private final MemoryManager memoryManager;
    private final ConversationSummarizer conversationSummarizer;
    private final ProjectMemoryExtractor projectMemoryExtractor;
    private final MemCellExtractor memCellExtractor;
    private final ContextCompressor contextCompressor;

    /**
     * 一轮对话（Agent 循环）完成后的记忆更新。异步执行，失败不影响对话主流程。
     */
    @Async("taskExecutor")
    public void onConversationTurnCompleted(String conversationId, String projectId,
                                            Long userId, List<ChatMessage> messages) {
        log.info("Memory pipeline triggered: conversationId={}, messageCount={}",
                conversationId, messages.size());

        Long projectIdLong = null;
        try {
            projectIdLong = projectId != null ? Long.parseLong(projectId) : null;
        } catch (NumberFormatException e) {
            // ignore
        }

        // 1. 生成对话摘要 / Episode（借鉴 EverMemOS 的结构化情景记忆）
        if (messages.size() >= EPISODE_THRESHOLD) {
            try {
                ConversationSummarizer.EpisodeResult episodeResult =
                        conversationSummarizer.generateEpisode(messages, conversationId, projectIdLong);

                ConversationSummarizer.SummaryResult summaryResult = episodeResult.getSummaryResult();

                ConversationSummary summary = episodeResult.toEntity(conversationId, projectIdLong, null);
                summary.setTokenCount(contextCompressor.estimateTokens(summaryResult.getSummaryText()));
                summary.setMessageCount(messages.size());

                memoryManager.updateConversationSummary(
                        conversationId,
                        summaryResult.getSummaryText(),
                        summaryResult.getKeyPoints(),
                        summaryResult.getLegalReferences(),
                        summaryResult.getMentionedEntities(),
                        summaryResult.getPendingTasks(),
                        contextCompressor.estimateTokens(summaryResult.getSummaryText()),
                        messages.size(),
                        null
                );

                log.info("Episode generated: type={}, events={}, key points={}, legal refs={}",
                        episodeResult.getEpisodeType(),
                        episodeResult.getEvents() != null ? episodeResult.getEvents().size() : 0,
                        summaryResult.getKeyPoints() != null ? summaryResult.getKeyPoints().size() : 0,
                        summaryResult.getLegalReferences() != null ? summaryResult.getLegalReferences().size() : 0);
            } catch (Exception e) {
                log.error("Failed to generate Episode: {}", e.getMessage(), e);
            }
        }

        // 2. 提取并更新项目记忆
        if (projectIdLong != null) {
            try {
                // 2.1 项目级记忆（正则提取，低成本）
                projectMemoryExtractor.extractAndUpdateProjectMemory(projectIdLong, messages);

                // 2.2 MemCell 原子记忆（LLM 提取，控制触发频率）
                if (messages.size() >= MEMCELL_THRESHOLD) {
                    int memCellCount = memCellExtractor.extractAndSave(projectIdLong, conversationId, messages);
                    if (memCellCount > 0) {
                        log.info("MemCell extraction completed: saved {} atomic memory units", memCellCount);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to extract project memory: {}", e.getMessage(), e);
            }
        }
    }
}
