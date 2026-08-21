package com.checkba.service.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PDF 编辑服务（PDFBox 3.x 层）
 *
 * 面向文本型、未加密 PDF 的四类操作 + 转 Word 提取：
 * - 高亮 / 便签批注：标准 PDF annotation，前端 Chromium 原生 PDF 引擎可直接渲染；
 * - 脱敏：黑框覆盖 + 仅受影响页光栅化重建（真删该页文字层，其余页保持可选中文本）；
 * - 短文本替换：白底覆盖 + 原位覆写（CJK 字体运行时解析、子集嵌入）；
 * - 转 Word 提取：逐页取文本 + 硬换行合并启发式，输出 markdown 交由 docx 导出。
 *
 * 定位一律"引用原文文本"而非坐标（拟人式原语口径）；坐标换算沿用
 * SensitiveService 已验证的 TextPosition 口径：x=getXDirAdj()，
 * 基线 y=pageHeight-getYDirAdj()（bottom-up），h=getHeightDir()，w=getWidthDirAdj()。
 *
 * 边界（工具描述需如实转述）：
 * - 加密 PDF 直接报错；扫描件（无文本层）无法定位；
 * - 旋转页不支持定位类操作（DirAdj 坐标到 user space 的换算不成立）；
 * - 替换只覆盖显示层，底层旧文字仍可提取——需要彻底清除用脱敏。
 */
@Service
public class PdfEditService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PdfEditService.class);

    private static final int REDACT_RENDER_DPI = 150;

    /** 可选覆盖：短文本替换用的 CJK 字体路径（ttf/ttc），不配则按内置候选表探测 */
    @Value("${external.pdf-edit.cjk-font-path:}")
    private String configuredCjkFontPath;

    public static class PdfEditException extends RuntimeException {
        public PdfEditException(String message) { super(message); }
    }

    /** 一处文本匹配：页内若跨行则拆成多个行矩形（bottom-up user space：{x, yBaseline, w, h}） */
    static class TextMatch {
        final int pageIndex;
        final List<float[]> lineRects = new ArrayList<>();
        float fontSizePt;

        TextMatch(int pageIndex) { this.pageIndex = pageIndex; }
    }

    public static class RedactResult {
        public final int matchCount;
        public final List<Integer> rasterizedPages;
        /** 传入但在文档里一个匹配都没找到的文本（常见于 AI 猜错人名/证件号）；空列表 = 全部命中。 */
        public final List<String> missing;

        RedactResult(int matchCount, List<Integer> rasterizedPages, List<String> missing) {
            this.matchCount = matchCount;
            this.rasterizedPages = rasterizedPages;
            this.missing = missing;
        }
    }

    // ==================== 读取 ====================

    /**
     * 结构化概览：页数 + 每页文本（截断）。供 AI 引用原文做定位锚点。
     *
     * @param pageIndex 0 起页码；null 返回全部页
     * @param maxCharsPerPage 每页文本截断长度
     */
    public String inspect(Path pdfPath, Integer pageIndex, int maxCharsPerPage) {
        try (PDDocument doc = load(pdfPath)) {
            int pageCount = doc.getNumberOfPages();
            if (pageIndex != null && (pageIndex < 0 || pageIndex >= pageCount)) {
                throw new PdfEditException(String.format("页码越界 pageIndex=%d（共 %d 页，从 0 开始）", pageIndex, pageCount));
            }
            JSONObject out = new JSONObject();
            out.set("page_count", pageCount);
            JSONArray pages = new JSONArray();
            int from = pageIndex != null ? pageIndex : 0;
            int to = pageIndex != null ? pageIndex : pageCount - 1;
            for (int i = from; i <= to; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(doc).strip();
                JSONObject p = new JSONObject();
                p.set("page_index", i);
                p.set("char_count", text.length());
                p.set("has_text_layer", !text.isEmpty());
                p.set("rotation", doc.getPage(i).getRotation());
                if (text.length() > maxCharsPerPage) {
                    p.set("text", text.substring(0, maxCharsPerPage));
                    p.set("truncated", true);
                } else {
                    p.set("text", text);
                }
                pages.add(p);
            }
            out.set("pages", pages);
            return out.toString();
        } catch (IOException e) {
            throw new PdfEditException("读取 PDF 失败: " + e.getMessage());
        }
    }

    /**
     * 提取结果：正文 markdown，以及「这份 PDF 看起来是扫描件」的判断。
     *
     * <p>两者分开返回而不是用 null 表示扫描件：判成扫描件时上游会去走 OCR，
     * 而 OCR 可能失败（组件没装/服务没起）。手里同时留着已经提取到的文本层，
     * OCR 失败还能回退过去，不至于把一份本来能转的文档变成一句「请去装 MinerU」。
     */
    public record ExtractedText(String markdown, boolean looksScanned) {}

    /** 单页字符数低于它就按扫描件处理，见 {@link #extractMarkdown} 的判据说明。 */
    static final int MIN_CHARS_PER_PAGE = 100;

    /**
     * 全文提取为 markdown（转 Word 用）。
     *
     * <p>「是不是扫描件」的判据从「全文不足 20 字」改成**按页密度**：
     * 一份几十页的扫描件，每页盖一个 Bates 章或印一行页眉，全文轻松过 20 字，
     * 于是被判成文本件走结构化转换——产出的 Word 里只有那些章和页眉，
     * 正文（图像）一个字都没有，而用户看到的是「转换成功」。
     * 中文法律文书正文一页在 700-1500 字量级，只剩页眉页码的页在 10-40 字量级，
     * 100 字/页落在中间且偏向 OCR 一侧。
     *
     * <p>刻意偏向 OCR：判错方向的代价不对称——扫描件被当文本件是**内容静默丢光**，
     * 文本件被当扫描件最多是慢一点（OCR 照样读得出渲染后的字），
     * 而且 OCR 失败时上游还会回退到这里提取到的文本。
     */
    public ExtractedText extractMarkdown(Path pdfPath) {
        try (PDDocument doc = load(pdfPath)) {
            StringBuilder md = new StringBuilder();
            int totalChars = 0;
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(doc).strip();
                totalChars += text.length();
                if (text.isEmpty()) continue;
                if (md.length() > 0) md.append("\n\n");
                md.append(linesToMarkdown(text));
            }
            int pages = Math.max(1, doc.getNumberOfPages());
            boolean looksScanned = totalChars < 20 || totalChars < MIN_CHARS_PER_PAGE * pages;
            return new ExtractedText(md.toString(), looksScanned);
        } catch (IOException e) {
            throw new PdfEditException("读取 PDF 失败: " + e.getMessage());
        }
    }

    /**
     * 行序列 → markdown 段落：
     * - 上一行"足够长且未以句读收尾"视为硬换行，与下一行合并；
     * - 行首的 markdown 控制符转义，避免 flexmark 把"1. / # / -"当语法重排编号（法律文书红线）。
     */
    static String linesToMarkdown(String pageText) {
        String[] lines = pageText.split("\\r?\\n");
        List<String> paras = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                if (cur.length() > 0) { paras.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            if (cur.length() == 0) {
                cur.append(line);
            } else if (isHardWrapped(cur.toString())) {
                cur.append(line);
            } else {
                paras.add(cur.toString());
                cur.setLength(0);
                cur.append(line);
            }
        }
        if (cur.length() > 0) paras.add(cur.toString());
        StringBuilder md = new StringBuilder();
        for (String p : paras) {
            if (md.length() > 0) md.append("\n\n");
            md.append(escapeMarkdownLead(p));
        }
        return md.toString();
    }

    private static boolean isHardWrapped(String prev) {
        if (prev.length() < 20) return false;
        char last = prev.charAt(prev.length() - 1);
        return "。；：！？.!?;:”」』】）)".indexOf(last) < 0;
    }

    private static String escapeMarkdownLead(String para) {
        String p = para;
        if (p.matches("^\\d{1,3}\\.\\s?.*")) {
            int dot = p.indexOf('.');
            p = p.substring(0, dot) + "\\." + p.substring(dot + 1);
        } else if (p.startsWith("#") || p.startsWith("-") || p.startsWith("*") || p.startsWith(">") || p.startsWith("+")) {
            p = "\\" + p;
        }
        return p;
    }

    // ==================== 高亮 / 批注 ====================

    /**
     * 高亮所有匹配文本。
     *
     * @param colorHex 如 #FFFF00；null 默认黄色
     * @param note 可选：附着在高亮上的说明（预览器悬停/点开可见）
     * @return 高亮的匹配数
     */
    public int highlight(Path pdfPath, String text, Integer pageIndex, String colorHex, String note) {
        try (PDDocument doc = load(pdfPath)) {
            List<TextMatch> matches = locate(doc, text, pageIndex);
            if (matches.isEmpty()) {
                throw new PdfEditException("未找到文本: " + text + notFoundHint(pageIndex));
            }
            float[] rgb = parseColor(colorHex, new float[]{1f, 1f, 0f});
            for (TextMatch m : matches) {
                PDPage page = doc.getPage(m.pageIndex);
                PDAnnotationHighlight hl = new PDAnnotationHighlight();
                hl.setQuadPoints(toQuadPoints(m));
                hl.setRectangle(union(m));
                hl.setColor(new PDColor(rgb, PDDeviceRGB.INSTANCE));
                hl.setTitlePopup("AI WorkDeck");
                if (note != null && !note.isBlank()) {
                    hl.setContents(note);
                }
                // 生成外观流（/AP）：不依赖查看器自行绘制，任何 PDF 引擎都能显示
                hl.constructAppearances(doc);
                page.getAnnotations().add(hl);
            }
            doc.save(pdfPath.toFile());
            return matches.size();
        } catch (IOException e) {
            throw new PdfEditException("高亮失败: " + e.getMessage());
        }
    }

    /**
     * 在锚点文本处添加便签批注（第一处匹配）。
     *
     * @return 批注所在页（0 起）
     */
    public int addNote(Path pdfPath, String anchorText, String comment, Integer pageIndex) {
        try (PDDocument doc = load(pdfPath)) {
            List<TextMatch> matches = locate(doc, anchorText, pageIndex);
            if (matches.isEmpty()) {
                throw new PdfEditException("未找到锚点文本: " + anchorText + notFoundHint(pageIndex));
            }
            TextMatch m = matches.get(0);
            float[] last = m.lineRects.get(m.lineRects.size() - 1);
            PDAnnotationText note = new PDAnnotationText();
            note.setName(PDAnnotationText.NAME_COMMENT);
            note.setRectangle(new PDRectangle(last[0] + last[2] + 2, last[1], 18, 18));
            note.setContents(comment);
            note.setTitlePopup("AI WorkDeck");
            note.setColor(new PDColor(new float[]{1f, 0.8f, 0f}, PDDeviceRGB.INSTANCE));
            doc.getPage(m.pageIndex).getAnnotations().add(note);
            doc.save(pdfPath.toFile());
            return m.pageIndex;
        } catch (IOException e) {
            throw new PdfEditException("添加批注失败: " + e.getMessage());
        }
    }

    // ==================== 脱敏 ====================

    /**
     * 真脱敏：黑框覆盖所有匹配 → 受影响页光栅化重建（该页文字层被彻底移除，无法复制提取）。
     * 未涉及的页保持原样（文字仍可选中复制）。
     */
    public RedactResult redact(Path pdfPath, List<String> texts, Integer pageIndex) {
        try (PDDocument doc = load(pdfPath)) {
            List<TextMatch> all = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String t : texts) {
                List<TextMatch> ms = locate(doc, t, pageIndex);
                if (ms.isEmpty()) missing.add(t);
                all.addAll(ms);
            }
            if (all.isEmpty()) {
                throw new PdfEditException("所有目标文本均未找到: " + missing + notFoundHint(pageIndex));
            }
            if (!missing.isEmpty()) {
                log.warn("Redact: some texts not found: {}", missing);
            }

            // 1. 画黑框（带边距，确保盖满字形上下缘）
            Set<Integer> affected = new LinkedHashSet<>();
            for (TextMatch m : all) {
                PDPage page = doc.getPage(m.pageIndex);
                affected.add(m.pageIndex);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setNonStrokingColor(0f, 0f, 0f);
                    for (float[] r : m.lineRects) {
                        cs.addRect(r[0] - 1, r[1] - r[3] * 0.3f, r[2] + 2, r[3] * 1.5f);
                    }
                    cs.fill();
                }
            }

            // 2. 受影响页光栅化重建：移除底层文字对象（否则黑框下的字仍可复制——脱敏形同虚设）。
            //    渲染时排除注释层：同页此前加的高亮/批注保持为活的 annotation（不烤进图片、
            //    不重影、popup 说明不丢），页面几何不变所以坐标依然成立。
            PDFRenderer renderer = new PDFRenderer(doc);
            renderer.setAnnotationsFilter(annotation -> false);
            for (int pi : affected) {
                PDPage page = doc.getPage(pi);
                BufferedImage image = renderer.renderImageWithDPI(pi, REDACT_RENDER_DPI, ImageType.RGB);
                PDImageXObject xImage = LosslessFactory.createFromImage(doc, image);
                float wPt = image.getWidth() * 72f / REDACT_RENDER_DPI;
                float hPt = image.getHeight() * 72f / REDACT_RENDER_DPI;
                // 渲染结果已应用页面旋转，重建后统一为无旋转页；几何变化时注释坐标失效，需清空
                boolean geometryChanged = page.getRotation() != 0;
                page.setMediaBox(new PDRectangle(wPt, hPt));
                page.setCropBox(new PDRectangle(wPt, hPt));
                page.setRotation(0);
                if (geometryChanged) {
                    page.setAnnotations(new ArrayList<>());
                }
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.OVERWRITE, true, true)) {
                    cs.drawImage(xImage, 0, 0, wPt, hPt);
                }
            }

            doc.save(pdfPath.toFile());
            List<Integer> pages = new ArrayList<>(affected);
            // 部分命中不算失败：上面这行 doc.save 已经把打码+光栅化后的字节不可逆地写回了
            // pdfPath——磁盘已经变了。此前这里在写盘之后才抛异常，调用方 PdfTools.pdf_redact
            // 的 finishModification（轮换 wpsFileId、更新 fileSize/updatedAt、发 reload）
            // 被异常跳过，磁盘已改、DB 与预览还停在旧版本，两者从此不一致。
            // 缺失目标（常见于 AI 猜错人名/证件号）如实带回 missing 字段，让调用方据此照实
            // 报告，而不是把一次真实生效的脱敏说成一次失败。
            return new RedactResult(all.size(), pages, missing);
        } catch (IOException e) {
            throw new PdfEditException("脱敏失败: " + e.getMessage());
        }
    }

    // ==================== 短文本替换 ====================

    /**
     * 短文本原位替换：白底覆盖 + 按原字号覆写。
     * 限制：匹配必须在同一行内；只覆盖显示层，底层旧文字仍可提取。
     *
     * @return 替换的匹配数
     */
    public int replaceText(Path pdfPath, String find, String replace, Integer pageIndex) {
        boolean needsCjk = replace.chars().anyMatch(c -> c > 127);
        File fontFile = needsCjk ? resolveCjkFontFile() : null;
        if (needsCjk && fontFile == null) {
            throw new PdfEditException("替换文本含中文但未找到可用的 CJK 字体。请配置 external.pdf-edit.cjk-font-path 指向一个 ttf/ttc 字体文件。");
        }

        TrueTypeCollection ttc = null;
        try (PDDocument doc = load(pdfPath)) {
            List<TextMatch> matches = locate(doc, find, pageIndex);
            if (matches.isEmpty()) {
                throw new PdfEditException("未找到文本: " + find + notFoundHint(pageIndex));
            }
            for (TextMatch m : matches) {
                if (m.lineRects.size() > 1) {
                    throw new PdfEditException("匹配文本『" + find + "』跨行，无法原位替换。请改用更短的、不跨行的唯一文本（可先 pdf_inspect 确认原文分行位置）。");
                }
            }

            PDFont font;
            if (needsCjk) {
                if (fontFile.getName().toLowerCase().endsWith(".ttc")) {
                    ttc = new TrueTypeCollection(fontFile);
                    TrueTypeFont[] first = new TrueTypeFont[1];
                    ttc.processAllFonts(f -> { if (first[0] == null) first[0] = f; });
                    if (first[0] == null) throw new PdfEditException("字体集合为空: " + fontFile);
                    font = PDType0Font.load(doc, first[0], true);
                } else {
                    font = PDType0Font.load(doc, fontFile);
                }
            } else {
                font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            }

            for (TextMatch m : matches) {
                PDPage page = doc.getPage(m.pageIndex);
                float[] r = m.lineRects.get(0);
                float fontSize = m.fontSizePt > 0 ? m.fontSizePt : r[3] * 0.85f;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setNonStrokingColor(1f, 1f, 1f);
                    cs.addRect(r[0] - 1, r[1] - r[3] * 0.3f, r[2] + 2, r[3] * 1.5f);
                    cs.fill();
                    cs.setNonStrokingColor(0f, 0f, 0f);
                    cs.beginText();
                    cs.setFont(font, fontSize);
                    cs.newLineAtOffset(r[0], r[1]);
                    cs.showText(replace);
                    cs.endText();
                }
            }
            doc.save(pdfPath.toFile());
            return matches.size();
        } catch (IOException e) {
            throw new PdfEditException("替换失败: " + e.getMessage());
        } finally {
            if (ttc != null) {
                try { ttc.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ==================== 定位 ====================

    /**
     * 在文档中定位文本的所有匹配。匹配在"去换行"的页文本上进行（原文在 PDF 里
     * 因排版被硬换行拆开时仍能命中）；needle 里的换行同样忽略。
     */
    List<TextMatch> locate(PDDocument doc, String needle, Integer onlyPage) throws IOException {
        if (needle == null || needle.isBlank()) {
            throw new PdfEditException("定位文本不能为空");
        }
        String target = needle.replace("\r", "").replace("\n", "");
        int pageCount = doc.getNumberOfPages();
        if (onlyPage != null && (onlyPage < 0 || onlyPage >= pageCount)) {
            throw new PdfEditException(String.format("页码越界 pageIndex=%d（共 %d 页，从 0 开始）", onlyPage, pageCount));
        }
        int from = onlyPage != null ? onlyPage : 0;
        int to = onlyPage != null ? onlyPage : pageCount - 1;

        List<TextMatch> result = new ArrayList<>();
        for (int pi = from; pi <= to; pi++) {
            PageCollector collector = new PageCollector();
            collector.setSortByPosition(true);
            collector.setStartPage(pi + 1);
            collector.setEndPage(pi + 1);
            collector.getText(doc);

            // 去换行的字符序列（保留每字符对应的 TextPosition）
            StringBuilder condensed = new StringBuilder();
            List<TextPosition> condensedPos = new ArrayList<>();
            for (int i = 0; i < collector.chars.length(); i++) {
                char c = collector.chars.charAt(i);
                if (c == '\n') continue;
                condensed.append(c);
                condensedPos.add(collector.positions.get(i));
            }

            String pageText = condensed.toString();
            int idx = pageText.indexOf(target);
            boolean rotated = doc.getPage(pi).getRotation() != 0;
            while (idx >= 0) {
                if (rotated) {
                    throw new PdfEditException(String.format("第 %d 页是旋转页面（rotation=%d），暂不支持定位类操作", pi, doc.getPage(pi).getRotation()));
                }
                TextMatch m = buildMatch(pi, condensedPos.subList(idx, idx + target.length()));
                if (m != null) result.add(m);
                idx = pageText.indexOf(target, idx + 1);
            }
        }
        return result;
    }

    /** 把一段匹配的 TextPosition 按行分组并聚合为行矩形 */
    private TextMatch buildMatch(int pageIndex, List<TextPosition> positions) {
        List<TextPosition> valid = positions.stream().filter(p -> p != null).toList();
        if (valid.isEmpty()) return null;
        TextMatch m = new TextMatch(pageIndex);
        m.fontSizePt = valid.get(0).getFontSizeInPt();

        List<TextPosition> line = new ArrayList<>();
        float lineY = Float.NaN;
        for (TextPosition tp : valid) {
            float y = tp.getYDirAdj();
            if (!line.isEmpty() && Math.abs(y - lineY) > tp.getHeightDir() * 0.7f + 1f) {
                m.lineRects.add(lineRect(line));
                line.clear();
            }
            if (line.isEmpty()) lineY = y;
            line.add(tp);
        }
        if (!line.isEmpty()) m.lineRects.add(lineRect(line));
        return m;
    }

    /** {x, yBaseline(bottom-up), w, h} —— 坐标口径与 SensitiveService 一致 */
    private float[] lineRect(List<TextPosition> line) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxH = 0;
        float pageHeight = line.get(0).getPageHeight();
        float baselineTopDown = line.get(0).getYDirAdj();
        for (TextPosition tp : line) {
            minX = Math.min(minX, tp.getXDirAdj());
            maxX = Math.max(maxX, tp.getXDirAdj() + tp.getWidthDirAdj());
            maxH = Math.max(maxH, tp.getHeightDir());
            baselineTopDown = Math.max(baselineTopDown, tp.getYDirAdj());
        }
        return new float[]{minX, pageHeight - baselineTopDown, maxX - minX, maxH};
    }

    private static class PageCollector extends PDFTextStripper {
        final StringBuilder chars = new StringBuilder();
        final List<TextPosition> positions = new ArrayList<>();

        PageCollector() throws IOException { super(); }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
            // 逐字符展开，保证 chars 与 positions 一一对应（一个 TextPosition 可能对应多字符）
            for (TextPosition tp : textPositions) {
                String u = tp.getUnicode();
                for (int i = 0; i < u.length(); i++) {
                    chars.append(u.charAt(i));
                    positions.add(tp);
                }
            }
        }

        @Override
        protected void writeLineSeparator() {
            chars.append('\n');
            positions.add(null);
        }

        @Override
        protected void writeWordSeparator() {
            // 词间空格不入序列：CJK 无空格，latin 匹配靠原文中真实空格字符
        }
    }

    // ==================== 辅助 ====================

    private PDDocument load(Path pdfPath) throws IOException {
        if (pdfPath == null || !Files.exists(pdfPath)) {
            throw new PdfEditException("文件不存在: " + pdfPath);
        }
        PDDocument doc = Loader.loadPDF(pdfPath.toFile());
        if (doc.isEncrypted()) {
            doc.close();
            throw new PdfEditException("该 PDF 已加密，无法操作。请先在其他工具中解除密码保护。");
        }
        return doc;
    }

    private String notFoundHint(Integer pageIndex) {
        return pageIndex != null
                ? String.format("（仅在第 %d 页查找。若不确定位置可不传页码全文查找，或先 pdf_inspect 核对原文）", pageIndex)
                : "（可先 pdf_inspect 核对原文的准确写法；若该 PDF 是扫描件则没有文本层，无法定位）";
    }

    private float[] toQuadPoints(TextMatch m) {
        float[] quads = new float[m.lineRects.size() * 8];
        int i = 0;
        for (float[] r : m.lineRects) {
            float x0 = r[0], y0 = r[1] - r[3] * 0.25f, x1 = r[0] + r[2], y1 = r[1] + r[3];
            quads[i++] = x0; quads[i++] = y1; // 左上
            quads[i++] = x1; quads[i++] = y1; // 右上
            quads[i++] = x0; quads[i++] = y0; // 左下
            quads[i++] = x1; quads[i++] = y0; // 右下
        }
        return quads;
    }

    private PDRectangle union(TextMatch m) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] r : m.lineRects) {
            minX = Math.min(minX, r[0]);
            minY = Math.min(minY, r[1] - r[3] * 0.25f);
            maxX = Math.max(maxX, r[0] + r[2]);
            maxY = Math.max(maxY, r[1] + r[3]);
        }
        PDRectangle rect = new PDRectangle();
        rect.setLowerLeftX(minX);
        rect.setLowerLeftY(minY);
        rect.setUpperRightX(maxX);
        rect.setUpperRightY(maxY);
        return rect;
    }

    static float[] parseColor(String hex, float[] fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (!h.matches("[0-9a-fA-F]{6}")) return fallback;
        return new float[]{
                Integer.parseInt(h.substring(0, 2), 16) / 255f,
                Integer.parseInt(h.substring(2, 4), 16) / 255f,
                Integer.parseInt(h.substring(4, 6), 16) / 255f,
        };
    }

    /**
     * CJK 字体探测：配置覆盖 → 仓内字体（dev 态）→ 操作系统字体。
     * 找不到返回 null（调用方给出配置指引）。
     */
    File resolveCjkFontFile() {
        List<String> candidates = new ArrayList<>();
        if (configuredCjkFontPath != null && !configuredCjkFontPath.isBlank()) {
            candidates.add(configuredCjkFontPath);
        }
        // dev 态：仓内现成字体（楷体 / Noto Sans SC）
        candidates.add(com.checkba.storage.ProjectStorageResolver
                .resolveConfiguredPath("frontend/dist/zetaoffice/cjk-kai.ttf").toString());
        candidates.add(com.checkba.storage.ProjectStorageResolver
                .resolveConfiguredPath("pptx-service/backend/fonts/NotoSansSC-Regular.ttf").toString());
        // macOS
        candidates.add("/System/Library/Fonts/STHeiti Light.ttc");
        candidates.add("/System/Library/Fonts/STHeiti Medium.ttc");
        candidates.add("/System/Library/Fonts/Supplemental/Songti.ttc");
        candidates.add("/Library/Fonts/Arial Unicode.ttf");
        // Windows
        candidates.add("C:\\Windows\\Fonts\\simkai.ttf");
        candidates.add("C:\\Windows\\Fonts\\simfang.ttf");
        candidates.add("C:\\Windows\\Fonts\\simhei.ttf");
        candidates.add("C:\\Windows\\Fonts\\msyh.ttc");
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) {
                log.info("PDF replace font resolved: {}", f);
                return f;
            }
        }
        log.warn("No CJK font found for PDF text replacement, candidates tried: {}", candidates.size());
        return null;
    }
}
