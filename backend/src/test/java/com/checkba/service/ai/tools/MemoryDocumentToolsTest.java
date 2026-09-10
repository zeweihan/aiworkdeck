// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.memory.AgenticRetriever;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.service.ai.memory.ProjectMemoryExtractor;
import com.checkba.service.ai.memory.document.MemoryDocumentService;
import com.checkba.service.ai.memory.document.MemoryFileView;
import com.checkba.service.ai.memory.document.MemorySpaceView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class MemoryDocumentToolsTest {
    private MemoryDocumentService documents;
    private MemoryTools tools;

    @BeforeEach
    void setUp() {
        documents = mock(MemoryDocumentService.class);
        tools = new MemoryTools(mock(MemoryManager.class), mock(ProjectMemoryExtractor.class),
                mock(AgenticRetriever.class));
        tools.setMemoryDocumentServiceForTest(documents);
        ProjectContextHolder.setUserId(7L);
        ProjectContextHolder.setProjectId("23");
        when(documents.listSpaces(7L, 23L)).thenReturn(List.of(
                new MemorySpaceView("personal-id", "user", "个人记忆", true, true, true, null),
                new MemorySpaceView("project-id", "project", "项目记忆", true, true, true, null),
                new MemorySpaceView("team:9", "team", "团队记忆", true, false, true, null),
                MemorySpaceView.unavailable("firm", "律所记忆", "尚未加入律所")));
    }

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    @Test
    void readToolsResolveAuthorizedScopeWithoutAcceptingOwnerIds() {
        MemoryFileView file = file("topics/style.md", "# 行文风格", 4, false);
        when(documents.listFiles(7L, "team:9")).thenReturn(List.of(file));
        when(documents.read(7L, "team:9", "topics/style.md")).thenReturn(file);
        when(documents.search(7L, "team:9", "风格", 10)).thenReturn(List.of(file));

        assertTrue(tools.memory_list("team").contains("topics/style.md"));
        assertTrue(tools.memory_read("team", "topics/style.md").contains("# 行文风格"));
        assertTrue(tools.memory_search("team", "风格").contains("topics/style.md"));

        verify(documents).listFiles(7L, "team:9");
        verify(documents).read(7L, "team:9", "topics/style.md");
        verify(documents).search(7L, "team:9", "风格", 10);
    }

    @Test
    void writeEditAndDeleteCarryOptimisticRevision() {
        when(documents.write(7L, "project-id", "topics/case.md", "# 案情", 0))
                .thenReturn(file("topics/case.md", "# 案情", 1, true));
        when(documents.read(7L, "project-id", "topics/case.md"))
                .thenReturn(file("topics/case.md", "唯一旧句", 1, true));
        when(documents.write(7L, "project-id", "topics/case.md", "唯一新句", 1))
                .thenReturn(file("topics/case.md", "唯一新句", 2, true));

        assertTrue(tools.memory_write("project", "topics/case.md", "# 案情", 0).contains("revision=1"));
        assertTrue(tools.memory_edit("project", "topics/case.md", "唯一旧句", "唯一新句", 1)
                .contains("revision=2"));
        assertTrue(tools.memory_delete("project", "topics/case.md", 2).contains("已删除"));

        verify(documents).delete(7L, "project-id", "topics/case.md", 2);
    }

    @Test
    void editRefusesAmbiguousReplacementAndUnavailableScope() {
        when(documents.read(7L, "project-id", "topics/case.md"))
                .thenReturn(file("topics/case.md", "重复 重复", 3, true));

        assertTrue(tools.memory_edit("project", "topics/case.md", "重复", "替换", 3).contains("恰好出现一次"));
        assertTrue(tools.memory_read("firm", "remember.md").contains("尚未加入律所"));
        verify(documents, never()).write(eq(7L), eq("project-id"), anyString(), anyString(), anyLong());
    }

    private static MemoryFileView file(String path, String content, long revision, boolean writable) {
        return new MemoryFileView(path, "标题", content, revision, LocalDateTime.of(2026, 9, 10, 9, 0), writable);
    }
}
