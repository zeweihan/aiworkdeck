// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.AiDocxExportService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.PdfEditService;
import com.checkba.service.ai.PptxServiceClient;
import com.checkba.service.ai.SseEmitterService;
import com.checkba.service.pack.NativePackService;
import com.checkba.service.pack.OptionalComponents;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可选组件缺失时的 component_required 提示（设计 §3.2 / §4.2）。
 *
 * <p>钉两件事：payload 六个字段的形状（#530 前端逐字依赖），以及「组件没装」与
 * 「装了但没起」两条分支的文案分道——前者说「已请用户确认下载」，后者才说「稍后重试」。
 */
class ComponentRequiredTest {

    /**
     * 这些用例直接调工具方法，绕过了 {@code ToolRegistry.execute}——而项目上下文正是在那里设的。
     *
     * <p>{@code PdfTools.getPdfFile} 现在会过 {@code ToolFileGuard.rejectIfOutsideProject}
     * （dev-board#805），没有项目上下文时 fail closed。生产路径上 ToolRegistry 每次调用前
     * 都会设，所以这里把那个前提补齐；不补的话 pdf_to_word 会在取文件那一步就被挡下来，
     * 表现成「一次 component_required 都没发」，与本用例真正要验的东西无关。
     */
    @org.junit.jupiter.api.BeforeEach
    void enterProjectContext() {
        com.checkba.service.ai.context.ProjectContextHolder.setProjectId("3");   // 与 pdfFile() 同一个项目
    }

    @org.junit.jupiter.api.AfterEach
    void leaveProjectContext() {
        com.checkba.service.ai.context.ProjectContextHolder.clear();
    }

    /** PptxTools 的依赖里只有三样与本用例相关，其余按 Lombok 构造器顺序补 null。 */
    private static PptxTools pptxTools(PptxServiceClient client, EditorBridgeService bridge,
                                       NativePackService packs) {
        return new PptxTools(client, null, null, bridge, null, null, null, null, null, null, packs);
    }

    /** PdfTools 同理：只有转换链路上那几样与本用例相关。 */
    private static PdfTools pdfTools(PdfEditService pdf, ProjectFileService files,
                                     EditorBridgeService bridge, AiDocxExportService docx,
                                     PptxServiceClient client,
                                     com.checkba.storage.ProjectStorageResolver storage,
                                     NativePackService packs) {
        return new PdfTools(pdf, files, null, bridge, docx, client, storage, packs);
    }

    private static ProjectFile pdfFile(Path onDisk) {
        ProjectFile f = new ProjectFile();
        f.setId(7L);
        f.setProjectId(3L);
        f.setName("scan.pdf");
        f.setFilePath(onDisk.toString());
        return f;
    }

    @Test
    @DisplayName("component_required 的 payload 形状固定：packId/service/modelId/sizeMb/features/trigger")
    void payloadShapeIsStable() throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        EditorBridgeService bridge = new EditorBridgeService(
                sse, new ObjectMapper(), mock(com.checkba.service.telemetry.TelemetryService.class));
        bridge.setCurrentConversationId("conv-1");

        bridge.sendComponentRequiredAction("pptx-runtime", "pptx-service", null, 165,
                List.of("pptxGenerate", "pdfToWordLayout"), "pptx_generate");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(sse).send(eq("conv-1"), eq("client_action"), body.capture());
        Map<?, ?> p = new ObjectMapper().readValue(String.valueOf(body.getValue()), Map.class);
        assertEquals("component_required", p.get("action"));
        assertEquals("pptx-runtime", p.get("packId"));
        assertEquals("pptx-service", p.get("service"));
        assertNull(p.get("modelId"));
        assertEquals(165, p.get("sizeMb"));
        assertEquals(List.of("pptxGenerate", "pdfToWordLayout"), p.get("features"));
        assertEquals("pptx_generate", p.get("trigger"));
    }

    @Test
    @DisplayName("服务不可达且 pack 未装：pptx_check_service 发提示，返回文本说「已请用户确认下载」而不是「稍后重试」")
    void checkServicePromptsInsteadOfRetry() {
        PptxServiceClient client = mock(PptxServiceClient.class);
        when(client.isHealthy()).thenReturn(false);
        NativePackService packs = mock(NativePackService.class);
        when(packs.isReady("pptx-runtime")).thenReturn(false);
        when(packs.knownSizes("pptx-runtime")).thenReturn(new NativePackService.Sizes(173_015_040L, 0L));
        EditorBridgeService bridge = mock(EditorBridgeService.class);

        String out = pptxTools(client, bridge, packs).pptx_check_service();

        verify(bridge).sendComponentRequiredAction(eq("pptx-runtime"), eq("pptx-service"), isNull(),
                eq(165L), eq(OptionalComponents.byPackId("pptx-runtime").featureKeys()), eq("pptx_check_service"));
        assertTrue(out.contains("已请用户确认下载"), out);
        assertFalse(out.contains("稍后重试"), "pack 没装时让用户「稍后重试」是死路：" + out);
    }

    @Test
    @DisplayName("pack 已装只是服务没起：维持原来的「稍后重试」，不弹下载提示")
    void installedButDownStillSaysRetry() {
        PptxServiceClient client = mock(PptxServiceClient.class);
        when(client.isHealthy()).thenReturn(false);
        NativePackService packs = mock(NativePackService.class);
        when(packs.isReady("pptx-runtime")).thenReturn(true);
        EditorBridgeService bridge = mock(EditorBridgeService.class);

        String out = pptxTools(client, bridge, packs).pptx_check_service();

        verify(bridge, never()).sendComponentRequiredAction(anyString(), anyString(), any(), anyLong(), anyList(), anyString());
        assertTrue(out.contains("稍后重试"), out);
    }

    @Test
    @DisplayName("pptx_export_editable：mineru-runtime 未装时发 component_required，不碰 pptx-service")
    void editableExportPromptsForMineruComponent() {
        PptxServiceClient client = mock(PptxServiceClient.class);
        NativePackService packs = mock(NativePackService.class);
        when(packs.isReady("pptx-runtime")).thenReturn(true);
        when(packs.isReady("mineru-runtime")).thenReturn(false);
        when(packs.knownSizes("mineru-runtime")).thenReturn(new NativePackService.Sizes(1_048_576_000L, 0L));
        EditorBridgeService bridge = mock(EditorBridgeService.class);

        String out = pptxTools(client, bridge, packs).pptx_export_editable("proj-1", "报告", null);

        verify(bridge).sendComponentRequiredAction(eq("mineru-runtime"), eq("mineru-service"),
                eq("mineru-models"), eq(1000L),
                eq(OptionalComponents.byPackId("mineru-runtime").featureKeys()), eq("pptx_export_editable"));
        assertTrue(out.contains("已请用户确认下载"), out);
        assertFalse(out.contains("稍后重试"), "组件没装时让用户「稍后重试」是死路：" + out);
        // 组件缺失时不能先去调服务：那条路就是历史上「静默降级纯图片」的来源
        verify(client, never()).startExportEditable(anyString(), any(), any());
    }

    @Test
    @DisplayName("pptx_export_editable：pptx-runtime 未装时先提示它（导出的宿主服务），trigger 仍是本工具名")
    void editableExportPromptsForPptxComponentFirst() {
        PptxServiceClient client = mock(PptxServiceClient.class);
        NativePackService packs = mock(NativePackService.class);
        when(packs.isReady("pptx-runtime")).thenReturn(false);
        when(packs.knownSizes("pptx-runtime")).thenReturn(new NativePackService.Sizes(173_015_040L, 0L));
        EditorBridgeService bridge = mock(EditorBridgeService.class);

        String out = pptxTools(client, bridge, packs).pptx_export_editable("proj-1", "报告", null);

        verify(bridge).sendComponentRequiredAction(eq("pptx-runtime"), eq("pptx-service"), isNull(),
                eq(165L), eq(OptionalComponents.byPackId("pptx-runtime").featureKeys()),
                eq("pptx_export_editable"));
        verify(bridge, never()).sendComponentRequiredAction(eq("mineru-runtime"), anyString(), any(),
                anyLong(), anyList(), anyString());
        assertFalse(out.contains("稍后重试"), out);
        verify(client, never()).startExportEditable(anyString(), any(), any());
    }

    @Test
    @DisplayName("mineru-runtime 的 featureKeys 含 pptxEditableExport（前端 features 文案的对面）")
    void mineruUnlocksEditableExport() {
        assertTrue(OptionalComponents.byPackId("mineru-runtime").featureKeys().contains("pptxEditableExport"),
                "可编辑导出靠 mineru 做版面分析，组件卡上必须列出这条解锁项");
    }

    @Test
    @DisplayName("扫描件 OCR 失败且 mineru-runtime 未装：发 component_required，不说「稍后重试」")
    void scannedPdfPromptsForMineruComponent(@TempDir Path tmp) throws Exception {
        Path onDisk = Files.writeString(tmp.resolve("scan.pdf"), "%PDF-1.7");
        PdfEditService pdf = mock(PdfEditService.class);
        when(pdf.extractMarkdown(onDisk)).thenReturn(new PdfEditService.ExtractedText("", true));
        ProjectFileService files = mock(ProjectFileService.class);
        when(files.getFile(7L)).thenReturn(pdfFile(onDisk));
        com.checkba.storage.ProjectStorageResolver storage =
                mock(com.checkba.storage.ProjectStorageResolver.class);
        when(storage.resolve(onDisk.toString())).thenReturn(onDisk);
        PptxServiceClient client = mock(PptxServiceClient.class);
        when(client.ocrPdfToMarkdown(onDisk.toString())).thenThrow(new RuntimeException("connection refused"));
        NativePackService packs = mock(NativePackService.class);
        when(packs.isReady("mineru-runtime")).thenReturn(false);
        when(packs.knownSizes("mineru-runtime")).thenReturn(new NativePackService.Sizes(1_048_576_000L, 0L));
        EditorBridgeService bridge = mock(EditorBridgeService.class);

        String out = pdfTools(pdf, files, bridge, mock(AiDocxExportService.class), client, storage, packs)
                .pdf_to_word(7L, null, null);

        verify(bridge).sendComponentRequiredAction(eq("mineru-runtime"), eq("mineru-service"),
                eq("mineru-models"), eq(1000L),
                eq(OptionalComponents.byPackId("mineru-runtime").featureKeys()), eq("pdf_to_word"));
        assertFalse(out.contains("稍后重试"), out);
    }

    /** 文本型 PDF + 版式级转换打不通 的公共夹具。 */
    private record TextPdfFixture(PdfTools tools, EditorBridgeService bridge, AiDocxExportService docxExport,
                                  ProjectFile docx, PdfEditService pdf) {}

    private static TextPdfFixture textPdfLayoutDown(Path tmp, boolean packReady) throws Exception {
        Path onDisk = Files.writeString(tmp.resolve("text.pdf"), "%PDF-1.7");
        PdfEditService pdf = mock(PdfEditService.class);
        when(pdf.extractMarkdown(onDisk)).thenReturn(new PdfEditService.ExtractedText("# 正文", false));
        ProjectFileService files = mock(ProjectFileService.class);
        when(files.getFile(7L)).thenReturn(pdfFile(onDisk));
        com.checkba.storage.ProjectStorageResolver storage =
                mock(com.checkba.storage.ProjectStorageResolver.class);
        when(storage.resolve(onDisk.toString())).thenReturn(onDisk);
        when(storage.projectRoot(3L)).thenReturn(tmp);
        PptxServiceClient client = mock(PptxServiceClient.class);
        when(client.convertPdfToDocx(anyString(), anyString())).thenThrow(new RuntimeException("service down"));
        NativePackService packs = mock(NativePackService.class);
        when(packs.isReady("pptx-runtime")).thenReturn(packReady);
        when(packs.knownSizes("pptx-runtime")).thenReturn(new NativePackService.Sizes(173_015_040L, 0L));
        AiDocxExportService docxExport = mock(AiDocxExportService.class);
        ProjectFile docx = new ProjectFile();
        docx.setId(9L);
        docx.setName("scan.docx");
        when(docxExport.exportMarkdownToDocx(eq(3L), isNull(), anyLong(), anyString(), eq("# 正文")))
                .thenReturn(docx);
        EditorBridgeService bridge = mock(EditorBridgeService.class);
        return new TextPdfFixture(pdfTools(pdf, files, bridge, docxExport, client, storage, packs),
                bridge, docxExport, docx, pdf);
    }

    @Test
    @DisplayName("版式级转换打不通且 pptx-runtime 未装：发提示并停下，不做结构级降级（dev-board#1016）")
    void layoutFailureWithComponentMissingStopsWithoutFallback(@TempDir Path tmp) throws Exception {
        TextPdfFixture f = textPdfLayoutDown(tmp, false);

        String out = f.tools().pdf_to_word(7L, null, null);

        verify(f.bridge()).sendComponentRequiredAction(eq("pptx-runtime"), eq("pptx-service"), isNull(),
                eq(165L), eq(OptionalComponents.byPackId("pptx-runtime").featureKeys()), eq("pdf_to_word"));
        // 旧行为：一边弹下载提示一边转出一份丢了表格的 docx 并自动打开——模型拿着它接着干
        verify(f.docxExport(), never()).exportMarkdownToDocx(any(), any(), any(), any(), any());
        verify(f.bridge(), never()).sendOpenFileAction(any());
        assertTrue(out.contains("已请用户确认下载"), out);
        assertTrue(out.contains("不要改用文字提取"), out);
        assertTrue(out.contains("装好后会自动重新执行"), out);
        assertFalse(out.contains("稍后重试"), out);
        assertFalse(out.startsWith("Error"), "等组件不是失败，别计入连续失败：" + out);
    }

    @Test
    @DisplayName("组件正在下载（isReady=false）时即便模型带了 allowStructuralFallback=true 也不降级")
    void downloadingComponentNeverFallsBackEvenIfAllowed(@TempDir Path tmp) throws Exception {
        TextPdfFixture f = textPdfLayoutDown(tmp, false);

        String out = f.tools().pdf_to_word(7L, null, true);

        verify(f.docxExport(), never()).exportMarkdownToDocx(any(), any(), any(), any(), any());
        verify(f.bridge(), never()).sendOpenFileAction(any());
        assertTrue(out.contains("已请用户确认下载"), out);
    }

    @Test
    @DisplayName("组件已装好、服务却失败：不自动降级，返回失败原因并要求先征得用户同意")
    void installedButServiceFailsNeedsExplicitConsent(@TempDir Path tmp) throws Exception {
        TextPdfFixture f = textPdfLayoutDown(tmp, true);

        String out = f.tools().pdf_to_word(7L, null, null);

        verify(f.bridge(), never()).sendComponentRequiredAction(anyString(), anyString(), any(), anyLong(),
                anyList(), anyString());
        verify(f.docxExport(), never()).exportMarkdownToDocx(any(), any(), any(), any(), any());
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("service down"), "失败原因要带出来：" + out);
        assertTrue(out.contains("表格与版式会丢失"), out);
        assertTrue(out.contains("allowStructuralFallback=true"), out);
    }

    @Test
    @DisplayName("用户同意后带 allowStructuralFallback=true：做结构级转换，返回里明写表格与版式已丢失")
    void allowedStructuralFallbackConvertsAndDeclaresTheLoss(@TempDir Path tmp) throws Exception {
        TextPdfFixture f = textPdfLayoutDown(tmp, true);

        String out = f.tools().pdf_to_word(7L, null, true);

        verify(f.docxExport()).exportMarkdownToDocx(eq(3L), isNull(), anyLong(), eq("scan.docx"), eq("# 正文"));
        verify(f.bridge()).sendOpenFileAction(f.docx());
        verify(f.bridge()).noteGenerated(EditorBridgeService.pdfToWordKey(7L, null), 9L);
        assertTrue(out.contains("结构级转换"), out);
        assertTrue(out.contains("表格与版式已丢失"), out);
    }

    @Test
    @DisplayName("同一轮对同一份 PDF 再转一次：直接复用已转出的那份，不再新建（dev-board#1017）")
    void sameRunSecondConversionReusesTheFirst(@TempDir Path tmp) throws Exception {
        TextPdfFixture f = textPdfLayoutDown(tmp, true);
        when(f.bridge().generatedInRun(EditorBridgeService.pdfToWordKey(7L, null))).thenReturn(9L);
        // pdfTools 里的 ProjectFileService 是夹具内部 mock，这里重新搭一份可控的
        Path onDisk = tmp.resolve("text.pdf");
        ProjectFileService files = mock(ProjectFileService.class);
        when(files.getFile(7L)).thenReturn(pdfFile(onDisk));
        when(files.findFile(9L)).thenReturn(java.util.Optional.of(f.docx()));
        PdfTools tools = pdfTools(f.pdf(), files, f.bridge(), f.docxExport(), mock(PptxServiceClient.class),
                mock(com.checkba.storage.ProjectStorageResolver.class), mock(NativePackService.class));

        String out = tools.pdf_to_word(7L, null, true);

        assertTrue(out.contains("本轮已生成过"), out);
        assertTrue(out.contains("9"), out);
        verify(f.pdf(), never()).extractMarkdown(any());
        verify(f.docxExport(), never()).exportMarkdownToDocx(any(), any(), any(), any(), any());
    }
}
