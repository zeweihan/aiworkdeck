// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.memory;

import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.ConversationSummarizer;
import com.checkba.version.memory.MemorySyncService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Markdown 记忆工具已经让模型在确定内容时立即持久化。每轮结束再跑正则和 LLM 抽取会
 * 形成两个独立写者，产生重复主题与覆盖竞态；后台管线只保留长对话摘要和 Git 同步。
 */
@DisplayName("MemoryPipelineService：不再无条件重复抽取模型已写入的记忆")
class MemoryPipelineServiceTest {

    private MemorySyncService memorySyncService;
    private MemoryPipelineService pipeline;

    @BeforeEach
    void setUp() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        ConversationSummarizer conversationSummarizer = mock(ConversationSummarizer.class);
        ContextCompressor contextCompressor = mock(ContextCompressor.class);
        memorySyncService = mock(MemorySyncService.class);

        pipeline = new MemoryPipelineService(memoryManager, conversationSummarizer,
                contextCompressor, memorySyncService);
    }

    private static List<ChatMessage> messages(int count) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(UserMessage.from("消息 " + i));
        }
        return list;
    }

    @Test
    @DisplayName("普通轮次不调用正则或 LLM 记忆抽取器")
    void completedTurnDoesNotRunLegacyExtractors() {
        // @Async 方法在没有 Spring 代理的单测里就是普通同步调用，直接验证副作用即可
        pipeline.onConversationTurnCompleted("conv-1", "1", 9L, messages(4));

        verify(memorySyncService).onMemoriesTouched(1L, 9L);
    }
}
