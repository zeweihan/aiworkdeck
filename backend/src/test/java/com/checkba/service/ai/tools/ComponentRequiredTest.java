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
                .pdf_to_word(7L, null);

        verify(bridge).sendComponentRequiredAction(eq("mineru-runtime"), eq("mineru-service"),
                eq("mineru-models"), eq(1000L),
                eq(OptionalComponents.byPackId("mineru-runtime").featureKeys()), eq("pdf_to_word"));
        assertFalse(out.contains("稍后重试"), out);
    }

    @Test
    @DisplayName("版式级转换打不通且 pptx-runtime 未装：发提示，但结构级降级转换照常做完")
    void layoutFallbackStillConvertsWhilePrompting(@TempDir Path tmp) throws Exception {
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
        when(packs.isReady("pptx-runtime")).thenReturn(false);
        when(packs.knownSizes("pptx-runtime")).thenReturn(new NativePackService.Sizes(173_015_040L, 0L));
        AiDocxExportService docxExport = mock(AiDocxExportService.class);
        ProjectFile docx = new ProjectFile();
        docx.setId(9L);
        docx.setName("scan.docx");
        when(docxExport.exportMarkdownToDocx(eq(3L), isNull(), anyLong(), anyString(), eq("# 正文")))
                .thenReturn(docx);
        EditorBridgeService bridge = mock(EditorBridgeService.class);

        String out = pdfTools(pdf, files, bridge, docxExport, client, storage, packs).pdf_to_word(7L, null);

        verify(bridge).sendComponentRequiredAction(eq("pptx-runtime"), eq("pptx-service"), isNull(),
                eq(165L), eq(OptionalComponents.byPackId("pptx-runtime").featureKeys()), eq("pdf_to_word"));
        // 降级路径不能因为发了提示就失败：docx 照样转出来并在编辑器里打开
        verify(bridge).sendOpenFileAction(docx);
        assertTrue(out.contains("结构级转换"), out);
    }
}
