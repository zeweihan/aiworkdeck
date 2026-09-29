// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.PptxServiceClient;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * pptx_export_editable 的产物落点（dev-board#1031）：从 pptx-service 取回、存进当前项目根目录并登记，
 * 回给模型的是项目内相对路径，不再是服务内部的下载地址。
 */
class PptxEditableExportSaveTest {

    @AfterEach
    void clear() {
        ToolContextHolder.clear();
    }

    private PptxTools tools(Path root, PptxServiceClient client, ProjectFileService files,
                            ProjectFileRepository repo, EditorBridgeService bridge) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        props.getLocal().setTemplatePath(root.resolve("template.docx").toAbsolutePath().toString());
        ProjectStorageResolver resolver = new ProjectStorageResolver(props, null);
        return new PptxTools(client, files, repo, bridge, null, null, null, null, null, resolver, null);
    }

    private static PptxServiceClient writingClient() {
        PptxServiceClient client = mock(PptxServiceClient.class);
        when(client.downloadPptx(anyString(), anyString())).thenAnswer(inv -> {
            Path target = Path.of((String) inv.getArgument(1));
            Files.write(target, new byte[] {'P', 'K', 3, 4});
            return target.toString();
        });
        return client;
    }

    @Test
    void savesIntoProjectRootAndReturnsRelativePath(@TempDir Path root) throws Exception {
        PptxServiceClient client = writingClient();
        ProjectFileService files = mock(ProjectFileService.class);
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        EditorBridgeService bridge = mock(EditorBridgeService.class);
        when(repo.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        ToolContextHolder.set(new ToolContext(5L, "conv-1", 1L, null));

        String out = tools(root, client, files, repo, bridge)
                .saveEditableExportToProject("/files/x/报告.pptx", "报告.pptx", null);

        assertEquals("报告.pptx", out);
        assertTrue(Files.exists(root.resolve("projects/5/报告.pptx")));
        verify(files).createOrUpdateFile(eq(5L), isNull(), eq("报告.pptx"), eq("pptx"), eq(4L),
                eq("projects/5/报告.pptx"), anyString(), anyLong());
        verify(bridge).sendRefreshFilesAction();
    }

    @Test
    void neverOverwritesAnExistingProjectFile(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/5"));
        Files.write(root.resolve("projects/5/报告.pptx"), new byte[] {1});
        ProjectFileService files = mock(ProjectFileService.class);
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        when(repo.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        ToolContextHolder.set(new ToolContext(5L, "conv-1", 1L, null));

        String out = tools(root, writingClient(), files, repo, mock(EditorBridgeService.class))
                .saveEditableExportToProject("/files/x/报告.pptx", "报告.pptx", null);

        assertEquals("报告 (2).pptx", out);
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(root.resolve("projects/5/报告.pptx")));
    }

    @Test
    void withoutProjectContextReportsErrorWithoutDownloadWording(@TempDir Path root) {
        PptxServiceClient client = writingClient();
        String out = tools(root, client, mock(ProjectFileService.class), mock(ProjectFileRepository.class),
                mock(EditorBridgeService.class)).saveEditableExportToProject("/files/x/a.pptx", "a.pptx", null);
        assertTrue(out.startsWith("错误："));
        assertFalse(out.contains("下载"));
        verify(client, never()).downloadPptx(anyString(), anyString());
    }
}
