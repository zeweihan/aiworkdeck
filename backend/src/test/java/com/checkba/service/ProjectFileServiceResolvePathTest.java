// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 计划审阅（dev-board#1022）：历史回放时按对话里「已保存到项目文件：」那行的相对路径反查 fileId。
 * 首段是 AI 助手文件夹的显示名，中英两种写法都要接受（库里真名是 AI Assistant Files）。
 */
@DisplayName("按相对路径解析 fileId")
class ProjectFileServiceResolvePathTest {

    private static final Long PID = 1L;
    private ProjectFileService service;

    @BeforeEach
    void setUp() {
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        ProjectFile root = file(10L, null, "AI Assistant Files", true);
        ProjectFile conv = file(11L, 10L, "股权转让", true);
        ProjectFile plan = file(12L, 11L, "三步工作计划.md", false);
        when(repo.findByProjectIdOrderBySortOrderAsc(PID)).thenReturn(List.of(root, conv, plan));
        service = new ProjectFileService(
                repo,
                mock(com.checkba.service.ai.ProjectRagService.class),
                mock(StorageServiceFactory.class),
                mock(com.checkba.version.WorkSessionService.class),
                mock(UserService.class),
                mock(com.checkba.service.quota.StageQuotaService.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.evidence.EvidenceLinkService.class));
    }

    private static ProjectFile file(Long id, Long parentId, String name, boolean folder) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(PID);
        f.setParentId(parentId);
        f.setName(name);
        f.setIsFolder(folder);
        f.setIsDeleted(false);
        return f;
    }

    @Test
    @DisplayName("首段是中文显示名「AI 助手文件」时命中")
    void chineseDisplayNameResolves() {
        Optional<ProjectFile> hit = service.resolveByRelativePath(PID, "AI 助手文件/股权转让/三步工作计划.md");
        assertTrue(hit.isPresent());
        assertEquals(12L, hit.get().getId());
    }

    @Test
    @DisplayName("首段是英文名「AI Assistant Files」时命中")
    void englishNameResolves() {
        Optional<ProjectFile> hit = service.resolveByRelativePath(PID, "AI Assistant Files/股权转让/三步工作计划.md");
        assertTrue(hit.isPresent());
        assertEquals(12L, hit.get().getId());
    }

    @Test
    @DisplayName("中间段缺失返回空")
    void missingMiddleSegmentIsEmpty() {
        assertTrue(service.resolveByRelativePath(PID, "AI 助手文件/不存在/三步工作计划.md").isEmpty());
        assertTrue(service.resolveByRelativePath(PID, "").isEmpty());
        assertTrue(service.resolveByRelativePath(PID, null).isEmpty());
    }
}
