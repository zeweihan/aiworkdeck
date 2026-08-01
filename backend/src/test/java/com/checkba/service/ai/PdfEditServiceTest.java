package com.checkba.service.ai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PdfEditService 核心操作回归：定位（含跨行）、高亮、批注、脱敏（真删文字层）、
 * 原位替换、markdown 提取启发式、加密件拒绝。全部用内存生成的 PDF，无外部依赖。
 */
class PdfEditServiceTest {

    @TempDir
    Path tempDir;

    private final PdfEditService service = new PdfEditService();

    /** 两行文本的单页 PDF：Confidential Agreement / Fee is 20 percent of total */
    private Path samplePdf() throws IOException {
        Path path = tempDir.resolve("sample.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Confidential Agreement");
                cs.endText();
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 680);
                cs.showText("Fee is 20 percent of total");
                cs.endText();
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private String extractText(Path path) throws IOException {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            // 关闭重叠去重：原位覆写的新文本与旧文本坐标重合，默认会被当作
            // 重复字符滤掉（这正说明覆写对位精确），断言需要看到两层全部字符
            stripper.setSuppressDuplicateOverlappingText(false);
            return stripper.getText(doc);
        }
    }

    @Test
    void locateFindsSingleLineMatch() throws IOException {
        Path pdf = samplePdf();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<PdfEditService.TextMatch> matches = service.locate(doc, "Fee is 20", null);
            assertEquals(1, matches.size());
            assertEquals(0, matches.get(0).pageIndex);
            assertEquals(1, matches.get(0).lineRects.size());
            assertTrue(matches.get(0).fontSizePt > 0);
        }
    }

    @Test
    void locateMatchesAcrossLineWrap() throws IOException {
        Path pdf = samplePdf();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            // "Agreement" 行尾 + "Fee" 下一行行首，原文中间有换行
            List<PdfEditService.TextMatch> matches = service.locate(doc, "AgreementFee is", null);
            assertEquals(1, matches.size());
            assertEquals(2, matches.get(0).lineRects.size(), "跨行匹配应拆成两个行矩形");
        }
    }

    @Test
    void highlightAddsAnnotationWithAppearance() throws IOException {
        Path pdf = samplePdf();
        int count = service.highlight(pdf, "Confidential", null, "#FFFF00", "注意保密条款");
        assertEquals(1, count);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            var annotations = doc.getPage(0).getAnnotations();
            assertEquals(1, annotations.size());
            assertEquals("Highlight", annotations.get(0).getSubtype());
            assertEquals("注意保密条款", annotations.get(0).getContents());
            assertNotNull(annotations.get(0).getAppearance(), "必须生成外观流，否则部分查看器不显示");
        }
    }

    @Test
    void annotateAddsNoteNearAnchor() throws IOException {
        Path pdf = samplePdf();
        int page = service.addNote(pdf, "20 percent", "费率需与附件一核对", null);
        assertEquals(0, page);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            var annotations = doc.getPage(0).getAnnotations();
            assertEquals(1, annotations.size());
            assertEquals("Text", annotations.get(0).getSubtype());
            assertEquals("费率需与附件一核对", annotations.get(0).getContents());
        }
    }

    @Test
    void redactRemovesTextLayerOnAffectedPage() throws IOException {
        Path pdf = samplePdf();
        PdfEditService.RedactResult result = service.redact(pdf, List.of("Confidential"), null);
        assertEquals(1, result.matchCount);
        assertEquals(List.of(0), result.rasterizedPages);
        String after = extractText(pdf);
        assertFalse(after.contains("Confidential"), "脱敏后文字必须不可提取");
        // 整页光栅化：该页全部文本层被移除（这是脱敏的代价，工具描述已声明）
        assertFalse(after.contains("Fee"), "受影响页整页转为图片，无残留文本层");
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertEquals(1, doc.getNumberOfPages(), "页数不变");
        }
    }

    @Test
    void annotationsSurviveRedactOnSamePage() throws IOException {
        Path pdf = samplePdf();
        service.highlight(pdf, "Fee is 20", null, "#FFFF00", "重点");
        service.redact(pdf, List.of("Confidential"), null);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            var annotations = doc.getPage(0).getAnnotations();
            assertEquals(1, annotations.size(), "光栅化脱敏不应吞掉同页已有的高亮/批注");
            assertEquals("Highlight", annotations.get(0).getSubtype());
        }
        assertFalse(extractText(pdf).contains("Confidential"), "文字层仍须被移除");
    }

    @Test
    void redactMissingTextReportsError() throws IOException {
        Path pdf = samplePdf();
        var e = assertThrows(PdfEditService.PdfEditException.class,
                () -> service.redact(pdf, List.of("不存在的文本"), null));
        assertTrue(e.getMessage().contains("未找到"));
    }

    @Test
    void replaceOverlaysNewText() throws IOException {
        Path pdf = samplePdf();
        int count = service.replaceText(pdf, "20 percent", "30 percent", null);
        assertEquals(1, count);
        String after = extractText(pdf);
        assertTrue(after.contains("30 percent"), "新文本应可见（覆写层）");
    }

    @Test
    void replaceRejectsCrossLineMatch() throws IOException {
        Path pdf = samplePdf();
        var e = assertThrows(PdfEditService.PdfEditException.class,
                () -> service.replaceText(pdf, "AgreementFee", "X", null));
        assertTrue(e.getMessage().contains("跨行"));
    }

    @Test
    void inspectReportsPagesAndText() throws IOException {
        Path pdf = samplePdf();
        String json = service.inspect(pdf, null, 3000);
        cn.hutool.json.JSONObject obj = cn.hutool.json.JSONUtil.parseObj(json);
        assertEquals(1, obj.getInt("page_count"));
        cn.hutool.json.JSONObject p0 = obj.getJSONArray("pages").getJSONObject(0);
        assertTrue(p0.getBool("has_text_layer"));
        assertTrue(p0.getStr("text").contains("Confidential Agreement"));
    }

    @Test
    void extractMarkdownReturnsNullForScannedLikePdf() throws IOException {
        // 空白页 = 无文本层（扫描件的最小等价物）
        Path path = tempDir.resolve("blank.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            doc.save(path.toFile());
        }
        assertEquals(null, service.extractMarkdown(path));
    }

    @Test
    void encryptedPdfIsRejected() throws IOException {
        Path path = tempDir.resolve("encrypted.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp = new StandardProtectionPolicy("owner-pass", "", ap);
            spp.setEncryptionKeyLength(128);
            doc.protect(spp);
            doc.save(path.toFile());
        }
        var e = assertThrows(PdfEditService.PdfEditException.class,
                () -> service.inspect(path, null, 100));
        assertTrue(e.getMessage().contains("加密"));
    }

    @Test
    void linesToMarkdownMergesHardWrapAndEscapesLeaders() {
        // 长行未以句读结尾 → 与下一行合并；短行（标题）不合并
        String pageText = "第一条 本协议由甲方与乙方本着平等自愿的原则经友好协商\n" +
                "一致后订立。\n" +
                "第二条 保密义务\n" +
                "1. 双方应对合作内容严格保密。";
        String md = PdfEditService.linesToMarkdown(pageText);
        assertTrue(md.contains("协商一致后订立"), "硬换行应合并");
        assertTrue(md.contains("第二条 保密义务"), "短行独立成段");
        assertTrue(md.contains("1\\."), "行首编号必须转义，防止 flexmark 重排编号");
    }
}
