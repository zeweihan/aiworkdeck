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
            replaceInTables(doc.getTables(), strategies);

            // 3. 页眉 / 页脚：此前完全没扫描。律所文书的抬头、落款、联系方式恰恰常见于页眉页脚，
            //    是高频泄露点。页眉页脚自身也可能用表格排版（如三栏落款），一并覆盖。
            for (XWPFHeader header : doc.getHeaderList()) {
                for (XWPFParagraph p : header.getParagraphs()) {
                    replaceInParagraph(p, strategies);
                }
                replaceInTables(header.getTables(), strategies);
            }
            for (XWPFFooter footer : doc.getFooterList()) {
                for (XWPFParagraph p : footer.getParagraphs()) {
                    replaceInParagraph(p, strategies);
                }
                replaceInTables(footer.getTables(), strategies);
            }

            // 4. 脚注 / 尾注：同样此前完全没扫描。
            for (XWPFFootnote footnote : doc.getFootnotes()) {
                for (XWPFParagraph p : footnote.getParagraphs()) {
                    replaceInParagraph(p, strategies);
                }
                replaceInTables(footnote.getTables(), strategies);
            }
            for (XWPFEndnote endnote : doc.getEndnotes()) {
                for (XWPFParagraph p : endnote.getParagraphs()) {
                    replaceInParagraph(p, strategies);
                }
                replaceInTables(endnote.getTables(), strategies);
            }

            // 已知局限（未覆盖，如实注明）：文本框（w:txbxContent）里的文字不脱敏。
            // POI 的 org.apache.poi.xwpf.usermodel 对象模型没有暴露 Word 文本框类型
            // （只有 xslf/xssf 分别为 PPT/Excel 提供了 TextBox 类），paragraphs/tables 都取不到
            // 文本框内容，需要手写 w:txbxContent 的原始 XML 遍历才能做，本次不做，如果文本框里
            // 塞了敏感信息不会被脱敏，需要人工核查。

            try (FileOutputStream out = new FileOutputStream(dest)) {
                doc.write(out);
            }
        }
    }

    private void replaceInTables(List<XWPFTable> tables, List<String> strategies) {
        for (XWPFTable tbl : tables) {
            for (XWPFTableRow row : tbl.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph p : cell.getParagraphs()) {
                        replaceInParagraph(p, strategies);
                    }
                }
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
            // 1. Scan doc to find redaction areas (page by page, so coordinates map to the right page)
            List<RedactionArea> areas = computeRedactionAreas(document, strategies);

            // 2. Draw black rectangles
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

    /**
     * 扫描 PDF 全文，返回命中敏感策略的黑框区域列表；按页处理，保证坐标落在正确的页。
     * 包可见（而非 private）：测试需要直接断言命中了哪些区域——脱敏后文字层会被栅格化抹掉，
     * 无法再靠"提取输出文本"这种黑盒方式验证是否真的画上了黑框（详见 SensitiveTextStripper 内的取舍说明）。
     */
    List<RedactionArea> computeRedactionAreas(PDDocument document, List<String> strategies) throws IOException {
        SensitiveTextStripper stripper = new SensitiveTextStripper(strategies);
        stripper.setSortByPosition(true);
        int totalPages = document.getNumberOfPages();
        for (int i = 0; i < totalPages; i++) {
            int pageIndex = i + 1; // 1-based for stripper
            stripper.setStartPage(pageIndex);
            stripper.setEndPage(pageIndex);
            stripper.currentPdfPageIndex = i;
            stripper.getText(document); // This triggers writeString logic, accumulating into the page buffer
            stripper.finishPage(); // 整页文本收集完毕，统一匹配、求黑框，并清空缓冲避免跨页串号
        }
        return stripper.getRedactionAreas();
    }

    // Inner class for coordinate extraction
    static class RedactionArea {
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

        // 整页累积缓冲：PDFBox 按"词/行"把文字拆成多次 writeString 回调喂进来（同一视觉行也可能因换行、
        // 分栏等原因被拆成多次回调，注释里举的 "138"/"0013" 就是这种片段）。若只在单次回调内做正则匹配，
        // 被拆开的敏感串永远凑不齐、永远命中不了。这里不在 writeString 里匹配，只积累"整页文本 + 逐字符
        // 位置"，等一页收完后在 finishPage() 里对整页文本统一匹配，再把命中区间映射回 TextPosition 求黑框。
        /** 基线 Y 相差超过它就算换行了（单位与 TextPosition 一致，磅）。 */
        private static final float LINE_TOLERANCE = 2.0f;

        private final StringBuilder pageText = new StringBuilder();
        private final List<TextPosition> pagePositions = new ArrayList<>();

        public SensitiveTextStripper(List<String> strategies) throws IOException {
            super();
            this.strategies = strategies;
        }

        public List<RedactionArea> getRedactionAreas() {
            return redactionAreas;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (StrUtil.isEmpty(text)) return;

            if (text.length() != textPositions.size()) {
                // 极少数情况（连字/组合字符等）字符数与位置数对不上，没法为这个片段建立可靠的
                // 字符->坐标映射。不静默丢弃：打日志留痕，并用 null 占位保持下标对齐——匹配阶段一旦
                // 命中区间落在 null 占位上，就如实判定"这次命中没能画框"而不是瞎猜一个位置画错框。
                log.warn("PDF脱敏：第{}页出现字符数({})与位置数({})不一致的文本片段，该片段可能无法精确定位黑框",
                        currentPdfPageIndex + 1, text.length(), textPositions.size());
                pageText.append(text);
                for (int i = 0; i < text.length(); i++) {
                    pagePositions.add(null);
                }
                return;
            }

            pageText.append(text);
            pagePositions.addAll(textPositions);
        }

        /**
         * 一页文字全部收集完毕后调用：在整页文本上做正则匹配，把命中区间映射回 TextPosition 求黑框，
         * 然后清空缓冲，避免跨页串号。必须在每次 getText(document) 处理完一页后由调用方显式调用。
         */
        void finishPage() {
            String text = pageText.toString();
            for (String strategyCode : strategies) {
                SensitiveType type = SensitiveType.fromCode(strategyCode);
                if (type == null) continue;

                Matcher matcher = type.getPattern().matcher(text);
                while (matcher.find()) {
                    // 校验位过不去的（18 位立案号之类）不是这类信息，涂黑它就是把正文改坏
                    if (!type.isPlausible(matcher.group())) continue;
                    addRedactionArea(type, matcher.start(), matcher.end());
                }
            }
            pageText.setLength(0);
            pagePositions.clear();
        }

        private void addRedactionArea(SensitiveType type, int start, int end) {
            if (start < 0 || end > pagePositions.size() || start >= end) {
                log.warn("PDF脱敏：第{}页命中{}但匹配区间越界（start={}, end={}, size={}），跳过画框",
                        currentPdfPageIndex + 1, type.getLabel(), start, end, pagePositions.size());
                return;
            }

            // **按行分段画框**：命中区间可能跨行（整页累积匹配的代价），而一个横跨两行的
            // 包围盒会把两行之间、左右两侧的全部内容一起涂黑——那是把无关正文毁掉，
            // 比漏盖更糟。所以先按基线把命中字符切成若干段，每段各画一个框。
            List<Integer> segmentStarts = new ArrayList<>();
            List<Integer> segmentEnds = new ArrayList<>();
            int segStart = start;
            Float lineY = null;
            for (int k = start; k < end; k++) {
                TextPosition tp = pagePositions.get(k);
                if (tp == null) {
                    // 该字符缺少可靠坐标（字符数/位置数不一致的片段），不能猜一个位置去画框——
                    // 如实记为"这次命中没能画框"，避免出现"看起来已脱敏、实则未覆盖"的假阳性。
                    log.warn("PDF脱敏：第{}页命中{}但命中区间内存在无法映射坐标的字符，跳过画框（原文可能未被遮盖，请人工核查）",
                            currentPdfPageIndex + 1, type.getLabel());
                    return;
                }
                float y = tp.getYDirAdj();
                if (lineY == null) {
                    lineY = y;
                } else if (Math.abs(y - lineY) > LINE_TOLERANCE) {
                    segmentStarts.add(segStart);
                    segmentEnds.add(k);
                    segStart = k;
                    lineY = y;
                }
            }
            segmentStarts.add(segStart);
            segmentEnds.add(end);

            for (int s = 0; s < segmentStarts.size(); s++) {
                addSingleLineArea(segmentStarts.get(s), segmentEnds.get(s));
            }
        }

        /** 同一行内的一段命中字符 -> 一个黑框。 */
        private void addSingleLineArea(int start, int end) {
            // TextPosition.getYDirAdj() 是从页面顶部往下算的基线 Y，PDPageContentStream 画矩形要用
            // PDF 用户空间（从底部往上）的坐标，所以要用 pageHeight 翻转过来。
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int k = start; k < end; k++) {
                TextPosition tp = pagePositions.get(k);
                float x = tp.getXDirAdj();
                float w = tp.getWidthDirAdj();
                float yBottom = tp.getPageHeight() - tp.getYDirAdj(); // 翻转为 PDF 底部起算坐标
                float h = tp.getHeightDir();
                if (x < minX) minX = x;
                if (x + w > maxX) maxX = x + w;
                if (yBottom < minY) minY = yBottom;
                if (yBottom + h > maxY) maxY = yBottom + h;
            }
            // 用真实的包围盒（minX~maxX）而不是"逐字宽度求和"：同一行内命中区间可能跨越词间距，
            // 求和会得到与实际视觉跨度对不上的宽度，导致黑框覆盖不全。
            redactionAreas.add(new RedactionArea(currentPdfPageIndex, minX, minY, maxX - minX, maxY - minY));
        }
    }

    String replaceSensitiveData(String content, String strategyCode) {
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
            // 同上：校验位过不去的原样留下，法律文书必须逐字可引
            String masked = type.isPlausible(original) ? type.mask(original) : original;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
