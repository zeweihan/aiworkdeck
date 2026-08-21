package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新建文件的同名冲突策略（dev-board#107 单元 F2）：
 * - 旧签名（无 policy 参数）行为不变：同名直接报错；
 * - RENAME 策略：自动加 " (n)" 直到不冲突；
 * - 新建不再拉整个同级列表求 max(sortOrder)，改走单条聚合查询
 *   （findByProjectIdAndParentIdOrderBySortOrderAsc 绝不该被调用）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFileServiceCreateConflictTest {

    @Mock
    private ProjectFileRepository projectFileRepository;
    @Mock
    private com.checkba.service.ai.ProjectRagService projectRagService;
    @Mock
    private com.checkba.storage.StorageServiceFactory storageServiceFactory;
    @Mock
    private com.checkba.service.telemetry.TelemetryService telemetryService;

    @InjectMocks
    private ProjectFileService projectFileService;

    private static final long PROJECT_ID = 1L;
    private static final long PARENT_ID = 5L;

    @Test
    void oldSignatureStillFailsOnSameName() {
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(
                PROJECT_ID, PARENT_ID, "a.pdf", -1L)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> projectFileService.createFile(PROJECT_ID, PARENT_ID, "a.pdf", "pdf", 10L, null, null, 1L));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("a.pdf"));
        // RENAME 专用的查重不该被 FAIL 策略调用
        verify(projectFileRepository, never()).existsByProjectIdAndParentIdAndNameAndIdNotAndIsDeletedFalse(
                anyLong(), any(), any(), anyLong());
    }

    @Test
    void renamePolicyAppendsIncrementingSuffixUntilFree() {
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNotAndIsDeletedFalse(
                PROJECT_ID, PARENT_ID, "a.pdf", -1L)).thenReturn(true);
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNotAndIsDeletedFalse(
                PROJECT_ID, PARENT_ID, "a (1).pdf", -1L)).thenReturn(true);
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNotAndIsDeletedFalse(
                PROJECT_ID, PARENT_ID, "a (2).pdf", -1L)).thenReturn(false);
        when(projectFileRepository.maxSortOrder(PROJECT_ID, PARENT_ID)).thenReturn(3);
        when(projectFileRepository.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectFile saved = projectFileService.createFile(PROJECT_ID, PARENT_ID, "a.pdf", "pdf", 10L, null, null, 1L,
                ProjectFileService.ConflictPolicy.RENAME);

        assertEquals("a (2).pdf", saved.getName());
        assertEquals(4, saved.getSortOrder());
        verify(projectFileRepository).maxSortOrder(PROJECT_ID, PARENT_ID);
        verify(projectFileRepository, never()).findByProjectIdAndParentIdOrderBySortOrderAsc(anyLong(), any());
        // FAIL 策略专用的查重不该被 RENAME 策略调用
        verify(projectFileRepository, never()).existsByProjectIdAndParentIdAndNameAndIdNot(
                anyLong(), any(), any(), anyLong());
    }

    @Test
    void renamePolicyReturnsOriginalNameWhenNoConflict() {
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNotAndIsDeletedFalse(
                PROJECT_ID, PARENT_ID, "unique.pdf", -1L)).thenReturn(false);
        when(projectFileRepository.maxSortOrder(PROJECT_ID, PARENT_ID)).thenReturn(null);
        when(projectFileRepository.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectFile saved = projectFileService.createFile(PROJECT_ID, PARENT_ID, "unique.pdf", "pdf", 10L, null, null, 1L,
                ProjectFileService.ConflictPolicy.RENAME);

        assertEquals("unique.pdf", saved.getName());
        assertEquals(0, saved.getSortOrder(), "空文件夹（maxSortOrder=null）新建的第一个文件序号应为 0");
        verify(projectFileRepository, times(1)).existsByProjectIdAndParentIdAndNameAndIdNotAndIsDeletedFalse(
                eq(PROJECT_ID), eq(PARENT_ID), eq("unique.pdf"), eq(-1L));
    }

    @Test
    void createFolderUsesSingleMaxSortOrderQueryNotFullSiblingList() {
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(
                eq(PROJECT_ID), isNull(), eq("新文件夹"), eq(-1L))).thenReturn(false);
        when(projectFileRepository.maxSortOrder(PROJECT_ID, null)).thenReturn(7);
        when(projectFileRepository.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectFile folder = projectFileService.createFolder(PROJECT_ID, null, "新文件夹", 1L);

        assertEquals(8, folder.getSortOrder());
        verify(projectFileRepository).maxSortOrder(PROJECT_ID, null);
        verify(projectFileRepository, never()).findByProjectIdAndParentIdOrderBySortOrderAsc(anyLong(), any());
    }
}
