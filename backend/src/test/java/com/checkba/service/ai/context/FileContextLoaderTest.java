package com.checkba.service.ai.context;

import com.checkba.config.AiContextProperties;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
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

    @BeforeEach
    void setUp() {
        projectFileService = mock(ProjectFileService.class);
        extractor = mock(FileContentExtractorService.class);
        props = new AiContextProperties();
        loader = new FileContextLoader(projectFileService, extractor, props);
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
    }
}
