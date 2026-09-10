// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
import com.checkba.service.sensitive.*;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.nio.file.Path;

@Service
@Slf4j
public class SensitiveService {

    public record Options(List<String> strategies, String mode, List<String> customTerms,
                          List<String> excludedTerms, String password) {
        public Options {
            strategies = strategies == null ? List.of() : List.copyOf(strategies);
            customTerms = cleanTerms(customTerms);
            excludedTerms = cleanTerms(excludedTerms);
            mode = mode == null ? "MASK" : mode;
            if (!Set.of("MASK", "TOKEN").contains(mode)) throw new IllegalArgumentException("未知脱敏模式");
            if (strategies.stream().anyMatch(s -> SensitiveType.fromCode(s) == null)) throw new IllegalArgumentException("未知脱敏策略");
            if (strategies.isEmpty() && customTerms.isEmpty()) throw new IllegalArgumentException("请选择策略或填写补充敏感词");
        }
        private static List<String> cleanTerms(List<String> terms) {
            if (terms == null) return List.of();
            if (terms.size() > 500) throw new IllegalArgumentException("词表最多500项");
            if (terms.stream().anyMatch(t -> t == null || t.length() > 200)) throw new IllegalArgumentException("单项词条最长200字符");
            return terms.stream().map(String::trim).filter(t -> !t.isEmpty()).distinct().toList();
        }
    }
    public record Result(String path, String recoveryKit, Map<String, Integer> counts, List<String> warnings) {}
    public record Preview(String text, Map<String, Integer> counts, List<String> warnings) {}
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "log");

    public String processFile(String filePath, List<String> strategies) throws Exception {
        return processFile(filePath, new Options(strategies, "MASK", List.of(), List.of(), null)).path();
    }

    private String validateSource(Path source, boolean reversible) throws IOException {
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("文件不存在或不是普通文件");
        if (Files.size(source) > 32 * 1024 * 1024) throw new IllegalArgumentException("文件超过32MB，请拆分处理");
        String ext = FileUtil.extName(source.toFile()).toLowerCase(Locale.ROOT);
        if (!TEXT_EXTENSIONS.contains(ext) && !Set.of("docx", "pdf").contains(ext)) {
            throw new IllegalArgumentException("仅支持 DOCX、文本型 PDF 和 UTF-8 文本（txt/md/csv/log）");
        }
        if (reversible && ext.equals("pdf")) throw new IllegalArgumentException("PDF 黑框脱敏不可复敏；请先使用文本或 DOCX 文件");
        return ext;
    }

    private List<String> warnings(String ext) {
        if (ext.equals("docx")) return List.of("处理正文、表格、页眉页脚、脚注尾注、批注文字及文本框；图片、嵌入附件、修订作者和文档属性未处理，请人工核查。字段更新可能重新带入内容。");
        if (ext.equals("pdf")) return List.of("PDF 采用不可逆黑框并移除文字层，输出不可选中文字；图片中的内容未识别，请逐页核查。");
        return List.of("规则可能漏识别或误识别；请检查预览，并补充简称、人名等敏感词。");
    }

    private SensitiveTextEngine engine(Options options, String text) {
        SensitiveTextEngine engine = new SensitiveTextEngine(options.strategies(), options.mode().equals("TOKEN"),
                options.customTerms(), options.excludedTerms());
        engine.learn(text);
        return engine;
    }

    public Preview previewFile(String filePath, Options options) throws Exception {
        Path source = Path.of(filePath);
        String ext = validateSource(source, options.mode().equals("TOKEN"));
        String text;
        if (ext.equals("docx")) text = String.join("\n", new SensitiveDocx(source).texts());
        else if (ext.equals("pdf")) {
            try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(source.toFile())) { text = pdfText(pdf); }
        } else text = Files.readString(source, StandardCharsets.UTF_8);
        SensitiveTextEngine engine = engine(options, text);
        String preview = engine.apply(text);
        var messages = new ArrayList<>(warnings(ext));
        if (engine.counts().isEmpty()) messages.add("未识别到敏感信息，不代表原文没有敏感信息。");
        if (preview.length() > 12000) messages.add("预览仅显示前12000字符，统计和实际处理覆盖全文。");
        return new Preview(preview.substring(0, Math.min(12000, preview.length())), engine.counts(), messages);
    }

    public Result processFile(String filePath, Options options) throws Exception {
        Path source = Path.of(filePath);
        boolean reversible = options.mode().equals("TOKEN");
        String ext = validateSource(source, reversible);
        if (reversible) SensitiveRecoveryKit.validatePassword(options.password());
        SensitiveDocx doc = ext.equals("docx") ? new SensitiveDocx(source) : null;
        String text = doc != null ? String.join("\n", doc.texts()) : ext.equals("pdf") ? "" : Files.readString(source, StandardCharsets.UTF_8);
        SensitiveTextEngine engine = engine(options, text);
        Path dest = outputPath(source, ext, "已脱敏");
        try {
            if (doc != null) { doc.transform(engine::edits); doc.write(dest); }
            else if (ext.equals("pdf")) processPdf(source.toFile(), dest.toFile(), engine);
            else Files.writeString(dest, engine.apply(text), StandardCharsets.UTF_8);
            String kit = reversible && !engine.recovery().isEmpty() ? SensitiveRecoveryKit.encrypt(engine.recovery(), options.password()) : "";
            var messages = new ArrayList<>(warnings(ext));
            if (engine.counts().isEmpty()) messages.add("未识别到敏感信息，本次没有替换任何内容。");
            return new Result(dest.toString(), kit, engine.counts(), messages);
        } catch (Exception e) { Files.deleteIfExists(dest); throw e; }
    }

    public Result restoreFile(String filePath, String recoveryKit, String password) throws Exception {
        Path source = Path.of(filePath);
        String ext = validateSource(source, true);
        Map<String, String> recovery = SensitiveRecoveryKit.decrypt(recoveryKit, password);
        SensitiveDocx doc = ext.equals("docx") ? new SensitiveDocx(source) : null;
        String text = doc == null ? Files.readString(source, StandardCharsets.UTF_8) : String.join("\n", doc.texts());
        int[] restored = {0}, unknown = {0};
        java.util.function.Function<String, List<SensitiveTextEngine.Edit>> transform = value -> {
            var edits = SensitiveTextEngine.restoreEdits(value, recovery);
            restored[0] += edits.size();
            var matcher = SensitiveTextEngine.TOKEN.matcher(value);
            while (matcher.find()) if (!recovery.containsKey(matcher.group())) unknown[0]++;
            return edits;
        };
        String result = null;
        if (doc == null) result = SensitiveTextEngine.apply(text, transform.apply(text));
        else doc.transform(transform);
        if (restored[0] == 0) throw new IllegalArgumentException("没有找到与该复敏文件匹配的编号；请核对文件，星号、黑框及被改写的编号无法还原");
        Path dest = outputPath(source, ext, "已复敏");
        try {
            if (doc != null) doc.write(dest); else Files.writeString(dest, result, StandardCharsets.UTF_8);
            var messages = new ArrayList<String>();
            messages.add("仅恢复原样保留的编号；已删除或被改写的编号无法还原。复敏后的文件含原始敏感信息。");
            if (unknown[0] > 0) messages.add("另有" + unknown[0] + "个编号不属于此复敏文件，已保留原样。");
            return new Result(dest.toString(), "", Map.of("RESTORED", restored[0]), messages);
        } catch (Exception e) { Files.deleteIfExists(dest); throw e; }
    }

    private Path outputPath(Path source, String ext, String prefix) throws IOException {
        // A neutral filename avoids leaking a company/person name from the source filename.
        return Files.createTempFile(source.toAbsolutePath().getParent(), "[" + prefix + "]-", "." + ext);
    }

    private String pdfText(PDDocument document) throws IOException {
        if (document.isEncrypted()) throw new IllegalArgumentException("请先解除 PDF 密码保护");
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            PDPage pdfPage = document.getPage(page - 1);
            PDRectangle crop = pdfPage.getCropBox(), media = pdfPage.getMediaBox();
            if (pdfPage.getRotation() != 0 || pdfPage.getUserUnit() != 1f
                    || crop.getLowerLeftX() != 0 || crop.getLowerLeftY() != 0
                    || media.getLowerLeftX() != 0 || media.getLowerLeftY() != 0
                    || crop.getWidth() != media.getWidth() || crop.getHeight() != media.getHeight()) {
                throw new IllegalArgumentException("PDF 第" + page + "页存在旋转或特殊页面坐标，请先规范页面或转为 DOCX/文本后处理");
            }
            stripper.setStartPage(page); stripper.setEndPage(page);
            String pageText = stripper.getText(document);
            if (pageText.isBlank()) throw new IllegalArgumentException("PDF 第" + page + "页无可识别文字；扫描页或空白页请先人工核查，不能直接自动脱敏");
            text.append(pageText).append('\n');
        }
        return text.toString();
    }

    private void processPdf(File src, File dest, SensitiveTextEngine engine) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(src)) {
            // 1. Scan doc to find redaction areas (page by page, so coordinates map to the right page)
            engine.learn(pdfText(document));
            List<RedactionArea> areas = computeRedactionAreas(document, engine);

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
        return computeRedactionAreas(document, new SensitiveTextEngine(strategies, false, List.of(), List.of()));
    }

    private List<RedactionArea> computeRedactionAreas(PDDocument document, SensitiveTextEngine engine) throws IOException {
        SensitiveTextStripper stripper = new SensitiveTextStripper(engine);
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
        private final SensitiveTextEngine engine;
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

        public SensitiveTextStripper(SensitiveTextEngine engine) throws IOException {
            super();
            this.engine = engine;
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
            for (var edit : engine.edits(text)) addRedactionArea(edit.start(), edit.end());
            pageText.setLength(0);
            pagePositions.clear();
        }

        private void addRedactionArea(int start, int end) {
            if (start < 0 || end > pagePositions.size() || start >= end) {
                log.warn("PDF脱敏：第{}页命中{}但匹配区间越界（start={}, end={}, size={}），跳过画框",
                        currentPdfPageIndex + 1, "敏感信息", start, end, pagePositions.size());
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
                            currentPdfPageIndex + 1, "敏感信息");
                    throw new IllegalArgumentException("PDF 文字坐标无法可靠定位，请改用文本或 DOCX 处理");
                }
                if (tp.getDir() != 0f) throw new IllegalArgumentException("PDF 命中旋转文字，无法可靠遮蔽，请转为 DOCX/文本后处理");
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
        SensitiveTextEngine engine = new SensitiveTextEngine(List.of(strategyCode), false, List.of(), List.of());
        engine.learn(content);
        return engine.apply(content);
    }
}
