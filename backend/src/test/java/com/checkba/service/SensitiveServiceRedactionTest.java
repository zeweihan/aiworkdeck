package com.checkba.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 锁定脱敏的安全性质：
 * - PDF：脱敏后为图片型 PDF，底层文字被移除，敏感号码不可再被提取（此前仅画黑框、可复制还原）。
 * - DOCX：敏感串即使被排版拆到多个 run 也会被脱敏（跨 run 匹配）。
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
}
