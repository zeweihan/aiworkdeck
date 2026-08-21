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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 审计条目：「Regex project-memory extraction failure silently skips the LLM MemCell
 * extraction for the whole turn」。runPipeline 里 2.1（正则，低成本）与 2.2（LLM MemCell，
 * 有成本）此前共用一个 try/catch：2.1 一抛异常，2.2 整轮都不会跑，且日志上看不出区别
 * （都是一条 "Failed to extract project memory"）。
 */
@DisplayName("MemoryPipelineService：正则抽取失败不该连带跳过 LLM MemCell 抽取")
class MemoryPipelineServiceTest {

    private ProjectMemoryExtractor projectMemoryExtractor;
    private MemCellExtractor memCellExtractor;
    private MemoryPipelineService pipeline;

    @BeforeEach
    void setUp() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        ConversationSummarizer conversationSummarizer = mock(ConversationSummarizer.class);
        projectMemoryExtractor = mock(ProjectMemoryExtractor.class);
        memCellExtractor = mock(MemCellExtractor.class);
        ContextCompressor contextCompressor = mock(ContextCompressor.class);
        MemorySyncService memorySyncService = mock(MemorySyncService.class);

        pipeline = new MemoryPipelineService(memoryManager, conversationSummarizer,
                projectMemoryExtractor, memCellExtractor, contextCompressor, memorySyncService);
    }

    private static List<ChatMessage> messages(int count) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(UserMessage.from("消息 " + i));
        }
        return list;
    }

    @Test
    @DisplayName("正则提取（2.1）抛异常时，LLM MemCell 抽取（2.2）依然要执行，不能被连带跳过")
    void regexFailureDoesNotSkipMemCellExtraction() {
        doThrow(new RuntimeException("模拟并发写 ProjectMemory 撞唯一约束"))
                .when(projectMemoryExtractor).extractAndUpdateProjectMemory(anyLong(), any());

        // @Async 方法在没有 Spring 代理的单测里就是普通同步调用，直接验证副作用即可
        pipeline.onConversationTurnCompleted("conv-1", "1", 9L, messages(4));

        verify(memCellExtractor, times(1)).extractAndSave(eq(1L), eq("conv-1"), any());
    }
}
