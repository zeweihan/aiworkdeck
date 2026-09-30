// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.context;

import com.checkba.config.AiContextProperties;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * FileContextLoader 测试：单文件提取路由（OCR/标准）与文件夹递归收集上限
 */
class FileContextLoaderTest {

    private ProjectFileService projectFileService;
    private FileContentExtractorService extractor;
    private AiContextProperties props;
    private FileContextLoader loader;
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        projectFileService = mock(ProjectFileService.class);
        extractor = mock(FileContentExtractorService.class);
        props = new AiContextProperties();
        com.checkba.storage.StorageProperties storageProps = new com.checkba.storage.StorageProperties();
        storageProps.getLocal().setRootPath(tempDir.toString());
        loader = new FileContextLoader(projectFileService, extractor, props,
                new com.checkba.storage.ProjectStorageResolver(storageProps, null),
                mock(com.checkba.service.file.ProjectFileTextExtractor.class));
    }

    private static ProjectFile file(Long id, String name, boolean isFolder) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setName(name);
        f.setIsFolder(isFolder);
        f.setFilePath("/nonexistent/" + name);
        return f;
    }

    @Test
    @DisplayName("单文件提取：OCR 支持的文件走 OCR 路径")
    void extractFileTextRoutesToOcr() throws Exception {
        ProjectFile pdf = file(1L, "contract.pdf", false);
        pdf.setFileType("pdf");
        when(projectFileService.getFileBytes(1L)).thenReturn("pdf-bytes".getBytes(StandardCharsets.UTF_8));
        when(extractor.isOcrSupported("contract.pdf")).thenReturn(true);
        when(extractor.extractTextWithOcr(any(File.class))).thenReturn("OCR 文本");

        String result = loader.extractFileText(pdf);

        assertEquals("OCR 文本", result);
        verify(extractor).extractTextWithOcr(any(File.class));
        verify(extractor, never()).extractText(any(File.class));
    }

    @Test
    @DisplayName("单文件提取：普通文件走标准提取路径")
    void extractFileTextRoutesToStandard() throws Exception {
        ProjectFile doc = file(2L, "note.txt", false);
        doc.setFileType("txt");
        when(projectFileService.getFileBytes(2L)).thenReturn("text".getBytes(StandardCharsets.UTF_8));
        when(extractor.isOcrSupported("note.txt")).thenReturn(false);
        when(extractor.extractText(any(File.class))).thenReturn("标准文本");

        String result = loader.extractFileText(doc);

        assertEquals("标准文本", result);
        verify(extractor).extractText(any(File.class));
        verify(extractor, never()).extractTextWithOcr(any(File.class));
    }

    @Test
    @DisplayName("单文件提取：内容为空时返回提示")
    void extractFileTextHandlesEmptyBytes() throws Exception {
        ProjectFile empty = file(3L, "empty.txt", false);
        when(projectFileService.getFileBytes(3L)).thenReturn(new byte[0]);

        assertEquals("[文件内容为空或无法读取]", loader.extractFileText(empty));
    }

    @Test
    @DisplayName("文件夹收集：递归读取子文件夹并拼接文件内容")
    void collectFolderContentRecursesIntoSubfolders() throws Exception {
        ProjectFile sub = file(10L, "子文件夹", true);
        ProjectFile f1 = file(11L, "a.txt", false);
        ProjectFile f2 = file(12L, "b.txt", false);

        when(projectFileService.getFilesByParent(1L, 100L)).thenReturn(List.of(f1, sub));
        when(projectFileService.getFilesByParent(1L, 10L)).thenReturn(List.of(f2));
        when(projectFileService.getFileBytes(any())).thenReturn("x".getBytes(StandardCharsets.UTF_8));
        when(extractor.extractText(any(File.class))).thenReturn("内容");

        String result = loader.collectFolderContent(1L, 100L);

        assertTrue(result.contains("a.txt"));
        assertTrue(result.contains("b.txt"));
    }

    @Test
    @DisplayName("文件夹收集：文件数量受 maxFilesPerContext 上限约束")
    void collectFolderContentRespectsFileLimit() throws Exception {
        props.getFiles().setMaxFilesPerContext(2);

        List<ProjectFile> files = List.of(
                file(21L, "f1.txt", false), file(22L, "f2.txt", false), file(23L, "f3.txt", false));
        when(projectFileService.getFilesByParent(1L, 100L)).thenReturn(files);
        when(projectFileService.getFileBytes(any())).thenReturn("x".getBytes(StandardCharsets.UTF_8));
        when(extractor.extractText(any(File.class))).thenReturn("内容");

        String result = loader.collectFolderContent(1L, 100L);

        assertTrue(result.contains("f1.txt"));
        assertTrue(result.contains("f2.txt"));
        assertFalse(result.contains("f3.txt"), "超出上限的文件不应被读取");
    }

    @Test
    @DisplayName("文件夹上下文：配额耗尽时只输出目录结构")
    void buildFolderContextStopsWhenQuotaExhausted() {
        ProjectFile f1 = file(31L, "doc.txt", false);
        when(projectFileService.getFilesByParent(1L, 200L)).thenReturn(List.of(f1));

        String result = loader.buildFolderContext("200", "1",
                props.getFiles().getMaxFilesPerContext()); // 已用完配额

        assertTrue(result.contains("Directory Content"));
        assertTrue(result.contains("doc.txt"), "目录结构仍应列出文件");
        assertFalse(result.contains("Folder Document Contents"), "不应再读取文件内容");
        assertTrue(result.contains("text preloaded from 0; yielded no text: 0; not attempted due to the file-count budget: 1"));
        verifyNoInteractions(extractor);
    }

    @Test
    @DisplayName("目录与正文均用相对路径区分根目录和子目录里的同名文件")
    void buildFolderContextDistinguishesDuplicateNames() throws Exception {
        ProjectFile rootFile = readableFile(41L, "意见书.txt", "root");
        ProjectFile nestedFile = readableFile(42L, "意见书.txt", "nested");
        ProjectFile folder = file(43L, "底稿", true);
        when(projectFileService.getFilesByParent(1L, 200L)).thenReturn(List.of(rootFile, folder));
        when(projectFileService.getFilesByParent(1L, 43L)).thenReturn(List.of(nestedFile));

        FileContextLoader.FolderContext result = loader.buildFolderContextCounted("200", "1", 0);

        assertTrue(result.text().contains("[FILE] 意见书.txt (ID: 41)"));
        assertTrue(result.text().contains("[FILE] 底稿/意见书.txt (ID: 42)"));
        assertTrue(result.text().contains("#### File: 意见书.txt\n```\nroot"));
        assertTrue(result.text().contains("#### File: 底稿/意见书.txt\n```\nnested"));
        assertTrue(result.text().contains("Of 2 listed file(s), text preloaded from 2"));
        assertFalse(result.text().contains("Directory listing is incomplete"));
        assertEquals(2, result.filesRead());
    }

    @Test
    @DisplayName("深度5的文件仍可列出，无更深目录时不误报截断")
    void buildFolderContextIncludesFilesAtDepthBoundary() {
        stubDepthBoundary(false);

        String result = loader.buildFolderContext("200", "1", props.getFiles().getMaxFilesPerContext());

        assertTrue(result.contains("[FILE] d1/d2/d3/d4/d5/boundary.txt"));
        assertFalse(result.contains("Directory listing is incomplete"));
        verify(projectFileService).getFilesByParent(1L, 305L);
    }

    @Test
    @DisplayName("超过深度5不追加查询，并诚实说明更深目录未遍历")
    void buildFolderContextReportsUntraversedSubfolders() {
        stubDepthBoundary(true);

        String result = loader.buildFolderContext("200", "1", props.getFiles().getMaxFilesPerContext());

        assertTrue(result.contains("[FILE] d1/d2/d3/d4/d5/boundary.txt"));
        assertTrue(result.contains("[DIR] d1/d2/d3/d4/d5/d6"));
        assertTrue(result.contains("Directory listing is incomplete: recursion depth limit 5 reached; deeper folders were not traversed"));
        verify(projectFileService, never()).getFilesByParent(1L, 306L);
    }

    @Test
    @DisplayName("数量预算按成功预读数扣减，区分失败与未尝试，并保留正文字符截断")
    void buildFolderContextReportsActualReadCoverage() throws Exception {
        props.getFiles().setMaxFilesPerContext(3);
        props.getFiles().setFolderFileMaxChars(3);
        ProjectFile missing = file(51L, "missing.txt", false);
        ProjectFile first = readableFile(52L, "first.txt", "abcdef");
        ProjectFile second = readableFile(53L, "second.txt", "ghijkl");
        ProjectFile remaining = readableFile(54L, "remaining.txt", "mnopqr");
        when(projectFileService.getFilesByParent(1L, 200L)).thenReturn(List.of(missing, first, second, remaining));

        FileContextLoader.FolderContext result = loader.buildFolderContextCounted("200", "1", 1);

        assertEquals(2, result.filesRead());
        assertEquals(1, result.unreadableCount());
        assertTrue(result.text().contains("Of 4 listed file(s), text preloaded from 2; yielded no text: 1; not attempted due to the file-count budget: 1"));
        assertTrue(result.text().contains("abc...[Truncated]"));
        assertTrue(result.text().contains("ghi...[Truncated]"));
        assertTrue(result.text().indexOf("#### File: first.txt") < result.text().indexOf("#### File: second.txt"));
        assertFalse(result.text().contains("#### File: remaining.txt"));
        verify(extractor, times(2)).extractText(any(File.class));
        verify(extractor, never()).extractTextWithOcr(any(File.class));
    }

    @Test
    @DisplayName("附件目录路径明确以该目录为基准，不冒充项目根路径；配额耗尽也保留说明")
    void folderPathsIdentifyTheirBaseBeforeContentOrQuotaReturn() throws Exception {
        ProjectFile nested = file(60L, "暂放", true);
        ProjectFile note = readableFile(61L, "协议.txt", "合成协议正文");
        when(projectFileService.getFilesByParent(1L, 200L)).thenReturn(List.of(nested));
        when(projectFileService.getFilesByParent(1L, 60L)).thenReturn(List.of(note));
        for (int used : List.of(0, props.getFiles().getMaxFilesPerContext())) {
            String result = loader.buildFolderContext("200", "1", used);
            assertTrue(result.contains("relative to the attached folder (ID: 200), NOT the project root"), result);
            assertTrue(result.contains("resolve the folder ID to its full project-relative path"), result);
            assertTrue(result.contains("using list_project_folders"), "按目录 ID 查路径须用返回目录的清单，不能用只返回文件的 doc_list_project_files");
            assertTrue(result.contains("[FILE] 暂放/协议.txt (ID: 61)"), result);
            assertFalse(result.contains(tempDir.toString()), "不输出宿主绝对路径");
            if (used == 0) assertTrue(result.contains("#### File: 暂放/协议.txt\n```\n合成协议正文"), result);
        }
    }

    private ProjectFile readableFile(long id, String name, String text) throws Exception {
        Path path = Files.writeString(tempDir.resolve(id + ".txt"), text);
        ProjectFile f = file(id, name, false);
        f.setFilePath(path.getFileName().toString());
        when(extractor.isTextFile(name)).thenReturn(true);
        when(extractor.extractText(path.toFile())).thenReturn(text);
        return f;
    }

    private void stubDepthBoundary(boolean deeperFolder) {
        long parent = 200L;
        for (int depth = 1; depth <= 5; depth++) {
            long child = 300L + depth;
            when(projectFileService.getFilesByParent(1L, parent))
                    .thenReturn(List.of(file(child, "d" + depth, true)));
            parent = child;
        }
        ProjectFile boundary = file(310L, "boundary.txt", false);
        when(projectFileService.getFilesByParent(1L, parent)).thenReturn(deeperFolder
                ? List.of(boundary, file(306L, "d6", true)) : List.of(boundary));
    }
}
