// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
 * 2. 触发 Markdown 记忆的 Git 同步
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
    private final MemoryManager memoryManager;
    private final ConversationSummarizer conversationSummarizer;
    private final ContextCompressor contextCompressor;
    private final com.checkba.version.memory.MemorySyncService memorySyncService;

    /**
     * 一轮对话（Agent 循环）完成后的记忆更新。异步执行，失败不影响对话主流程。
     * 独立池：本方法同步阻塞调 LLM（摘要/MemCell 提取），一次可占几十秒，
     * 放 taskExecutor 会与编排循环抢线程（F-08），故隔离到 memoryExecutor。
     */
    @Async("memoryExecutor")
    public void onConversationTurnCompleted(String conversationId, String projectId,
                                            Long userId, List<ChatMessage> messages) {
        // 独立线程池：平台通道按用户计费，摘要/抽取这几次 LLM 调用要落在本人的额度上
        com.checkba.service.ai.PlatformAiUserScope.run(userId,
                () -> runPipeline(conversationId, projectId, userId, messages));
    }

    private void runPipeline(String conversationId, String projectId,
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

        // 2. 模型在对话内通过 memory_write/memory_edit 立即持久化确定信息。后台不再并行运行
        //    正则与 LLM 抽取写者，避免同一事实生成重复记录；长对话摘要仍由上面的压缩路径维护。

        // 3. 记忆 Git 同步（防抖导出 + push，spec Phase A）。方法自身吞掉一切异常且只对
        //    已配置同步的领域生效，这里再包一层保险——同步永远不能反噬记忆管线。
        try {
            memorySyncService.onMemoriesTouched(projectIdLong, userId);
        } catch (Exception e) {
            log.warn("Memory git sync trigger failed (swallowed): {}", e.getMessage());
        }
    }
}
