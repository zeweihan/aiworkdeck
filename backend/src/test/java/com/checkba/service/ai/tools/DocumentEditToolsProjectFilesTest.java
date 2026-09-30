// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentEditToolsProjectFilesTest {
    private final ProjectFileRepository repository = mock(ProjectFileRepository.class);
    private final DocumentEditTools tools = new DocumentEditTools(null, repository, null, null,
            null, null, null, null);

    @Test
    void distinguishesDuplicateNamesAndPreservesRepositoryOrderWithoutHostPaths() {
        ProjectFile first = file(12, "合同.docx", 2L, false);
        first.setFilePath("/private/host/projects/7/合同.docx");
        ProjectFile deleted = file(13, "已删.pdf", null, false);
        deleted.setIsDeleted(true);
        when(repository.findByProjectIdOrderBySortOrderAsc(7L)).thenReturn(List.of(
                file(1, "甲方", null, true), file(2, "底稿", 1L, true),
                first, file(3, "乙方", null, true), file(11, "合同.docx", 3L, false),
                file(14, "说明.txt", null, false), deleted));

        String result = tools.doc_list_project_files(7L);

        assertTrue(result.contains("共 3 个"), result);
        assertTrue(result.contains("ID: 12, 名称: 合同.docx, 路径: 甲方/底稿/合同.docx"), result);
        assertTrue(result.contains("ID: 11, 名称: 合同.docx, 路径: 乙方/合同.docx"), result);
        assertTrue(result.contains("路径: 说明.txt"), result);
        assertTrue(result.indexOf("ID: 12,") < result.indexOf("ID: 11,"));
        assertFalse(result.contains("ID: 1,"));
        assertFalse(result.contains("已删.pdf"));
        assertFalse(result.contains("/private/host"));
        verifyNoMoreInteractionsAfterList();
    }

    @Test
    void incompleteAndCyclicFoldersKeepEveryFileWithoutFollowingOtherProjects() {
        ProjectFile deletedFolder = file(1, "删除目录", null, true);
        deletedFolder.setIsDeleted(true);
        when(repository.findByProjectIdOrderBySortOrderAsc(7L)).thenReturn(List.of(
                deletedFolder, file(2, "循环甲", 3L, true), file(3, "循环乙", 2L, true),
                file(10, "同名.txt", 1L, false), file(11, "同名.txt", 999L, false),
                file(12, "循环.txt", 2L, false), file(13, "父是文件.txt", 10L, false)));
        // Even if a parent exists elsewhere, listing must never resolve it by global ID.
        ProjectFile foreign = file(999, "其他项目秘密目录", null, true);
        foreign.setProjectId(8L);
        lenient().when(repository.findById(999L)).thenReturn(java.util.Optional.of(foreign));

        String result = tools.doc_list_project_files(7L);

        assertTrue(result.contains("共 4 个"), result);
        assertTrue(result.contains("ID: 10, 名称: 同名.txt, 路径: 同名.txt"), result);
        assertTrue(result.contains("ID: 11, 名称: 同名.txt, 路径: 同名.txt"), result);
        assertTrue(result.contains("路径: 循环乙/循环甲/循环.txt"), result);
        assertTrue(result.contains("路径: 父是文件.txt"), result);
        assertFalse(result.contains("删除目录"));
        assertFalse(result.contains("其他项目"));
        assertFalse(result.contains("循环甲/循环乙/循环甲"));
        verifyNoMoreInteractionsAfterList();
    }

    @Test
    void emptyOrOnlyFoldersAndDeletedFilesKeepsEmptyResponse() {
        ProjectFile deleted = file(2, "删除.txt", null, false);
        deleted.setIsDeleted(true);
        when(repository.findByProjectIdOrderBySortOrderAsc(7L))
                .thenReturn(List.of(file(1, "目录", null, true), deleted));
        assertEquals("项目中还没有任何文件。", tools.doc_list_project_files(7L));
    }

    @Test
    void sharedPathBuilderRejectsCrossProjectParentEvenIfPresentInIndex() {
        ProjectFile foreign = file(1, "其他项目", null, true);
        foreign.setProjectId(8L);
        ProjectFile child = file(2, "合同.docx", 1L, false);
        assertEquals("合同.docx", FileTools.relativeProjectPath(child, java.util.Map.of(1L, foreign, 2L, child)));
    }

    private void verifyNoMoreInteractionsAfterList() {
        verify(repository).findByProjectIdOrderBySortOrderAsc(7L);
        verifyNoMoreInteractions(repository);
    }

    private static ProjectFile file(long id, String name, Long parentId, boolean folder) {
        ProjectFile file = new ProjectFile();
        file.setId(id);
        file.setProjectId(7L);
        file.setName(name);
        file.setParentId(parentId);
        file.setIsFolder(folder);
        file.setIsDeleted(false);
        file.setFileType(folder ? "folder" : "docx");
        return file;
    }
}
