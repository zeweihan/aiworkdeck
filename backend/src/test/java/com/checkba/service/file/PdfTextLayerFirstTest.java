// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.file;

import com.checkba.config.AiContextProperties;
import com.checkba.service.OcrService;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.ocr.OcrResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PDF 抽取：<b>先文字层，抽不出才 OCR</b>（dev-board#800）。
 *
 * <p>病灶：{@code ai.context.ocr-extensions} 里有 pdf，于是 read_document / read_file
 * 把<b>每一份</b> PDF 都送去逐页 150DPI 渲染 + 云端 OCR——绝大多数合同、裁判文书、
 * 招股书本来带完整文字层，PDFBox 毫秒级就能读。三个后果叠在一起：每轮按页扣 Credits、
 * OCR 转写把数字与主体名读错、只看前 20 页却让模型以为那就是全文。
 */
class PdfTextLayerFirstTest {

    private static final String BODY =
            "SHARE TRANSFER AGREEMENT\n"
                    + "Article 1 The Transferor shall transfer 40% of the equity to the Transferee.\n"
                    + "Article 2 The consideration is RMB 12,000,000.";

    /** 带文字层的 PDF（真 PDFBox 写出来的，不是伪造的字节）。 */
    static Path textLayerPdf(Path dir, String fileName, String body) throws IOException {
        Path path = dir.resolve(fileName);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                for (String line : body.split("\n")) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -18);
                }
                cs.endText();
            }
            doc.save(path.toFile());
        }
        return path;
    }

    /** 没有文字层的「扫描件」：只有空白页，PDFTextStripper 一个字都抽不出来。 */
    static Path scannedPdf(Path dir, String fileName, int pages) throws IOException {
        Path path = dir.resolve(fileName);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private static FileContentExtractorService extractorWith(OcrService ocr, AiContextProperties props) {
        return new FileContentExtractorService(ocr, props);
    }

    @Test
    @DisplayName("带文字层的 PDF：一次 OCR 都不调，正文原样返回")
    void textLayerPdfNeverTouchesOcr(@TempDir Path dir) throws Exception {
        OcrService ocr = Mockito.mock(OcrService.class);
        Path pdf = textLayerPdf(dir, "股权转让协议.pdf", BODY);

        String out = extractorWith(ocr, new AiContextProperties()).extractTextWithOcr(pdf.toFile());

        Mockito.verify(ocr, Mockito.never()).recognizeGeneral(Mockito.anyString());
        assertTrue(out.contains("SHARE TRANSFER AGREEMENT"), "文字层正文要原样返回：" + out);
        assertTrue(out.contains("12,000,000"), "金额是法律文书里最怕 OCR 读错的东西：" + out);
        assertFalse(out.contains("--- 第 1 页 ---"), "走了文字层就不该有 OCR 的分页抬头：" + out);
        assertFalse(out.contains("[System:"), "文字层路径不该带扫描件说明：" + out);
    }

    @Test
    @DisplayName("没有文字层的扫描件：照旧逐页 OCR")
    void scannedPdfStillGoesThroughOcr(@TempDir Path dir) throws Exception {
        OcrService ocr = Mockito.mock(OcrService.class);
        Mockito.when(ocr.recognizeGeneral(Mockito.anyString()))
                .thenReturn(new OcrResult("被识别出来的一行字", ""));
        Path pdf = scannedPdf(dir, "扫描件.pdf", 3);

        String out = extractorWith(ocr, new AiContextProperties()).extractTextWithOcr(pdf.toFile());

        Mockito.verify(ocr, Mockito.times(3)).recognizeGeneral(Mockito.anyString());
        assertTrue(out.contains("被识别出来的一行字"), out);
        assertTrue(out.contains("文字识别"), "OCR 结果必须自报是转写，不然模型会当成原文：" + out);
    }

    @Test
    @DisplayName("扫描件超过页数上限：只识别前 N 页，并在末尾明说其余没读")
    void scannedPdfStatesThePageCap(@TempDir Path dir) throws Exception {
        OcrService ocr = Mockito.mock(OcrService.class);
        Mockito.when(ocr.recognizeGeneral(Mockito.anyString()))
                .thenReturn(new OcrResult("一页内容", ""));
        AiContextProperties props = new AiContextProperties();
        props.setOcrMaxPdfPages(2);
        Path pdf = scannedPdf(dir, "长扫描件.pdf", 5);

        String out = extractorWith(ocr, props).extractTextWithOcr(pdf.toFile());

        Mockito.verify(ocr, Mockito.times(2)).recognizeGeneral(Mockito.anyString());
        assertTrue(out.contains("共 5 页"), "要说清全文多少页：" + out);
        assertTrue(out.contains("仅识别前 2 页"), "要说清只识别了多少页：" + out);
        assertTrue(out.contains("不要当作全文"), "不明说的话模型会把前 2 页当成整份文件：" + out);
    }

    @Test
    @DisplayName("页数上限只管 OCR：几百页的文字层 PDF 整篇抽取，不截到第 20 页")
    void pageCapDoesNotApplyToTheTextLayer(@TempDir Path dir) throws Exception {
        OcrService ocr = Mockito.mock(OcrService.class);
        Path path = dir.resolve("招股说明书.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= 25; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Section " + i + " of the prospectus, with enough words to count.");
                    cs.endText();
                }
            }
            doc.save(path.toFile());
        }

        String out = extractorWith(ocr, new AiContextProperties()).extractTextWithOcr(path.toFile());

        Mockito.verify(ocr, Mockito.never()).recognizeGeneral(Mockito.anyString());
        assertTrue(out.contains("Section 25"), "第 25 页的内容必须在里面（旧实现只有前 20 页）：" + out);
    }

    @Test
    @DisplayName("判据只此一份：噪声级的残留文字不算文字层，仍走 OCR")
    void noiseIsNotATextLayer() {
        assertFalse(PdfTextLayer.isUsable(null));
        assertFalse(PdfTextLayer.isUsable("   \n\n  "));
        assertFalse(PdfTextLayer.isUsable("1\n2\n3"), "扫描件常残留的页码不是正文");
        assertFalse(PdfTextLayer.isUsable("···———···"), "纯标点不算实义字符");
        assertTrue(PdfTextLayer.isUsable("本协议由甲方与乙方于二零二六年签署。"));
        assertTrue(PdfTextLayer.isUsable("Article 1 The Transferor shall transfer."));
    }
}
