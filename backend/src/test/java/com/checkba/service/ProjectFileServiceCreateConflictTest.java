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
 * 新建文件的同名冲突策略（dev-board#107 单元 F2，#550 复核 H1/L3 加固）：
 * - 旧签名（无 policy 参数）行为不变：同名直接报错（按 trim 后的名字查，与 RENAME 对称）；
 * - RENAME 策略：自动加 " (n)" 直到不冲突——「冲突」含回收站里的同名行与物理路径已存在，
 *   否则回收站文件会被新文件盖掉，还原后内容错位；
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

    @Mock
    private com.checkba.storage.StorageService storageService;

    @InjectMocks
    private ProjectFileService projectFileService;

    private static final long PROJECT_ID = 1L;
    private static final long PARENT_ID = 5L;

    @org.junit.jupiter.api.BeforeEach
    void wireStorage() {
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
    }

    /** 模拟数据库里（含回收站）是否有同名行。 */
    private void dbHas(String name, boolean taken) {
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(
                PROJECT_ID, PARENT_ID, name, -1L)).thenReturn(taken);
    }

    @Test
    void oldSignatureStillFailsOnSameName() {
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(
                PROJECT_ID, PARENT_ID, "a.pdf", -1L)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> projectFileService.createFile(PROJECT_ID, PARENT_ID, "a.pdf", "pdf", 10L, null, null, 1L));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("a.pdf"));
        verify(projectFileRepository, never()).save(any(ProjectFile.class));
    }

    @Test
    void failPolicyChecksTrimmedNameLikeRename() {
        // L3：落库的是 trim 后的名字，同名检查也必须按 trim 后的名字查，否则 " a.pdf " 能绕过查重
        dbHas("a.pdf", true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> projectFileService.createFile(PROJECT_ID, PARENT_ID, "  a.pdf ", "pdf", 10L, null, null, 1L));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("a.pdf"));
        verify(projectFileRepository).existsByProjectIdAndParentIdAndNameAndIdNot(PROJECT_ID, PARENT_ID, "a.pdf", -1L);
        verify(projectFileRepository, never()).save(any(ProjectFile.class));
    }

    @Test
    void renamePolicyAppendsIncrementingSuffixUntilFree() {
        dbHas("a.pdf", true);
        dbHas("a (1).pdf", true);
        dbHas("a (2).pdf", false);
        when(projectFileRepository.maxSortOrder(PROJECT_ID, PARENT_ID)).thenReturn(3);
        when(projectFileRepository.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectFile saved = projectFileService.createFile(PROJECT_ID, PARENT_ID, "a.pdf", "pdf", 10L, null, null, 1L,
                ProjectFileService.ConflictPolicy.RENAME);

        assertEquals("a (2).pdf", saved.getName());
        assertEquals(4, saved.getSortOrder());
        verify(projectFileRepository).maxSortOrder(PROJECT_ID, PARENT_ID);
        verify(projectFileRepository, never()).findByProjectIdAndParentIdOrderBySortOrderAsc(anyLong(), any());
    }

    @Test
    void renamePolicyReturnsOriginalNameWhenNoConflict() {
        dbHas("unique.pdf", false);
        when(projectFileRepository.maxSortOrder(PROJECT_ID, PARENT_ID)).thenReturn(null);
        when(projectFileRepository.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectFile saved = projectFileService.createFile(PROJECT_ID, PARENT_ID, "unique.pdf", "pdf", 10L, null, null, 1L,
                ProjectFileService.ConflictPolicy.RENAME);

        assertEquals("unique.pdf", saved.getName());
        assertEquals(0, saved.getSortOrder(), "空文件夹（maxSortOrder=null）新建的第一个文件序号应为 0");
        verify(projectFileRepository, times(1)).existsByProjectIdAndParentIdAndNameAndIdNot(
                eq(PROJECT_ID), eq(PARENT_ID), eq("unique.pdf"), eq(-1L));
    }

    /**
     * H1 病灶：RENAME 此前只查「活着」的同名行。回收站里的 a.pdf 字节仍在按名字算出的
     * 物理路径上，判「a.pdf 可用」会让调用方 save/move(REPLACE_EXISTING) 把它盖掉。
     * 这里模拟的是「不过滤 isDeleted 的查重」命中（即软删同名存在），必须得到 a (1).pdf。
     */
    @Test
    void renamePolicyTreatsSoftDeletedSiblingAsConflict() {
        dbHas("a.pdf", true);      // 只有回收站里有这一行
        dbHas("a (1).pdf", false);
        when(projectFileRepository.maxSortOrder(PROJECT_ID, PARENT_ID)).thenReturn(null);
        when(projectFileRepository.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectFile saved = projectFileService.createFile(PROJECT_ID, PARENT_ID, "a.pdf", "pdf", 10L, null, null, 1L,
                ProjectFileService.ConflictPolicy.RENAME);

        assertEquals("a (1).pdf", saved.getName());
        // 绝不能再靠「只看活着的行」那个查询做决定
        verify(projectFileRepository, never()).existsByProjectIdAndParentIdAndNameAndIdNotAndIsDeletedFalse(
                anyLong(), any(), any(), anyLong());
    }

    /** H1：库里没同名行、但物理路径上已有文件（行被彻底删了字节残留/外部写入），同样继续加序号。 */
    @Test
    void renamePolicyTreatsExistingPhysicalFileAsConflict() {
        dbHas("a.pdf", false);
        dbHas("a (1).pdf", false);
        dbHas("a (2).pdf", false);
        // buildPhysicalPath 在 mock 仓库下（父目录查不到）得到 projects/1/<name>
        when(storageService.exists("projects/1/a.pdf")).thenReturn(true);
        when(storageService.exists("projects/1/a (1).pdf")).thenReturn(true);
        when(storageService.exists("projects/1/a (2).pdf")).thenReturn(false);
        when(projectFileRepository.maxSortOrder(PROJECT_ID, PARENT_ID)).thenReturn(null);
        when(projectFileRepository.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectFile saved = projectFileService.createFile(PROJECT_ID, PARENT_ID, "a.pdf", "pdf", 10L, null, null, 1L,
                ProjectFileService.ConflictPolicy.RENAME);

        assertEquals("a (2).pdf", saved.getName());
        assertEquals("projects/1/a (2).pdf", saved.getFilePath());
        verify(storageService).exists("projects/1/a.pdf");
        verify(storageService).exists("projects/1/a (1).pdf");
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
