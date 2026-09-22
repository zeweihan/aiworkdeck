// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
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
import org.apache.pdfbox.util.Matrix;
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

    /** 见 {@link #inspect(Path, Integer, int, int)}；offset 默认 0（从页首读）。 */
    public String inspect(Path pdfPath, Integer pageIndex, int maxCharsPerPage) {
        return inspect(pdfPath, pageIndex, maxCharsPerPage, 0);
    }

    /**
     * 结构化概览：页数 + 每页文本（截断）。供 AI 引用原文做定位锚点。
     *
     * <p><b>offset 续读</b>（dev-board#805，审计 B-13）：每页上限只有 3000 字符，
     * 而中文 A4 密排合同页单页两三千字很常见——原文落在上限之后的那一段，
     * 在没有 offset 之前<b>整个够不着</b>：工具描述又要求「改之前先核对原文的准确写法」，
     * 模型只能凭记忆写 find 串，然后命中 0 处。所以截断时回一个 {@code next_offset}，
     * 下一次带着它再读同一页。
     *
     * <p>{@code char_count} 始终是<b>整页</b>的字符数（不是本次返回的那一段），
     * 模型据它判断还剩多少、以及 offset 是不是给过头了。
     *
     * @param pageIndex 0 起页码；null 返回全部页
     * @param maxCharsPerPage 每页单次返回的文本上限
     * @param offset 该页正文的起始字符位置（0 起）；&gt;0 时必须指定 pageIndex
     */
    public String inspect(Path pdfPath, Integer pageIndex, int maxCharsPerPage, int offset) {
        if (offset < 0) {
            throw new PdfEditException("offset 不能为负数");
        }
        if (offset > 0 && pageIndex == null) {
            throw new PdfEditException("续读位点是按页算的：给 offset 时必须同时指定 pageIndex（0 起）。");
        }
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
                if (offset > 0) {
                    p.set("offset", offset);
                }
                int start = Math.min(offset, text.length());
                int end = Math.min(start + maxCharsPerPage, text.length());
                p.set("text", text.substring(start, end));
                if (end < text.length()) {
                    p.set("truncated", true);
                    p.set("next_offset", end);
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
                TrueTypeCollection[] holder = new TrueTypeCollection[1];
                font = loadEmbeddedCjkFont(doc, fontFile, holder);
                ttc = holder[0];
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

    // ==================== 页级操作：合并 / 提页 / 删页 / 旋转 / 编页码 ====================

    /*
     * dev-board#805（审计 A17 / B-12）。这一组与上面几个的根本区别是**不改原件**：
     * 每个方法都是 src -> target 两条路径，原件一个字节都不动。证据卷宗的组织操作
     * 一旦做成原位修改，用户丢的就是证据原件本身，而 PDF 没有修订痕迹、项目里也没有
     * 针对 PDF 的检查点——没有任何东西能把它捞回来。
     *
     * 「PDF 大范围修改不做原位编辑、统一引导 pdf_to_word」那条刻意设计仍然成立：
     * 页级组织不是改内容，两者不冲突。
     */

    /** 一次合并允许的最多份数：防一条模型指令把整个项目的 PDF 全读进内存。 */
    private static final int MAX_MERGE_SOURCES = 50;

    private static final float PAGE_NUMBER_FONT_SIZE = 9f;
    private static final float PAGE_NUMBER_BOTTOM_MARGIN = 28f;
    private static final float PAGE_NUMBER_SIDE_MARGIN = 42f;

    /** {@code {n}} 或 {@code {n:6}}（零填充到 6 位，贝茨编号要靠它才排得了序）。 */
    private static final java.util.regex.Pattern PAGE_NUMBER_TOKEN =
            java.util.regex.Pattern.compile("\\{n(?::(\\d{1,3}))?}");

    /** 零填充最多几位：贝茨编号实务上不超过 10 位左右，再长只是印出一串没意义的 0。 */
    private static final int MAX_PAGE_NUMBER_PAD = 12;

    /** 一段连续页码。{@code raw} 保留用户书写的原串（如 "1-3"、"8-"），用于给拆分产物命名。 */
    public record PageRange(String raw, List<Integer> pages) {}

    /**
     * 解析页码范围串，展平成<b>升序去重</b>的 0 基下标。
     *
     * <p>语法（1 基，与用户在阅读器里看到的页码一致）：{@code N}、{@code N-M}、{@code N-}
     * （到最后一页），逗号分隔，例如 {@code "1-3,5,8-"}。
     *
     * <p>越界 / 倒序 / 0 页 / 空串一律<b>报错</b>，不做就近夹取：夹取会静默产出一份
     * 看着正常、内容却少一页的卷宗，而工具返回的仍是「成功」。
     */
    public static List<Integer> parsePageRanges(String ranges, int pageCount) {
        Set<Integer> all = new java.util.TreeSet<>();
        for (PageRange seg : parsePageRangeSegments(ranges, pageCount)) {
            all.addAll(seg.pages());
        }
        return List.copyOf(all);
    }

    /** 同 {@link #parsePageRanges}，但保留逗号分段——{@code pdf_split} 一段产出一份文件。 */
    public static List<PageRange> parsePageRangeSegments(String ranges, int pageCount) {
        String normalized = ranges == null ? "" : ranges
                .replace('，', ',')
                .replace('、', ',')
                .replace('－', '-')
                .replace('–', '-')
                .replace('—', '-')
                .trim();
        List<PageRange> out = new ArrayList<>();
        for (String raw : normalized.split(",")) {
            String seg = raw.trim();
            if (seg.isEmpty()) continue;
            out.add(new PageRange(seg, parseOneSegment(seg, pageCount)));
        }
        if (out.isEmpty()) {
            throw new PdfEditException(rangeSyntaxError("页码范围不能为空"));
        }
        return out;
    }

    private static List<Integer> parseOneSegment(String seg, int pageCount) {
        int dash = seg.indexOf('-');
        String startText = dash < 0 ? seg : seg.substring(0, dash);
        String endText = dash < 0 ? seg : seg.substring(dash + 1);
        if (dash >= 0 && endText.indexOf('-') >= 0) {
            throw new PdfEditException(rangeSyntaxError("『" + seg + "』里有多个连字符"));
        }
        if (startText.isBlank()) {
            throw new PdfEditException(rangeSyntaxError("『" + seg + "』缺少起始页"));
        }
        int start = parseOneBasedPage(startText, seg, pageCount);
        int end = (dash >= 0 && endText.isBlank())
                ? pageCount
                : parseOneBasedPage(endText, seg, pageCount);
        if (start > end) {
            throw new PdfEditException(String.format(
                    "范围『%s』的起始页大于结束页。页码范围要从小到大写；提页不会改变页序，需要重排请另行说明。", seg));
        }
        List<Integer> pages = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            pages.add(i - 1);
        }
        return pages;
    }

    private static int parseOneBasedPage(String text, String seg, int pageCount) {
        int value;
        try {
            value = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new PdfEditException(rangeSyntaxError("『" + seg + "』不是有效的页码"));
        }
        if (value < 1) {
            throw new PdfEditException(String.format(
                    "页码从 1 开始（『%s』写成了 %d）。注意 pdf_inspect 的 pageIndex 是 0 基，页码范围是 1 基。",
                    seg, value));
        }
        if (value > pageCount) {
            throw new PdfEditException(String.format(
                    "页码 %d 越界：该 PDF 共 %d 页（页码范围用 1 基页码）。", value, pageCount));
        }
        return value;
    }

    private static String rangeSyntaxError(String why) {
        return why + "。页码范围写法：\"1-3,5,8-\"（1 基页码，逗号分隔，N- 表示到最后一页）。";
    }

    /** 这份 PDF 有多少页（顺带做加密件校验）。 */
    public int pageCount(Path pdfPath) {
        try (PDDocument doc = load(pdfPath)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new PdfEditException("读取 PDF 失败: " + e.getMessage());
        }
    }

    /**
     * 按给定顺序把多份 PDF 接成一册，写到 {@code target}；原件不动。
     *
     * @return 合并后的总页数
     */
    public int merge(List<Path> sources, Path target) {
        if (sources == null || sources.size() < 2) {
            throw new PdfEditException("合并至少需要 2 份 PDF。");
        }
        if (sources.size() > MAX_MERGE_SOURCES) {
            throw new PdfEditException("一次最多合并 " + MAX_MERGE_SOURCES + " 份 PDF，本次给了 " + sources.size() + " 份。");
        }
        List<PDDocument> opened = new ArrayList<>();
        try (PDDocument dest = new PDDocument()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (Path source : sources) {
                PDDocument src = load(source);   // 加密件在这里就被挡住
                opened.add(src);
                merger.appendDocument(dest, src);
            }
            // 必须在源文档还开着的时候保存：appendDocument 之后 dest 里仍引用着源的对象树
            dest.save(target.toFile());
            return dest.getNumberOfPages();
        } catch (IOException e) {
            throw new PdfEditException("合并 PDF 失败: " + e.getMessage());
        } finally {
            for (PDDocument d : opened) {
                try {
                    d.close();
                } catch (IOException ignore) {
                    // 关闭失败不影响已经落盘的结果
                }
            }
        }
    }

    /**
     * 只保留指定页，写到 {@code target}；原件不动。
     *
     * <p>做法是「加载 → 删掉其余页 → 另存」，不是重新 importPage 组装：前者把注释、
     * 书签、表单域原样带过去，后者会在重建页对象时丢掉一部分。代价是<b>不支持重排</b>，
     * 产物永远保持原文档页序——这与工具名（提页）一致。
     *
     * @param pages 0 基页下标
     * @return 产物页数
     */
    public int extractPages(Path src, Path target, List<Integer> pages) {
        Set<Integer> keep = requirePages(pages);
        try (PDDocument doc = load(src)) {
            validateIndices(keep, doc.getNumberOfPages());
            for (int i = doc.getNumberOfPages() - 1; i >= 0; i--) {
                if (!keep.contains(i)) {
                    doc.removePage(i);
                }
            }
            doc.save(target.toFile());
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new PdfEditException("提取 PDF 页失败: " + e.getMessage());
        }
    }

    /**
     * 删掉指定页，写到 {@code target}；原件不动。
     *
     * @param pages 0 基页下标
     * @return 产物页数
     */
    public int deletePages(Path src, Path target, List<Integer> pages) {
        Set<Integer> drop = requirePages(pages);
        try (PDDocument doc = load(src)) {
            int total = doc.getNumberOfPages();
            validateIndices(drop, total);
            if (drop.size() >= total) {
                throw new PdfEditException("这会删掉全部 " + total + " 页，产物将是一份空 PDF。请改用更小的页码范围。");
            }
            for (int i = total - 1; i >= 0; i--) {
                if (drop.contains(i)) {
                    doc.removePage(i);
                }
            }
            doc.save(target.toFile());
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new PdfEditException("删除 PDF 页失败: " + e.getMessage());
        }
    }

    /**
     * 旋转指定页，写到 {@code target}；原件不动。
     *
     * <p>角度是<b>相对当前角度</b>叠加的，不是设成绝对值：用户说「把这几页转正」时
     * 心里想的是「再转 90 度」，而同一份扫描件里各页的当前角度常常不一样，
     * 设绝对值会把本来就正的页转歪。当前角度可从 {@code pdf_inspect} 的 rotation 字段读到。
     *
     * @param pages 0 基页下标
     * @param degrees 只接受 90 / 180 / 270（顺时针）
     * @return 实际旋转的页数
     */
    public int rotatePages(Path src, Path target, List<Integer> pages, int degrees) {
        if (degrees != 90 && degrees != 180 && degrees != 270) {
            throw new PdfEditException("旋转角度只支持 90 / 180 / 270（顺时针），收到 " + degrees + "。");
        }
        Set<Integer> targets = requirePages(pages);
        try (PDDocument doc = load(src)) {
            validateIndices(targets, doc.getNumberOfPages());
            for (int i : targets) {
                PDPage page = doc.getPage(i);
                page.setRotation(normalizeRotation(page.getRotation() + degrees));
            }
            doc.save(target.toFile());
            return targets.size();
        } catch (IOException e) {
            throw new PdfEditException("旋转 PDF 页失败: " + e.getMessage());
        }
    }

    /**
     * 逐页写入页码 / 贝茨编号，写到 {@code target}；原件不动。
     *
     * <p>模板里 {@code {n}} 是本页号、{@code {total}} 是总页数，{@code {n:6}} 是零填充到 6 位
     * （贝茨编号要靠它才排得了序）。含中文时走与短文本替换同一条 CJK 字体探测链。
     *
     * <p>页码跟着<b>页面的显示方向</b>走：/Rotate 非 0 的页（{@code pdf_rotate_pages} 之后
     * 就是这样）如果按未旋转的坐标画，页码会横着印在纸的侧边——而「先转正、再编页码」
     * 恰恰是这套工具最常见的连用方式。
     *
     * @param position {@code bottom-center} 或 {@code bottom-right}
     * @param startAt 第一页印的号
     * @return 实际写入页码的页数
     */
    public int addPageNumbers(Path src, Path target, String position, int startAt, String format) {
        String pos = position == null || position.isBlank() ? "bottom-center" : position.trim().toLowerCase();
        if (!pos.equals("bottom-center") && !pos.equals("bottom-right")) {
            throw new PdfEditException("页码位置只支持 bottom-center 或 bottom-right，收到『" + position + "』。");
        }
        if (format == null || format.isBlank()) {
            throw new PdfEditException("页码模板不能为空，例如 \"第 {n} 页 / 共 {total} 页\" 或贝茨编号 \"AWD{n:6}\"。");
        }
        if (!PAGE_NUMBER_TOKEN.matcher(format).find()) {
            throw new PdfEditException("页码模板『" + format + "』里没有 {n}，整册会印上同一个数。"
                    + "用 {n} 表示本页号、{total} 表示总页数，{n:6} 表示零填充到 6 位。");
        }
        if (startAt < 0) {
            throw new PdfEditException("起始页号不能为负数。");
        }

        TrueTypeCollection ttc = null;
        try (PDDocument doc = load(src)) {
            int total = doc.getNumberOfPages();
            List<String> labels = new ArrayList<>(total);
            boolean needsCjk = false;
            for (int i = 0; i < total; i++) {
                String label = renderPageNumber(format, startAt + i, total);
                labels.add(label);
                needsCjk |= label.chars().anyMatch(c -> c > 127);
            }

            PDFont font;
            if (needsCjk) {
                File fontFile = resolveCjkFontFile();
                if (fontFile == null) {
                    throw new PdfEditException("页码模板含中文但未找到可用的 CJK 字体。"
                            + "请改用纯英数模板（如 \"{n} / {total}\"），或配置 external.pdf-edit.cjk-font-path。");
                }
                TrueTypeCollection[] holder = new TrueTypeCollection[1];
                font = loadEmbeddedCjkFont(doc, fontFile, holder);
                ttc = holder[0];
            } else {
                font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            }

            for (int i = 0; i < total; i++) {
                stampPageNumber(doc, doc.getPage(i), labels.get(i), font, pos);
            }
            doc.save(target.toFile());
            return total;
        } catch (IOException e) {
            throw new PdfEditException("写入页码失败: " + e.getMessage());
        } finally {
            if (ttc != null) {
                try {
                    ttc.close();
                } catch (IOException ignore) {
                    // 字体集合关闭失败不影响已经落盘的结果
                }
            }
        }
    }

    /**
     * 把模板渲染成这一页真正印上去的字符串。
     *
     * <p>零填充位数<b>夹在 1..{@link #MAX_PAGE_NUMBER_PAD} 之间</b>：{@code {n:0}} 会让
     * {@code String.format("%00d")} 抛 DuplicateFormatFlagsException（模型看到的是一句
     * 「Flags = '0'」，无从下手），{@code {n:99}} 则会印出 99 位数字。两头都夹掉，
     * 结果就是「写了个没意义的位数 → 按最接近的合法位数印」，不炸也不出怪东西。
     */
    static String renderPageNumber(String format, int n, int total) {
        java.util.regex.Matcher m = PAGE_NUMBER_TOKEN.matcher(format);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String width = m.group(1);
            String value;
            if (width == null) {
                value = String.valueOf(n);
            } else {
                int pad = Math.max(1, Math.min(MAX_PAGE_NUMBER_PAD, Integer.parseInt(width)));
                value = String.format("%0" + pad + "d", n);
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString().replace("{total}", String.valueOf(total));
    }

    /**
     * 把 label 画到这一页<b>显示方向</b>的底部。
     *
     * <p>/Rotate 把内容顺时针转了 R 度再显示，所以要先把「显示坐标」换回 user space：
     * R=90 时 user(x,y) 显示在 (y, w-x)，反解得 x=w-dy, y=dx，文字本身再逆时针转 90 度
     * 才与显示的水平方向一致。另外三档同理。
     */
    private void stampPageNumber(PDDocument doc, PDPage page, String label, PDFont font, String position)
            throws IOException {
        PDRectangle box = page.getCropBox();
        float w = box.getWidth();
        float h = box.getHeight();
        int rotation = normalizeRotation(page.getRotation());
        float displayWidth = (rotation == 90 || rotation == 270) ? h : w;

        float textWidth = font.getStringWidth(label) / 1000f * PAGE_NUMBER_FONT_SIZE;
        float dx = position.equals("bottom-right")
                ? displayWidth - PAGE_NUMBER_SIDE_MARGIN - textWidth
                : (displayWidth - textWidth) / 2f;
        float dy = PAGE_NUMBER_BOTTOM_MARGIN;

        float x;
        float y;
        double angle;
        switch (rotation) {
            case 90 -> { x = w - dy; y = dx;     angle = 90; }
            case 180 -> { x = w - dx; y = h - dy; angle = 180; }
            case 270 -> { x = dy;     y = h - dx; angle = 270; }
            default -> { x = dx;      y = dy;     angle = 0; }
        }
        x += box.getLowerLeftX();
        y += box.getLowerLeftY();

        try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            cs.beginText();
            cs.setFont(font, PAGE_NUMBER_FONT_SIZE);
            cs.setNonStrokingColor(0f, 0f, 0f);
            cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(angle), x, y));
            cs.showText(label);
            cs.endText();
        }
    }

    private static int normalizeRotation(int rotation) {
        return ((rotation % 360) + 360) % 360;
    }

    private static Set<Integer> requirePages(List<Integer> pages) {
        if (pages == null || pages.isEmpty()) {
            throw new PdfEditException(rangeSyntaxError("没有指定任何页"));
        }
        return new java.util.TreeSet<>(pages);
    }

    private static void validateIndices(Set<Integer> pages, int pageCount) {
        for (int i : pages) {
            if (i < 0 || i >= pageCount) {
                throw new PdfEditException(String.format(
                        "页码越界：该 PDF 共 %d 页（页码范围用 1 基页码）。", pageCount));
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
     * 把探测到的字体文件子集嵌入 {@code doc}。
     *
     * <p>{@code .ttc} 字体集合必须在文档<b>保存之后</b>才能关闭（子集化是惰性的），
     * 所以这里不自己 close，而是把它交回给调用方的 finally——
     * {@code ttcOut[0]} 只在确实打开了集合时被写入。
     */
    private PDFont loadEmbeddedCjkFont(PDDocument doc, File fontFile, TrueTypeCollection[] ttcOut)
            throws IOException {
        if (!fontFile.getName().toLowerCase().endsWith(".ttc")) {
            return PDType0Font.load(doc, fontFile);
        }
        TrueTypeCollection collection = new TrueTypeCollection(fontFile);
        ttcOut[0] = collection;
        TrueTypeFont[] first = new TrueTypeFont[1];
        collection.processAllFonts(f -> {
            if (first[0] == null) first[0] = f;
        });
        if (first[0] == null) {
            throw new PdfEditException("字体集合为空: " + fontFile);
        }
        return PDType0Font.load(doc, first[0], true);
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
            if (!f.isFile()) continue;
            if (hasCffOutlines(f)) {
                // 「存在」不等于「能用」：PDFBox 只能子集嵌入 glyf 轮廓的 TrueType。
                // 仓内的 NotoSansSC-Regular.ttf 其实是 CFF 轮廓的 OpenType（扩展名骗人），
                // 早先这里只判 isFile()，于是在没有 LOWA 字体产物的机器上，带中文的
                // pdf_replace_text 会把 PDFBox 那句英文原文
                // 「True Type fonts using CFF outlines are not supported」直接甩给用户。
                log.debug("Skipping CFF-outline font (PDFBox cannot embed it): {}", f);
                continue;
            }
            log.info("PDF CJK font resolved: {}", f);
            return f;
        }
        log.warn("No embeddable CJK font found for PDF text output, candidates tried: {}", candidates.size());
        return null;
    }

    /** sfnt 版本标签为 {@code OTTO} 即 CFF 轮廓；读不出来一律当不可用，继续找下一个候选。 */
    private static boolean hasCffOutlines(File font) {
        try (java.io.InputStream in = new java.io.FileInputStream(font)) {
            byte[] tag = in.readNBytes(4);
            return tag.length < 4
                    || (tag[0] == 'O' && tag[1] == 'T' && tag[2] == 'T' && tag[3] == 'O');
        } catch (IOException e) {
            return true;
        }
    }
}
