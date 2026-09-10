// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.MemoryEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect")
class MemoryEntryRepositoryIsolationTest {
    @Autowired private MemoryEntryRepository repository;

    @Test
    void projectRetrievalCannotLeakUserOrGlobalRowsThatCarryAProjectId() {
        MemoryEntry project = save("project", "可见事实");
        save("file", "可见文件事实");
        save("conversation", "可见对话事实");
        save("user", "私有偏好");
        save("global", "全局资料");

        assertEquals(Set.of("可见事实", "可见文件事实", "可见对话事实"),
                repository.findTopImportantMemories(9L, PageRequest.of(0, 10)).stream()
                        .map(MemoryEntry::getMemoryValue).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of(project.getId()), repository.searchByKeywordAndType(
                9L, "可见事实", null, PageRequest.of(0, 10)).stream().map(MemoryEntry::getId).toList());
    }

    @Test
    void fileAndConversationLookupRequireTheirDeclaredScope() {
        MemoryEntry file = save("file", "文件记忆");
        file.setSourceFileId(42L);
        repository.save(file);
        MemoryEntry projectWithFileId = save("project", "项目记忆");
        projectWithFileId.setSourceFileId(42L);
        projectWithFileId.setConversationId("conv-1");
        repository.save(projectWithFileId);
        MemoryEntry conversation = save("conversation", "对话记忆");
        conversation.setConversationId("conv-1");
        repository.save(conversation);

        assertEquals(List.of(file.getId()), repository.findBySourceFileIdOrderByImportanceScoreDesc(42L)
                .stream().map(MemoryEntry::getId).toList());
        assertEquals(List.of(conversation.getId()), repository.findByConversationIdOrderByCreatedAtDesc("conv-1")
                .stream().map(MemoryEntry::getId).toList());
    }

    private MemoryEntry save(String scope, String value) {
        return repository.saveAndFlush(MemoryEntry.builder()
                .projectId(9L).userId(5L).scope(scope).memoryType("fact")
                .memoryKey(value).memoryValue(value).importanceScore(0.7).isProtected(false).build());
    }
}
