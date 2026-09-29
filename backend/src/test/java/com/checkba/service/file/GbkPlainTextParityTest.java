// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.file;

import com.checkba.config.AiContextProperties;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.DocumentTextService;
import com.checkba.service.OcrService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.tools.FileTools;
import com.checkba.service.ai.tools.LegalTools;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「同一份 GBK 编码的中文 txt，从两个入口读出同一份文字」（dev-board#1065，审计 T-05）。
 *
 * <p>病灶：{@code read_document} 对纯文本走自己的「UTF-8 严格解码、失败回退 GBK」，
 * 而 {@code extract_file_text} 走 {@link ProjectFileTextExtractor} → Tika，靠字符集猜测——
 * 短小的 GBK 中文文本 Tika 常常猜错，于是同一份文件从两个入口读出两份不同的文字。
 * 修法是把那段解码并进抽取器，两个入口（以及附件注入、文件夹上下文）同一口径；
 * 之后 {@code read_document} 才能安心降为只登记不下发的兼容入口。
 *
 * <p>接线是真的：真 Tika、真 {@link FileContentExtractorService}、真抽取器，只有存储层是替身。
 */
class GbkPlainTextParityTest {

    private static final String BODY = "甲方：北京某某科技有限公司\n"
            + "第八条 违约责任：任何一方违约的，应向守约方支付合同总价百分之二十的违约金。\n";

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    @Test
    @DisplayName("GBK 中文 txt：extract_file_text 与 read_document 读出同一份、而且是对的文字")
    void gbkTextReadsIdenticallyThroughBothEntries(@TempDir Path dir) throws Exception {
        Path onDisk = dir.resolve("会议纪要.txt");
        Files.write(onDisk, BODY.getBytes(Charset.forName("GBK")));

        ProjectFile record = new ProjectFile();
        record.setId(51L);
        record.setProjectId(7L);
        record.setName("会议纪要.txt");
        record.setFileType("txt");
        record.setFilePath(onDisk.toString());
        record.setIsFolder(false);
        record.setFileSize(Files.size(onDisk));

        ProjectFileService fileService = mock(ProjectFileService.class);
        when(fileService.getFile(51L)).thenReturn(record);
        when(fileService.findFile(51L)).thenReturn(Optional.of(record));
        when(fileService.getFileBytes(51L)).thenReturn(Files.readAllBytes(onDisk));
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        when(repo.findById(51L)).thenReturn(Optional.of(record));

        StorageService storage = mock(StorageService.class);
        when(storage.load(anyString())).thenAnswer(inv -> new FileSystemResource(onDisk));
        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        when(factory.getStorageService()).thenReturn(storage);
        DocumentTextService documentTextService = new DocumentTextService(factory);

        FileContentExtractorService fileContent =
                new FileContentExtractorService(mock(OcrService.class), new AiContextProperties());
        ProjectFileTextExtractor textExtractor =
                new ProjectFileTextExtractor(documentTextService, fileContent, fileService, null, null);
        FileTools fileTools = new FileTools(fileService, repo, null, fileContent, null, documentTextService,
                null, null, textExtractor);
        LegalTools legalTools = new LegalTools(fileService, null, textExtractor);

        ProjectContextHolder.setProjectId("7");
        String viaExtract = fileTools.extract_file_text(51L);
        String viaReadDocument = legalTools.read_document("51");
        String viaExtractor = textExtractor.extractText(record);

        System.out.printf("[dev-board#1065] GBK txt：extract_file_text=%s | read_document=%s%n",
                viaExtract.replace('\n', '/'), viaReadDocument.replace('\n', '/'));
        assertEquals(BODY, viaExtractor, "抽取器本身就得把 GBK 解对");
        assertEquals(BODY, viaReadDocument, "read_document 必须读出原文");
        assertEquals("[文件 会议纪要.txt]\n" + BODY, viaExtract,
                "extract_file_text 必须与 read_document 逐字相同（只多一行「[文件 X]」抬头）");
    }

    @Test
    @DisplayName("UTF-8 中文 txt 照旧按 UTF-8 解（回退 GBK 只在 UTF-8 严格解码失败时发生）")
    void utf8TextIsUnchanged(@TempDir Path dir) throws Exception {
        Path onDisk = dir.resolve("说明.md");
        Files.writeString(onDisk, BODY, StandardCharsets.UTF_8);
        ProjectFile record = new ProjectFile();
        record.setId(52L);
        record.setProjectId(7L);
        record.setName("说明.md");
        record.setFileType("md");
        record.setFilePath(onDisk.toString());
        record.setIsFolder(false);

        ProjectFileService fileService = mock(ProjectFileService.class);
        when(fileService.getFileBytes(52L)).thenReturn(Files.readAllBytes(onDisk));
        FileContentExtractorService fileContent =
                new FileContentExtractorService(mock(OcrService.class), new AiContextProperties());
        ProjectFileTextExtractor textExtractor =
                new ProjectFileTextExtractor(mock(DocumentTextService.class), fileContent, fileService, null, null);

        assertEquals(BODY, textExtractor.extractText(record));
        assertTrue(textExtractor.extract(record).contains("违约金"), "参考入口同一口径");
    }
}
