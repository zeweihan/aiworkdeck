// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.memory.document;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.model.entity.Project;
import com.checkba.repository.MemoryDocumentRepository;
import com.checkba.repository.MemoryEntryRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect")
@Import(MemoryDocumentService.class)
class MemoryDocumentServiceTest {

    @Autowired private MemoryDocumentService service;
    @Autowired private ProjectRepository projects;
    @Autowired private MemoryEntryRepository legacyEntries;
    @Autowired private MemoryDocumentRepository documents;
    @MockBean private ProjectMemberService projectMembers;
    @MockBean private MemoryOrganizationGateway organizations;

    @BeforeEach
    void defaultOrganizationSpacesAreUnavailable() {
        when(organizations.listSpaces(anyLong())).thenReturn(List.of(
                MemorySpaceView.unavailable("team", "团队记忆", "尚未加入团队"),
                MemorySpaceView.unavailable("firm", "律所记忆", "尚未加入律所")));
    }

    @Test
    void projectDocumentCannotAppearForAnotherProjectsUser() {
        Project a = project(11L, "A 项目");
        Project b = project(22L, "B 项目");
        when(projectMembers.hasReadPermission(a.getId(), 11L)).thenReturn(true);
        when(projectMembers.hasWritePermission(a.getId(), 11L)).thenReturn(true);
        when(projectMembers.hasReadPermission(a.getId(), 22L)).thenReturn(false);
        when(projectMembers.hasReadPermission(b.getId(), 22L)).thenReturn(true);
        when(projectMembers.hasWritePermission(b.getId(), 22L)).thenReturn(true);

        String aSpace = space(service.listSpaces(11L, a.getId()), "project").id();
        service.write(11L, aSpace, "facts.md", "# A 的事实\n\n只属于 A 项目。", 0);

        MemoryDocumentException denied = assertThrows(MemoryDocumentException.class,
                () -> service.listFiles(22L, aSpace));
        assertEquals(403, denied.status());
        assertTrue(service.listFiles(22L,
                space(service.listSpaces(22L, b.getId()), "project").id()).stream()
                .noneMatch(f -> f.content() != null && f.content().contains("只属于 A 项目")));
    }

    @Test
    void topicLinksResolveAndDeleteKeepsIndexValid() {
        Project project = project(7L, "尽调项目");
        when(projectMembers.hasReadPermission(project.getId(), 7L)).thenReturn(true);
        when(projectMembers.hasWritePermission(project.getId(), 7L)).thenReturn(true);
        String spaceId = space(service.listSpaces(7L, project.getId()), "project").id();

        MemoryFileView topic = service.write(7L, spaceId, "decisions.md",
                "# 决策\n\n采用分步交割。", 0);
        MemoryFileView index = service.read(7L, spaceId, "remember.md");
        assertTrue(index.content().contains("[决策](decisions.md)"));
        assertEquals("采用分步交割。", service.read(7L, spaceId, "decisions.md")
                .content().lines().skip(2).findFirst().orElseThrow());

        service.delete(7L, spaceId, topic.path(), topic.revision());
        MemoryFileView afterDelete = service.read(7L, spaceId, "remember.md");
        assertFalse(afterDelete.content().contains("(decisions.md)"));
        assertEquals(List.of("remember.md"), service.listFiles(7L, spaceId).stream()
                .map(MemoryFileView::path).toList());
    }

    @Test
    void traversalAndNonMarkdownPathsAreRejected() {
        String userSpace = space(service.listSpaces(9L, null), "user").id();
        for (String path : List.of("../secret.md", "/tmp/secret.md", "a\\..\\secret.md", "notes.txt")) {
            MemoryDocumentException error = assertThrows(MemoryDocumentException.class,
                    () -> service.write(9L, userSpace, path, "x", 0));
            assertEquals(400, error.status(), path);
        }
        MemoryDocumentException oversized = assertThrows(MemoryDocumentException.class,
                () -> service.write(9L, userSpace, "large.md", "中".repeat(50_000), 0));
        assertEquals(400, oversized.status());
    }

    @Test
    void staleRevisionReturnsConflictWithoutOverwriting() {
        String userSpace = space(service.listSpaces(9L, null), "user").id();
        MemoryFileView created = service.write(9L, userSpace, "preferences.md", "第一版", 0);
        MemoryFileView updated = service.write(9L, userSpace, "preferences.md", "第二版", created.revision());

        MemoryDocumentException conflict = assertThrows(MemoryDocumentException.class,
                () -> service.write(9L, userSpace, "preferences.md", "陈旧覆盖", created.revision()));
        assertEquals(409, conflict.status());
        assertEquals(updated.revision(), service.read(9L, userSpace, "preferences.md").revision());
        assertEquals("第二版", service.read(9L, userSpace, "preferences.md").content());
    }

    @Test
    void deletedLegacyDocumentIsNotResurrectedByRepeatedMigration() {
        MemoryEntry legacy = MemoryEntry.builder()
                .userId(5L)
                .scope(MemoryEntry.MemoryScope.USER)
                .memoryType(MemoryEntry.MemoryType.PREFERENCE)
                .memoryKey("称谓")
                .memoryValue("使用韩律师")
                .build();
        legacy.setUid(UUID.randomUUID().toString());
        legacy = legacyEntries.saveAndFlush(legacy);

        MemoryFileView migrated = service.migrateLegacyEntry(legacy);
        assertEquals(MemoryEntry.MemoryScope.USER,
                legacyEntries.findFirstByUid(legacy.getUid()).orElseThrow().getScope());
        service.delete(5L, space(service.listSpaces(5L, null), "user").id(),
                migrated.path(), migrated.revision());
        assertNull(service.migrateLegacyEntry(legacy));
        assertTrue(documents.findBySourceMemoryUid(legacy.getUid()).orElseThrow().isDeleted());
    }

    @Test
    void noTeamStillReturnsVisibleUnavailableOrganizationSpaces() {
        List<MemorySpaceView> spaces = service.listSpaces(3L, null);
        assertEquals(List.of("user", "project", "team", "firm"),
                spaces.stream().map(MemorySpaceView::scope).toList());
        assertFalse(space(spaces, "team").available());
        assertFalse(space(spaces, "team").readable());
        assertNull(space(spaces, "team").id());
        assertEquals("尚未加入团队", space(spaces, "team").reason());
    }

    private Project project(Long ownerId, String name) {
        Project p = new Project();
        p.setName(name);
        p.setProjectType("OTHER");
        p.setListedCompanyName("");
        p.setTargetCompanyName("");
        p.setUserId(ownerId);
        return projects.saveAndFlush(p);
    }

    private static MemorySpaceView space(List<MemorySpaceView> spaces, String scope) {
        return spaces.stream().filter(s -> scope.equals(s.scope())).findFirst().orElseThrow();
    }
}
