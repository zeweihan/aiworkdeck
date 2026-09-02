package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.DocumentTextService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.ai.context.ProjectContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * extract_file_text 对图片与扫描件的 OCR 路由（dev-board#396）。
 *
 * <p>病灶：这个工具原来只有 Tika 一条路，项目里的 jpg 恒抽不出正文，返回的提示还只提
 * 「try read_file with OCR for image PDFs」——模型读作「只有 PDF 能 OCR」，于是转头调
 * run_python 想自己跑 OCR，撞上「Cannot run program docker」后告诉用户
 * 「OCR 环境（docker）不可用」。图片其实一直可读：云端 OCR 就在 read_file 那条分支上。
 */
class ExtractFileTextOcrTest {

    private final ProjectFileService projectFileService = Mockito.mock(ProjectFileService.class);
    private final ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
    private final FileContentExtractorService extractor = Mockito.mock(FileContentExtractorService.class);
    private final DocumentTextService documentTextService = Mockito.mock(DocumentTextService.class);

    private final FileTools tools =
            new FileTools(projectFileService, repo, null, extractor, null, documentTextService, null, null);

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    private ProjectFile registerFile(long id, String name, String type) throws Exception {
        ProjectContextHolder.setProjectId("7");
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(7L);
        f.setName(name);
        f.setFileType(type);
        f.setIsFolder(false);
        Mockito.when(repo.findById(id)).thenReturn(Optional.of(f));
        Mockito.when(projectFileService.getFileBytes(id)).thenReturn(new byte[]{1, 2, 3});
        return f;
    }

    @Test
    @DisplayName("jpg 直接走云端 OCR，不再回那句只提 image PDFs 的死提示")
    void imageGoesThroughCloudOcr() throws Exception {
        registerFile(11L, "现场照片.jpg", "jpg");
        Mockito.when(extractor.isOcrSupported("现场照片.jpg")).thenReturn(true);
        Mockito.when(extractor.extractTextWithOcr(Mockito.any(File.class)))
                .thenReturn("甲方：某某公司\n乙方：某某个人");

        String out = tools.extract_file_text(11L);

        Mockito.verify(extractor).extractTextWithOcr(Mockito.any(File.class));
        // 图片没有文字层，抽一次 Tika 是纯浪费
        Mockito.verify(documentTextService, Mockito.never()).extractText(Mockito.any());
        assertTrue(out.contains("甲方：某某公司"), "OCR 正文要原样返回：" + out);
        assertFalse(out.startsWith("Warning"), "不该再退回「抽不出正文」：" + out);
        assertFalse(out.contains("image PDFs"), "旧提示会让模型以为只有 PDF 能 OCR：" + out);
    }

    @Test
    @DisplayName("临时文件保留原扩展名——extractTextWithOcr 按文件名分图片/PDF 两条分支")
    void ocrTempFileKeepsExtension() throws Exception {
        registerFile(12L, "签署页.png", "png");
        Mockito.when(extractor.isOcrSupported("签署页.png")).thenReturn(true);
        Mockito.when(extractor.extractTextWithOcr(Mockito.any(File.class))).thenReturn("识别结果");

        tools.extract_file_text(12L);

        org.mockito.ArgumentCaptor<File> captor = org.mockito.ArgumentCaptor.forClass(File.class);
        Mockito.verify(extractor).extractTextWithOcr(captor.capture());
        assertTrue(captor.getValue().getName().endsWith(".png"),
                "临时文件名丢了扩展名，OCR 会判成不支持：" + captor.getValue().getName());
    }

    @Test
    @DisplayName("扫描版 PDF：文字层为空才补一次 OCR")
    void scannedPdfFallsBackToOcr() throws Exception {
        registerFile(13L, "判决书.pdf", "pdf");
        Mockito.when(extractor.isOcrSupported("判决书.pdf")).thenReturn(true);
        Mockito.when(documentTextService.extractText(Mockito.any())).thenReturn("   ");
        Mockito.when(extractor.extractTextWithOcr(Mockito.any(File.class))).thenReturn("本院认为……");

        String out = tools.extract_file_text(13L);

        assertTrue(out.contains("本院认为"), out);
    }

    @Test
    @DisplayName("文本型 PDF 照旧走文字层，不白花 OCR 的钱")
    void textPdfDoesNotHitOcr() throws Exception {
        registerFile(14L, "合同.pdf", "pdf");
        Mockito.when(extractor.isOcrSupported("合同.pdf")).thenReturn(true);
        Mockito.when(documentTextService.extractText(Mockito.any())).thenReturn("第一条 定义");

        String out = tools.extract_file_text(14L);

        Mockito.verify(extractor, Mockito.never()).extractTextWithOcr(Mockito.any(File.class));
        assertTrue(out.contains("第一条 定义"), out);
    }

    @Test
    @DisplayName("OCR 失败要把真实原因带出来，且判定为失败")
    void ocrFailureSurfacesTheRealReason() throws Exception {
        registerFile(15L, "扫描件.jpg", "jpg");
        Mockito.when(extractor.isOcrSupported("扫描件.jpg")).thenReturn(true);
        Mockito.when(extractor.extractTextWithOcr(Mockito.any(File.class)))
                .thenReturn("[System: OCR 识别失败: Credits 余额不足]");

        String out = tools.extract_file_text(15L);

        assertTrue(out.startsWith("Error"), "失败必须以 Error 前缀返回，否则被判成成功：" + out);
        assertTrue(out.contains("Credits 余额不足"), "底层原因必须原样透出，模型才不会自己编：" + out);
    }

    @Test
    @DisplayName("非 OCR 格式抽不出正文时，提示里不许再出现 image PDFs")
    void nonOcrFormatWarningNoLongerMisleads() throws Exception {
        registerFile(16L, "空表.xlsx", "xlsx");
        Mockito.when(extractor.isOcrSupported("空表.xlsx")).thenReturn(false);
        Mockito.when(documentTextService.extractText(Mockito.any())).thenReturn("");

        String out = tools.extract_file_text(16L);

        assertTrue(out.startsWith("Warning"), out);
        assertFalse(out.contains("image PDFs"), out);
        assertFalse(out.contains("read_file"), "别再把用户往另一条工具上引，那条路并不更强：" + out);
    }
}
