package com.checkba.service.ai.tools;

import com.checkba.config.AiContextProperties;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.DocumentTextService;
import com.checkba.service.OcrService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.io.FileSystemResource;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * read_document 对 Office 格式必须真的读得出正文。
 *
 * <p>病灶：docx 既不在 {@code FileContentExtractorService.ALLOWED_TEXT_EXTENSIONS}
 * （只有 java/js/md/txt/csv/json 这类），也不在 {@code ai.context.ocr-extensions}
 * （只有图片 + pdf），于是恒定落进 extractText 的 else 分支返回空串。空串再往下
 * 就是 {@code ToolExecutionResultMessage.from} 的 ensureNotBlank 把整轮打掉，
 * 而 Active Document 注入的正文同样恒为空——模型转头自己再调一次 read_document。
 */
class ReadDocumentOfficeFormatTest {

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    private static Path writeDocx(Path dir, String fileName, String... paragraphs) throws IOException {
        Path path = dir.resolve(fileName);
        try (XWPFDocument doc = new XWPFDocument(); FileOutputStream out = new FileOutputStream(path.toFile())) {
            for (String p : paragraphs) {
                doc.createParagraph().createRun().setText(p);
            }
            doc.write(out);
        }
        return path;
    }

    private static ProjectFile docxRecord(Path path) {
        ProjectFile f = new ProjectFile();
        f.setId(12L);
        f.setProjectId(7L);
        f.setName(path.getFileName().toString());
        f.setFileType("docx");
        f.setFilePath(path.toString());
        f.setIsFolder(false);
        return f;
    }

    /** 真的 Tika + 真的 PDFBox，只把「文件从哪来」换成本地临时文件。 */
    private static LegalTools toolsFor(ProjectFile record, Path onDisk) throws Exception {
        ProjectFileService fileService = Mockito.mock(ProjectFileService.class);
        Mockito.when(fileService.getFile(12L)).thenReturn(record);
        Mockito.when(fileService.getFileBytes(12L)).thenReturn(Files.readAllBytes(onDisk));

        StorageService storage = Mockito.mock(StorageService.class);
        Mockito.when(storage.load(record.getFilePath())).thenReturn(new FileSystemResource(onDisk));
        StorageServiceFactory factory = Mockito.mock(StorageServiceFactory.class);
        Mockito.when(factory.getStorageService()).thenReturn(storage);

        FileContentExtractorService extractor = new FileContentExtractorService(
                Mockito.mock(OcrService.class), new AiContextProperties());
        return new LegalTools(fileService, null, extractor, new DocumentTextService(factory));
    }

    @Test
    @DisplayName("docx 返回真实正文，不再是空串")
    void readsDocxBody(@TempDir Path dir) throws Exception {
        Path docx = writeDocx(dir, "股权转让协议.docx",
                "第一条 转让标的", "甲方将其持有的目标公司 40% 股权转让给乙方。");
        ProjectContextHolder.setProjectId("7");

        String out = toolsFor(docxRecord(docx), docx).read_document("12");

        assertFalse(out.isBlank(), "docx 恒返回空串正是整条崩溃链的起点");
        assertTrue(out.contains("股权转让"), "要拿到真正的正文，实际是：" + out);
        assertTrue(out.contains("40%"), "正文不能只剩标题，实际是：" + out);
    }

    @Test
    @DisplayName("抽不出正文时给一句可行动的说明，绝不返回空白")
    void neverReturnsBlankForEmptyDocument(@TempDir Path dir) throws Exception {
        Path docx = writeDocx(dir, "空白.docx");
        ProjectContextHolder.setProjectId("7");

        String out = toolsFor(docxRecord(docx), docx).read_document("12");

        assertFalse(out.isBlank(), "空白输出会被 langchain4j 的 ensureNotBlank 打掉整轮");
        assertTrue(out.contains("extract_file_text"), "要给模型下一步，实际是：" + out);
    }
}
