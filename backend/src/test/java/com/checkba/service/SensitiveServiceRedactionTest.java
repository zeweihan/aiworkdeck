package com.checkba.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFEndnote;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFFootnote;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定脱敏的安全性质：
 * - PDF：脱敏后为图片型 PDF，底层文字被移除，敏感号码不可再被提取（此前仅画黑框、可复制还原）。
 *        且黑框命中不能依赖敏感串恰好落在同一次 writeString 回调里——PDFBox 按行/按词把文字喂给
 *        stripper，被拆成多段的敏感串也必须命中（dev-board#74 审计 B2-1）。
 * - DOCX：敏感串即使被排版拆到多个 run 也会被脱敏（跨 run 匹配）；且页眉/页脚/脚注/尾注要与正文
 *         同等处理，不能只扫正文段落与表格（dev-board#74 审计 B2-2）。
 */
class SensitiveServiceRedactionTest {

    @Test
    void pdfRedactionRemovesExtractableText() throws Exception {
        File src = File.createTempFile("redact-src-", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Contact 13800001111 today");
                cs.endText();
            }
            doc.save(src);
        }

        String outPath = new SensitiveService().processFile(src.getAbsolutePath(), List.of("PHONE"));

        try (PDDocument out = Loader.loadPDF(new File(outPath))) {
            String text = new PDFTextStripper().getText(out);
            assertFalse(text.contains("13800001111"), "脱敏后敏感号码不应可被提取");
        }
    }

    @Test
    void docxRedactionCatchesCrossRunSensitiveData() throws Exception {
        File src = File.createTempFile("redact-src-", ".docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("138");        // run 1
            p.createRun().setText("00001111");   // run 2 → 手机号被拆到两个 run
            try (FileOutputStream out = new FileOutputStream(src)) {
                doc.write(out);
            }
        }

        String outPath = new SensitiveService().processFile(src.getAbsolutePath(), List.of("PHONE"));

        try (XWPFDocument out = new XWPFDocument(Files.newInputStream(new File(outPath).toPath()))) {
            String text = out.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining());
            assertFalse(text.contains("13800001111"), "跨 run 的手机号必须被脱敏");
        }
    }

    /**
     * PDFBox 的 PDFTextStripper 按"行/词"把文字喂给 writeString 回调（同一视觉行也可能因换行、
     * 分栏等原因被拆成多次回调）。若只在每次回调拿到的片段内做正则匹配，被拆开的敏感串永远凑不齐、
     * 永远命中不了——但栅格化步骤仍然无条件执行，输出看起来"已脱敏"，实则敏感信息以图像形式完好保留。
     * 用 computeRedactionAreas 直接断言命中区域列表，而不是提取输出文本：栅格化后底层文字必然被抹掉，
     * 靠"文本提取不到"是无法区分"真的脱敏了"和"压根没匹配上、只是文字层被删了"这两种情况的。
     */
    @Test
    void pdfRedactionCatchesPhoneNumberSplitAcrossFragments() throws Exception {
        SensitiveService service = new SensitiveService();

        // A：手机号一次性 showText 写完——基线场景，不应受本次改动影响。
        try (PDDocument wholeDoc = new PDDocument()) {
            PDPage page = new PDPage();
            wholeDoc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(wholeDoc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("13800001111");
                cs.endText();
            }
            List<SensitiveService.RedactionArea> areas = service.computeRedactionAreas(wholeDoc, List.of("PHONE"));
            assertFalse(areas.isEmpty(), "完整写入同一片段的手机号必须命中");
        }

        // B：手机号被拆成 "1380000" + "1111" 两段、分两行写入，PDFBox 会用两次独立的 writeString
        // 回调喂给 stripper（Y 坐标跳变，行内 overlap 判断会强制切行）。
        try (PDDocument splitDoc = new PDDocument()) {
            PDPage page = new PDPage();
            splitDoc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(splitDoc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("1380000");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 660); // 换行，Y 坐标明显跳变
                cs.showText("1111");
                cs.endText();
            }
            List<SensitiveService.RedactionArea> areas = service.computeRedactionAreas(splitDoc, List.of("PHONE"));
            assertFalse(areas.isEmpty(),
                    "被拆成多个 writeString 片段（跨行）的手机号也必须命中，不能因为单次回调只看到片段就漏判");

            // 跨行命中必须按行切成多个框。一个横跨两行的包围盒会把两行之间、左右两侧的
            // 全部内容一起涂黑——那是把无关正文毁掉，比漏盖更糟。
            assertTrue(areas.size() >= 2, "跨行命中要按行分段画框，实际只有 " + areas.size() + " 个区域");
            float lineGap = 700 - 660;
            for (SensitiveService.RedactionArea area : areas) {
                assertTrue(area.height < lineGap,
                        "单个黑框不该纵向跨越两行（高度 " + area.height + " >= 行距 " + lineGap + "）");
            }
        }
    }

    /**
     * processDocx 此前只遍历 doc.getParagraphs() 与 doc.getTables()，页眉/页脚/脚注/尾注原样保留。
     * 律所文书的抬头、落款、联系方式恰恰常见于页眉页脚——正文测不含手机号、只在页眉里放一个，
     * 复现"页眉完全没被扫描"的缺陷。
     */
    @Test
    void docxRedactionCoversHeader() throws Exception {
        File src = File.createTempFile("redact-src-", ".docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
            header.createParagraph().createRun().setText("联系电话:13800001111");
            doc.createParagraph().createRun().setText("正文不含手机号");
            try (FileOutputStream out = new FileOutputStream(src)) {
                doc.write(out);
            }
        }

        String outPath = new SensitiveService().processFile(src.getAbsolutePath(), List.of("PHONE"));

        try (XWPFDocument out = new XWPFDocument(Files.newInputStream(new File(outPath).toPath()))) {
            String headerText = out.getHeaderList().stream()
                    .map(XWPFHeader::getText)
                    .collect(Collectors.joining());
            assertTrue(headerText.contains("联系电话"), "页眉本身应该还在，只是号码要被脱敏（确认真的读到了页眉，不是空断言）");
            assertFalse(headerText.contains("13800001111"), "页眉里的手机号必须被脱敏");
        }
    }

    /**
     * 与页眉同一根因，一并覆盖页脚、脚注、尾注三处——避免"只补了页眉，脚注/尾注还是漏的"这种半吊子修复。
     */
    @Test
    void docxRedactionCoversFooterFootnoteAndEndnote() throws Exception {
        File src = File.createTempFile("redact-src-", ".docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
            footer.createParagraph().createRun().setText("footer:13800001112");

            XWPFFootnote footnote = doc.createFootnote();
            footnote.createParagraph().createRun().setText("footnote:13800001113");

            XWPFEndnote endnote = doc.createEndnote();
            endnote.createParagraph().createRun().setText("endnote:13800001114");

            doc.createParagraph().createRun().setText("正文不含手机号");
            try (FileOutputStream out = new FileOutputStream(src)) {
                doc.write(out);
            }
        }

        String outPath = new SensitiveService().processFile(src.getAbsolutePath(), List.of("PHONE"));

        try (XWPFDocument out = new XWPFDocument(Files.newInputStream(new File(outPath).toPath()))) {
            String footerText = out.getFooterList().stream()
                    .map(XWPFFooter::getText)
                    .collect(Collectors.joining());
            String footnoteText = out.getFootnotes().stream()
                    .map(f -> f.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining()))
                    .collect(Collectors.joining());
            String endnoteText = out.getEndnotes().stream()
                    .map(e -> e.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining()))
                    .collect(Collectors.joining());

            // 用 assertAll：三处互不短路，任何一处漏改都会在同一次运行里独立报出来，
            // 不会被排在前面的失败挡住看不见。
            assertAll(
                    () -> assertFalse(footerText.contains("13800001112"), "页脚里的手机号必须被脱敏"),
                    () -> assertFalse(footnoteText.contains("13800001113"), "脚注里的手机号必须被脱敏"),
                    () -> assertFalse(endnoteText.contains("13800001114"), "尾注里的手机号必须被脱敏")
            );
        }
    }
}
