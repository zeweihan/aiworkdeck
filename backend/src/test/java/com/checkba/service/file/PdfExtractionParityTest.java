// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.file;

import com.checkba.config.AiContextProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.ai.AgentMode;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.DocumentTextService;
import com.checkba.service.OcrService;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.ClientCapabilityService;
import com.checkba.service.ai.ContextAssemblerService;
import com.checkba.service.ai.InlineContentCache;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.ai.context.FileContextLoader;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.service.ai.skill.SkillRouter;
import com.checkba.service.ai.tools.LegalTools;
import com.checkba.service.ocr.OcrResult;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「同一份 PDF，三条口径一份正文、一份账单」（dev-board#800）。
 *
 * <p>病灶：同一份 PDF 直接拖进对话走 read_document（逐页 150DPI 云端 OCR、按页扣 Credits、
 * 只看前 20 页、带「--- 第 N 页 ---」抬头）、放在文件夹里拖走 DocumentTextService
 *（PDFBox 文字层、无页数上限、零 Credits、无分页抬头）、用 extract_file_text 读又是第三条
 *（文字层优先）。用户看不出自己走的是哪条，模型拿到的正文也不一样。
 *
 * <p>外加一条性能不变式：附件与活跃文档的正文是<b>每一轮都要重新注入</b>的，
 * 同一份文件在一条会话里只该抽一次。
 */
class PdfExtractionParityTest {

    /** 数一数「真的去读文件字节」了几次：抽取发生在这里，指纹（mtime/size）不经过它。 */
    static class CountingResource extends FileSystemResource {
        final AtomicInteger reads;

        CountingResource(Path path, AtomicInteger reads) {
            super(path);
            this.reads = reads;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            reads.incrementAndGet();
            return super.getInputStream();
        }
    }

    /** 一套真实接线：真 PDFBox / 真 Tika / 真缓存，只有云端 OCR 与存储层是替身。 */
    static class Wiring {
        final AtomicInteger resourceReads = new AtomicInteger();
        final OcrService ocr = mock(OcrService.class);
        final ProjectFileService fileService = mock(ProjectFileService.class);
        final ProjectFileTextCacheTest.FakeRepo repo = new ProjectFileTextCacheTest.FakeRepo();
        final AiContextProperties props = new AiContextProperties();
        final FileContentExtractorService extractor = new FileContentExtractorService(ocr, props);
        final ProjectFileTextCacheService cache = new ProjectFileTextCacheService(repo.mock, props);
        ProjectFileTextExtractor textExtractor;
        LegalTools legalTools;
        FileContextLoader folderLoader;
        ProjectFile record;

        Wiring(Path onDisk, long fileId) throws Exception {
            record = new ProjectFile();
            record.setId(fileId);
            record.setProjectId(7L);
            record.setName(onDisk.getFileName().toString());
            record.setFileType("pdf");
            record.setFilePath(onDisk.toString());
            record.setIsFolder(false);
            when(fileService.getFile(fileId)).thenReturn(record);
            when(fileService.getFileBytes(fileId)).thenReturn(Files.readAllBytes(onDisk));
            when(fileService.getFilesByParent(anyLong(), anyLong()))
                    .thenReturn(List.of(record));
            newProcess(onDisk);
        }

        /** 重建全部进程内状态（内存 LRU 也一起丢掉），只有落库缓存跨过来——等价于一次重启。 */
        void newProcess(Path onDisk) {
            StorageService storage = mock(StorageService.class);
            try {
                when(storage.load(anyString())).thenAnswer(inv -> new CountingResource(onDisk, resourceReads));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            StorageServiceFactory factory = mock(StorageServiceFactory.class);
            when(factory.getStorageService()).thenReturn(storage);
            DocumentTextService documentTextService = new DocumentTextService(factory);
            textExtractor = new ProjectFileTextExtractor(documentTextService, extractor, fileService, cache, null);
            legalTools = new LegalTools(fileService, null, extractor, textExtractor);
            ProjectStorageResolver resolver = mock(ProjectStorageResolver.class);
            try {
                when(resolver.resolve(anyString())).thenReturn(onDisk);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            folderLoader = new FileContextLoader(fileService, extractor, props, resolver, textExtractor);
        }
    }

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    private static final String BODY =
            "SHARE TRANSFER AGREEMENT\n"
                    + "Article 1 The Transferor transfers 40% of the equity to the Transferee.\n"
                    + "Article 2 The consideration is RMB 12,000,000.";

    @Test
    @DisplayName("文字层 PDF：read_document 一次 OCR 都不调，正文直接读出来")
    void readDocumentSkipsOcrForTextLayerPdf(@TempDir Path dir) throws Exception {
        ProjectContextHolder.setProjectId("7");
        Path pdf = PdfTextLayerFirstTest.textLayerPdf(dir, "股权转让协议.pdf", BODY);
        Wiring w = new Wiring(pdf, 31L);

        String out = w.legalTools.read_document("31");

        Mockito.verify(w.ocr, Mockito.never()).recognizeGeneral(anyString());
        assertTrue(out.contains("SHARE TRANSFER AGREEMENT"), out);
        assertTrue(out.contains("12,000,000"), out);
    }

    @Test
    @DisplayName("同一份 PDF 两轮 assemble 只抽一次：第二轮命中缓存")
    void twoAssembleRoundsExtractOnce(@TempDir Path dir) throws Exception {
        ProjectContextHolder.setProjectId("7");
        Path pdf = PdfTextLayerFirstTest.textLayerPdf(dir, "尽调材料.pdf", BODY);
        Wiring w = new Wiring(pdf, 32L);
        ContextAssemblerService assembler = assemblerWith(w);

        AiAgentController.ContextItem attachment = new AiAgentController.ContextItem();
        attachment.setId("32");
        attachment.setName("尽调材料.pdf");
        attachment.setFileType("pdf");

        String first = systemTextOf(assembler, attachment);
        int afterFirst = w.resourceReads.get();
        String second = systemTextOf(assembler, attachment);

        assertTrue(first.contains("SHARE TRANSFER AGREEMENT"), "第一轮就该注入正文：" + first);
        assertTrue(second.contains("SHARE TRANSFER AGREEMENT"), "第二轮正文不能少：" + second);
        assertEquals(afterFirst, w.resourceReads.get(),
                "第二轮必须命中缓存、不再读文件（附件正文每轮重注，这里是首 token 延迟的最大单点）");
        Mockito.verify(w.ocr, Mockito.never()).recognizeGeneral(anyString());
    }

    @Test
    @DisplayName("扫描件附件：两轮 assemble 只 OCR 一次（这一条是 Credits 的账）")
    void scannedAttachmentIsOcrdOnceAcrossRounds(@TempDir Path dir) throws Exception {
        ProjectContextHolder.setProjectId("7");
        Path pdf = PdfTextLayerFirstTest.scannedPdf(dir, "扫描的合同.pdf", 3);
        Wiring w = new Wiring(pdf, 38L);
        when(w.ocr.recognizeGeneral(anyString())).thenReturn(new OcrResult("识别出来的条款", ""));
        ContextAssemblerService assembler = assemblerWith(w);

        AiAgentController.ContextItem attachment = new AiAgentController.ContextItem();
        attachment.setId("38");
        attachment.setName("扫描的合同.pdf");
        attachment.setFileType("pdf");

        String first = systemTextOf(assembler, attachment);
        String second = systemTextOf(assembler, attachment);

        // 3 页 × 1 轮。第二轮再来一遍就是同一份文件被扣两次 Credits——而扫描件那条路
        // 没有内存 LRU 兜底（DocumentTextService 只缓存文字层结果），只有落库缓存救得了它
        Mockito.verify(w.ocr, Mockito.times(3)).recognizeGeneral(anyString());
        assertTrue(first.contains("识别出来的条款"), first);
        assertTrue(second.contains("识别出来的条款"), "第二轮正文不能因为命中缓存就少了：" + second);
    }

    @Test
    @DisplayName("缓存跨重启：换一个进程（新的抽取器、空的内存 LRU）仍然命中")
    void cacheSurvivesARestart(@TempDir Path dir) throws Exception {
        ProjectContextHolder.setProjectId("7");
        Path pdf = PdfTextLayerFirstTest.textLayerPdf(dir, "合同.pdf", BODY);
        Wiring w = new Wiring(pdf, 33L);

        String first = w.legalTools.read_document("33");
        int afterFirst = w.resourceReads.get();
        assertTrue(afterFirst > 0, "第一次总要真读一次");

        w.newProcess(pdf);
        String second = w.legalTools.read_document("33");

        assertEquals(first, second);
        assertEquals(afterFirst, w.resourceReads.get(), "落库缓存要跨重启命中，否则扫描件每次重启都重新扣一遍 Credits");
    }

    @Test
    @DisplayName("文件改过（mtime 变）即失效，重新抽取")
    void changedFileInvalidatesTheCache(@TempDir Path dir) throws Exception {
        ProjectContextHolder.setProjectId("7");
        Path pdf = PdfTextLayerFirstTest.textLayerPdf(dir, "在改的合同.pdf", BODY);
        Wiring w = new Wiring(pdf, 34L);

        w.legalTools.read_document("34");
        int afterFirst = w.resourceReads.get();

        // 覆盖成另一版正文（mtime 与 size 都会变）
        Path rewritten = PdfTextLayerFirstTest.textLayerPdf(dir, "改过的.pdf",
                "SUPPLEMENTARY AGREEMENT\nThe consideration is revised to RMB 9,000,000.");
        Files.copy(rewritten, pdf, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(pdf, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(pdf).toMillis() + 5000));
        when(w.fileService.getFileBytes(34L)).thenReturn(Files.readAllBytes(pdf));

        String second = w.legalTools.read_document("34");

        assertTrue(w.resourceReads.get() > afterFirst, "文件改过就必须重抽，不能喂旧正文");
        assertTrue(second.contains("9,000,000"), "读到的必须是改过之后的那一版：" + second);
    }

    @Test
    @DisplayName("read_file（按路径）的 PDF 分支同样先看文字层")
    void readFileAlsoPrefersTheTextLayer(@TempDir Path dir) throws Exception {
        Path projectRoot = Files.createDirectories(dir.resolve("projects").resolve("7"));
        Path pdf = PdfTextLayerFirstTest.textLayerPdf(projectRoot, "按路径读的合同.pdf", BODY);
        ProjectContextHolder.setProjectId("7");

        OcrService ocr = mock(OcrService.class);
        AiContextProperties props = new AiContextProperties();
        FileContentExtractorService extractor = new FileContentExtractorService(ocr, props);
        ProjectStorageResolver resolver = mock(ProjectStorageResolver.class);
        when(resolver.projectRoot(7L)).thenReturn(projectRoot);
        com.checkba.service.ai.tools.FileTools tools = new com.checkba.service.ai.tools.FileTools(
                null, null, null, extractor, resolver, null, null, null, null);

        String out = tools.read_file(pdf.toString());

        // OcrService.recognizeGeneral 是全仓唯一的 OCR 扣费点（平台档按次经网关扣 Credits），
        // 一次都没调 = 这份 PDF 一分钱没花
        Mockito.verify(ocr, Mockito.never()).recognizeGeneral(anyString());
        assertTrue(out.contains("SHARE TRANSFER AGREEMENT"), out);
    }

    @Test
    @DisplayName("文件夹里的 PDF 与直接拖进来的 PDF，正文逐字相同")
    void folderAndAttachmentAgreeOnTheSameFile(@TempDir Path dir) throws Exception {
        ProjectContextHolder.setProjectId("7");
        Path pdf = PdfTextLayerFirstTest.textLayerPdf(dir, "卷宗内的合同.pdf", BODY);
        Wiring w = new Wiring(pdf, 35L);

        String direct = w.legalTools.read_document("35");
        String folder = w.folderLoader.buildFolderContext("900", "7", 0);

        Mockito.verify(w.ocr, Mockito.never()).recognizeGeneral(anyString());
        for (String line : direct.split("\n")) {
            if (!line.isBlank()) {
                assertTrue(folder.contains(line.trim()),
                        "文件夹路径缺了这一行：" + line + "\n---\n" + folder);
            }
        }
    }

    @Test
    @DisplayName("文件夹里的扫描件不做 OCR，但要在名单里写明原因")
    void folderScanNamesTheReasonInsteadOfSilentlyDroppingScans(@TempDir Path dir) throws Exception {
        ProjectContextHolder.setProjectId("7");
        Path pdf = PdfTextLayerFirstTest.scannedPdf(dir, "扫描的判决书.pdf", 2);
        Wiring w = new Wiring(pdf, 36L);

        String folder = w.folderLoader.buildFolderContext("900", "7", 0);

        Mockito.verify(w.ocr, Mockito.never()).recognizeGeneral(anyString());
        assertTrue(folder.contains("扫描的判决书.pdf"), folder);
        assertTrue(folder.contains("文字识别"), "只给一个光秃秃的文件名，模型只能猜它是不是空文件：" + folder);
        assertTrue(folder.contains("extract_file_text"), "要给出下一步：" + folder);
    }

    @Test
    @DisplayName("扫描件走 read_document 照常 OCR，并明示只识别了前 N 页")
    void scannedPdfThroughReadDocumentStillOcrsAndSaysSo(@TempDir Path dir) throws Exception {
        ProjectContextHolder.setProjectId("7");
        Path pdf = PdfTextLayerFirstTest.scannedPdf(dir, "扫描件.pdf", 4);
        Wiring w = new Wiring(pdf, 37L);
        w.props.setOcrMaxPdfPages(2);
        when(w.ocr.recognizeGeneral(anyString())).thenReturn(new OcrResult("识别出来的正文片段", ""));

        String out = w.legalTools.read_document("37");

        Mockito.verify(w.ocr, Mockito.times(2)).recognizeGeneral(anyString());
        assertTrue(out.contains("识别出来的正文片段"), out);
        assertTrue(out.contains("共 4 页") && out.contains("仅识别前 2 页"), out);
    }

    // --- helpers ---

    private static String systemTextOf(ContextAssemblerService assembler, AiAgentController.ContextItem attachment) {
        List<ChatMessage> messages = assembler.assemble(
                "conv-1", "run-1", "这份材料里对价是多少", List.of(attachment), null,
                null, null, "7", AgentMode.AGENT, 1L, null);
        return ((SystemMessage) messages.get(0)).text();
    }

    private static ContextAssemblerService assemblerWith(Wiring w) {
        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(anyString())).thenReturn(Collections.emptyList());
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.match(anyString())).thenReturn(Optional.empty());
        MemoryManager memoryManager = mock(MemoryManager.class);
        when(memoryManager.getProjectMemory(anyLong())).thenReturn(Optional.empty());
        when(memoryManager.retrieveMemories(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(memoryManager.retrieveUserMemories(anyLong(), anyInt())).thenReturn(Collections.emptyList());
        ContextCompressor compressor = mock(ContextCompressor.class);
        when(compressor.needsCompression(any(), any())).thenReturn(false);
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.effectiveModelSupportsVision(any())).thenReturn(false);
        return new ContextAssemblerService(
                w.legalTools, messageService, w.folderLoader, w.props, skillRouter,
                new ClientCapabilityService(), new InlineContentCache(), memoryManager, compressor,
                mock(com.checkba.service.AppLanguageService.class), chatModelFactory, w.fileService);
    }
}
