package com.checkba.service.ai.tools;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.memory.AgenticRetriever;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.service.ai.memory.ProjectMemoryExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计条目：「query_memory / search_knowledge_base / deep_search ignore memory scope entirely,
 * so file- and conversation-scoped saves are not reliably recallable」。
 *
 * save_memory(scope="file", sourceFileId=42) 存下的记忆，此前只能靠关键词/语义检索"运气好"
 * 才捞得到——三个检索工具完全不认 scope。修法是在调用方明确给出 scope 时，额外走一条
 * 确定性的按 scope 查找并入结果，不依赖检索算法本身的相关性判断。
 */
@DisplayName("MemoryTools：三个检索工具要认 scope")
class MemoryToolsScopeTest {

    private MemoryManager memoryManager;
    private AgenticRetriever agenticRetriever;
    private MemoryTools tools;

    @BeforeEach
    void setUp() {
        memoryManager = mock(MemoryManager.class);
        ProjectMemoryExtractor projectMemoryExtractor = mock(ProjectMemoryExtractor.class);
        agenticRetriever = mock(AgenticRetriever.class);
        when(agenticRetriever.agenticRetrieve(anyLong(), any(), anyInt())).thenReturn(List.of());
        tools = new MemoryTools(memoryManager, projectMemoryExtractor, agenticRetriever);

        ProjectContextHolder.setProjectId("1");
        ProjectContextHolder.setConversationId("conv-1");
    }

    @AfterEach
    void tearDown() {
        ProjectContextHolder.clear();
    }

    private static MemoryEntry fileScopedEntry() {
        return MemoryEntry.builder()
                .id(99L)
                .projectId(1L)
                .memoryType("fact")
                .memoryKey("审查结论")
                .memoryValue("该合同第 5 条存在争议")
                .scope(MemoryEntry.MemoryScope.FILE)
                .sourceFileId(42L)
                .importanceScore(0.7)
                .build();
    }

    @Test
    @DisplayName("修复：query_memory 传 scope=file 时，即便关键词检索为空也要能找到绑定该文件的记忆")
    void queryMemoryFindsFileScopedEntryEvenWhenKeywordSearchIsEmpty() {
        when(memoryManager.retrieveMemories(eq(1L), any(), any(), anyInt())).thenReturn(List.of());
        when(memoryManager.retrieveFileMemories(42L)).thenReturn(List.of(fileScopedEntry()));
        when(memoryManager.formatAsEvidenceLedger(any())).thenReturn("[EVIDENCE:99]");

        String result = tools.query_memory("随便什么不相关的词", null, "file", 42L);

        assertFalse(result.contains("未找到相关记忆"), "明确按文件 scope 查找时不该说没找到: " + result);
        assertTrue(result.contains("[EVIDENCE:99]"));
    }

    @Test
    @DisplayName("对照：不传 scope 时行为不变——关键词检索为空就是没找到")
    void queryMemoryWithoutScopeIsUnaffected() {
        when(memoryManager.retrieveMemories(eq(1L), any(), any(), anyInt())).thenReturn(List.of());

        String result = tools.query_memory("随便什么不相关的词", null, null, null);

        assertTrue(result.contains("未找到相关记忆"));
    }

    @Test
    @DisplayName("修复：search_knowledge_base 传 scope=conversation 时要能找到绑定当前对话的记忆")
    void searchKnowledgeBaseFindsConversationScopedEntry() {
        MemoryEntry convEntry = MemoryEntry.builder()
                .id(77L).projectId(1L).memoryType("fact")
                .memoryValue("本次对话讨论的要点").scope(MemoryEntry.MemoryScope.CONVERSATION)
                .conversationId("conv-1").importanceScore(0.7).build();
        when(memoryManager.hybridSearch(eq(1L), any(), anyInt())).thenReturn(List.of());
        when(memoryManager.retrieveConversationMemories("conv-1")).thenReturn(List.of(convEntry));

        String result = tools.search_knowledge_base("不相关的词", 5, "conversation", null);

        assertFalse(result.contains("未在知识库中找到相关信息"), "明确按对话 scope 查找时不该说没找到: " + result);
        assertTrue(result.contains("本次对话讨论的要点"));
    }

    @Test
    @DisplayName("修复：deep_search 传 scope=file 时要能找到绑定该文件的记忆")
    void deepSearchFindsFileScopedEntry() {
        when(memoryManager.retrieveFileMemories(42L)).thenReturn(List.of(fileScopedEntry()));

        String result = tools.deep_search("不相关的词", 10, "file", 42L);

        assertFalse(result.contains("深度搜索未找到相关信息"), "明确按文件 scope 查找时不该说没找到: " + result);
        assertTrue(result.contains("该合同第 5 条存在争议"));
    }
}
