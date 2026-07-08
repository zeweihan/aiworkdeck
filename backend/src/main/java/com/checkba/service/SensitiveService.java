package com.checkba.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.checkba.model.SensitiveType;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

@Service
@Slf4j
public class SensitiveService {

    public String processFile(String filePath, List<String> strategies) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }

        String ext = FileUtil.extName(file).toLowerCase();
        String newFileName = "[已脱敏]" + file.getName();
        // Avoid overwriting if existing
        File newFile = FileUtil.file(file.getParent(), newFileName);
        int counter = 1;
        while (newFile.exists()) {
            newFile = FileUtil.file(file.getParent(), "[已脱敏]" + counter + "_" + file.getName());
            counter++;
        }

        log.info("Processing file: {} -> {}, Strategies: {}", file.getName(), newFile.getName(), strategies);

        if ("docx".equals(ext)) {
            processDocx(file, newFile, strategies);
        } else if ("pdf".equals(ext)) {
            processPdf(file, newFile, strategies);
        } else {
            // Default to Text
            processText(file, newFile, strategies);
        }

        return newFile.getAbsolutePath();
    }

    private void processText(File src, File dest, List<String> strategies) {
        String content = FileUtil.readString(src, StandardCharsets.UTF_8); // Assumption: UTF-8. 
        // Better: Detect encoding, but hutool's readString usually good enough or defaults.
        
        for (String strategy : strategies) {
            content = replaceSensitiveData(content, strategy);
        }
        
        FileUtil.writeString(content, dest, StandardCharsets.UTF_8);
    }

    private void processDocx(File src, File dest, List<String> strategies) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(src.toPath()))) {
            // 1. Paragraphs
            for (XWPFParagraph p : doc.getParagraphs()) {
                replaceInParagraph(p, strategies);
            }

            // 2. Tables
            for (XWPFTable tbl : doc.getTables()) {
                for (XWPFTableRow row : tbl.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph p : cell.getParagraphs()) {
                            replaceInParagraph(p, strategies);
                        }
                    }
                }
            }

            try (FileOutputStream out = new FileOutputStream(dest)) {
                doc.write(out);
            }
        }
    }

    private void replaceInParagraph(XWPFParagraph p, List<String> strategies) {
        List<XWPFRun> runs = p.getRuns();
        if (runs == null || runs.isEmpty()) return;

        // 拼接整段文本再匹配：敏感串常因排版被拆到多个 run（如加粗 "138" + 普通 "00001111"），
        // 逐 run 匹配会漏掉跨 run 的敏感数据。
        StringBuilder sb = new StringBuilder();
        for (XWPFRun r : runs) {
            String t = r.getText(0);
            if (t != null) sb.append(t);
        }
        String original = sb.toString();
        if (original.isEmpty()) return;

        String replaced = original;
        for (String strategy : strategies) {
            replaced = replaceSensitiveData(replaced, strategy);
        }

        if (!replaced.equals(original)) {
            // 命中敏感串：整段文本写回首个 run、清空其余 run。
            // 仅对"含敏感数据"的段落折叠排版（丢失非首 run 的格式），正确性优先于排版保留。
            runs.get(0).setText(replaced, 0);
            for (int i = 1; i < runs.size(); i++) {
                runs.get(i).setText("", 0);
            }
        }
    }
    
    private void processPdf(File src, File dest, List<String> strategies) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(src)) {
            // We need to find coordinates of sensitive text.
            // Using a custom PDFTextStripper to intercept writing.
            SensitiveTextStripper stripper = new SensitiveTextStripper(strategies);
            stripper.setSortByPosition(true);
            
            // 1. Scan doc to find redaction areas
            // Note: processing page by page to map coordinates to pages correctly.
            int totalPages = document.getNumberOfPages();
            
            // Collect all redactions first
            // Map<PageIndex, List<RedactionArea>>
            // Actually, stripper runs on range. We can reset it per page or run globally if we track page index.
            // PDFBox stripper is stateful. Best to process one page at a time.
            
            for (int i = 0; i < totalPages; i++) {
                int pageIndex = i + 1; // 1-based for stripper
                stripper.setStartPage(pageIndex);
                stripper.setEndPage(pageIndex);
                stripper.currentPdfPageIndex = i; 
                stripper.getText(document); // This triggers writeString logic
            }
            
            // 2. Draw black rectangles
            List<RedactionArea> areas = stripper.getRedactionAreas();
            for (RedactionArea area : areas) {
                PDPage page = document.getPage(area.pageIndex);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    contentStream.setNonStrokingColor(0f, 0f, 0f); // Black
                    // Adjust height? PDF coordinates are bottom-up usually, but TextPosition gives specific usage.
                    // TextPosition.getY() is usually from top-left in PDFTextStripper (yDirAdj). 
                    // Let's verify: In PDFTextStripper, 'y' is usually top-down. 
                    // But PDPageContentStream uses PDF user space (usually bottom-up).
                    // We need to flip Y. 
                    // PDRectangle mediaBox = page.getMediaBox();
                    // float y = mediaBox.getHeight() - area.y - area.height; // Logic depends on stripper config.
                    
                    // Actually, let's look at SensitiveTextStripper implementation below.
                    // It will store raw Y (top-down) from TextPosition? No, it stores TextPosition values.
                    
                    contentStream.addRect(area.x, area.y, area.width, area.height);
                    contentStream.fill();
                }
            }
            
            // 3. 真删文字层：把已画黑框的每一页栅格化为图片并重建 PDF，移除底层可提取的文字对象。
            //    此前仅在文字上叠加黑框，接收方复制/文本提取仍可还原原文——脱敏形同虚设。
            //    权衡：输出为图片型 PDF（体积更大、正文不可再选中），换取"敏感文字不可提取"的安全保证。
            PDFRenderer renderer = new PDFRenderer(document);
            try (PDDocument flattened = new PDDocument()) {
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    PDRectangle mediaBox = document.getPage(i).getMediaBox();
                    BufferedImage image = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
                    PDPage newPage = new PDPage(mediaBox);
                    flattened.addPage(newPage);
                    PDImageXObject xImage = LosslessFactory.createFromImage(flattened, image);
                    try (PDPageContentStream cs = new PDPageContentStream(flattened, newPage)) {
                        cs.drawImage(xImage, 0, 0, mediaBox.getWidth(), mediaBox.getHeight());
                    }
                }
                flattened.save(dest);
            }
        }
    }

    // Inner class for coordinate extraction
    private static class RedactionArea {
        int pageIndex;
        float x, y, width, height;

        public RedactionArea(int pageIndex, float x, float y, float width, float height) {
            this.pageIndex = pageIndex;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private class SensitiveTextStripper extends PDFTextStripper {
        private final List<String> strategies;
        private final List<RedactionArea> redactionAreas = new ArrayList<>();
        public int currentPdfPageIndex = 0;

        public SensitiveTextStripper(List<String> strategies) throws IOException {
            super();
            this.strategies = strategies;
        }

        public List<RedactionArea> getRedactionAreas() {
            return redactionAreas;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            // Check if text matches any strategy
            // Problem: 'text' might be a fragment (e.g. "138", "0013"). Regex needs context.
            // PDFTextStripper aggregates lines usually. 
            // writeString is called with a "word" or "line". 
            // If we match strictly regex on 'text', it might match.
            
            // To handle "fragmented" text, standard stripper aggregates words. 
            // We'll simplisticly assume writeString gives us enough context or we match what we have.
            
            if (StrUtil.isEmpty(text)) return;
            
            for (String strategyCode : strategies) {
                SensitiveType type = SensitiveType.fromCode(strategyCode);
                if (type == null) continue;
                
                Matcher matcher = type.getPattern().matcher(text);
                while (matcher.find()) {
                    // Match found! We need to map this match back to TextPositions.
                    int start = matcher.start();
                    int end = matcher.end();
                    
                    // Calculate bounding box for the matched range
                    // textPositions should align with 'text' characters.
                     if (start < textPositions.size() && end <= textPositions.size()) {
                        float minX = Float.MAX_VALUE;
                        float maxY = -1; // Top-most Y (in PDF bottom-up, Top is max? No, in standard PDF, Bottom is 0)
                        // Wait, TextPosition getYDirAdj() is Top-Down (0 at top).
                        // PDPageContentStream uses Bottom-Up (0 at bottom).
                        // We need to convert.
                        
                        float minY_PDF = Float.MAX_VALUE;
                        float maxY_PDF = -1;
                        
                        for (int k = start; k < end; k++) {
                            TextPosition tp = textPositions.get(k);
                            
                            // X is standard
                            if (tp.getXDirAdj() < minX) minX = tp.getXDirAdj();
                            
                            // Y: tp.getYDirAdj() is from Top. 
                            // We need PDF Y (from bottom). 
                            // Fortunately, TextPosition also has getPageHeight() maybe? No.
                            // But tp.getY() is usually unadjusted? 
                            // actually TextPosition textY, pageHeight are available?
                            // Let's use getX() and getY() (unadjusted non-flipped) ??
                            // Usually getXDirAdj() and getYDirAdj() are best for "visual" extraction.
                            
                            // Let's assume we want to draw exactly where the text is.
                            // We can use the Matrix? 
                            // Simpler: Use TextPosition.getX() and getY() (but check coordinate system).
                            // 
                            // Correct approach for redaction usually involves:
                            // x = tp.getXDirAdj();
                            // y = tp.getPageHeight() - tp.getYDirAdj(); (Flip back to bottom-up)
                            // height = tp.getHeightDir();
                            // width = tp.getWidthDirAdj();
                            
                            float pHeight = tp.getPageHeight();
                            float pY = pHeight - tp.getYDirAdj(); // Bottom-up Y of the baseline approx
                            // Adjust for font height to cover top of letters
                            // The box should start at pY - descender? 
                            // Simplified: 
                            // Box Bottom Y = pHeight - tp.getYDirAdj() - (some descent);
                            // Box Top Y = Box Bottom Y + tp.getHeightDir();
                            
                            // Let's use the provided bounding box logic from common PDFbox examples:
                            // rect x = tp.getXDirAdj()
                            // rect y = tp.getPageHeight() - tp.getYDirAdj() - tp.getHeightDir(); (Assuming yDirAdj is baseline?)
                            // Actually getYDirAdj() is usually the "Baseline" Y from top.
                            
                            // Let's rely on getX(), getY(), getHeight(), getWidth().
                            // tp.getY() -> This is usually bottom-up Y of baseline? 
                            // No, PDFTextStripper normalizes to Top-Left 0,0.
                            
                            float x = tp.getXDirAdj();
                            float yTop = tp.getYDirAdj(); // Y from top
                            float h = tp.getHeightDir();
                            float w = tp.getWidthDirAdj();
                            
                            // Convert to bottom-up for ContentStream
                            float pageHeight = tp.getPageHeight();
                            float yBottom = pageHeight - yTop; // This is the baseline Y in bottom-up
                            
                            // We want to cover from yBottom (descender?) to yBottom + h.
                            // Actually 'h' from TextPosition is usually font height.
                            // The rectangle should specify bottom-left corner (x, y) and w, h.
                            // So: x = x, y = yBottom, w = w, h = h.
                            // Note: PDF 'y' usually refers to baseline. Text goes up and down.
                            // Let's add a small buffer?
                            
                            // Since we want to obliterate it, being slightly larger is better.
                            // y = yBottom - 2 (descent buffer)
                            // h = h + 2 + 2 
                            
                            float rectX = x;
                            float rectY = yBottom; 
                            float rectW = w;
                            float rectH = h;
                            
                            
                            if (rectY < minY_PDF) minY_PDF = rectY;
                            if (rectY + rectH > maxY_PDF) maxY_PDF = rectY + rectH;
                        }
                        
                         // Aggregate width for the whole word match
                        float wordWidth = 0;
                        for (int k = start; k < end; k++) {
                             wordWidth += textPositions.get(k).getWidthDirAdj();
                        }
                        
                        // We need a single rect for the whole match? 
                        // Or individual rects? A single rect is cleaner visually.
                        // Assuming horizontal text.
                        
                        redactionAreas.add(new RedactionArea(currentPdfPageIndex, minX, minY_PDF, wordWidth, maxY_PDF - minY_PDF));
                     }
                }
            }
        }
    }

    private String replaceSensitiveData(String content, String strategyCode) {
        if (StrUtil.isEmpty(content)) return content;
        
        SensitiveType type = SensitiveType.fromCode(strategyCode);
        if (type == null) {
            log.warn("Unknown sensitive type: {}", strategyCode);
            return content;
        }

        Matcher matcher = type.getPattern().matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            String masked = type.mask(original);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
