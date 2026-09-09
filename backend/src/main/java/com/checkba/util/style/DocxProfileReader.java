// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.util.style;

import com.checkba.util.style.StyleProfile.Length;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.docx4j.XmlUtils;
import org.docx4j.dml.BaseStyles;
import org.docx4j.dml.TextFont;
import org.docx4j.model.structure.HeaderFooterPolicy;
import org.docx4j.model.structure.SectionWrapper;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.ThemePart;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart;
import org.docx4j.openpackaging.parts.WordprocessingML.StyleDefinitionsPart;
import org.docx4j.wml.*;

import javax.xml.bind.JAXBElement;
import java.io.InputStream;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * docx4j 直读 styles.xml / numbering.xml / document.xml / theme1.xml / sectPr / header&amp;footer，
 * 产出 styleProfile v1（schema 见样式盘点 §2）。
 *
 * <p>口径：
 * <ul>
 *   <li>样式链（docDefaults → basedOn 递归 → 本样式）给「声明值」；实例（正文里段落/run 的直接格式）
 *       按众数统计，占比 ≥ 1/2 时覆盖声明值并标 {@code source=instance}。</li>
 *   <li>{@code numbering.kind}：段落带 numPr → auto；文本以「一、」「（一）」「1.」开头 → literal；否则 none。</li>
 *   <li>表格边框三层（表格样式 / 表级 / 单元格级）不做合并，只判定「声明在哪一层」（{@code borders.source}）
 *       并按那一层取值。</li>
 *   <li>多份模板：逐叶子众数，{@code confidence} 记各块叶子的平均众数占比。</li>
 * </ul>
 */
public final class DocxProfileReader {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    private static final Pattern HEADING_STYLE_ID = Pattern.compile("^(?:Heading|heading|标题)\\s*(\\d)$");
    private static final Pattern HEADING_STYLE_NAME = Pattern.compile("^(?:heading|Heading|标题)\\s*(\\d)$");
    private static final Pattern LITERAL_CN_DUN = Pattern.compile("^([一二三四五六七八九十百]+)、");
    private static final Pattern LITERAL_CN_PAREN = Pattern.compile("^[（(]([一二三四五六七八九十百]+)[）)]");
    private static final Pattern LITERAL_DECIMAL_DOT = Pattern.compile("^(\\d+)[.、．]");
    private static final Pattern LITERAL_DECIMAL_PAREN = Pattern.compile("^[（(](\\d+)[）)]");
    private static final Pattern LITERAL_MULTI = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\.?\\s");

    private static final Pattern DRAWING_TAG = Pattern.compile("<(?:\\w+:)?(?:drawing|pict|anchor|inline|shape|imagedata)[\\s>/]");

    static final Pattern CELL_NUMBER = Pattern.compile("^[-+（(]?[0-9][0-9,.，%．]*[%）)]?$");
    static final Pattern CELL_DATE = Pattern.compile("^\\d{4}\\s*[年./-]\\s*\\d{1,2}\\s*([月./-]\\s*\\d{1,2}\\s*日?)?$");
    static final Pattern CELL_SERIAL = Pattern.compile("^(?:\\d{1,3}|[（(]\\d{1,3}[）)]|[①-⑳]|[一二三四五六七八九十]{1,3})$");

    private DocxProfileReader() {
    }

    /** 一份模板的来源描述；{@code fileId} 可为 null。 */
    public record Source(Long fileId, String name, InputStream in) {
    }

    /** 多份模板：逐叶子众数 + 置信度；一份时直接返回该份画像。 */
    public static StyleProfile read(List<Source> sources) throws Exception {
        if (sources == null || sources.isEmpty()) throw new IllegalArgumentException("没有模板可读");
        List<ObjectNode> profiles = new ArrayList<>();
        ArrayNode learnedFrom = J.arrayNode();
        for (Source s : sources) {
            WordprocessingMLPackage pkg = WordprocessingMLPackage.load(s.in());
            profiles.add(readOne(pkg).root());
            ObjectNode lf = J.objectNode();
            if (s.fileId() != null) lf.put("fileId", s.fileId());
            if (s.name() != null) lf.put("name", s.name());
            lf.put("kind", "docx");
            learnedFrom.add(lf);
        }
        ObjectNode merged = profiles.size() == 1 ? profiles.get(0) : vote(profiles);
        merged.set("learnedFrom", learnedFrom);
        merged.put("learnedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return StyleProfiles.of(merged);
    }

    public static StyleProfile read(InputStream in) throws Exception {
        return readOne(WordprocessingMLPackage.load(in));
    }

    /** 单元格内容分类：date / serial / number / text（写端按同一口径选对齐）。 */
    public static String classifyCell(String text) {
        return Ctx.classify(text == null ? "" : text);
    }

    public static StyleProfile readOne(WordprocessingMLPackage pkg) {
        Ctx ctx = new Ctx(pkg);
        ObjectNode root = J.objectNode();
        root.put("schemaVersion", 1);
        ObjectNode page = ctx.readPage();
        if (page != null) root.set("page", page);
        ObjectNode defaults = ctx.readDefaults();
        if (defaults != null) root.set("defaults", defaults);
        root.set("body", ctx.readBody());
        ArrayNode headings = ctx.readHeadings();
        if (headings.size() > 0) root.set("headings", headings);
        ObjectNode numbering = ctx.readNumberingBlock();
        if (numbering != null) root.set("numbering", numbering);
        ObjectNode table = ctx.readTables();
        if (table != null) root.set("table", table);
        ObjectNode hf = ctx.readHeaderFooter();
        if (hf != null) root.set("headerFooter", hf);
        ObjectNode toc = ctx.readToc();
        if (toc != null) root.set("toc", toc);
        if (!ctx.notes.isEmpty()) {
            ArrayNode notes = J.arrayNode();
            ctx.notes.forEach(notes::add);
            root.set("notes", notes);
        }
        return StyleProfiles.of(root);
    }

    // ================================================================== 多份投票

    static ObjectNode vote(List<ObjectNode> profiles) {
        List<Map<String, JsonNode>> flats = new ArrayList<>();
        for (ObjectNode p : profiles) {
            Map<String, JsonNode> flat = new LinkedHashMap<>();
            flatten("", p, flat);
            flats.add(flat);
        }
        Map<String, Vote<String>> votes = new LinkedHashMap<>();
        Map<String, JsonNode> byKey = new HashMap<>();
        for (Map<String, JsonNode> flat : flats) {
            for (Map.Entry<String, JsonNode> e : flat.entrySet()) {
                String key = e.getValue().toString();
                votes.computeIfAbsent(e.getKey(), k -> new Vote<>()).add(key);
                byKey.put(e.getKey() + "|" + key, e.getValue());
            }
        }
        ObjectNode out = J.objectNode();
        Map<String, List<Double>> shares = new LinkedHashMap<>();
        for (Map.Entry<String, Vote<String>> e : votes.entrySet()) {
            String path = e.getKey();
            if (path.equals("learnedFrom") || path.equals("learnedAt") || path.startsWith("learnedFrom.")) continue;
            String mode = e.getValue().mode();
            setPath(out, path, byKey.get(path + "|" + mode));
            String top = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
            shares.computeIfAbsent(top, k -> new ArrayList<>()).add(e.getValue().share(mode, profiles.size()));
        }
        ObjectNode confidence = J.objectNode();
        for (Map.Entry<String, List<Double>> e : shares.entrySet()) {
            double avg = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
            confidence.put(e.getKey(), Math.round(avg * 100.0) / 100.0);
        }
        out.set("confidence", confidence);
        return out;
    }

    private static void flatten(String prefix, JsonNode n, Map<String, JsonNode> out) {
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (n.isArray()) {
            // headings 按 level 对齐，其它数组整体当叶子
            if (prefix.equals("headings")) {
                for (JsonNode h : n) flatten(prefix + "." + h.path("level").asInt(), h, out);
            } else {
                out.put(prefix, n);
            }
        } else {
            out.put(prefix, n);
        }
    }

    private static void setPath(ObjectNode root, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String p = parts[i];
            if ("headings".equals(p)) {
                ArrayNode arr = cur.has("headings") ? (ArrayNode) cur.get("headings") : cur.putArray("headings");
                int level = Integer.parseInt(parts[++i]);
                ObjectNode hit = null;
                for (JsonNode h : arr) if (h.path("level").asInt() == level) hit = (ObjectNode) h;
                if (hit == null) { hit = arr.addObject(); hit.put("level", level); }
                cur = hit;
            } else {
                JsonNode next = cur.get(p);
                if (next == null || !next.isObject()) next = cur.putObject(p);
                cur = (ObjectNode) next;
            }
        }
        cur.set(parts[parts.length - 1], value.deepCopy());
    }

    static final class Vote<T> {
        private final Map<T, Integer> counts = new LinkedHashMap<>();
        private int total;

        void add(T v) {
            if (v == null) return;
            counts.merge(v, 1, Integer::sum);
            total++;
        }

        T mode() {
            T best = null;
            int bestN = -1;
            for (Map.Entry<T, Integer> e : counts.entrySet()) {
                if (e.getValue() > bestN) { best = e.getKey(); bestN = e.getValue(); }
            }
            return best;
        }

        double share(T v, int denominator) {
            Integer n = counts.get(v);
            return denominator <= 0 || n == null ? 0 : (double) n / denominator;
        }

        /** 众数在已统计样本中的占比（不计未声明的样本）。 */
        double modeShare() {
            T m = mode();
            return m == null ? 0 : share(m, total);
        }

        int total() {
            return total;
        }

        boolean isEmpty() {
            return total == 0;
        }
    }

    // ================================================================== 单份读取上下文

    private static final class Ctx {
        final WordprocessingMLPackage pkg;
        final MainDocumentPart mdp;
        final Map<String, Style> styles = new LinkedHashMap<>();
        final RPr docDefaultRPr;
        final PPr docDefaultPPr;
        final NumberingDefinitionsPart ndp;
        final String[] themeMinor = new String[2]; // [latin, eastAsia]
        final String[] themeMajor = new String[2];
        final List<String> notes = new ArrayList<>();
        /** level(1 基) → styleId，来自 outlineLvl 或样式名 */
        final Map<Integer, String> headingStyleByLevel = new LinkedHashMap<>();
        final Map<String, Integer> levelByStyleId = new HashMap<>();
        final List<P> bodyParas = new ArrayList<>();
        final Map<Integer, List<P>> headingParas = new LinkedHashMap<>();
        final List<Tbl> tables = new ArrayList<>();
        final List<P> allParas = new ArrayList<>();
        Integer headingAutoAbstractNumId;

        Ctx(WordprocessingMLPackage pkg) {
            this.pkg = pkg;
            this.mdp = pkg.getMainDocumentPart();
            StyleDefinitionsPart sdp = mdp.getStyleDefinitionsPart();
            RPr ddr = null;
            PPr ddp = null;
            if (sdp != null && sdp.getJaxbElement() != null) {
                Styles st = sdp.getJaxbElement();
                for (Style s : st.getStyle()) if (s.getStyleId() != null) styles.put(s.getStyleId(), s);
                if (st.getDocDefaults() != null) {
                    if (st.getDocDefaults().getRPrDefault() != null) ddr = st.getDocDefaults().getRPrDefault().getRPr();
                    if (st.getDocDefaults().getPPrDefault() != null) ddp = st.getDocDefaults().getPPrDefault().getPPr();
                }
            }
            docDefaultRPr = ddr;
            docDefaultPPr = ddp;
            ndp = mdp.getNumberingDefinitionsPart();
            readTheme();
            indexHeadingStyles();
            scanBody();
        }

        // ---------------------------------------------------------- 主题字体

        private void readTheme() {
            try {
                ThemePart tp = mdp.getThemePart();
                if (tp == null || tp.getJaxbElement() == null) return;
                BaseStyles.FontScheme fs = tp.getJaxbElement().getThemeElements().getFontScheme();
                if (fs == null) return;
                fillTheme(fs.getMinorFont(), themeMinor);
                fillTheme(fs.getMajorFont(), themeMajor);
            } catch (Exception ignore) {
                // 主题缺失或结构异常：退回不解析槽位名
            }
        }

        private static void fillTheme(org.docx4j.dml.FontCollection fc, String[] out) {
            if (fc == null) return;
            TextFont latin = fc.getLatin();
            if (latin != null && latin.getTypeface() != null && !latin.getTypeface().isEmpty()) out[0] = latin.getTypeface();
            TextFont ea = fc.getEa();
            if (ea != null && ea.getTypeface() != null && !ea.getTypeface().isEmpty()) out[1] = ea.getTypeface();
            if (out[1] == null && fc.getFont() != null) {
                for (org.docx4j.dml.FontCollection.Font f : fc.getFont()) {
                    if ("Hans".equalsIgnoreCase(f.getScript())) { out[1] = f.getTypeface(); break; }
                }
            }
        }

        private String themeFont(String slot) {
            if (slot == null) return null;
            String s = slot.toLowerCase();
            boolean major = s.startsWith("major");
            String[] set = major ? themeMajor : themeMinor;
            return s.endsWith("eastasia") ? set[1] : set[0];
        }

        // ---------------------------------------------------------- 样式索引与扫描

        private void indexHeadingStyles() {
            for (Style s : styles.values()) {
                if (!"paragraph".equals(s.getType())) continue;
                Integer level = null;
                PPr p = s.getPPr();
                if (p != null && p.getOutlineLvl() != null && p.getOutlineLvl().getVal() != null) {
                    level = p.getOutlineLvl().getVal().intValue() + 1;
                }
                if (level == null) {
                    Matcher m = HEADING_STYLE_ID.matcher(s.getStyleId());
                    if (m.find()) level = Integer.parseInt(m.group(1));
                }
                if (level == null && s.getName() != null && s.getName().getVal() != null) {
                    Matcher m = HEADING_STYLE_NAME.matcher(s.getName().getVal().trim());
                    if (m.find()) level = Integer.parseInt(m.group(1));
                }
                if (level == null || level < 1 || level > 9) continue;
                levelByStyleId.put(s.getStyleId(), level);
                // 同级多个样式时优先 Heading 名的那个
                String existing = headingStyleByLevel.get(level);
                if (existing == null || HEADING_STYLE_ID.matcher(s.getStyleId()).find()) {
                    headingStyleByLevel.put(level, s.getStyleId());
                }
            }
        }

        private void scanBody() {
            for (Object o : mdp.getContent()) {
                Object u = XmlUtils.unwrap(o);
                if (u instanceof P p) {
                    allParas.add(p);
                    String sid = p.getPPr() != null && p.getPPr().getPStyle() != null ? p.getPPr().getPStyle().getVal() : null;
                    Integer level = sid == null ? null : levelByStyleId.get(sid);
                    if (level == null && p.getPPr() != null && p.getPPr().getOutlineLvl() != null) {
                        level = p.getPPr().getOutlineLvl().getVal().intValue() + 1;
                    }
                    if (level != null) {
                        headingParas.computeIfAbsent(level, k -> new ArrayList<>()).add(p);
                    } else if (isBodyLike(sid) && !textOf(p).isBlank() && !containsField(p)) {
                        bodyParas.add(p);
                    }
                } else if (u instanceof Tbl t) {
                    tables.add(t);
                } else if (u instanceof SdtBlock sdt) {
                    // 目录之类的内容控件：只为 TOC 扫描保留段落
                    collectParas(sdt.getSdtContent() == null ? List.of() : sdt.getSdtContent().getContent(), allParas);
                }
            }
        }

        /** 图片可能裹在 mc:AlternateContent / VML 里，对象树走不到；按序列化后的 XML 判。 */
        private static boolean containsDrawing(Object node) {
            try {
                String xml = XmlUtils.marshaltoString(node, true, false);
                // 序列化时前缀可能被改写（ns3: 之类），只认本地名
                return DRAWING_TAG.matcher(xml).find();
            } catch (Exception e) {
                return false;
            }
        }

        private static void collectParas(List<Object> content, List<P> out) {
            for (Object o : content) {
                Object u = XmlUtils.unwrap(o);
                if (u instanceof P p) out.add(p);
                else if (u instanceof ContentAccessor ca) collectParas(ca.getContent(), out);
            }
        }

        private boolean isBodyLike(String styleId) {
            if (styleId == null) return true;
            String s = styleId.toLowerCase();
            if (s.startsWith("toc") || s.startsWith("title") || s.startsWith("caption") || s.startsWith("list")) return false;
            return s.equals("normal") || s.equals("bodytext") || s.contains("body") || s.equals("a") || s.startsWith("正文");
        }

        // ---------------------------------------------------------- 有效属性链

        private List<Style> chain(String styleId) {
            List<Style> out = new ArrayList<>();
            String cur = styleId;
            int guard = 0;
            while (cur != null && guard++ < 20) {
                Style s = styles.get(cur);
                if (s == null) break;
                out.add(0, s);
                cur = s.getBasedOn() == null ? null : s.getBasedOn().getVal();
            }
            return out;
        }

        /** 声明值叠加：docDefaults → basedOn 链 → 本样式；返回的 Para/Chars 可能含 null 叶子。 */
        private Para effectivePara(String styleId) {
            Para para = new Para();
            if (docDefaultPPr != null) para.overlay(docDefaultPPr);
            for (Style s : chain(styleId)) if (s.getPPr() != null) para.overlay(s.getPPr());
            return para;
        }

        private Chars effectiveChars(String styleId) {
            Chars ch = new Chars();
            if (docDefaultRPr != null) ch.overlay(docDefaultRPr);
            for (Style s : chain(styleId)) if (s.getRPr() != null) ch.overlay(s.getRPr());
            return ch;
        }

        private Chars resolveThemeSlots(Chars ch) {
            if (ch.eastAsia == null && ch.eastAsiaTheme != null) ch.eastAsia = themeFont(ch.eastAsiaTheme);
            if (ch.western == null && ch.westernTheme != null) ch.western = themeFont(ch.westernTheme);
            if (ch.eastAsia == null && ch.western == null && ch.eastAsiaTheme == null && ch.westernTheme == null) {
                // 整条链都没声明字体：Word 的隐含默认是主题 minor 字体
                ch.eastAsia = themeMinor[1];
                ch.western = themeMinor[0];
                if (ch.eastAsia != null) ch.eastAsiaTheme = "minorEastAsia";
                if (ch.western != null) ch.westernTheme = "minorHAnsi";
            }
            return ch;
        }

        // ---------------------------------------------------------- defaults / body / headings

        ObjectNode readDefaults() {
            if (docDefaultRPr == null && docDefaultPPr == null) return null;
            Chars ch = new Chars();
            if (docDefaultRPr != null) ch.overlay(docDefaultRPr);
            resolveThemeSlots(ch);
            ObjectNode o = J.objectNode();
            ObjectNode font = ch.fontNode();
            if (font != null) o.set("font", font);
            if (ch.sizeHalfPt != null) o.set("size", Length.of(ch.sizeHalfPt / 2.0, "pt").toNode());
            if (ch.color != null) o.put("color", "#" + ch.color);
            return o.size() == 0 ? null : o;
        }

        ObjectNode readBody() {
            String sid = styles.containsKey("Normal") ? "Normal" : defaultParagraphStyleId();
            ObjectNode o = paragraphBlock(sid, bodyParas, true);
            o.put("styleId", sid == null ? "Normal" : sid);
            // 表后首段段前：紧跟表格的第一段的 spacing.before 众数
            Vote<String> v = new Vote<>();
            Map<String, JsonNode> byKey = new HashMap<>();
            boolean prevTable = false;
            for (Object c : mdp.getContent()) {
                Object u = XmlUtils.unwrap(c);
                if (u instanceof Tbl) { prevTable = true; continue; }
                if (u instanceof P p) {
                    if (prevTable) {
                        Para para = new Para();
                        if (p.getPPr() != null) para.overlay(p.getPPr());
                        JsonNode before = para.spaceBeforeNode();
                        if (before != null) { v.add(before.toString()); byKey.put(before.toString(), before); }
                    }
                    prevTable = false;
                }
            }
            if (!v.isEmpty()) o.set("afterTableSpaceBefore", byKey.get(v.mode()));
            return o;
        }

        private String defaultParagraphStyleId() {
            for (Style s : styles.values()) {
                if ("paragraph".equals(s.getType()) && s.isDefault()) return s.getStyleId();
            }
            return null;
        }

        ArrayNode readHeadings() {
            ArrayNode arr = J.arrayNode();
            List<Integer> levels = new ArrayList<>(headingParas.keySet());
            if (levels.isEmpty()) {
                for (int l = 1; l <= 3; l++) if (headingStyleByLevel.containsKey(l)) levels.add(l);
            }
            levels.sort(Integer::compareTo);
            for (int level : levels) {
                List<P> paras = headingParas.getOrDefault(level, List.of());
                String sid = headingStyleByLevel.get(level);
                if (sid == null && !paras.isEmpty()) {
                    Vote<String> v = new Vote<>();
                    for (P p : paras) if (p.getPPr() != null && p.getPPr().getPStyle() != null) v.add(p.getPPr().getPStyle().getVal());
                    sid = v.mode();
                }
                ObjectNode h = paragraphBlock(sid, paras, false);
                h.put("level", level);
                if (sid != null) {
                    h.put("styleId", sid);
                    Style s = styles.get(sid);
                    if (s != null && s.getName() != null) h.put("styleName", s.getName().getVal());
                }
                Para eff = effectivePara(sid);
                if (eff.keepNext != null) h.put("keepWithNext", eff.keepNext);
                if (eff.pageBreakBefore != null) h.put("pageBreakBefore", eff.pageBreakBefore);
                h.set("numbering", headingNumbering(level, sid, paras, eff));
                // 字段顺序：level/styleId 放前面
                ObjectNode ordered = J.objectNode();
                ordered.set("level", h.remove("level"));
                if (h.has("styleId")) ordered.set("styleId", h.remove("styleId"));
                if (h.has("styleName")) ordered.set("styleName", h.remove("styleName"));
                ordered.setAll(h);
                arr.add(ordered);
            }
            return arr;
        }

        /**
         * 段落块：样式链声明值 + 实例众数（占比 ≥ 1/2 覆盖）。bodyMode 时缺省缩进/间距按 Word 隐含值补 0。
         */
        private ObjectNode paragraphBlock(String styleId, List<P> paras, boolean bodyMode) {
            Para para = effectivePara(styleId);
            Chars chars = resolveThemeSlots(effectiveChars(styleId));
            boolean fromInstance = false;

            if (!paras.isEmpty()) {
                Vote<String> jc = new Vote<>(), before = new Vote<>(), after = new Vote<>(), line = new Vote<>(),
                        first = new Vote<>(), left = new Vote<>();
                Vote<String> ea = new Vote<>(), west = new Vote<>(), sz = new Vote<>(), bold = new Vote<>(), color = new Vote<>();
                Map<String, JsonNode> nodes = new HashMap<>();
                for (P p : paras) {
                    Para ip = new Para();
                    if (p.getPPr() != null) ip.overlay(p.getPPr());
                    voteNode(jc, nodes, ip.alignmentNode());
                    voteNode(before, nodes, ip.spaceBeforeNode());
                    voteNode(after, nodes, ip.spaceAfterNode());
                    voteNode(line, nodes, ip.lineSpacingNode());
                    voteNode(first, nodes, ip.firstLineNode());
                    voteNode(left, nodes, ip.leftIndentNode());
                    Chars ic = runChars(p);
                    if (ic != null) {
                        if (ic.eastAsia != null) ea.add(ic.eastAsia);
                        if (ic.western != null) west.add(ic.western);
                        if (ic.sizeHalfPt != null) sz.add(String.valueOf(ic.sizeHalfPt));
                        if (ic.bold != null) bold.add(String.valueOf(ic.bold));
                        if (ic.color != null) color.add(ic.color);
                    }
                }
                int n = paras.size();
                if (override(jc, n)) { para.alignmentOverride = nodes.get(jc.mode()).asText(); fromInstance = true; }
                if (override(before, n)) { para.beforeOverride = nodes.get(before.mode()); fromInstance = true; }
                if (override(after, n)) { para.afterOverride = nodes.get(after.mode()); fromInstance = true; }
                if (override(line, n)) { para.lineOverride = nodes.get(line.mode()); fromInstance = true; }
                if (override(first, n)) { para.firstOverride = nodes.get(first.mode()); fromInstance = true; }
                if (override(left, n)) { para.leftOverride = nodes.get(left.mode()); fromInstance = true; }
                if (override(ea, n)) { chars.eastAsia = ea.mode(); chars.eastAsiaTheme = null; fromInstance = true; }
                if (override(west, n)) { chars.western = west.mode(); chars.westernTheme = null; fromInstance = true; }
                if (override(sz, n)) { chars.sizeHalfPt = Integer.parseInt(sz.mode()); fromInstance = true; }
                if (override(bold, n)) { chars.bold = Boolean.parseBoolean(bold.mode()); fromInstance = true; }
                if (override(color, n)) { chars.color = color.mode(); fromInstance = true; }
            }

            ObjectNode o = J.objectNode();
            o.put("source", fromInstance ? "instance" : "style");
            o.put("samples", paras.size());
            ObjectNode font = chars.fontNode();
            if (font != null) o.set("font", font);
            if (chars.sizeHalfPt != null) o.set("size", Length.of(chars.sizeHalfPt / 2.0, "pt").toNode());
            o.put("bold", Boolean.TRUE.equals(chars.bold));
            if (chars.italic != null && chars.italic) o.put("italic", true);
            if (chars.color != null) o.put("color", "#" + chars.color);
            o.put("alignment", para.alignment());
            o.set("lineSpacing", para.lineSpacing());
            o.set("spaceBefore", para.spaceBefore());
            o.set("spaceAfter", para.spaceAfter());
            o.set("firstLineIndent", para.firstLine());
            JsonNode hanging = para.hanging();
            if (hanging != null) o.set("hangingIndent", hanging);
            o.set("leftIndent", para.leftIndent());
            return o;
        }

        private static boolean override(Vote<String> v, int n) {
            return !v.isEmpty() && v.share(v.mode(), n) >= 0.5;
        }

        private static void voteNode(Vote<String> v, Map<String, JsonNode> nodes, JsonNode n) {
            if (n == null) return;
            String k = n.toString();
            v.add(k);
            nodes.put(k, n);
        }

        /** 段落内各 run 的直接格式众数（按 run 计）。 */
        private Chars runChars(P p) {
            Vote<String> ea = new Vote<>(), west = new Vote<>(), sz = new Vote<>(), bold = new Vote<>(), color = new Vote<>();
            int runs = 0;
            for (Object ro : p.getContent()) {
                Object ru = XmlUtils.unwrap(ro);
                if (ru instanceof org.docx4j.wml.CTSimpleField sf) {
                    for (Object x : sf.getContent()) { Object xu = XmlUtils.unwrap(x); if (xu instanceof R r) { runs++; voteRun(r, ea, west, sz, bold, color); } }
                    continue;
                }
                if (!(ru instanceof R r)) continue;
                if (textOfRun(r).isBlank()) continue;
                runs++;
                voteRun(r, ea, west, sz, bold, color);
            }
            if (runs == 0) return null;
            Chars c = new Chars();
            if (override(ea, runs)) c.eastAsia = ea.mode();
            if (override(west, runs)) c.western = west.mode();
            if (override(sz, runs)) c.sizeHalfPt = Integer.parseInt(sz.mode());
            // 加粗：run 级没写 b 视为 false（否则「样式粗、实例没写」与「实例显式不粗」分不开）
            c.bold = !bold.isEmpty() && override(bold, runs) ? Boolean.parseBoolean(bold.mode()) : null;
            if (override(color, runs)) c.color = color.mode();
            return c;
        }

        private void voteRun(R r, Vote<String> ea, Vote<String> west, Vote<String> sz, Vote<String> bold, Vote<String> color) {
            Chars c = new Chars();
            if (r.getRPr() != null) c.overlay(r.getRPr());
            if (c.eastAsia == null && c.eastAsiaTheme != null) c.eastAsia = themeFont(c.eastAsiaTheme);
            if (c.western == null && c.westernTheme != null) c.western = themeFont(c.westernTheme);
            if (c.eastAsia != null) ea.add(c.eastAsia);
            if (c.western != null) west.add(c.western);
            if (c.sizeHalfPt != null) sz.add(String.valueOf(c.sizeHalfPt));
            if (c.bold != null) bold.add(String.valueOf(c.bold));
            if (c.color != null) color.add(c.color);
        }

        // ---------------------------------------------------------- 编号

        private ObjectNode headingNumbering(int level, String styleId, List<P> paras, Para eff) {
            ObjectNode num = J.objectNode();
            // 1) 实例 numPr 众数
            Vote<String> numKey = new Vote<>();
            int literalHits = 0;
            Vote<String> literalKind = new Vote<>();
            for (P p : paras) {
                PPrBase.NumPr np = p.getPPr() == null ? null : p.getPPr().getNumPr();
                if (np != null && np.getNumId() != null && np.getNumId().getVal() != null && np.getNumId().getVal().signum() > 0) {
                    int ilvl = np.getIlvl() == null || np.getIlvl().getVal() == null ? level - 1 : np.getIlvl().getVal().intValue();
                    numKey.add(np.getNumId().getVal() + ":" + ilvl);
                } else {
                    String kind = literalKind(textOf(p).trim());
                    if (kind != null) { literalHits++; literalKind.add(kind); }
                }
            }
            String chosen = null;
            if (!numKey.isEmpty() && numKey.share(numKey.mode(), Math.max(1, paras.size())) >= 0.5) {
                chosen = numKey.mode();
            } else if (paras.isEmpty() || numKey.isEmpty()) {
                // 2) 样式链上的 numPr
                if (eff.numId != null && eff.numId.signum() > 0) {
                    chosen = eff.numId + ":" + (eff.ilvl == null ? level - 1 : eff.ilvl);
                }
            }
            if (chosen != null) {
                String[] parts = chosen.split(":");
                Lvl lvl = resolveLvl(new BigInteger(parts[0]), Integer.parseInt(parts[1]));
                num.put("kind", "auto");
                num.put("numId", Long.parseLong(parts[0]));
                num.put("ilvl", Integer.parseInt(parts[1]));
                if (lvl != null) {
                    fillLvl(num, lvl);
                    if (headingAutoAbstractNumId == null) headingAutoAbstractNumId = abstractNumIdOf(new BigInteger(parts[0]));
                }
                return num;
            }
            if (!paras.isEmpty() && literalHits * 2 >= paras.size()) {
                num.put("kind", "literal");
                String kind = literalKind.mode();
                switch (kind) {
                    case "cnDun" -> { num.put("numFmt", "chineseCounting"); num.put("lvlText", "%" + level + "、"); num.put("suffix", "nothing"); }
                    case "cnParen" -> { num.put("numFmt", "chineseCounting"); num.put("lvlText", "（%" + level + "）"); num.put("suffix", "nothing"); }
                    case "decDot" -> { num.put("numFmt", "decimal"); num.put("lvlText", "%" + level + "."); num.put("suffix", "space"); }
                    case "decParen" -> { num.put("numFmt", "decimal"); num.put("lvlText", "（%" + level + "）"); num.put("suffix", "nothing"); }
                    case "multi" -> { num.put("numFmt", "decimal"); num.put("lvlText", multiLvlText(level)); num.put("suffix", "space"); }
                    default -> { }
                }
                num.put("start", 1);
                notes.add(level + " 级标题编号是手打的字面文本（非自动编号），生成时按 literal 拼接");
                return num;
            }
            num.put("kind", "none");
            return num;
        }

        private static String multiLvlText(int level) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= level; i++) { if (i > 1) sb.append('.'); sb.append('%').append(i); }
            return sb.toString();
        }

        private static String literalKind(String text) {
            if (text.isEmpty()) return null;
            if (LITERAL_CN_DUN.matcher(text).find()) return "cnDun";
            if (LITERAL_CN_PAREN.matcher(text).find()) return "cnParen";
            if (LITERAL_MULTI.matcher(text).find()) return "multi";
            if (LITERAL_DECIMAL_DOT.matcher(text).find()) return "decDot";
            if (LITERAL_DECIMAL_PAREN.matcher(text).find()) return "decParen";
            return null;
        }

        private Integer abstractNumIdOf(BigInteger numId) {
            if (ndp == null || ndp.getJaxbElement() == null) return null;
            for (Numbering.Num n : ndp.getJaxbElement().getNum()) {
                if (numId.equals(n.getNumId()) && n.getAbstractNumId() != null) return n.getAbstractNumId().getVal().intValue();
            }
            return null;
        }

        private Lvl resolveLvl(BigInteger numId, int ilvl) {
            if (ndp == null || ndp.getJaxbElement() == null) return null;
            Numbering numbering = ndp.getJaxbElement();
            Numbering.Num num = null;
            for (Numbering.Num n : numbering.getNum()) if (numId.equals(n.getNumId())) { num = n; break; }
            if (num == null) return null;
            for (Numbering.Num.LvlOverride ov : num.getLvlOverride()) {
                if (ov.getIlvl() != null && ov.getIlvl().intValue() == ilvl && ov.getLvl() != null) return ov.getLvl();
            }
            if (num.getAbstractNumId() == null) return null;
            BigInteger absId = num.getAbstractNumId().getVal();
            for (Numbering.AbstractNum an : numbering.getAbstractNum()) {
                if (absId.equals(an.getAbstractNumId())) {
                    for (Lvl l : an.getLvl()) if (l.getIlvl() != null && l.getIlvl().intValue() == ilvl) return l;
                }
            }
            return null;
        }

        private static void fillLvl(ObjectNode num, Lvl lvl) {
            if (lvl.getNumFmt() != null && lvl.getNumFmt().getVal() != null) num.put("numFmt", lvl.getNumFmt().getVal().value());
            if (lvl.getLvlText() != null) num.put("lvlText", lvl.getLvlText().getVal());
            if (lvl.getStart() != null && lvl.getStart().getVal() != null) num.put("start", lvl.getStart().getVal().intValue());
            num.put("suffix", lvl.getSuff() == null || lvl.getSuff().getVal() == null ? "tab" : lvl.getSuff().getVal());
            if (lvl.getPPr() != null && lvl.getPPr().getInd() != null) {
                Para p = new Para();
                p.overlay(lvl.getPPr());
                ObjectNode ind = J.objectNode();
                ind.set("left", p.leftIndent());
                JsonNode hanging = p.hanging();
                if (hanging != null) ind.set("hanging", hanging);
                JsonNode first = p.firstLineNode();
                if (first != null) ind.set("firstLine", first);
                num.set("indent", ind);
            }
        }

        ObjectNode readNumberingBlock() {
            if (headingAutoAbstractNumId == null || ndp == null || ndp.getJaxbElement() == null) return null;
            for (Numbering.AbstractNum an : ndp.getJaxbElement().getAbstractNum()) {
                if (an.getAbstractNumId() == null || an.getAbstractNumId().intValue() != headingAutoAbstractNumId) continue;
                ObjectNode o = J.objectNode();
                o.put("abstractNumId", headingAutoAbstractNumId);
                o.put("multilevelLinked", an.getMultiLevelType() != null
                        && an.getMultiLevelType().getVal() != null
                        && an.getMultiLevelType().getVal().toLowerCase().contains("multi"));
                ArrayNode levels = J.arrayNode();
                for (Lvl l : an.getLvl()) {
                    ObjectNode ln = J.objectNode();
                    ln.put("ilvl", l.getIlvl() == null ? 0 : l.getIlvl().intValue());
                    fillLvl(ln, l);
                    if (l.getPStyle() != null) ln.put("pStyle", l.getPStyle().getVal());
                    levels.add(ln);
                }
                o.set("levels", levels);
                return o;
            }
            return null;
        }

        // ---------------------------------------------------------- 表格

        ObjectNode readTables() {
            if (tables.isEmpty()) return null;
            ObjectNode o = J.objectNode();
            o.put("source", "instance");
            o.put("samples", tables.size());

            Vote<String> styleId = new Vote<>(), bordersSource = new Vote<>(), alignment = new Vote<>(), layout = new Vote<>();
            Vote<String> outside = new Vote<>(), insideH = new Vote<>(), insideV = new Vote<>(), width = new Vote<>();
            Vote<String> hdrRepeat = new Vote<>(), hdrBold = new Vote<>(), hdrFill = new Vote<>(), hdrJc = new Vote<>(), hdrVAlign = new Vote<>(), hdrSz = new Vote<>(), hdrEa = new Vote<>(), hdrWest = new Vote<>();
            Vote<String> cellSz = new Vote<>(), cellEa = new Vote<>(), cellWest = new Vote<>(), cellLine = new Vote<>(), cellBefore = new Vote<>(), cellAfter = new Vote<>(), cellFirst = new Vote<>(), cellVAlign = new Vote<>();
            Map<String, Vote<String>> jcByType = new LinkedHashMap<>();
            Vote<String> zebraFill = new Vote<>();
            Map<String, JsonNode> nodes = new HashMap<>();
            List<List<Long>> gridSamples = new ArrayList<>();

            for (Tbl tbl : tables) {
                TblPr tblPr = tbl.getTblPr();
                String tsid = tblPr != null && tblPr.getTblStyle() != null ? tblPr.getTblStyle().getVal() : null;
                if (tsid != null) styleId.add(tsid);
                if (tblPr != null && tblPr.getJc() != null && tblPr.getJc().getVal() != null) alignment.add(DocxStyleWriter.alignmentName(tblPr.getJc().getVal()));
                if (tblPr != null && tblPr.getTblLayout() != null && tblPr.getTblLayout().getType() != null) layout.add(tblPr.getTblLayout().getType().value());
                if (tblPr != null && tblPr.getTblW() != null) {
                    TblWidth w = tblPr.getTblW();
                    if ("pct".equals(w.getType()) && w.getW() != null) voteNode(width, nodes, Length.of(w.getW().doubleValue() / 50.0, "percent").toNode());
                    else if ("dxa".equals(w.getType()) && w.getW() != null) voteNode(width, nodes, Units.twipsToPt(w.getW().longValue()).toNode());
                }
                // gridCol
                if (tbl.getTblGrid() != null && !tbl.getTblGrid().getGridCol().isEmpty()) {
                    List<Long> cols = new ArrayList<>();
                    for (TblGridCol gc : tbl.getTblGrid().getGridCol()) cols.add(gc.getW() == null ? 0L : gc.getW().longValue());
                    if (!gridSamples.contains(cols)) gridSamples.add(cols);
                }
                // 行列
                List<Tr> rows = new ArrayList<>();
                for (Object ro : tbl.getContent()) { Object ru = XmlUtils.unwrap(ro); if (ru instanceof Tr tr) rows.add(tr); }
                int cellCount = 0, cellBordered = 0;
                Tc topLeft = null;
                Tc secondRowFirst = null;
                for (int ri = 0; ri < rows.size(); ri++) {
                    List<Tc> cells = cellsOf(rows.get(ri));
                    for (int ci = 0; ci < cells.size(); ci++) {
                        Tc tc = cells.get(ci);
                        cellCount++;
                        if (tc.getTcPr() != null && tc.getTcPr().getTcBorders() != null && hasAnyBorder(tc.getTcPr().getTcBorders())) cellBordered++;
                        if (ri == 0 && ci == 0) topLeft = tc;
                        if (ri == 1 && ci == 0) secondRowFirst = tc;
                    }
                }
                boolean tblBorders = tblPr != null && tblPr.getTblBorders() != null && hasAnyBorder(tblPr.getTblBorders());
                String src;
                if (cellCount > 0 && cellBordered * 2 >= cellCount) src = "cell";
                else if (tblBorders) src = "table";
                else if (tsid != null && styles.get(tsid) != null && styles.get(tsid).getTblPr() != null && styles.get(tsid).getTblPr().getTblBorders() != null) src = "style";
                else src = "none";
                bordersSource.add(src);
                TblBorders tb = null;
                switch (src) {
                    case "table" -> tb = tblPr.getTblBorders();
                    case "style" -> tb = styles.get(tsid).getTblPr().getTblBorders();
                    default -> { }
                }
                if (tb != null) {
                    voteNode(outside, nodes, borderNode(tb.getTop()));
                    voteNode(insideH, nodes, borderNode(tb.getInsideH()));
                    voteNode(insideV, nodes, borderNode(tb.getInsideV()));
                } else if ("cell".equals(src) && topLeft != null && topLeft.getTcPr() != null && topLeft.getTcPr().getTcBorders() != null) {
                    TcPrInner.TcBorders cb = topLeft.getTcPr().getTcBorders();
                    voteNode(outside, nodes, borderNode(cb.getTop()));
                    JsonNode ih = borderNode(cb.getBottom());
                    if (ih == null && secondRowFirst != null && secondRowFirst.getTcPr() != null && secondRowFirst.getTcPr().getTcBorders() != null) ih = borderNode(secondRowFirst.getTcPr().getTcBorders().getTop());
                    voteNode(insideH, nodes, ih);
                    voteNode(insideV, nodes, borderNode(cb.getRight()));
                }
                // 表头（首行）
                if (!rows.isEmpty()) {
                    Tr first = rows.get(0);
                    boolean repeat = first.getTrPr() != null && first.getTrPr().getCnfStyleOrDivIdOrGridBefore().stream()
                            .anyMatch(x -> x.getName() != null && "tblHeader".equals(x.getName().getLocalPart()));
                    hdrRepeat.add(String.valueOf(repeat));
                    for (Tc tc : cellsOf(first)) {
                        CellFacts f = cellFacts(tc);
                        if (f.bold != null) hdrBold.add(String.valueOf(f.bold));
                        hdrFill.add(f.fill == null ? "none" : f.fill);
                        if (f.jc != null) hdrJc.add(f.jc);
                        if (f.vAlign != null) hdrVAlign.add(f.vAlign);
                        if (f.sizeHalfPt != null) hdrSz.add(String.valueOf(f.sizeHalfPt));
                        if (f.eastAsia != null) hdrEa.add(f.eastAsia);
                        if (f.western != null) hdrWest.add(f.western);
                    }
                }
                // 正文单元格
                for (int ri = 1; ri < rows.size(); ri++) {
                    for (Tc tc : cellsOf(rows.get(ri))) {
                        CellFacts f = cellFacts(tc);
                        if (f.sizeHalfPt != null) cellSz.add(String.valueOf(f.sizeHalfPt));
                        if (f.eastAsia != null) cellEa.add(f.eastAsia);
                        if (f.western != null) cellWest.add(f.western);
                        voteNode(cellLine, nodes, f.line);
                        voteNode(cellBefore, nodes, f.before);
                        voteNode(cellAfter, nodes, f.after);
                        voteNode(cellFirst, nodes, f.first);
                        if (f.vAlign != null) cellVAlign.add(f.vAlign);
                        if (f.jc != null && !f.text.isBlank()) jcByType.computeIfAbsent(classify(f.text), k -> new Vote<>()).add(f.jc);
                        zebraFill.add((ri % 2 == 1 ? "odd:" : "even:") + (f.fill == null ? "none" : f.fill));
                    }
                }
            }

            if (!styleId.isEmpty()) o.put("tableStyleId", styleId.mode());
            if (!width.isEmpty()) o.set("width", nodes.get(width.mode()));
            if (!alignment.isEmpty()) o.put("alignment", alignment.mode());
            if (!layout.isEmpty()) o.put("layout", layout.mode());
            ObjectNode borders = J.objectNode();
            borders.put("source", bordersSource.mode());
            if (!outside.isEmpty()) borders.set("outside", nodes.get(outside.mode()));
            if (!insideH.isEmpty()) borders.set("insideH", nodes.get(insideH.mode()));
            if (!insideV.isEmpty()) borders.set("insideV", nodes.get(insideV.mode()));
            o.set("borders", borders);

            ObjectNode header = J.objectNode();
            header.put("rows", 1);
            header.put("repeatOnEachPage", !hdrRepeat.isEmpty() && Boolean.parseBoolean(hdrRepeat.mode()));
            header.put("bold", !hdrBold.isEmpty() && Boolean.parseBoolean(hdrBold.mode()));
            if (!hdrJc.isEmpty()) header.put("alignment", hdrJc.mode());
            if (!hdrVAlign.isEmpty()) header.put("verticalAlign", hdrVAlign.mode());
            String fill = hdrFill.isEmpty() ? null : hdrFill.mode();
            if (fill == null || "none".equals(fill)) header.putNull("fill"); else header.put("fill", "#" + fill);
            ObjectNode hfont = fontNode(hdrEa, hdrWest);
            if (hfont != null) header.set("font", hfont);
            if (!hdrSz.isEmpty()) header.set("size", Length.of(Integer.parseInt(hdrSz.mode()) / 2.0, "pt").toNode());
            o.set("header", header);

            ObjectNode cell = J.objectNode();
            ObjectNode cfont = fontNode(cellEa, cellWest);
            if (cfont != null) cell.set("font", cfont);
            if (!cellSz.isEmpty()) cell.set("size", Length.of(Integer.parseInt(cellSz.mode()) / 2.0, "pt").toNode());
            if (!cellLine.isEmpty()) cell.set("lineSpacing", nodes.get(cellLine.mode()));
            if (!cellBefore.isEmpty()) cell.set("spaceBefore", nodes.get(cellBefore.mode()));
            if (!cellAfter.isEmpty()) cell.set("spaceAfter", nodes.get(cellAfter.mode()));
            if (!cellFirst.isEmpty()) cell.set("firstLineIndent", nodes.get(cellFirst.mode()));
            if (!cellVAlign.isEmpty()) cell.put("verticalAlign", cellVAlign.mode());
            ObjectNode byType = J.objectNode();
            for (String t : List.of("text", "number", "date", "serial")) {
                Vote<String> v = jcByType.get(t);
                if (v != null && !v.isEmpty()) byType.putObject(t).put("alignment", v.mode());
            }
            if (byType.size() > 0) cell.set("byContentType", byType);
            o.set("cell", cell);

            // 斑马纹：奇偶行底纹众数不同且至少一方有色
            String oddFill = modeWithPrefix(zebraFill, "odd:");
            String evenFill = modeWithPrefix(zebraFill, "even:");
            ObjectNode zebra = J.objectNode();
            boolean zebraOn = oddFill != null && evenFill != null && !oddFill.equals(evenFill)
                    && (!"none".equals(oddFill) || !"none".equals(evenFill));
            zebra.put("enabled", zebraOn);
            if (zebraOn) {
                if ("none".equals(oddFill)) zebra.putNull("oddFill"); else zebra.put("oddFill", "#" + oddFill);
                if ("none".equals(evenFill)) zebra.putNull("evenFill"); else zebra.put("evenFill", "#" + evenFill);
            }
            o.set("zebra", zebra);

            if (!gridSamples.isEmpty()) {
                ObjectNode cw = J.objectNode();
                cw.put("mode", "twips");
                ArrayNode samples = J.arrayNode();
                for (int i = 0; i < Math.min(5, gridSamples.size()); i++) {
                    ArrayNode s = J.arrayNode();
                    gridSamples.get(i).forEach(s::add);
                    samples.add(s);
                }
                cw.set("samples", samples);
                o.set("columnWidths", cw);
            }
            return o;
        }

        private static String modeWithPrefix(Vote<String> v, String prefix) {
            Vote<String> sub = new Vote<>();
            for (Map.Entry<String, Integer> e : v.counts.entrySet()) {
                if (e.getKey().startsWith(prefix)) for (int i = 0; i < e.getValue(); i++) sub.add(e.getKey().substring(prefix.length()));
            }
            return sub.mode();
        }

        private static ObjectNode fontNode(Vote<String> ea, Vote<String> west) {
            if (ea.isEmpty() && west.isEmpty()) return null;
            ObjectNode f = J.objectNode();
            if (!ea.isEmpty()) f.put("eastAsia", ea.mode());
            if (!west.isEmpty()) f.put("western", west.mode());
            return f;
        }

        private static List<Tc> cellsOf(Tr tr) {
            List<Tc> out = new ArrayList<>();
            for (Object o : tr.getContent()) { Object u = XmlUtils.unwrap(o); if (u instanceof Tc tc) out.add(tc); }
            return out;
        }

        private static boolean hasAnyBorder(TblBorders b) {
            return isDrawn(b.getTop()) || isDrawn(b.getBottom()) || isDrawn(b.getLeft()) || isDrawn(b.getRight()) || isDrawn(b.getInsideH()) || isDrawn(b.getInsideV());
        }

        private static boolean hasAnyBorder(TcPrInner.TcBorders b) {
            return isDrawn(b.getTop()) || isDrawn(b.getBottom()) || isDrawn(b.getLeft()) || isDrawn(b.getRight());
        }

        private static boolean isDrawn(CTBorder b) {
            return b != null && b.getVal() != null && b.getVal() != STBorder.NIL && b.getVal() != STBorder.NONE;
        }

        private static ObjectNode borderNode(CTBorder b) {
            if (!isDrawn(b)) return null;
            ObjectNode o = J.objectNode();
            o.put("style", b.getVal().value());
            if (b.getSz() != null) o.set("width", Length.of(b.getSz().doubleValue() / 8.0, "pt").toNode());
            String color = b.getColor();
            o.put("color", color == null || "auto".equalsIgnoreCase(color) ? "#000000" : "#" + color.toUpperCase());
            return o;
        }

        static String classify(String text) {
            String t = text.trim();
            if (CELL_DATE.matcher(t).matches()) return "date";
            if (CELL_SERIAL.matcher(t).matches()) return "serial";
            if (CELL_NUMBER.matcher(t).matches()) return "number";
            return "text";
        }

        private static final class CellFacts {
            String text = "";
            Boolean bold;
            String fill, jc, vAlign, eastAsia, western;
            Integer sizeHalfPt;
            JsonNode line, before, after, first;
        }

        private CellFacts cellFacts(Tc tc) {
            CellFacts f = new CellFacts();
            TcPr tcPr = tc.getTcPr();
            if (tcPr != null) {
                if (tcPr.getShd() != null && tcPr.getShd().getFill() != null && !"auto".equalsIgnoreCase(tcPr.getShd().getFill())) f.fill = tcPr.getShd().getFill().toUpperCase();
                if (tcPr.getVAlign() != null && tcPr.getVAlign().getVal() != null) f.vAlign = tcPr.getVAlign().getVal().value();
            }
            f.text = textOf(tc).trim();
            Vote<String> jc = new Vote<>(), ea = new Vote<>(), west = new Vote<>(), sz = new Vote<>(), bold = new Vote<>();
            Vote<String> line = new Vote<>(), before = new Vote<>(), after = new Vote<>(), first = new Vote<>();
            Map<String, JsonNode> nodes = new HashMap<>();
            int runs = 0;
            for (Object po : tc.getContent()) {
                Object pu = XmlUtils.unwrap(po);
                if (!(pu instanceof P p)) continue;
                Para para = new Para();
                if (p.getPPr() != null) para.overlay(p.getPPr());
                JsonNode a = para.alignmentNode();
                if (a != null) jc.add(a.asText());
                voteNode(line, nodes, para.lineSpacingNode());
                voteNode(before, nodes, para.spaceBeforeNode());
                voteNode(after, nodes, para.spaceAfterNode());
                voteNode(first, nodes, para.firstLineNode());
                for (Object ro : p.getContent()) {
                    Object ru = XmlUtils.unwrap(ro);
                    if (!(ru instanceof R r) || textOfRun(r).isBlank()) continue;
                    runs++;
                    voteRun(r, ea, west, sz, bold, new Vote<>());
                }
            }
            if (!jc.isEmpty()) f.jc = jc.mode();
            if (!ea.isEmpty()) f.eastAsia = ea.mode();
            if (!west.isEmpty()) f.western = west.mode();
            if (!sz.isEmpty()) f.sizeHalfPt = Integer.parseInt(sz.mode());
            if (runs > 0) f.bold = !bold.isEmpty() && Boolean.parseBoolean(bold.mode()) && bold.share(bold.mode(), runs) >= 0.5;
            if (!line.isEmpty()) f.line = nodes.get(line.mode());
            if (!before.isEmpty()) f.before = nodes.get(before.mode());
            if (!after.isEmpty()) f.after = nodes.get(after.mode());
            if (!first.isEmpty()) f.first = nodes.get(first.mode());
            return f;
        }

        // ---------------------------------------------------------- 页面 / 页眉页脚 / 目录

        private SectPr lastSectPr() {
            Body body = mdp.getJaxbElement() == null ? null : mdp.getJaxbElement().getBody();
            return body == null ? null : body.getSectPr();
        }

        ObjectNode readPage() {
            SectPr sp = lastSectPr();
            if (sp == null) return null;
            ObjectNode o = J.objectNode();
            if (sp.getPgSz() != null) {
                ObjectNode size = J.objectNode();
                if (sp.getPgSz().getW() != null) size.set("width", Units.twipsToMm(sp.getPgSz().getW().longValue()).toNode());
                if (sp.getPgSz().getH() != null) size.set("height", Units.twipsToMm(sp.getPgSz().getH().longValue()).toNode());
                size.put("orientation", sp.getPgSz().getOrient() != null && "landscape".equalsIgnoreCase(sp.getPgSz().getOrient().value()) ? "landscape" : "portrait");
                o.set("size", size);
            }
            if (sp.getPgMar() != null) {
                ObjectNode m = J.objectNode();
                if (sp.getPgMar().getTop() != null) m.set("top", Units.twipsToCm(sp.getPgMar().getTop().longValue()).toNode());
                if (sp.getPgMar().getBottom() != null) m.set("bottom", Units.twipsToCm(sp.getPgMar().getBottom().longValue()).toNode());
                if (sp.getPgMar().getLeft() != null) m.set("left", Units.twipsToCm(sp.getPgMar().getLeft().longValue()).toNode());
                if (sp.getPgMar().getRight() != null) m.set("right", Units.twipsToCm(sp.getPgMar().getRight().longValue()).toNode());
                if (sp.getPgMar().getHeader() != null) m.set("header", Units.twipsToCm(sp.getPgMar().getHeader().longValue()).toNode());
                if (sp.getPgMar().getFooter() != null) m.set("footer", Units.twipsToCm(sp.getPgMar().getFooter().longValue()).toNode());
                o.set("margins", m);
            }
            if (sp.getDocGrid() != null) {
                ObjectNode g = J.objectNode();
                g.put("type", sp.getDocGrid().getType() == null ? "default" : sp.getDocGrid().getType().value());
                if (sp.getDocGrid().getLinePitch() != null) g.set("linePitch", Units.twipsToPt(sp.getDocGrid().getLinePitch().longValue()).toNode());
                if (sp.getDocGrid().getCharSpace() != null) g.put("charSpace", sp.getDocGrid().getCharSpace().longValue());
                o.set("docGrid", g);
            }
            if (sp.getCols() != null && sp.getCols().getNum() != null && sp.getCols().getNum().intValue() > 1) o.put("columns", sp.getCols().getNum().intValue());
            return o.size() == 0 ? null : o;
        }

        ObjectNode readHeaderFooter() {
            List<SectionWrapper> sections;
            try {
                sections = pkg.getDocumentModel().getSections();
            } catch (Exception e) {
                return null;
            }
            if (sections == null || sections.isEmpty()) return null;
            // 封面/目录常单独成节且无页码：从最后一节往前找第一组真有页眉页脚的
            HeaderPart hp = null;
            FooterPart fp = null;
            for (int i = sections.size() - 1; i >= 0; i--) {
                HeaderFooterPolicy hfp = sections.get(i).getHeaderFooterPolicy();
                if (hfp == null) continue;
                if (hp == null && hfp.getDefaultHeader() != null) hp = hfp.getDefaultHeader();
                if (fp == null && hfp.getDefaultFooter() != null) fp = hfp.getDefaultFooter();
                if (hp != null && fp != null) break;
            }
            ObjectNode o = J.objectNode();
            o.set("header", hfBlock(hp == null ? null : hp.getJaxbElement(), false));
            o.set("footer", hfBlock(fp == null ? null : fp.getJaxbElement(), true));
            SectPr sp = lastSectPr();
            o.put("differentFirstPage", sp != null && sp.getTitlePg() != null && !Boolean.FALSE.equals(sp.getTitlePg().isVal()));
            boolean oddEven = false;
            try {
                var settings = mdp.getDocumentSettingsPart();
                oddEven = settings != null && settings.getJaxbElement() != null && settings.getJaxbElement().getEvenAndOddHeaders() != null
                        && !Boolean.FALSE.equals(settings.getJaxbElement().getEvenAndOddHeaders().isVal());
            } catch (Exception ignore) {
                // settings 缺失
            }
            o.put("differentOddEven", oddEven);
            boolean any = o.path("header").path("enabled").asBoolean() || o.path("footer").path("enabled").asBoolean();
            return any ? o : null;
        }

        private ObjectNode hfBlock(ContentAccessor part, boolean footer) {
            ObjectNode o = J.objectNode();
            if (part == null) { o.put("enabled", false); return o; }
            StringBuilder text = new StringBuilder();
            boolean[] hasPage = {false};
            String alignment = null;
            Chars chars = null;
            List<P> paras = new ArrayList<>();
            collectParas(part.getContent(), paras);
            for (P p : paras) {
                String t = textWithFields(p, hasPage).trim();
                if (t.isBlank()) continue;
                if (text.length() > 0) text.append('\n');
                text.append(t);
                if (alignment == null && p.getPPr() != null && p.getPPr().getJc() != null) alignment = DocxStyleWriter.alignmentName(p.getPPr().getJc().getVal());
                if (chars == null) chars = runChars(p);
            }
            boolean hasImage = containsDrawing(part);
            boolean enabled = text.length() > 0 || hasImage;
            o.put("enabled", enabled);
            if (hasImage) o.put("hasImage", true); // 律所 logo 之类：只能提示有图，读不出内容
            if (text.length() == 0) {
                if (footer) { ObjectNode pn = J.objectNode(); pn.put("enabled", false); o.set("pageNumber", pn); }
                return o;
            }
            if (alignment == null) {
                // 没直接写对齐的取样式链
                for (P p : paras) {
                    String sid = p.getPPr() != null && p.getPPr().getPStyle() != null ? p.getPPr().getPStyle().getVal() : null;
                    if (sid != null) { alignment = effectivePara(sid).alignment(); break; }
                }
            }
            if (footer) {
                ObjectNode pn = J.objectNode();
                pn.put("enabled", hasPage[0]);
                if (hasPage[0]) {
                    pn.put("pattern", text.toString());
                    if (alignment != null) pn.put("alignment", alignment);
                    SectPr sp = lastSectPr();
                    String fmt = sp != null && sp.getPgNumType() != null && sp.getPgNumType().getFmt() != null ? sp.getPgNumType().getFmt().value() : "decimal";
                    pn.put("format", fmt);
                    pn.put("start", sp != null && sp.getPgNumType() != null && sp.getPgNumType().getStart() != null ? sp.getPgNumType().getStart().intValue() : 1);
                    o.set("pageNumber", pn);
                    o.putNull("text");
                } else {
                    o.set("pageNumber", pn);
                    o.put("text", text.toString());
                }
            } else {
                o.put("text", text.toString());
                if (hasPage[0]) o.put("hasPageNumber", true);
            }
            if (alignment != null) o.put("alignment", alignment);
            if (chars != null) {
                ObjectNode font = chars.fontNode();
                if (font != null) o.set("font", font);
                if (chars.sizeHalfPt != null) o.set("size", Length.of(chars.sizeHalfPt / 2.0, "pt").toNode());
            }
            return o;
        }

        ObjectNode readToc() {
            for (P p : allParas) {
                String instr = fieldInstr(p);
                if (instr == null) continue;
                String trimmed = instr.trim();
                if (!trimmed.startsWith("TOC")) continue;
                ObjectNode o = J.objectNode();
                o.put("enabled", true);
                Matcher m = Pattern.compile("\\\\o\\s+\"(\\d+)-(\\d+)\"").matcher(trimmed);
                if (m.find()) o.put("levels", m.group(1) + "-" + m.group(2));
                o.put("hyperlinks", trimmed.contains("\\h"));
                o.put("instr", trimmed);
                // 目录标题：紧邻其前的非空段
                int idx = allParas.indexOf(p);
                for (int i = idx - 1; i >= 0 && i >= idx - 2; i--) {
                    String t = textOf(allParas.get(i)).trim();
                    if (!t.isEmpty()) { o.put("title", t); break; }
                }
                return o;
            }
            return null;
        }

        // ---------------------------------------------------------- 文本与域

        private static String fieldInstr(P p) {
            StringBuilder sb = new StringBuilder();
            for (Object o : p.getContent()) {
                Object u = XmlUtils.unwrap(o);
                if (u instanceof CTSimpleField sf) {
                    sb.append(sf.getInstr()).append(' ');
                } else if (u instanceof R r) {
                    for (Object c : r.getContent()) {
                        if (c instanceof JAXBElement<?> je && "instrText".equals(je.getName().getLocalPart()) && je.getValue() instanceof Text t) {
                            sb.append(t.getValue());
                        }
                    }
                } else if (u instanceof ContentAccessor ca) {
                    for (Object c : ca.getContent()) {
                        Object cu = XmlUtils.unwrap(c);
                        if (cu instanceof R r) {
                            for (Object x : r.getContent()) {
                                if (x instanceof JAXBElement<?> je && "instrText".equals(je.getName().getLocalPart()) && je.getValue() instanceof Text t) sb.append(t.getValue());
                            }
                        }
                    }
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        private static boolean containsField(P p) {
            return fieldInstr(p) != null;
        }

        /** 段落文本，域替换成 {PAGE}/{NUMPAGES}/{SECTIONPAGES} 占位。 */
        private static String textWithFields(P p, boolean[] hasPage) {
            StringBuilder sb = new StringBuilder();
            int depth = 0;
            boolean skippingResult = false;
            StringBuilder instr = new StringBuilder();
            List<Object> flat = new ArrayList<>();
            flattenRuns(p.getContent(), flat);
            for (Object u : flat) {
                if (u instanceof CTSimpleField sf) {
                    sb.append(placeholder(sf.getInstr(), hasPage));
                    continue;
                }
                if (!(u instanceof R r)) continue;
                for (Object c : r.getContent()) {
                    Object cu = XmlUtils.unwrap(c);
                    if (cu instanceof FldChar fc) {
                        switch (fc.getFldCharType()) {
                            case BEGIN -> { depth++; instr.setLength(0); skippingResult = false; }
                            case SEPARATE -> skippingResult = true;
                            case END -> {
                                depth = Math.max(0, depth - 1);
                                sb.append(placeholder(instr.toString(), hasPage));
                                skippingResult = false;
                            }
                        }
                    } else if (c instanceof JAXBElement<?> je && "instrText".equals(je.getName().getLocalPart()) && je.getValue() instanceof Text t) {
                        instr.append(t.getValue());
                    } else if (cu instanceof Text t) {
                        if (depth == 0) sb.append(t.getValue());
                    }
                }
            }
            return sb.toString();
        }

        private static void flattenRuns(List<Object> content, List<Object> out) {
            for (Object o : content) {
                Object u = XmlUtils.unwrap(o);
                if (u instanceof R || u instanceof CTSimpleField) out.add(u);
                else if (u instanceof ContentAccessor ca) flattenRuns(ca.getContent(), out);
            }
        }

        private static String placeholder(String instr, boolean[] hasPage) {
            String s = instr == null ? "" : instr.trim().toUpperCase();
            if (s.startsWith("NUMPAGES")) { hasPage[0] = true; return "{NUMPAGES}"; }
            if (s.startsWith("SECTIONPAGES")) { hasPage[0] = true; return "{SECTIONPAGES}"; }
            if (s.startsWith("PAGE")) { hasPage[0] = true; return "{PAGE}"; }
            return "";
        }

        static String textOf(Object node) {
            StringBuilder sb = new StringBuilder();
            collectText(node, sb);
            return sb.toString();
        }

        private static String textOfRun(R r) {
            StringBuilder sb = new StringBuilder();
            for (Object c : r.getContent()) {
                if (c instanceof JAXBElement<?> je && !"t".equals(je.getName().getLocalPart())) continue;
                Object u = XmlUtils.unwrap(c);
                if (u instanceof Text t) sb.append(t.getValue());
            }
            return sb.toString();
        }

        private static void collectText(Object node, StringBuilder sb) {
            if (node instanceof JAXBElement<?> je && !"t".equals(je.getName().getLocalPart()) && je.getValue() instanceof Text) return;
            Object u = XmlUtils.unwrap(node);
            if (u instanceof Text t) sb.append(t.getValue());
            else if (u instanceof ContentAccessor ca) for (Object c : ca.getContent()) collectText(c, sb);
        }
    }

    // ================================================================== 段落/字符属性的可叠加表示

    /** 段落属性叠加器：后叠的非 null 覆盖先前的；读端只关心这些字段。 */
    static final class Para {
        JcEnumeration jc;
        BigInteger before, beforeLines, after, afterLines, line;
        STLineSpacingRule lineRule;
        BigInteger left, leftChars, firstLine, firstLineChars, hanging, hangingChars;
        BigInteger numId;
        Integer ilvl;
        Boolean keepNext, pageBreakBefore;
        // 实例覆盖（已是 JSON 形态）
        String alignmentOverride;
        JsonNode beforeOverride, afterOverride, lineOverride, firstOverride, leftOverride;

        void overlay(PPrBase p) {
            if (p.getJc() != null && p.getJc().getVal() != null) jc = p.getJc().getVal();
            PPrBase.Spacing s = p.getSpacing();
            if (s != null) {
                if (s.getBefore() != null) { before = s.getBefore(); beforeLines = null; }
                if (s.getBeforeLines() != null) beforeLines = s.getBeforeLines();
                if (s.getAfter() != null) { after = s.getAfter(); afterLines = null; }
                if (s.getAfterLines() != null) afterLines = s.getAfterLines();
                if (s.getLine() != null) { line = s.getLine(); lineRule = s.getLineRule(); }
            }
            PPrBase.Ind i = p.getInd();
            if (i != null) {
                if (i.getLeft() != null) left = i.getLeft();
                if (i.getLeftChars() != null) leftChars = i.getLeftChars();
                if (i.getFirstLine() != null) { firstLine = i.getFirstLine(); if (i.getFirstLineChars() == null) firstLineChars = null; }
                if (i.getFirstLineChars() != null) firstLineChars = i.getFirstLineChars();
                if (i.getHanging() != null) { hanging = i.getHanging(); firstLine = null; firstLineChars = null; }
                if (i.getHangingChars() != null) hangingChars = i.getHangingChars();
            }
            if (p.getNumPr() != null) {
                if (p.getNumPr().getNumId() != null) numId = p.getNumPr().getNumId().getVal();
                if (p.getNumPr().getIlvl() != null && p.getNumPr().getIlvl().getVal() != null) ilvl = p.getNumPr().getIlvl().getVal().intValue();
            }
            if (p.getKeepNext() != null) keepNext = !Boolean.FALSE.equals(p.getKeepNext().isVal());
            if (p.getPageBreakBefore() != null) pageBreakBefore = !Boolean.FALSE.equals(p.getPageBreakBefore().isVal());
        }

        String alignment() {
            if (alignmentOverride != null) return alignmentOverride;
            return jc == null ? "left" : DocxStyleWriter.alignmentName(jc);
        }

        JsonNode alignmentNode() {
            return jc == null ? null : J.textNode(DocxStyleWriter.alignmentName(jc));
        }

        JsonNode spaceBeforeNode() {
            if (beforeLines != null && beforeLines.signum() != 0) return Length.of(beforeLines.doubleValue() / 100.0, "lines").toNode();
            if (before != null) return Units.twipsToPt(before.longValue()).toNode();
            return null;
        }

        JsonNode spaceAfterNode() {
            if (afterLines != null && afterLines.signum() != 0) return Length.of(afterLines.doubleValue() / 100.0, "lines").toNode();
            if (after != null) return Units.twipsToPt(after.longValue()).toNode();
            return null;
        }

        JsonNode lineSpacingNode() {
            if (line == null) return null;
            STLineSpacingRule rule = lineRule == null ? STLineSpacingRule.AUTO : lineRule;
            return switch (rule) {
                case AT_LEAST -> new StyleProfile.LineSpacing("atLeast", Units.round2(line.doubleValue() / 20.0), "pt").toNode();
                case EXACT -> new StyleProfile.LineSpacing("exactly", Units.round2(line.doubleValue() / 20.0), "pt").toNode();
                default -> new StyleProfile.LineSpacing("auto", Units.round2(line.doubleValue() / 240.0), null).toNode();
            };
        }

        JsonNode firstLineNode() {
            if (hanging != null || hangingChars != null) return Length.of(0, "pt").toNode();
            if (firstLineChars != null && firstLineChars.signum() != 0) return Length.of(firstLineChars.doubleValue() / 100.0, "chars").toNode();
            if (firstLine != null) return Units.twipsToPt(firstLine.longValue()).toNode();
            if (firstLineChars != null) return Length.of(0, "pt").toNode();
            return null;
        }

        JsonNode leftIndentNode() {
            if (leftChars != null && leftChars.signum() != 0) return Length.of(leftChars.doubleValue() / 100.0, "chars").toNode();
            if (left != null) return Units.twipsToPt(left.longValue()).toNode();
            return null;
        }

        JsonNode spaceBefore() {
            if (beforeOverride != null) return beforeOverride;
            JsonNode n = spaceBeforeNode();
            return n == null ? Length.of(0, "pt").toNode() : n;
        }

        JsonNode spaceAfter() {
            if (afterOverride != null) return afterOverride;
            JsonNode n = spaceAfterNode();
            return n == null ? Length.of(0, "pt").toNode() : n;
        }

        JsonNode lineSpacing() {
            if (lineOverride != null) return lineOverride;
            JsonNode n = lineSpacingNode();
            return n == null ? new StyleProfile.LineSpacing("auto", 1, null).toNode() : n;
        }

        JsonNode firstLine() {
            if (firstOverride != null) return firstOverride;
            JsonNode n = firstLineNode();
            return n == null ? Length.of(0, "pt").toNode() : n;
        }

        JsonNode hanging() {
            if (hangingChars != null && hangingChars.signum() != 0) return Length.of(hangingChars.doubleValue() / 100.0, "chars").toNode();
            if (hanging != null) return Units.twipsToPt(hanging.longValue()).toNode();
            return null;
        }

        JsonNode leftIndent() {
            if (leftOverride != null) return leftOverride;
            JsonNode n = leftIndentNode();
            return n == null ? Length.of(0, "pt").toNode() : n;
        }
    }

    /** 字符属性叠加器。 */
    static final class Chars {
        String eastAsia, western, cs, eastAsiaTheme, westernTheme;
        Integer sizeHalfPt;
        Boolean bold, italic;
        String color;

        void overlay(RPr r) {
            RFonts f = r.getRFonts();
            if (f != null) {
                if (f.getEastAsia() != null) { eastAsia = f.getEastAsia(); eastAsiaTheme = null; }
                if (f.getEastAsiaTheme() != null) { eastAsiaTheme = f.getEastAsiaTheme().value(); eastAsia = null; }
                String w = f.getAscii() != null ? f.getAscii() : f.getHAnsi();
                if (w != null) { western = w; westernTheme = null; }
                if (f.getAsciiTheme() != null) { westernTheme = f.getAsciiTheme().value(); western = null; }
                else if (f.getHAnsiTheme() != null && western == null) { westernTheme = f.getHAnsiTheme().value(); }
                if (f.getCs() != null) cs = f.getCs();
            }
            if (r.getSz() != null && r.getSz().getVal() != null) sizeHalfPt = r.getSz().getVal().intValue();
            if (r.getB() != null) bold = !Boolean.FALSE.equals(r.getB().isVal());
            if (r.getI() != null) italic = !Boolean.FALSE.equals(r.getI().isVal());
            if (r.getColor() != null && r.getColor().getVal() != null && !"auto".equalsIgnoreCase(r.getColor().getVal())) color = r.getColor().getVal().toUpperCase();
        }

        ObjectNode fontNode() {
            if (eastAsia == null && western == null && eastAsiaTheme == null && westernTheme == null) return null;
            ObjectNode f = J.objectNode();
            if (eastAsia != null) f.put("eastAsia", eastAsia);
            if (western != null) f.put("western", western);
            if (cs != null) f.put("cs", cs);
            if (eastAsiaTheme != null || westernTheme != null) {
                ObjectNode theme = J.objectNode();
                if (eastAsiaTheme != null) theme.put("eastAsia", eastAsiaTheme);
                if (westernTheme != null) theme.put("western", westernTheme);
                f.set("theme", theme);
            }
            return f;
        }
    }
}
