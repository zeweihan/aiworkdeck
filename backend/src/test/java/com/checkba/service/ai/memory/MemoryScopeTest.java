package com.checkba.service.ai.memory;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.tools.MemoryTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 记忆作用域测试：MemoryEntry 作用域模型与 save_memory 工具的作用域路由。
 */
class MemoryScopeTest {

    @AfterEach
    void tearDown() {
        ProjectContextHolder.clear();
    }

    @Test
    @DisplayName("作用域常量校验")
    void validatesScopes() {
        assertTrue(MemoryEntry.MemoryScope.isValid("user"));
        assertTrue(MemoryEntry.MemoryScope.isValid("PROJECT"));
        assertTrue(MemoryEntry.MemoryScope.isValid("file"));
        assertTrue(MemoryEntry.MemoryScope.isValid("global"));
        assertTrue(MemoryEntry.MemoryScope.isValid("conversation"));
        assertFalse(MemoryEntry.MemoryScope.isValid("team"));
        assertFalse(MemoryEntry.MemoryScope.isValid(null));
    }

    @Test
    @DisplayName("实体默认作用域为 project")
    void defaultsToProjectScope() {
        MemoryEntry entry = MemoryEntry.builder().memoryType("fact").memoryValue("v").build();
        assertEquals(MemoryEntry.MemoryScope.PROJECT, entry.getScope());
    }

    @Test
    @DisplayName("save_memory：用户级记忆写入 userId 与 scope")
    void savesUserScopedMemory() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryTools tools = new MemoryTools(memoryManager,
                mock(ProjectMemoryExtractor.class), mock(AgenticRetriever.class));

        ProjectContextHolder.setProjectId("10");
        ProjectContextHolder.setConversationId("conv-1");
        ProjectContextHolder.setUserId(7L);

        String result = tools.save_memory("preference", "行文风格", "偏好书面化、条款式表达", false, "user", null);
        assertTrue(result.contains("✓"), "should succeed: " + result);

        ArgumentCaptor<MemoryEntry> captor = ArgumentCaptor.forClass(MemoryEntry.class);
        verify(memoryManager).saveMemory(captor.capture());
        MemoryEntry saved = captor.getValue();
        assertEquals(MemoryEntry.MemoryScope.USER, saved.getScope());
        assertEquals(7L, saved.getUserId());
        assertEquals("preference", saved.getMemoryType());
    }

    @Test
    @DisplayName("save_memory：无 userId 时拒绝用户级记忆")
    void rejectsUserScopeWithoutUserId() {
        MemoryTools tools = new MemoryTools(mock(MemoryManager.class),
                mock(ProjectMemoryExtractor.class), mock(AgenticRetriever.class));
        ProjectContextHolder.setProjectId("10");

        String result = tools.save_memory("preference", "k", "v", false, "user", null);
        assertTrue(result.contains("错误"));
    }

    @Test
    @DisplayName("save_memory：文件级记忆必须携带 sourceFileId")
    void requiresSourceFileIdForFileScope() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryTools tools = new MemoryTools(memoryManager,
                mock(ProjectMemoryExtractor.class), mock(AgenticRetriever.class));
        ProjectContextHolder.setProjectId("10");

        String rejected = tools.save_memory("conclusion", "审查结论", "该合同存在对赌条款", false, "file", null);
        assertTrue(rejected.contains("错误"));

        String accepted = tools.save_memory("conclusion", "审查结论", "该合同存在对赌条款", true, "file", 33L);
        assertTrue(accepted.contains("✓"));

        ArgumentCaptor<MemoryEntry> captor = ArgumentCaptor.forClass(MemoryEntry.class);
        verify(memoryManager).saveMemory(captor.capture());
        assertEquals(33L, captor.getValue().getSourceFileId());
        assertEquals(MemoryEntry.MemoryScope.FILE, captor.getValue().getScope());
        assertEquals(1.0, captor.getValue().getImportanceScore());
    }

    @Test
    @DisplayName("save_memory：非法作用域回落到 project")
    void fallsBackToProjectScope() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryTools tools = new MemoryTools(memoryManager,
                mock(ProjectMemoryExtractor.class), mock(AgenticRetriever.class));
        ProjectContextHolder.setProjectId("10");

        String result = tools.save_memory("fact", "k", "v", false, "galaxy", null);
        assertTrue(result.contains("✓"));

        ArgumentCaptor<MemoryEntry> captor = ArgumentCaptor.forClass(MemoryEntry.class);
        verify(memoryManager).saveMemory(captor.capture());
        assertEquals(MemoryEntry.MemoryScope.PROJECT, captor.getValue().getScope());
    }
}
