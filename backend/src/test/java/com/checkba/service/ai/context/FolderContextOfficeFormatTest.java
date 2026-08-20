package com.checkba.service.ai.context;

import com.checkba.config.AiContextProperties;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.DocumentTextService;
import com.checkba.service.OcrService;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.io.FileSystemResource;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件夹上下文必须真的读得出 Office/PDF 正文。
 *
 * <p>病灶：{@code buildFolderContext} 只走
 * {@link FileContentExtractorService#extractText}，而它的白名单
 * （java/js/md/txt/csv…）不含 docx/xlsx/pptx/pdf，恒返回空串，随后
 * {@code if (text != null && !text.isEmpty())} 把这些文件<b>静默跳过</b>——
 * 上下文里「### Folder Document Contents」标题下一个字都没有，模型只能当
 * 这些文件不存在或自己去猜。
 *
 * <p>这是 17ca80d7（docx 恒返回空串）的漏网分支：那次修的是单文件路径
 * （{@code read_document} 改走 {@link DocumentTextService}）与 {@code <file>} 段守卫，
 * 文件夹路径没跟上。对法律工作台来说「一个案子 = 一个文件夹的 Word/PDF」是主用法。
 */
class FolderContextOfficeFormatTest {

    private static final long PROJECT_ID = 7L;
    private static final long FOLDER_ID = 100L;

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

    private static ProjectFile record(long id, String name, String type, Path onDisk) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(PROJECT_ID);
        f.setName(name);
        f.setFileType(type);
        f.setFilePath(onDisk.toString());
        f.setIsFolder(false);
        return f;
    }

    /** 真的 Tika、真的提取器，只把「文件从哪来」换成本地临时文件。 */
    private static FileContextLoader loaderFor(List<ProjectFile> children) {
        ProjectFileService fileService = Mockito.mock(ProjectFileService.class);
        Mockito.when(fileService.getFilesByParent(PROJECT_ID, FOLDER_ID)).thenReturn(children);

        StorageService storage = Mockito.mock(StorageService.class);
        for (ProjectFile f : children) {
            Mockito.when(storage.load(f.getFilePath()))
                    .thenReturn(new FileSystemResource(Path.of(f.getFilePath())));
        }
        StorageServiceFactory factory = Mockito.mock(StorageServiceFactory.class);
        Mockito.when(factory.getStorageService()).thenReturn(storage);

        ProjectStorageResolver resolver = Mockito.mock(ProjectStorageResolver.class);
        Mockito.when(resolver.resolve(Mockito.anyString()))
                .thenAnswer(inv -> Path.of(inv.getArgument(0, String.class)));

        return new FileContextLoader(
                fileService,
                new FileContentExtractorService(Mockito.mock(OcrService.class), new AiContextProperties()),
                new AiContextProperties(),
                resolver,
                new DocumentTextService(factory));
    }

    @Test
    @DisplayName("文件夹里的 docx 正文必须进上下文，不能被静默跳过")
    void folderContextIncludesDocxBody(@TempDir Path dir) throws Exception {
        Path docx = writeDocx(dir, "股权转让协议.docx",
                "第一条 转让标的", "甲方将其持有的目标公司 40% 股权转让给乙方。");

        String out = loaderFor(List.of(record(11L, "股权转让协议.docx", "docx", docx)))
                .buildFolderContext(String.valueOf(FOLDER_ID), String.valueOf(PROJECT_ID), 0);

        assertTrue(out.contains("股权转让协议.docx"), "目录结构里要有这个文件，实际是：" + out);
        assertTrue(out.contains("甲方将其持有的目标公司"),
                "docx 正文必须真的进上下文——恒空正是「拖进来等于没拖」的成因，实际是：" + out);
        assertTrue(out.contains("40%"), "正文不能只剩标题，实际是：" + out);
    }

    @Test
    @DisplayName("纯文本文件的既有行为不变")
    void folderContextStillReadsPlainText(@TempDir Path dir) throws Exception {
        Path txt = dir.resolve("笔记.txt");
        Files.writeString(txt, "开庭时间 2026-09-01", StandardCharsets.UTF_8);

        String out = loaderFor(List.of(record(12L, "笔记.txt", "txt", txt)))
                .buildFolderContext(String.valueOf(FOLDER_ID), String.valueOf(PROJECT_ID), 0);

        assertTrue(out.contains("开庭时间 2026-09-01"), "纯文本路径不许被改坏，实际是：" + out);
    }

    @Test
    @DisplayName("抽不出正文的文件要留痕，不能让模型以为文件夹是空的")
    void unreadableFilesAreDisclosed(@TempDir Path dir) throws Exception {
        // 扩展名不在文本白名单，Tika 也抽不出正文的二进制文件
        Path bin = dir.resolve("扫描件.jpg");
        Files.write(bin, new byte[]{0x11, 0x22, 0x33, 0x44});

        String out = loaderFor(List.of(record(13L, "扫描件.jpg", "jpg", bin)))
                .buildFolderContext(String.valueOf(FOLDER_ID), String.valueOf(PROJECT_ID), 0);

        assertTrue(out.contains("扫描件.jpg"), "文件名要出现，实际是：" + out);
        assertTrue(out.contains("no extractable text"),
                "读不出来要明说，静默跳过等于骗模型，实际是：" + out);
        assertFalse(out.contains("```"), "没有正文就不该出现空的代码块，实际是：" + out);
    }
}
