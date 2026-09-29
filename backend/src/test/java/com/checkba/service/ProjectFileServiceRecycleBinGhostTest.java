// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回收站「标题 (2)、列表空」（dev-board#1019）。
 *
 * <p>现场：回收站列表接口返回的行里夹着根级 {@code __staging_area__} 空壳——工作台每次打开懒建
 * 文件缓存区，v0.49.0 之前本地文件夹项目的对账又把「磁盘上没有目录」的空缓存区送进回收站，
 * 一个项目攒了几十个。前端按名字把系统文件夹从列表里藏掉，标题却按接口条数计数，
 * 于是删光能看见的行之后永远剩下看不见、也删不掉的「(2)」。
 */
@ExtendWith(MockitoExtension.class)
class ProjectFileServiceRecycleBinGhostTest {

    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private ProjectRagService projectRagService;
    @Mock private StorageServiceFactory storageServiceFactory;
    @Mock private StorageService storageService;
    @Mock private EvidenceLinkService evidenceLinkService;
    @Mock private com.checkba.version.WorkSessionService workSessionService;
    @Mock private com.checkba.service.telemetry.TelemetryService telemetryService;
    @Mock private UserService userService;

    @InjectMocks private ProjectFileService projectFileService;

    private static ProjectFile row(long id, Long parentId, String name, boolean folder, boolean deleted) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(1L);
        f.setParentId(parentId);
        f.setName(name);
        f.setIsFolder(folder);
        f.setIsDeleted(deleted);
        if (!folder) f.setFilePath("projects/1/" + name);
        return f;
    }

    /** 假设核实：永久删除文件夹会带走已软删的子孙（「待删除」文件夹那条路径不是病灶）。 */
    @Test
    void permDeleteFolderPurgesSoftDeletedChildren() {
        ProjectFile a = row(10L, null, "待删除", true, true);
        ProjectFile f1 = row(11L, 10L, "中间稿1.docx", false, true);
        ProjectFile f2 = row(12L, 10L, "中间稿2.docx", false, true);
        when(projectFileRepository.findById(10L)).thenReturn(Optional.of(a));
        when(projectFileRepository.findByProjectIdAndParentId(1L, 10L)).thenReturn(List.of(f1, f2));
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);

        projectFileService.permDelete(10L, 9L);

        verify(projectFileRepository).deleteById(10L);
        verify(projectFileRepository).deleteById(11L);
        verify(projectFileRepository).deleteById(12L);
    }

    /** 根级文件缓存区空壳不进回收站列表：前端看不见它，计数也就不能数它。 */
    @Test
    void recycleBinListOmitsEmptyRootStagingShells() {
        ProjectFile shell1 = row(20L, null, "__staging_area__", true, true);
        ProjectFile shell2 = row(21L, null, "__staging_area__", true, true);
        ProjectFile doc = row(22L, null, "起诉状.docx", false, true);
        when(projectFileRepository.findByProjectIdAndIsDeletedTrueOrderByDeletedAtDesc(1L))
                .thenReturn(List.of(shell1, doc, shell2));
        lenient().when(projectFileRepository.countByParentId(20L)).thenReturn(0L);
        lenient().when(projectFileRepository.countByParentId(21L)).thenReturn(0L);

        List<ProjectFile> bin = projectFileService.getRecycleBinFiles(1L);

        assertEquals(List.of(doc), bin);
    }

    /** 装着文件的缓存区照常列出：它的子行还原时要靠它当「先还原上层」的锚点。 */
    @Test
    void recycleBinListKeepsNonEmptyStagingFolder() {
        ProjectFile staging = row(30L, null, "__staging_area__", true, true);
        ProjectFile staged = row(31L, 30L, "证据.pdf", false, true);
        when(projectFileRepository.findByProjectIdAndIsDeletedTrueOrderByDeletedAtDesc(1L))
                .thenReturn(List.of(staging, staged));
        when(projectFileRepository.countByParentId(30L)).thenReturn(1L);

        assertEquals(List.of(staging, staged), projectFileService.getRecycleBinFiles(1L));
    }

    /**
     * 回收站里的缓存区与活着的缓存区同名同位置，物理路径是同一个目录：彻底删除旧的那一行
     * 不能把整个目录删掉，否则活着的缓存区里的文件字节跟着没了。子文件按各自 filePath 删。
     */
    @Test
    void permDeleteRootStagingFolderKeepsSharedDirectory() {
        ProjectFile staging = row(40L, null, "__staging_area__", true, true);
        ProjectFile staged = row(41L, 40L, "证据.pdf", false, true);
        staged.setFilePath("projects/1/__staging_area__/证据.pdf");
        when(projectFileRepository.findById(40L)).thenReturn(Optional.of(staging));
        when(projectFileRepository.findByProjectIdAndParentId(1L, 40L)).thenReturn(List.of(staged));
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);

        projectFileService.permDelete(40L, 9L);

        verify(storageService).delete("projects/1/__staging_area__/证据.pdf");
        verify(storageService, never()).delete(org.mockito.ArgumentMatchers.argThat(
                (String p) -> p != null && p.replace('\\', '/').endsWith("__staging_area__")));
        verify(projectFileRepository).deleteById(40L);
        verify(projectFileRepository).deleteById(41L);
    }
}
