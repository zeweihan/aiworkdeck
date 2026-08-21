package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ensureFolderPath（dev-board#109 单元 H3）：逐级复用已有文件夹、缺的补建、
 * 某段是文件时拒绝、空白段跳过、全空报错。插件宿主 Files.createFolderPath / move_file / 会议录音目录共用。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFileServiceEnsureFolderPathTest {

    @Mock
    private ProjectFileRepository repo;
    @Mock
    private com.checkba.service.ai.ProjectRagService projectRagService;
    @Mock
    private com.checkba.storage.StorageServiceFactory storageServiceFactory;
    @Mock
    private com.checkba.service.telemetry.TelemetryService telemetryService;

    @InjectMocks
    private ProjectFileService svc;

    private ProjectFile node(long id, Long parentId, String name, boolean folder) {
        ProjectFile p = new ProjectFile();
        p.setId(id);
        p.setProjectId(1L);
        p.setParentId(parentId);
        p.setName(name);
        p.setIsFolder(folder);
        return p;
    }

    @Test
    @DisplayName("已有的段复用、缺的段补建，返回最深一级")
    void reusesExistingAndCreatesMissing() {
        ProjectFile a = node(10L, null, "a", true);
        when(repo.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(1L, null, "a")).thenReturn(Optional.of(a));
        when(repo.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(1L, 10L, "b")).thenReturn(Optional.empty());
        when(repo.maxSortOrder(anyLong(), any())).thenReturn(null);
        when(repo.save(any(ProjectFile.class))).thenAnswer(inv -> {
            ProjectFile p = inv.getArgument(0);
            p.setId(11L);
            return p;
        });

        ProjectFile deepest = svc.ensureFolderPath(1L, 9L, Arrays.asList("a", " b "));
        assertEquals(11L, deepest.getId());
        assertEquals("b", deepest.getName());
        assertEquals(10L, deepest.getParentId());
    }

    @Test
    @DisplayName("某段已存在但是文件：拒绝，不往下建")
    void segmentIsFileRejected() {
        ProjectFile f = node(10L, null, "a", false);
        when(repo.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(1L, null, "a")).thenReturn(Optional.of(f));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.ensureFolderPath(1L, 9L, List.of("a", "b")));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("a"));
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("空白段跳过；全部为空报错")
    void blankSegments() {
        ProjectFile a = node(10L, null, "a", true);
        when(repo.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(1L, null, "a")).thenReturn(Optional.of(a));
        assertEquals(10L, svc.ensureFolderPath(1L, 9L, Arrays.asList("", "a", "  ")).getId());
        assertThrows(IllegalArgumentException.class, () -> svc.ensureFolderPath(1L, 9L, List.of("", " ")));
        assertThrows(IllegalArgumentException.class, () -> svc.ensureFolderPath(1L, 9L, null));
    }
}
