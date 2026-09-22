// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        when(memoryManager.retrieveFileMemories(1L, 42L)).thenReturn(List.of(fileScopedEntry()));
        when(memoryManager.formatAsEvidenceLedger(any())).thenReturn("[EVIDENCE:99]");

        String result = tools.query_memory("随便什么不相关的词", null, "file", 42L, null, null);

        assertFalse(result.contains("未找到相关记忆"), "明确按文件 scope 查找时不该说没找到: " + result);
        assertTrue(result.contains("[EVIDENCE:99]"));
        verify(memoryManager).retrieveFileMemories(1L, 42L);
    }

    @Test
    @DisplayName("对照：不传 scope 时行为不变——关键词检索为空就是没找到")
    void queryMemoryWithoutScopeIsUnaffected() {
        when(memoryManager.retrieveMemories(eq(1L), any(), any(), anyInt())).thenReturn(List.of());

        String result = tools.query_memory("随便什么不相关的词", null, null, null, null, null);

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
        when(memoryManager.retrieveConversationMemories(1L, "conv-1")).thenReturn(List.of(convEntry));

        String result = tools.search_knowledge_base("不相关的词", 5, "conversation", null);

        assertFalse(result.contains("未在项目记忆中找到相关信息"), "明确按对话 scope 查找时不该说没找到: " + result);
        assertTrue(result.contains("本次对话讨论的要点"));
        verify(memoryManager).retrieveConversationMemories(1L, "conv-1");
    }

    @Test
    @DisplayName("修复：deep_search 传 scope=file 时要能找到绑定该文件的记忆")
    void deepSearchFindsFileScopedEntry() {
        when(memoryManager.retrieveFileMemories(1L, 42L)).thenReturn(List.of(fileScopedEntry()));

        String result = tools.deep_search("不相关的词", 10, "file", 42L);

        assertFalse(result.contains("未在项目记忆中找到相关信息"), "明确按文件 scope 查找时不该说没找到: " + result);
        assertTrue(result.contains("该合同第 5 条存在争议"));
    }

    // ==================== depth 三档（dev-board#807，审计 A13） ====================

    @Test
    @DisplayName("depth=hybrid 走 RRF 融合，与旧 search_knowledge_base 同一条算法")
    void hybridDepthRunsTheRrfSearch() {
        when(memoryManager.hybridSearch(eq(1L), any(), anyInt())).thenReturn(List.of(fileScopedEntry()));

        String result = tools.query_memory("争议", null, null, null, "hybrid", 5);

        assertTrue(result.contains("RRF 融合"), result);
        assertTrue(result.contains("该合同第 5 条存在争议"), result);
        verify(memoryManager).hybridSearch(eq(1L), any(), anyInt());
        verify(memoryManager, never()).retrieveMemories(anyLong(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("depth=deep 才起 Agentic 多轮召回——这一档要额外花一次辅助模型调用，不该被误触发")
    void deepDepthIsTheOnlyOneThatSpendsAnExtraModelCall() {
        when(agenticRetriever.agenticRetrieve(eq(1L), any(), anyInt())).thenReturn(List.of(fileScopedEntry()));

        String result = tools.query_memory("争议", null, null, null, "deep", null);

        assertTrue(result.contains("Agentic 多轮召回"), result);
        verify(agenticRetriever).agenticRetrieve(eq(1L), any(), anyInt());

        // 另外两档一次都不许碰它
        tools.query_memory("争议", null, null, null, null, null);
        tools.query_memory("争议", null, null, null, "hybrid", null);
        verify(agenticRetriever, times(1)).agenticRetrieve(anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("depth 填错不报错，回落 quick——检索档位写错绝不该让整次调用失败")
    void unknownDepthFallsBackToQuick() {
        when(memoryManager.retrieveMemories(eq(1L), any(), any(), anyInt())).thenReturn(List.of());

        String result = tools.query_memory("随便什么不相关的词", null, null, null, "超级深度", null);

        assertTrue(result.contains("未找到相关记忆"), result);
        verify(memoryManager).retrieveMemories(eq(1L), any(), any(), anyInt());
    }

    @Test
    @DisplayName("兼容入口仍然可执行，并在结果末尾指路 query_memory")
    void compatEntriesStillRunAndPointAtQueryMemory() {
        when(memoryManager.hybridSearch(eq(1L), any(), anyInt())).thenReturn(List.of(fileScopedEntry()));

        String result = tools.search_knowledge_base("争议", 5, null, null);

        assertTrue(result.contains("该合同第 5 条存在争议"), "兼容入口必须照常把结果给出来: " + result);
        assertTrue(result.contains("query_memory"), "末尾要指路，否则模型会一直照抄旧名字: " + result);
    }
}
