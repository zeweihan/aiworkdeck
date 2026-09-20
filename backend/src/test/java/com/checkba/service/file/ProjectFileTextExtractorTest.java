// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.file;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.DocumentTextService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 项目文件文字抽取器（dev-board#718）：extract_file_text、云端项目参考来源、桌面端参考读取共用同一条抽取路由。
 *
 * <p>要守住的东西：
 * <ol>
 *   <li>真实文件经存储层读出、原文返回（不是只测 mock 的连线）；</li>
 *   <li>参考材料入口挡住文件夹与超过 50MB 的文件，且挡在读字节之前；</li>
 *   <li>OCR 的「[System: …]」失败形态必须变成异常，不能被当正文；</li>
 *   <li>不在项目文件表里的字节（案件库、git）按扩展名路由：纯文本按 UTF-8，PDF 走 PDFBox，其余走 Tika；</li>
 *   <li>只有工具入口 extractText 走 OCR，参考入口（extract / extractBytes）一律拒绝——
 *       平台代采档的 OCR 按页扣 Credits，而 PRIVACY 对参考材料承诺的是不扣费。</li>
 * </ol>
 */
class ProjectFileTextExtractorTest {

    private final StorageServiceFactory storageFactory = mock(StorageServiceFactory.class);
    private final StorageService storage = mock(StorageService.class);
    private final FileContentExtractorService ocr = mock(FileContentExtractorService.class);
    private final ProjectFileService fileService = mock(ProjectFileService.class);
    private final DocumentTextService documentTextService = new DocumentTextService(storageFactory);
    private final ProjectFileTextExtractor extractor =
            new ProjectFileTextExtractor(documentTextService, ocr, fileService);

    private static ProjectFile file(long id, String name, String type, String path, Long size) {
        ProjectFile pf = new ProjectFile();
        pf.setId(id);
        pf.setProjectId(7L);
        pf.setName(name);
        pf.setFileType(type);
        pf.setFilePath(path);
        pf.setFileSize(size);
        pf.setIsFolder(false);
        return pf;
    }

    @Test
    void extractReadsProjectFileThroughStorage(@TempDir Path tmp) throws Exception {
        Path onDisk = tmp.resolve("说明.txt");
        Files.writeString(onDisk, "第一条 甲方应于十日内付款。", StandardCharsets.UTF_8);
        when(storageFactory.getStorageService()).thenReturn(storage);
        when(storage.load("projects/7/说明.txt")).thenReturn(new FileSystemResource(onDisk));

        String text = extractor.extract(file(1L, "说明.txt", "txt", "projects/7/说明.txt", Files.size(onDisk)));

        assertThat(text.trim()).isEqualTo("第一条 甲方应于十日内付款。");
    }

    @Test
    void extractRefusesFilesOver50MbBeforeReadingBytes() throws Exception {
        ProjectFile huge = file(2L, "证据.pdf", "pdf", "projects/7/证据.pdf", 60L * 1024 * 1024);

        assertThatThrownBy(() -> extractor.extract(huge))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("50MB");
        verifyNoInteractions(storageFactory);
        verify(fileService, never()).getFileBytes(any());
    }

    @Test
    void extractRefusesFolders() {
        ProjectFile dir = file(3L, "合同", null, null, null);
        dir.setIsFolder(true);

        assertThatThrownBy(() -> extractor.extract(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("这是一个文件夹");
        verifyNoInteractions(storageFactory);
    }

    @Test
    void ocrSystemFailureBecomesAnExceptionWithTheRealReason() throws Exception {
        ProjectFile img = file(4L, "扫描件.jpg", "jpg", "projects/7/扫描件.jpg", 3L);
        when(ocr.isOcrSupported("扫描件.jpg")).thenReturn(true);
        when(fileService.getFileBytes(4L)).thenReturn(new byte[]{1, 2, 3});
        when(ocr.extractTextWithOcr(any(File.class))).thenReturn("[System: OCR 识别失败: Credits 余额不足]");

        assertThatThrownBy(() -> extractor.extractText(img))
                .isInstanceOf(ProjectFileTextExtractor.OcrFailedException.class)
                .hasMessageContaining("Credits 余额不足");
        // 图片没有文字层，不许白抽一次 Tika
        verifyNoInteractions(storageFactory);
    }

    /**
     * 参考读取不许走 OCR：平台代采档的 OCR 按页扣 Credits，而 legal/PRIVACY.md 对参考材料
     * 白纸黑字写的是「不产生 Credits 扣费」。扣了钱用户既没被问过，也没法拒绝。
     */
    @Test
    void referenceReadOfAScanRefusesInsteadOfSpendingOcrCredits() throws Exception {
        ProjectFile img = file(4L, "扫描件.jpg", "jpg", "projects/7/扫描件.jpg", 3L);
        when(ocr.isOcrSupported("扫描件.jpg")).thenReturn(true);

        assertThatThrownBy(() -> extractor.extract(img))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("文字识别");
        verify(ocr, never()).extractTextWithOcr(any(File.class));
        verifyNoInteractions(fileService);
    }

    @Test
    void extractBytesDecodesPlainTextAsUtf8() throws Exception {
        assertThat(extractor.extractBytes("a.txt", "中文".getBytes(StandardCharsets.UTF_8))).isEqualTo("中文");
        assertThat(extractor.extractBytes("资料/说明.MD", "# 标题".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("# 标题");
    }

    @Test
    void extractBytesParsesOfficeFormatsWithTika() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("本合同由甲乙双方签订。");
            doc.write(out);
        }

        assertThat(extractor.extractBytes("合同/主合同.docx", out.toByteArray())).contains("本合同由甲乙双方签订。");
    }

    /** 案件库/git 的字节也是参考材料，同样不许替用户掏 OCR 的 Credits。 */
    @Test
    void extractBytesOfAScannedPdfRefusesInsteadOfSpendingOcrCredits() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage()); // 没有文字层的一页，等同扫描件
            doc.save(out);
        }
        when(ocr.isOcrSupported("卷宗/判决书.pdf")).thenReturn(true);

        assertThatThrownBy(() -> extractor.extractBytes("卷宗/判决书.pdf", out.toByteArray()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("文字识别");
        verify(ocr, never()).extractTextWithOcr(any(File.class));
    }

    @Test
    void extractBytesRefusesOver50Mb() {
        byte[] big = new byte[(int) ProjectFileTextExtractor.MAX_BYTES + 1];

        assertThatThrownBy(() -> extractor.extractBytes("大文件.docx", big))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("50MB");
    }
}
