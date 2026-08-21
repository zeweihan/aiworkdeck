package com.checkba.util;

import com.checkba.util.style.DocxProfileReader;
import com.checkba.util.style.DocxStyleWriter;
import com.checkba.util.style.StyleProfile;
import com.checkba.util.style.StyleProfile.Block;
import com.checkba.util.style.StyleProfile.Border;
import com.checkba.util.style.StyleProfile.Font;
import com.checkba.util.style.StyleProfile.Length;
import com.checkba.util.style.StyleProfiles;
import com.checkba.util.style.Units;
import com.fasterxml.jackson.databind.JsonNode;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.DocumentSettingsPart;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart;
import org.docx4j.openpackaging.parts.WordprocessingML.StyleDefinitionsPart;
import org.docx4j.relationships.Relationship;
import org.docx4j.wml.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBElement;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class to add missing styles to a WordprocessingMLPackage.
 * This fixes the "Couldn't find style: BodyText" and "Couldn't find style: Quotations"
 * errors that occur when using flexmark-docx-converter with docx4j.
 *
 * These styles are used by flexmark when rendering markdown elements like blockquotes.
 */
public class DocxStyleHelper {

    private static final Logger log = LoggerFactory.getLogger(DocxStyleHelper.class);

    /**
     * Adds missing styles (BodyText, Quotations) to the WordprocessingMLPackage.
     * This should be called after WordprocessingMLPackage.createPackage() and
     * before DocxRenderer.render().
     *
     * @param wordMLPackage The WordprocessingMLPackage to add styles to
     */
    public static void addMissingStyles(WordprocessingMLPackage wordMLPackage) {
        try {
            StyleDefinitionsPart stylesPart = wordMLPackage.getMainDocumentPart().getStyleDefinitionsPart();
            if (stylesPart == null) {
                log.warn("StyleDefinitionsPart is null, cannot add missing styles");
                return;
            }

            Styles styles = stylesPart.getJaxbElement();
            if (styles == null) {
                log.warn("Styles element is null, cannot add missing styles");
                return;
            }

            // Add BodyText style if not present
            if (!hasStyle(styles, "BodyText")) {
                styles.getStyle().add(createBodyTextStyle());
                log.debug("Added BodyText style to document");
            }

            // Add Quotations style if not present
            if (!hasStyle(styles, "Quotations")) {
                styles.getStyle().add(createQuotationsStyle());
                log.debug("Added Quotations style to document");
            }

            // Add ParagraphTextBody style if not present
            if (!hasStyle(styles, "ParagraphTextBody")) {
                styles.getStyle().add(createParagraphTextBodyStyle());
                log.debug("Added ParagraphTextBody style to document");
            }

        } catch (Exception e) {
            log.warn("Failed to add missing styles, document may still work but with warnings: {}", e.getMessage());
        }
    }

    /**
     * Checks if a style with the given ID already exists
     */
    private static boolean hasStyle(Styles styles, String styleId) {
        for (Style style : styles.getStyle()) {
            if (styleId.equals(style.getStyleId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a BodyText paragraph style
     */
    private static Style createBodyTextStyle() {
        ObjectFactory factory = new ObjectFactory();

        Style style = factory.createStyle();
        style.setType("paragraph");
        style.setStyleId("BodyText");

        // Set style name
        Style.Name styleName = factory.createStyleName();
        styleName.setVal("Body Text");
        style.setName(styleName);

        // Base on Normal style
        Style.BasedOn basedOn = factory.createStyleBasedOn();
        basedOn.setVal("Normal");
        style.setBasedOn(basedOn);

        // Paragraph properties
        PPr pPr = factory.createPPr();

        // Add spacing after paragraph
        PPrBase.Spacing spacing = factory.createPPrBaseSpacing();
        spacing.setAfter(java.math.BigInteger.valueOf(200)); // 10pt spacing after
        spacing.setLine(java.math.BigInteger.valueOf(276)); // 1.15 line spacing
        spacing.setLineRule(STLineSpacingRule.AUTO);
        pPr.setSpacing(spacing);

        style.setPPr(pPr);

        return style;
    }

    /**
     * Creates a Quotations paragraph style (for blockquotes)
     */
    private static Style createQuotationsStyle() {
        ObjectFactory factory = new ObjectFactory();

        Style style = factory.createStyle();
        style.setType("paragraph");
        style.setStyleId("Quotations");

        // Set style name
        Style.Name styleName = factory.createStyleName();
        styleName.setVal("Quote");
        style.setName(styleName);

        // Base on Normal style
        Style.BasedOn basedOn = factory.createStyleBasedOn();
        basedOn.setVal("Normal");
        style.setBasedOn(basedOn);

        // Paragraph properties
        PPr pPr = factory.createPPr();

        // Add left indent for quote appearance
        PPrBase.Ind indent = factory.createPPrBaseInd();
        indent.setLeft(java.math.BigInteger.valueOf(720)); // 0.5 inch left indent
        pPr.setInd(indent);

        // Add spacing
        PPrBase.Spacing spacing = factory.createPPrBaseSpacing();
        spacing.setBefore(java.math.BigInteger.valueOf(100)); // 5pt before
        spacing.setAfter(java.math.BigInteger.valueOf(100)); // 5pt after
        pPr.setSpacing(spacing);

        style.setPPr(pPr);

        // Run properties (italic for quote style)
        RPr rPr = factory.createRPr();
        BooleanDefaultTrue italic = factory.createBooleanDefaultTrue();
        italic.setVal(true);
        rPr.setI(italic);

        // Gray color for quotes
        Color color = factory.createColor();
        color.setVal("666666");
        rPr.setColor(color);

        style.setRPr(rPr);

        return style;
    }

    /**
     * Creates a ParagraphTextBody paragraph style (for general text body)
     */
    private static Style createParagraphTextBodyStyle() {
        ObjectFactory factory = new ObjectFactory();

        Style style = factory.createStyle();
        style.setType("paragraph");
        style.setStyleId("ParagraphTextBody");

        // Set style name
        Style.Name styleName = factory.createStyleName();
        styleName.setVal("Paragraph Text Body");
        style.setName(styleName);

        // Base on Normal style
        Style.BasedOn basedOn = factory.createStyleBasedOn();
        basedOn.setVal("Normal");
        style.setBasedOn(basedOn);

        // Paragraph properties
        PPr pPr = factory.createPPr();

        // Add spacing
        PPrBase.Spacing spacing = factory.createPPrBaseSpacing();
        spacing.setBefore(java.math.BigInteger.valueOf(0));
        spacing.setAfter(java.math.BigInteger.valueOf(120)); // 6pt after
        spacing.setLine(java.math.BigInteger.valueOf(240)); // 1.0 line spacing
        spacing.setLineRule(STLineSpacingRule.AUTO);
        pPr.setSpacing(spacing);

        style.setPPr(pPr);

        return style;
    }

    // ==================== 律所标准格式（house style）→ 画像驱动 ====================
    // 标准格式的数值不再写在这里：唯一出处是 style-profiles/house-default.json（StyleProfiles.houseDefault()），
    // 编辑器 worker 与 Office 插件端的 HOUSE 也从同一份 JSON 派生，HouseProfileParityTest 对拍。
    // applyProfile 接任意 styleProfile v1（模板画像 docx_inspect_template 学出来的，或项目 _模板/画像.json）。
    // 单位换算：twips = 磅×20；字号 sz 用半磅；边框 sz 用 1/8 磅；firstLineChars 用 1/100 字符（见 Units）。

    private static final ObjectFactory F = new ObjectFactory();
    private static final Pattern HEADING_ID = Pattern.compile("^Heading(\\d)$");

    /**
     * 对 flexmark 渲染完成的文档应用律所标准格式（= house-default 画像）。
     * 在 renderer.render() 之后、save 之前调用（表格要先渲染出来才能后处理）。
     */
    public static void applyStandardFormat(WordprocessingMLPackage pkg) {
        applyProfile(pkg, StyleProfiles.houseDefault());
    }

    /** 对 flexmark 渲染完成的文档应用指定画像；缺省叶子不约束（调用方通常先 merge 到 houseDefault 上）。 */
    public static void applyProfile(WordprocessingMLPackage pkg, StyleProfile profile) {
        if (profile == null) profile = StyleProfiles.houseDefault();
        try {
            overrideStyles(pkg, profile);
        } catch (Exception e) {
            log.warn("applyProfile: style override failed: {}", e.getMessage());
        }
        try {
            applyHeadingNumbering(pkg, profile);
        } catch (Exception e) {
            log.warn("applyProfile: heading numbering failed: {}", e.getMessage());
        }
        try {
            formatBodyTables(pkg, profile);
        } catch (Exception e) {
            log.warn("applyProfile: table post-process failed: {}", e.getMessage());
        }
        try {
            applyPage(pkg, profile);
        } catch (Exception e) {
            log.warn("applyProfile: page setup failed: {}", e.getMessage());
        }
        try {
            applyHeaderFooter(pkg, profile);
        } catch (Exception e) {
            log.warn("applyProfile: header/footer failed: {}", e.getMessage());
        }
        try {
            applyToc(pkg, profile);
        } catch (Exception e) {
            log.warn("applyProfile: toc failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 样式定义

    private static void overrideStyles(WordprocessingMLPackage pkg, StyleProfile profile) {
        StyleDefinitionsPart stylesPart = pkg.getMainDocumentPart().getStyleDefinitionsPart();
        if (stylesPart == null || stylesPart.getJaxbElement() == null) return;
        Styles styles = stylesPart.getJaxbElement();
        Block body = profile.body();
        if (body == null) return;
        double bodyPt = sizePt(body, 12);

        // docDefaults：画像 defaults 块真写进 rPrDefault（没写过样式的 run 也能落到同一套字体/字号）
        Block defaults = profile.defaults();
        if (defaults != null) {
            DocDefaults dd = styles.getDocDefaults();
            if (dd == null) { dd = F.createDocDefaults(); styles.setDocDefaults(dd); }
            DocDefaults.RPrDefault rpd = dd.getRPrDefault();
            if (rpd == null) { rpd = F.createDocDefaultsRPrDefault(); dd.setRPrDefault(rpd); }
            if (rpd.getRPr() == null) rpd.setRPr(F.createRPr());
            DocxStyleWriter.applyRun(rpd.getRPr(), defaults.font(), defaults.size(), defaults.color(), null);
        }

        // 正文类样式：Normal 及 flexmark 会挂到段落上的几个别名样式
        for (String id : new String[]{"Normal", "BodyText", "ParagraphTextBody"}) {
            Style s = findOrCreateStyle(styles, id);
            applyBlockToStyle(s, body, bodyPt, null);
        }
        // 引用块：字体颜色字号对齐到画像，保留其左缩进/斜体外观
        Style quote = findOrCreateStyle(styles, "Quotations");
        DocxStyleWriter.applyRun(rPrOf(quote), body.font(), body.size(), body.color(), null);

        for (int i = 1; i <= 6; i++) {
            Block h = profile.heading(i);
            Boolean forceBold = null;
            if (h == null) { h = body; forceBold = Boolean.TRUE; } // 画像没学到的级别：与正文同款但加粗
            Style s = findOrCreateStyle(styles, "Heading" + i);
            applyBlockToStyle(s, h, bodyPt, forceBold);
        }
    }

    private static void applyBlockToStyle(Style s, Block b, double bodyPt, Boolean forceBold) {
        double pt = sizePt(b, bodyPt);
        DocxStyleWriter.applyRun(rPrOf(s), b.font(), b.size(), b.color(), forceBold != null ? forceBold : b.bold());
        PPr pPr = pPrOf(s);
        DocxStyleWriter.applyParagraph(pPr, b.alignment(), b.spaceBefore(), b.spaceAfter(), b.lineSpacing(),
                b.firstLineIndent(), b.leftIndent(), pt);
        Boolean keep = b.bool("keepWithNext");
        if (keep != null) {
            BooleanDefaultTrue k = F.createBooleanDefaultTrue();
            k.setVal(keep);
            pPr.setKeepNext(k);
        }
        Boolean pbb = b.bool("pageBreakBefore");
        if (pbb != null) {
            BooleanDefaultTrue v = F.createBooleanDefaultTrue();
            v.setVal(pbb);
            pPr.setPageBreakBefore(v);
        }
    }

    private static double sizePt(Block b, double fallback) {
        Length sz = b == null ? null : b.size();
        return sz == null ? fallback : Units.toPt(sz, fallback);
    }

    private static RPr rPrOf(Style s) {
        if (s.getRPr() == null) s.setRPr(F.createRPr());
        return s.getRPr();
    }

    private static PPr pPrOf(Style s) {
        if (s.getPPr() == null) s.setPPr(F.createPPr());
        return s.getPPr();
    }

    private static PPr pPrOf(P p) {
        if (p.getPPr() == null) p.setPPr(F.createPPr());
        return p.getPPr();
    }

    private static Style findOrCreateStyle(Styles styles, String styleId) {
        for (Style style : styles.getStyle()) {
            if (styleId.equals(style.getStyleId())) return style;
        }
        Style style = F.createStyle();
        style.setType("paragraph");
        style.setStyleId(styleId);
        Style.Name name = F.createStyleName();
        name.setVal(styleId);
        style.setName(name);
        if (!"Normal".equals(styleId)) {
            Style.BasedOn basedOn = F.createStyleBasedOn();
            basedOn.setVal("Normal");
            style.setBasedOn(basedOn);
        }
        styles.getStyle().add(style);
        return style;
    }

    // ------------------------------------------------------------------ 标题编号

    private static void applyHeadingNumbering(WordprocessingMLPackage pkg, StyleProfile profile) throws Exception {
        MainDocumentPart mdp = pkg.getMainDocumentPart();
        List<Block> auto = new ArrayList<>();
        boolean anyLiteral = false;
        for (Block h : profile.headings()) {
            Block n = h.numbering();
            String kind = n == null ? "none" : n.string("kind");
            if ("auto".equals(kind)) auto.add(h);
            else if ("literal".equals(kind)) anyLiteral = true;
        }
        if (auto.isEmpty() && !anyLiteral) return;

        // markdown 里模型可能已经手打了「一、」——auto 级别去掉它（由自动编号接管），literal 级别不重复拼
        if (!auto.isEmpty()) {
            long numId = ensureHeadingNumbering(mdp, profile, auto);
            StyleDefinitionsPart sdp = mdp.getStyleDefinitionsPart();
            Styles styles = sdp.getJaxbElement();
            for (Block h : auto) {
                Integer level = h.level();
                if (level == null) continue;
                Style s = findOrCreateStyle(styles, "Heading" + level);
                PPrBase.NumPr numPr = F.createPPrBaseNumPr();
                PPrBase.NumPr.Ilvl ilvl = F.createPPrBaseNumPrIlvl();
                ilvl.setVal(BigInteger.valueOf(level - 1));
                numPr.setIlvl(ilvl);
                PPrBase.NumPr.NumId nid = F.createPPrBaseNumPrNumId();
                nid.setVal(BigInteger.valueOf(numId));
                numPr.setNumId(nid);
                pPrOf(s).setNumPr(numPr);
            }
        }

        int[] counters = new int[10];
        for (Object o : mdp.getContent()) {
            Object u = XmlUtils.unwrap(o);
            if (!(u instanceof P p)) continue;
            int level = headingLevel(p);
            if (level < 1 || level > 9) continue;
            Block h = profile.heading(level);
            Block n = h == null ? null : h.numbering();
            String kind = n == null ? "none" : n.string("kind");
            if ("none".equals(kind) || n == null) continue;
            counters[level]++;
            for (int i = level + 1; i < counters.length; i++) counters[i] = 0;
            String raw = textOf(p);
            String text = raw.trim();
            String stripped = stripLiteralPrefix(text);
            if ("auto".equals(kind)) {
                // 偏移按未 trim 的原文算：段首空白 + 编号前缀一起删
                int leading = raw.length() - raw.stripLeading().length();
                if (!stripped.equals(text)) removeLeadingText(p, leading + text.length() - stripped.length());
                continue;
            }
            // literal：文字自带编号的不重复拼
            if (!stripped.equals(text)) continue;
            String prefix = formatLiteral(n, counters, level);
            if (prefix.isEmpty()) continue;
            prependText(p, prefix);
        }
    }

    /** 建（或复用）标题用的 abstractNum/num，返回 numId。 */
    private static long ensureHeadingNumbering(MainDocumentPart mdp, StyleProfile profile, List<Block> auto) throws Exception {
        NumberingDefinitionsPart ndp = mdp.getNumberingDefinitionsPart();
        if (ndp == null) {
            ndp = new NumberingDefinitionsPart();
            ndp.setJaxbElement(F.createNumbering());
            mdp.addTargetPart(ndp);
        }
        Numbering numbering = ndp.getJaxbElement();
        if (numbering == null) { numbering = F.createNumbering(); ndp.setJaxbElement(numbering); }
        long absId = 0, numId = 0;
        for (Numbering.AbstractNum an : numbering.getAbstractNum()) if (an.getAbstractNumId() != null) absId = Math.max(absId, an.getAbstractNumId().longValue() + 1);
        for (Numbering.Num n : numbering.getNum()) if (n.getNumId() != null) numId = Math.max(numId, n.getNumId().longValue() + 1);
        if (numId == 0) numId = 1;

        Numbering.AbstractNum an = F.createNumberingAbstractNum();
        an.setAbstractNumId(BigInteger.valueOf(absId));
        Numbering.AbstractNum.MultiLevelType mlt = F.createNumberingAbstractNumMultiLevelType();
        mlt.setVal("multilevel");
        an.setMultiLevelType(mlt);
        Block numberingBlock = profile.numbering();
        for (int ilvl = 0; ilvl < 9; ilvl++) {
            int level = ilvl + 1;
            Block h = null;
            for (Block b : auto) if (b.level() != null && b.level() == level) h = b;
            Lvl lvl = F.createLvl();
            lvl.setIlvl(BigInteger.valueOf(ilvl));
            Lvl.Start start = F.createLvlStart();
            NumFmt fmt = F.createNumFmt();
            Lvl.LvlText lvlText = F.createLvlLvlText();
            Lvl.Suff suff = F.createLvlSuff();
            Jc lvlJc = F.createJc();
            lvlJc.setVal(JcEnumeration.LEFT);
            PPr lvlPPr = F.createPPr();
            if (h != null) {
                Block n = h.numbering();
                Integer st = n.integer("start");
                start.setVal(BigInteger.valueOf(st == null ? 1 : st));
                fmt.setVal(numberFormat(n.string("numFmt")));
                String lt = n.string("lvlText");
                lvlText.setVal(lt == null ? "%" + level + "." : lt);
                String sf = n.string("suffix");
                suff.setVal(sf == null ? "nothing" : sf);
                Lvl.PStyle ps = F.createLvlPStyle();
                ps.setVal("Heading" + level);
                lvl.setPStyle(ps);
                // 级别缩进镜像标题自身的缩进（编号级 pPr 会盖过样式 pPr）
                Length first = n.has("numIndent") ? n.length("numIndent") : h.firstLineIndent();
                Length left = h.leftIndent();
                Block levelDef = levelDef(numberingBlock, ilvl);
                if (levelDef != null && levelDef.sub("indent") != null) {
                    Block ind = levelDef.sub("indent");
                    if (ind.length("left") != null) left = ind.length("left");
                    if (ind.length("firstLine") != null) first = ind.length("firstLine");
                }
                DocxStyleWriter.applyParagraph(lvlPPr, null, null, null, null,
                        first == null ? Length.of(0, "pt") : first, left == null ? Length.of(0, "pt") : left, sizePt(h, 12));
            } else {
                start.setVal(BigInteger.ONE);
                fmt.setVal(NumberFormat.NONE);
                lvlText.setVal("");
                suff.setVal("nothing");
            }
            lvl.setStart(start);
            lvl.setNumFmt(fmt);
            lvl.setSuff(suff);
            lvl.setLvlText(lvlText);
            lvl.setLvlJc(lvlJc);
            lvl.setPPr(lvlPPr);
            an.getLvl().add(lvl);
        }
        numbering.getAbstractNum().add(an);

        Numbering.Num num = F.createNumberingNum();
        num.setNumId(BigInteger.valueOf(numId));
        Numbering.Num.AbstractNumId ref = F.createNumberingNumAbstractNumId();
        ref.setVal(BigInteger.valueOf(absId));
        num.setAbstractNumId(ref);
        numbering.getNum().add(num);
        return numId;
    }

    private static Block levelDef(Block numberingBlock, int ilvl) {
        if (numberingBlock == null) return null;
        JsonNode levels = numberingBlock.node().get("levels");
        if (levels == null || !levels.isArray()) return null;
        for (JsonNode l : levels) if (l.path("ilvl").asInt(-1) == ilvl && l.isObject()) return new Block((com.fasterxml.jackson.databind.node.ObjectNode) l);
        return null;
    }

    private static NumberFormat numberFormat(String v) {
        if (v == null) return NumberFormat.DECIMAL;
        for (NumberFormat nf : NumberFormat.values()) if (nf.value().equals(v)) return nf;
        return NumberFormat.DECIMAL;
    }

    private static int headingLevel(P p) {
        if (p.getPPr() == null) return 0;
        if (p.getPPr().getPStyle() != null && p.getPPr().getPStyle().getVal() != null) {
            Matcher m = HEADING_ID.matcher(p.getPPr().getPStyle().getVal());
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        if (p.getPPr().getOutlineLvl() != null && p.getPPr().getOutlineLvl().getVal() != null) {
            return p.getPPr().getOutlineLvl().getVal().intValue() + 1;
        }
        return 0;
    }

    /**
     * 段首字面编号：「一、」「（一）」「1.」「1.2.3 」「（1）」。裸数字后面必须有分隔符
     * （. 、 ． ) ）或多级点号），只跟空白不算编号——「2024 年度报告」不能被剥。
     */
    private static final Pattern LEADING_LITERAL = Pattern.compile(
            "^(?:[一二三四五六七八九十百]+、|[（(][一二三四五六七八九十百]+[）)]|\\d+(?:\\.\\d+)+\\.?|\\d+[.、．)）]|[（(]\\d+[）)])\\s*");

    public static String stripLiteralPrefix(String text) {
        Matcher m = LEADING_LITERAL.matcher(text);
        if (!m.find()) return text;
        return text.substring(m.group().length());
    }

    /** 按 lvlText 拼字面编号：%N 取 N 级计数，后缀 space/tab/nothing。 */
    public static String formatLiteral(Block numbering, int[] counters, int level) {
        String lvlText = numbering.string("lvlText");
        if (lvlText == null) lvlText = "%" + level + ".";
        String fmt = numbering.string("numFmt");
        Integer start = numbering.integer("start");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lvlText.length(); i++) {
            char c = lvlText.charAt(i);
            if (c == '%' && i + 1 < lvlText.length() && Character.isDigit(lvlText.charAt(i + 1))) {
                int n = lvlText.charAt(i + 1) - '0';
                int value = n >= 1 && n < counters.length ? counters[n] : 0;
                if (n == level && start != null && start != 1) value = value - 1 + start;
                sb.append(formatNumber(fmt, Math.max(value, 1)));
                i++;
            } else {
                sb.append(c);
            }
        }
        String suffix = numbering.string("suffix");
        if ("space".equals(suffix)) sb.append(' ');
        else if ("tab".equals(suffix)) sb.append('\t');
        return sb.toString();
    }

    public static String formatNumber(String numFmt, int n) {
        String fmt = numFmt == null ? "decimal" : numFmt;
        return switch (fmt) {
            case "chineseCounting", "chineseCountingThousand", "chineseLegalSimplified", "ideographTraditional" -> chinese(n);
            case "lowerLetter" -> n >= 1 && n <= 26 ? String.valueOf((char) ('a' + n - 1)) : String.valueOf(n);
            case "upperLetter" -> n >= 1 && n <= 26 ? String.valueOf((char) ('A' + n - 1)) : String.valueOf(n);
            case "lowerRoman" -> roman(n).toLowerCase();
            case "upperRoman" -> roman(n);
            case "decimalEnclosedCircle", "decimalEnclosedCircleChinese" -> n >= 1 && n <= 20 ? String.valueOf((char) ('①' + n - 1)) : String.valueOf(n);
            case "decimalFullWidth" -> String.valueOf(n).chars().mapToObj(c -> String.valueOf((char) (c - '0' + '０'))).reduce("", String::concat);
            default -> String.valueOf(n);
        };
    }

    private static final char[] CN_DIGITS = "零一二三四五六七八九".toCharArray();

    public static String chinese(int n) {
        if (n <= 0) return "零";
        if (n < 10) return String.valueOf(CN_DIGITS[n]);
        if (n < 20) return "十" + (n % 10 == 0 ? "" : CN_DIGITS[n % 10]);
        if (n < 100) return CN_DIGITS[n / 10] + "十" + (n % 10 == 0 ? "" : String.valueOf(CN_DIGITS[n % 10]));
        if (n < 1000) {
            StringBuilder sb = new StringBuilder().append(CN_DIGITS[n / 100]).append('百');
            int rest = n % 100;
            if (rest == 0) return sb.toString();
            if (rest < 10) return sb.append('零').append(CN_DIGITS[rest]).toString();
            return sb.append(chinese(rest).replaceFirst("^十", "一十")).toString();
        }
        return String.valueOf(n);
    }

    private static String roman(int n) {
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length && n > 0; i++) while (n >= v[i]) { sb.append(s[i]); n -= v[i]; }
        return sb.toString();
    }

    private static void prependText(P p, String prefix) {
        R first = null;
        for (Object o : p.getContent()) { Object u = XmlUtils.unwrap(o); if (u instanceof R r) { first = r; break; } }
        R run = F.createR();
        if (first != null && first.getRPr() != null) run.setRPr(XmlUtils.deepCopy(first.getRPr()));
        Text t = F.createText();
        t.setValue(prefix);
        t.setSpace("preserve");
        run.getContent().add(t);
        int idx = first == null ? 0 : p.getContent().indexOf(first);
        if (idx < 0) idx = 0;
        p.getContent().add(idx, run);
    }

    /** 从段首删掉 n 个字符（跨 run）。 */
    private static void removeLeadingText(P p, int n) {
        int remaining = n;
        for (Object o : p.getContent()) {
            if (remaining <= 0) break;
            Object u = XmlUtils.unwrap(o);
            if (!(u instanceof R r)) continue;
            for (Object c : r.getContent()) {
                if (remaining <= 0) break;
                if (c instanceof JAXBElement<?> je && !"t".equals(je.getName().getLocalPart())) continue;
                Object cu = XmlUtils.unwrap(c);
                if (!(cu instanceof Text t) || t.getValue() == null) continue;
                String v = t.getValue();
                int cut = Math.min(remaining, v.length());
                t.setValue(v.substring(cut));
                remaining -= cut;
            }
        }
    }

    // ------------------------------------------------------------------ 表格

    private static void formatBodyTables(WordprocessingMLPackage pkg, StyleProfile profile) {
        Block table = profile.table();
        Block body = profile.body();
        Length afterTable = body == null ? null : body.length("afterTableSpaceBefore");
        List<Object> content = pkg.getMainDocumentPart().getContent();
        boolean prevWasTable = false;
        double bodyPt = sizePt(body, 12);
        for (Object o : content) {
            Object u = XmlUtils.unwrap(o);
            if (u instanceof Tbl) {
                if (table != null) styleTable((Tbl) u, table, profile);
                prevWasTable = true;
            } else if (u instanceof P) {
                if (prevWasTable && afterTable != null) {
                    setSpacingBefore((P) u, Units.toTwips(afterTable, bodyPt));
                    prevWasTable = false;
                } else {
                    prevWasTable = false;
                }
            }
        }
    }

    private static void setSpacingBefore(P p, long twips) {
        PPr pPr = pPrOf(p);
        PPrBase.Spacing spacing = pPr.getSpacing();
        if (spacing == null) { spacing = F.createPPrBaseSpacing(); pPr.setSpacing(spacing); }
        spacing.setBefore(BigInteger.valueOf(twips));
        spacing.setBeforeLines(null);
    }

    private static void styleTable(Tbl tbl, Block table, StyleProfile profile) {
        TblPr tblPr = tbl.getTblPr();
        if (tblPr == null) { tblPr = F.createTblPr(); tbl.setTblPr(tblPr); }

        Block bordersBlock = table.sub("borders");
        Border outside = bordersBlock == null ? null : bordersBlock.border("outside");
        Border insideH = bordersBlock == null ? null : bordersBlock.border("insideH");
        Border insideV = bordersBlock == null ? null : bordersBlock.border("insideV");
        if (insideH == null) insideH = outside;
        if (insideV == null) insideV = outside;
        boolean cellLevel = bordersBlock != null && "cell".equals(bordersBlock.string("source"));
        if (outside != null) {
            TblBorders borders = F.createTblBorders();
            borders.setTop(DocxStyleWriter.border(outside));
            borders.setBottom(DocxStyleWriter.border(outside));
            borders.setLeft(DocxStyleWriter.border(outside));
            borders.setRight(DocxStyleWriter.border(outside));
            borders.setInsideH(DocxStyleWriter.border(insideH));
            borders.setInsideV(DocxStyleWriter.border(insideV));
            tblPr.setTblBorders(borders);
        }
        String alignment = table.alignment();
        if (alignment != null) {
            Jc jc = F.createJc();
            jc.setVal(DocxStyleWriter.jc(alignment));
            tblPr.setJc(jc);
        }

        List<Tr> rows = new ArrayList<>();
        for (Object ro : tbl.getContent()) { Object ru = XmlUtils.unwrap(ro); if (ru instanceof Tr tr) rows.add(tr); }
        int colCount = 0;
        for (Tr tr : rows) colCount = Math.max(colCount, cellsOf(tr).size());

        List<Long> widths = columnWidths(table, colCount, profile);
        if (widths != null) {
            TblGrid grid = F.createTblGrid();
            long total = 0;
            for (Long w : widths) {
                TblGridCol gc = F.createTblGridCol();
                gc.setW(BigInteger.valueOf(w));
                grid.getGridCol().add(gc);
                total += w;
            }
            tbl.setTblGrid(grid);
            TblWidth tw = F.createTblWidth();
            tw.setType("dxa");
            tw.setW(BigInteger.valueOf(total));
            tblPr.setTblW(tw);
            CTTblLayoutType layout = F.createCTTblLayoutType();
            layout.setType(STTblLayoutType.FIXED);
            tblPr.setTblLayout(layout);
        }

        Block header = table.sub("header");
        int headerRows = header == null ? 1 : (header.integer("rows") == null ? 1 : header.integer("rows"));
        Block cell = table.sub("cell");
        for (int ri = 0; ri < rows.size(); ri++) {
            Tr tr = rows.get(ri);
            boolean isHeader = ri < headerRows;
            if (isHeader && header != null && Boolean.TRUE.equals(header.bool("repeatOnEachPage"))) {
                TrPr trPr = tr.getTrPr();
                if (trPr == null) { trPr = F.createTrPr(); tr.setTrPr(trPr); }
                boolean has = trPr.getCnfStyleOrDivIdOrGridBefore().stream()
                        .anyMatch(x -> x.getName() != null && "tblHeader".equals(x.getName().getLocalPart()));
                if (!has) {
                    BooleanDefaultTrue hdr = F.createBooleanDefaultTrue();
                    trPr.getCnfStyleOrDivIdOrGridBefore().add(F.createCTTrPrBaseTblHeader(hdr));
                }
            }
            List<Tc> cells = cellsOf(tr);
            for (int ci = 0; ci < cells.size(); ci++) {
                Tc tc = cells.get(ci);
                styleTableCell(tc, isHeader, header, cell, profile);
                if (widths != null && ci < widths.size()) {
                    TcPr tcPr = tcPrOf(tc);
                    TblWidth w = F.createTblWidth();
                    w.setType("dxa");
                    w.setW(BigInteger.valueOf(widths.get(ci)));
                    tcPr.setTcW(w);
                }
                if (cellLevel && outside != null) {
                    TcPrInner.TcBorders cb = F.createTcPrInnerTcBorders();
                    cb.setTop(DocxStyleWriter.border(ri == 0 ? outside : insideH));
                    cb.setBottom(DocxStyleWriter.border(ri == rows.size() - 1 ? outside : insideH));
                    cb.setLeft(DocxStyleWriter.border(ci == 0 ? outside : insideV));
                    cb.setRight(DocxStyleWriter.border(ci == cells.size() - 1 ? outside : insideV));
                    tcPrOf(tc).setTcBorders(cb);
                }
            }
        }
    }

    /** A4 纸宽减默认 3.17cm 双边距的版心（twips），画像没写页面时的兜底。 */
    private static final long DEFAULT_TEXT_WIDTH_TWIPS = 9026;

    /** 版心宽度 = 画像纸宽 − 左右边距；画像没有页面块时退到 A4 默认。 */
    private static long textWidthTwips(StyleProfile profile) {
        Block page = profile.page();
        if (page == null || page.sub("size") == null) return DEFAULT_TEXT_WIDTH_TWIPS;
        Length width = page.sub("size").length("width");
        if (width == null) return DEFAULT_TEXT_WIDTH_TWIPS;
        long w = Units.toTwips(width, 12);
        Block margins = page.sub("margins");
        if (margins != null) {
            if (margins.length("left") != null) w -= Units.toTwips(margins.length("left"), 12);
            if (margins.length("right") != null) w -= Units.toTwips(margins.length("right"), 12);
        }
        return w > 0 ? w : DEFAULT_TEXT_WIDTH_TWIPS;
    }

    /** 画像里与本表列数相符的列宽样本（twips）；percent 模式按画像版心宽折算。 */
    private static List<Long> columnWidths(Block table, int colCount, StyleProfile profile) {
        Block cw = table.sub("columnWidths");
        if (cw == null || colCount == 0) return null;
        JsonNode samples = cw.node().get("samples");
        if (samples == null || !samples.isArray()) return null;
        String mode = cw.string("mode");
        long textWidth = textWidthTwips(profile);
        for (JsonNode sample : samples) {
            if (!sample.isArray() || sample.size() != colCount) continue;
            List<Long> out = new ArrayList<>();
            boolean ok = true;
            for (JsonNode v : sample) {
                double d = v.asDouble();
                if (d <= 0) { ok = false; break; }
                if ("percent".equals(mode)) out.add(Math.round(d / 100.0 * textWidth));
                else if ("cm".equals(mode)) out.add(Units.toTwips(Length.of(d, "cm"), 12));
                else out.add(Math.round(d));
            }
            if (ok) return out;
        }
        return null;
    }

    private static TcPr tcPrOf(Tc tc) {
        if (tc.getTcPr() == null) tc.setTcPr(F.createTcPr());
        return tc.getTcPr();
    }

    private static List<Tc> cellsOf(Tr tr) {
        List<Tc> out = new ArrayList<>();
        for (Object o : tr.getContent()) { Object u = XmlUtils.unwrap(o); if (u instanceof Tc tc) out.add(tc); }
        return out;
    }

    private static void styleTableCell(Tc tc, boolean isHeader, Block header, Block cell, StyleProfile profile) {
        TcPr tcPr = tcPrOf(tc);
        String vAlign = isHeader && header != null && header.string("verticalAlign") != null
                ? header.string("verticalAlign") : (cell == null ? null : cell.string("verticalAlign"));
        if (vAlign != null) {
            CTVerticalJc vjc = F.createCTVerticalJc();
            vjc.setVal("top".equals(vAlign) ? STVerticalJc.TOP : "bottom".equals(vAlign) ? STVerticalJc.BOTTOM : STVerticalJc.CENTER);
            tcPr.setVAlign(vjc);
        }
        if (isHeader && header != null && header.string("fill") != null) {
            CTShd shd = F.createCTShd();
            shd.setVal(STShd.CLEAR);
            shd.setColor("auto");
            shd.setFill(DocxStyleWriter.hex(header.string("fill")));
            tcPr.setShd(shd);
        }
        String cellText = textOf(tc).trim();
        String alignment;
        if (isHeader && header != null && header.alignment() != null) {
            alignment = header.alignment();
        } else {
            Block byType = cell == null ? null : cell.sub("byContentType");
            Block t = byType == null ? null : byType.sub(DocxProfileReader.classifyCell(cellText));
            alignment = t != null && t.alignment() != null ? t.alignment()
                    : (byType != null && byType.sub("text") != null ? byType.sub("text").alignment() : null);
        }
        Length size = isHeader && header != null && header.size() != null ? header.size() : (cell == null ? null : cell.size());
        Font font = isHeader && header != null && header.font() != null ? header.font() : (cell == null ? null : cell.font());
        Boolean bold = isHeader ? (header == null ? Boolean.TRUE : header.bold()) : null;
        double pt = size == null ? 10 : Units.toPt(size, 10);
        for (Object po : tc.getContent()) {
            Object pu = XmlUtils.unwrap(po);
            if (!(pu instanceof P p)) continue;
            PPr pPr = pPrOf(p);
            if (cell != null) {
                DocxStyleWriter.applyParagraph(pPr, alignment, cell.spaceBefore(), cell.spaceAfter(), cell.lineSpacing(),
                        cell.firstLineIndent() == null ? Length.of(0, "pt") : cell.firstLineIndent(), null, pt);
            } else if (alignment != null) {
                Jc jcEl = F.createJc();
                jcEl.setVal(DocxStyleWriter.jc(alignment));
                pPr.setJc(jcEl);
            }
            for (Object ro : p.getContent()) {
                Object ru = XmlUtils.unwrap(ro);
                if (!(ru instanceof R r)) continue;
                if (r.getRPr() == null) r.setRPr(F.createRPr());
                DocxStyleWriter.applyRun(r.getRPr(), font, size, null, bold);
            }
        }
    }

    // ------------------------------------------------------------------ 页面

    private static SectPr sectPrOf(MainDocumentPart mdp) {
        Body body = mdp.getJaxbElement().getBody();
        if (body.getSectPr() == null) body.setSectPr(F.createSectPr());
        return body.getSectPr();
    }

    private static void applyPage(WordprocessingMLPackage pkg, StyleProfile profile) {
        Block page = profile.page();
        if (page == null) return;
        SectPr sp = sectPrOf(pkg.getMainDocumentPart());
        Block size = page.sub("size");
        if (size != null) {
            SectPr.PgSz pgSz = sp.getPgSz() == null ? F.createSectPrPgSz() : sp.getPgSz();
            if (size.length("width") != null) pgSz.setW(BigInteger.valueOf(Units.toTwips(size.length("width"), 12)));
            if (size.length("height") != null) pgSz.setH(BigInteger.valueOf(Units.toTwips(size.length("height"), 12)));
            if ("landscape".equals(size.string("orientation"))) pgSz.setOrient(STPageOrientation.LANDSCAPE);
            sp.setPgSz(pgSz);
        }
        Block margins = page.sub("margins");
        if (margins != null) {
            SectPr.PgMar m = sp.getPgMar() == null ? F.createSectPrPgMar() : sp.getPgMar();
            if (margins.length("top") != null) m.setTop(BigInteger.valueOf(Units.toTwips(margins.length("top"), 12)));
            if (margins.length("bottom") != null) m.setBottom(BigInteger.valueOf(Units.toTwips(margins.length("bottom"), 12)));
            if (margins.length("left") != null) m.setLeft(BigInteger.valueOf(Units.toTwips(margins.length("left"), 12)));
            if (margins.length("right") != null) m.setRight(BigInteger.valueOf(Units.toTwips(margins.length("right"), 12)));
            if (margins.length("header") != null) m.setHeader(BigInteger.valueOf(Units.toTwips(margins.length("header"), 12)));
            if (margins.length("footer") != null) m.setFooter(BigInteger.valueOf(Units.toTwips(margins.length("footer"), 12)));
            if (m.getGutter() == null) m.setGutter(BigInteger.ZERO);
            sp.setPgMar(m);
        }
        Block grid = page.sub("docGrid");
        if (grid != null) {
            CTDocGrid g = sp.getDocGrid() == null ? F.createCTDocGrid() : sp.getDocGrid();
            String type = grid.string("type");
            if (type != null) for (STDocGrid t : STDocGrid.values()) if (t.value().equals(type)) g.setType(t);
            if (grid.length("linePitch") != null) g.setLinePitch(BigInteger.valueOf(Units.toTwips(grid.length("linePitch"), 12)));
            if (grid.integer("charSpace") != null) g.setCharSpace(BigInteger.valueOf(grid.integer("charSpace")));
            sp.setDocGrid(g);
        }
    }

    // ------------------------------------------------------------------ 页眉页脚

    private static void applyHeaderFooter(WordprocessingMLPackage pkg, StyleProfile profile) throws Exception {
        Block hf = profile.headerFooter();
        if (hf == null) return;
        MainDocumentPart mdp = pkg.getMainDocumentPart();
        SectPr sp = sectPrOf(mdp);
        Block footer = hf.sub("footer");
        if (footer != null && Boolean.TRUE.equals(footer.bool("enabled"))) {
            Block pn = footer.sub("pageNumber");
            String pattern = null;
            String alignment = footer.alignment();
            if (pn != null && Boolean.TRUE.equals(pn.bool("enabled"))) {
                pattern = pn.string("pattern") == null ? "{PAGE}" : pn.string("pattern");
                if (pn.alignment() != null) alignment = pn.alignment();
            } else if (footer.string("text") != null) {
                pattern = footer.string("text");
            }
            if (pattern != null) {
                FooterPart fp = new FooterPart();
                Ftr ftr = F.createFtr();
                ftr.getContent().add(hfParagraph(pattern, alignment == null ? "center" : alignment, footer, profile));
                fp.setJaxbElement(ftr);
                Relationship rel = mdp.addTargetPart(fp);
                FooterReference ref = F.createFooterReference();
                ref.setType(HdrFtrRef.DEFAULT);
                ref.setId(rel.getId());
                sp.getEGHdrFtrReferences().add(ref);
                if (pn != null && pn.integer("start") != null && pn.integer("start") != 1) {
                    CTPageNumber pnt = sp.getPgNumType() == null ? F.createCTPageNumber() : sp.getPgNumType();
                    pnt.setStart(BigInteger.valueOf(pn.integer("start")));
                    sp.setPgNumType(pnt);
                }
            }
        }
        Block header = hf.sub("header");
        if (header != null && Boolean.TRUE.equals(header.bool("enabled")) && header.string("text") != null) {
            HeaderPart hp = new HeaderPart();
            Hdr hdr = F.createHdr();
            hdr.getContent().add(hfParagraph(header.string("text"), header.alignment() == null ? "center" : header.alignment(), header, profile));
            hp.setJaxbElement(hdr);
            Relationship rel = mdp.addTargetPart(hp);
            HeaderReference ref = F.createHeaderReference();
            ref.setType(HdrFtrRef.DEFAULT);
            ref.setId(rel.getId());
            sp.getEGHdrFtrReferences().add(ref);
        }
        if (Boolean.TRUE.equals(hf.bool("differentFirstPage"))) {
            BooleanDefaultTrue t = F.createBooleanDefaultTrue();
            sp.setTitlePg(t);
        }
    }

    private static final Pattern FIELD_TOKEN = Pattern.compile("\\{(PAGE|NUMPAGES|SECTIONPAGES)}");

    /** 「第 {PAGE} 页 共 {NUMPAGES} 页」→ 文本 run + 域 run 交替。 */
    private static P hfParagraph(String pattern, String alignment, Block block, StyleProfile profile) {
        P p = F.createP();
        PPr pPr = pPrOf(p);
        Jc jc = F.createJc();
        jc.setVal(DocxStyleWriter.jc(alignment));
        pPr.setJc(jc);
        PPrBase.Spacing spacing = F.createPPrBaseSpacing();
        spacing.setBefore(BigInteger.ZERO);
        spacing.setAfter(BigInteger.ZERO);
        pPr.setSpacing(spacing);
        PPrBase.Ind ind = F.createPPrBaseInd();
        ind.setFirstLineChars(BigInteger.ZERO);
        ind.setFirstLine(BigInteger.ZERO);
        pPr.setInd(ind);
        Font font = block.font() != null ? block.font() : (profile.body() == null ? null : profile.body().font());
        Length size = block.size() != null ? block.size() : Length.of(9, "pt");
        Matcher m = FIELD_TOKEN.matcher(pattern);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) p.getContent().add(textRun(pattern.substring(last, m.start()), font, size));
            p.getContent().addAll(fieldRuns(m.group(1), font, size));
            last = m.end();
        }
        if (last < pattern.length()) p.getContent().add(textRun(pattern.substring(last), font, size));
        return p;
    }

    private static R textRun(String text, Font font, Length size) {
        R r = F.createR();
        r.setRPr(F.createRPr());
        DocxStyleWriter.applyRun(r.getRPr(), font, size, null, null);
        Text t = F.createText();
        t.setValue(text);
        t.setSpace("preserve");
        r.getContent().add(t);
        return r;
    }

    private static List<Object> fieldRuns(String instr, Font font, Length size) {
        List<Object> out = new ArrayList<>();
        R begin = runWith(font, size);
        FldChar fb = F.createFldChar();
        fb.setFldCharType(STFldCharType.BEGIN);
        begin.getContent().add(fb);
        out.add(begin);
        R instrRun = runWith(font, size);
        Text it = F.createText();
        it.setValue(" " + instr + " ");
        it.setSpace("preserve");
        instrRun.getContent().add(F.createRInstrText(it));
        out.add(instrRun);
        R sep = runWith(font, size);
        FldChar fs = F.createFldChar();
        fs.setFldCharType(STFldCharType.SEPARATE);
        sep.getContent().add(fs);
        out.add(sep);
        out.add(textRun("1", font, size));
        R end = runWith(font, size);
        FldChar fe = F.createFldChar();
        fe.setFldCharType(STFldCharType.END);
        end.getContent().add(fe);
        out.add(end);
        return out;
    }

    private static R runWith(Font font, Length size) {
        R r = F.createR();
        r.setRPr(F.createRPr());
        DocxStyleWriter.applyRun(r.getRPr(), font, size, null, null);
        return r;
    }

    // ------------------------------------------------------------------ 目录

    private static void applyToc(WordprocessingMLPackage pkg, StyleProfile profile) throws Exception {
        Block toc = profile.toc();
        if (toc == null || !Boolean.TRUE.equals(toc.bool("enabled"))) return;
        MainDocumentPart mdp = pkg.getMainDocumentPart();
        List<Object> content = mdp.getContent();
        // 已有目录域不重复插
        for (Object o : content) {
            Object u = XmlUtils.unwrap(o);
            if (u instanceof P p && textOf(p).isEmpty() && hasTocField(p)) return;
        }
        String levels = toc.string("levels");
        if (levels == null) {
            Integer n = toc.integer("levels");
            levels = "1-" + (n == null ? 3 : n);
        }
        boolean hyper = !Boolean.FALSE.equals(toc.bool("hyperlinks"));
        String instr = " TOC \\o \"" + levels + "\"" + (hyper ? " \\h \\z \\u " : " \\z \\u ");
        Font font = profile.body() == null ? null : profile.body().font();

        List<Object> block = new ArrayList<>();
        String title = toc.string("title") == null ? "目  录" : toc.string("title");
        Block titleStyle = toc.sub("titleStyle");
        P titleP = F.createP();
        PPr tpPr = pPrOf(titleP);
        Jc tjc = F.createJc();
        tjc.setVal(DocxStyleWriter.jc(titleStyle != null && titleStyle.alignment() != null ? titleStyle.alignment() : "center"));
        tpPr.setJc(tjc);
        PPrBase.Ind tind = F.createPPrBaseInd();
        tind.setFirstLineChars(BigInteger.ZERO);
        tind.setFirstLine(BigInteger.ZERO);
        tpPr.setInd(tind);
        R tr = F.createR();
        tr.setRPr(F.createRPr());
        DocxStyleWriter.applyRun(tr.getRPr(), font,
                titleStyle != null && titleStyle.size() != null ? titleStyle.size() : Length.of(16, "pt"), null,
                titleStyle == null || titleStyle.bold() == null ? Boolean.TRUE : titleStyle.bold());
        Text tt = F.createText();
        tt.setValue(title);
        tt.setSpace("preserve");
        tr.getContent().add(tt);
        titleP.getContent().add(tr);
        block.add(titleP);

        P fieldP = F.createP();
        PPr fpPr = pPrOf(fieldP);
        PPrBase.Ind find = F.createPPrBaseInd();
        find.setFirstLineChars(BigInteger.ZERO);
        find.setFirstLine(BigInteger.ZERO);
        fpPr.setInd(find);
        R begin = F.createR();
        FldChar fb = F.createFldChar();
        fb.setFldCharType(STFldCharType.BEGIN);
        fb.setDirty(true);
        begin.getContent().add(fb);
        fieldP.getContent().add(begin);
        R instrRun = F.createR();
        Text it = F.createText();
        it.setValue(instr);
        it.setSpace("preserve");
        instrRun.getContent().add(F.createRInstrText(it));
        fieldP.getContent().add(instrRun);
        R sep = F.createR();
        FldChar fs = F.createFldChar();
        fs.setFldCharType(STFldCharType.SEPARATE);
        sep.getContent().add(fs);
        fieldP.getContent().add(sep);
        fieldP.getContent().add(textRun("（打开文档后按 F9 或右键「更新域」生成目录）", font, Length.of(10, "pt")));
        R end = F.createR();
        FldChar fe = F.createFldChar();
        fe.setFldCharType(STFldCharType.END);
        end.getContent().add(fe);
        fieldP.getContent().add(end);
        block.add(fieldP);

        if (!Boolean.FALSE.equals(toc.bool("pageBreakAfter"))) {
            P br = F.createP();
            R r = F.createR();
            Br b = F.createBr();
            b.setType(STBrType.PAGE);
            r.getContent().add(b);
            br.getContent().add(r);
            block.add(br);
        }

        // 主标题（首段 Heading1）之后插；否则放最前
        int insertAt = 0;
        if (!content.isEmpty()) {
            Object first = XmlUtils.unwrap(content.get(0));
            if (first instanceof P p && headingLevel(p) == 1) insertAt = 1;
        }
        content.addAll(insertAt, block);

        DocumentSettingsPart dsp = mdp.getDocumentSettingsPart();
        if (dsp == null) {
            dsp = new DocumentSettingsPart();
            dsp.setJaxbElement(F.createCTSettings());
            mdp.addTargetPart(dsp);
        }
        CTSettings settings = dsp.getJaxbElement();
        if (settings == null) { settings = F.createCTSettings(); dsp.setJaxbElement(settings); }
        BooleanDefaultTrue upd = F.createBooleanDefaultTrue();
        settings.setUpdateFields(upd);
    }

    private static boolean hasTocField(P p) {
        for (Object o : p.getContent()) {
            Object u = XmlUtils.unwrap(o);
            if (!(u instanceof R r)) continue;
            for (Object c : r.getContent()) {
                if (c instanceof JAXBElement<?> je && "instrText".equals(je.getName().getLocalPart()) && je.getValue() instanceof Text t
                        && t.getValue() != null && t.getValue().trim().startsWith("TOC")) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 文本

    private static String textOf(Object node) {
        StringBuilder sb = new StringBuilder();
        collectText(node, sb);
        return sb.toString();
    }

    private static void collectText(Object node, StringBuilder sb) {
        if (node instanceof JAXBElement<?> je && !"t".equals(je.getName().getLocalPart()) && je.getValue() instanceof Text) return;
        Object u = XmlUtils.unwrap(node);
        if (u instanceof Text) {
            sb.append(((Text) u).getValue());
        } else if (u instanceof ContentAccessor) {
            for (Object child : ((ContentAccessor) u).getContent()) collectText(child, sb);
        }
    }
}
