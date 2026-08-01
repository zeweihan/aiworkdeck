package com.checkba.util;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.StyleDefinitionsPart;
import org.docx4j.wml.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // ==================== 律所标准格式（house style） ====================
    // 与编辑器 worker（office_thread.js 的 HOUSE 常量）同一套规范：
    // 正文楷体_GB2312/西文 Arial、12 号黑色、两端对齐、段前 0 段后 18 磅、行距最小
    // 值 16 磅、首行缩进 2 字符；主标题（Heading1）16 号加粗居中不缩进；其余标题与
    // 正文同款但加粗；表格 Grid 实线 1.5 磅、10 号字、单元格段前后 0.2 行、行距最小
    // 值 12 磅、首行加粗居中、数字居右；紧跟表格的段落段前 18 磅。
    // 单位换算：twips = 磅×20；字号 sz 用半磅；边框 sz 用 1/8 磅；firstLineChars 用 1/100 字符。

    private static final String HOUSE_FONT_WESTERN = "Arial";
    private static final String HOUSE_FONT_ASIAN = "楷体_GB2312";
    private static final java.util.regex.Pattern NUMERIC_CELL = java.util.regex.Pattern.compile("^[-+（(]?[0-9][0-9,.，%．]*[%）)]?$");

    /**
     * 对 flexmark 渲染完成的文档应用律所标准格式。
     * 在 renderer.render() 之后、save 之前调用（表格要先渲染出来才能后处理）。
     */
    public static void applyStandardFormat(WordprocessingMLPackage pkg) {
        try {
            overrideBaseStyles(pkg);
        } catch (Exception e) {
            log.warn("applyStandardFormat: style override failed: {}", e.getMessage());
        }
        try {
            formatBodyTables(pkg);
        } catch (Exception e) {
            log.warn("applyStandardFormat: table post-process failed: {}", e.getMessage());
        }
    }

    private static void overrideBaseStyles(WordprocessingMLPackage pkg) {
        StyleDefinitionsPart stylesPart = pkg.getMainDocumentPart().getStyleDefinitionsPart();
        if (stylesPart == null || stylesPart.getJaxbElement() == null) return;
        Styles styles = stylesPart.getJaxbElement();

        // 正文类样式：Normal 及 flexmark 会挂到段落上的几个别名样式
        for (String id : new String[]{"Normal", "BodyText", "ParagraphTextBody"}) {
            Style s = findOrCreateStyle(styles, id);
            houseRPr(s, 24, null);                 // 12 号
            housePPr(s, JcEnumeration.BOTH, 200);  // 两端对齐 + 首行缩进 2 字符
        }
        // 引用块：字体颜色对齐到规范（黑色、宋体系），保留其左缩进/斜体外观
        Style quote = findOrCreateStyle(styles, "Quotations");
        houseRPr(quote, 24, null);

        // Heading1 = 主标题：16 号加粗居中，不缩进
        Style h1 = findOrCreateStyle(styles, "Heading1");
        houseRPr(h1, 32, Boolean.TRUE);
        housePPr(h1, JcEnumeration.CENTER, 0);
        // Heading2-6 = 小标题：与正文同款但加粗
        for (int i = 2; i <= 6; i++) {
            Style h = findOrCreateStyle(styles, "Heading" + i);
            houseRPr(h, 24, Boolean.TRUE);
            housePPr(h, JcEnumeration.BOTH, 200);
        }
    }

    private static Style findOrCreateStyle(Styles styles, String styleId) {
        for (Style style : styles.getStyle()) {
            if (styleId.equals(style.getStyleId())) return style;
        }
        ObjectFactory factory = new ObjectFactory();
        Style style = factory.createStyle();
        style.setType("paragraph");
        style.setStyleId(styleId);
        Style.Name name = factory.createStyleName();
        name.setVal(styleId);
        style.setName(name);
        if (!"Normal".equals(styleId)) {
            Style.BasedOn basedOn = factory.createStyleBasedOn();
            basedOn.setVal("Normal");
            style.setBasedOn(basedOn);
        }
        styles.getStyle().add(style);
        return style;
    }

    /** 标准字符属性。halfPoints=字号半磅值；bold=null 表示不动加粗位。 */
    private static void houseRPr(Style s, int halfPoints, Boolean bold) {
        ObjectFactory f = new ObjectFactory();
        RPr rPr = s.getRPr();
        if (rPr == null) { rPr = f.createRPr(); s.setRPr(rPr); }
        RFonts fonts = f.createRFonts();
        fonts.setAscii(HOUSE_FONT_WESTERN);
        fonts.setHAnsi(HOUSE_FONT_WESTERN);
        fonts.setCs(HOUSE_FONT_WESTERN);
        fonts.setEastAsia(HOUSE_FONT_ASIAN);
        rPr.setRFonts(fonts);
        HpsMeasure sz = f.createHpsMeasure();
        sz.setVal(java.math.BigInteger.valueOf(halfPoints));
        rPr.setSz(sz);
        HpsMeasure szCs = f.createHpsMeasure();
        szCs.setVal(java.math.BigInteger.valueOf(halfPoints));
        rPr.setSzCs(szCs);
        Color color = f.createColor();
        color.setVal("000000");
        rPr.setColor(color);
        if (bold != null) {
            BooleanDefaultTrue b = f.createBooleanDefaultTrue();
            b.setVal(bold);
            rPr.setB(b);
            rPr.setBCs(b);
        }
    }

    /** 标准段落属性：段前 0/段后 18 磅、行距最小值 16 磅、对齐、首行缩进（1/100 字符）。 */
    private static void housePPr(Style s, JcEnumeration jc, int firstLineChars) {
        ObjectFactory f = new ObjectFactory();
        PPr pPr = s.getPPr();
        if (pPr == null) { pPr = f.createPPr(); s.setPPr(pPr); }
        PPrBase.Spacing spacing = f.createPPrBaseSpacing();
        spacing.setBefore(java.math.BigInteger.ZERO);
        spacing.setAfter(java.math.BigInteger.valueOf(360));   // 18 磅
        spacing.setLine(java.math.BigInteger.valueOf(320));    // 16 磅
        spacing.setLineRule(STLineSpacingRule.AT_LEAST);
        pPr.setSpacing(spacing);
        Jc jcEl = f.createJc();
        jcEl.setVal(jc);
        pPr.setJc(jcEl);
        PPrBase.Ind ind = f.createPPrBaseInd();
        ind.setFirstLineChars(java.math.BigInteger.valueOf(firstLineChars));
        if (firstLineChars == 0) ind.setFirstLine(java.math.BigInteger.ZERO);
        pPr.setInd(ind);
    }

    /** 表格后处理：Grid 1.5 磅边框、10 号字、首行加粗居中、垂直居中、数字居右、表后段落段前 18 磅。 */
    private static void formatBodyTables(WordprocessingMLPackage pkg) {
        java.util.List<Object> content = pkg.getMainDocumentPart().getContent();
        boolean prevWasTable = false;
        for (Object o : content) {
            Object u = org.docx4j.XmlUtils.unwrap(o);
            if (u instanceof Tbl) {
                styleTable((Tbl) u);
                prevWasTable = true;
            } else if (u instanceof P) {
                if (prevWasTable) {
                    setSpacingBefore((P) u, 360); // 表格后首段段前 18 磅
                    prevWasTable = false;
                }
            }
        }
    }

    private static void setSpacingBefore(P p, int twips) {
        ObjectFactory f = new ObjectFactory();
        PPr pPr = p.getPPr();
        if (pPr == null) { pPr = f.createPPr(); p.setPPr(pPr); }
        PPrBase.Spacing spacing = pPr.getSpacing();
        if (spacing == null) { spacing = f.createPPrBaseSpacing(); pPr.setSpacing(spacing); }
        spacing.setBefore(java.math.BigInteger.valueOf(twips));
    }

    private static void styleTable(Tbl tbl) {
        ObjectFactory f = new ObjectFactory();
        TblPr tblPr = tbl.getTblPr();
        if (tblPr == null) { tblPr = f.createTblPr(); tbl.setTblPr(tblPr); }
        TblBorders borders = f.createTblBorders();
        borders.setTop(gridBorder(f));
        borders.setBottom(gridBorder(f));
        borders.setLeft(gridBorder(f));
        borders.setRight(gridBorder(f));
        borders.setInsideH(gridBorder(f));
        borders.setInsideV(gridBorder(f));
        tblPr.setTblBorders(borders);

        int rowIdx = 0;
        for (Object rowO : tbl.getContent()) {
            Object rowU = org.docx4j.XmlUtils.unwrap(rowO);
            if (!(rowU instanceof Tr)) continue;
            boolean isHeader = rowIdx == 0;
            for (Object cellO : ((Tr) rowU).getContent()) {
                Object cellU = org.docx4j.XmlUtils.unwrap(cellO);
                if (!(cellU instanceof Tc)) continue;
                styleTableCell(f, (Tc) cellU, isHeader);
            }
            rowIdx++;
        }
    }

    private static CTBorder gridBorder(ObjectFactory f) {
        CTBorder b = f.createCTBorder();
        b.setVal(STBorder.SINGLE);
        b.setSz(java.math.BigInteger.valueOf(12)); // 1/8 磅单位：12 = 1.5 磅
        b.setSpace(java.math.BigInteger.ZERO);
        b.setColor("000000");
        return b;
    }

    private static void styleTableCell(ObjectFactory f, Tc tc, boolean isHeader) {
        // 单元格垂直居中
        TcPr tcPr = tc.getTcPr();
        if (tcPr == null) { tcPr = f.createTcPr(); tc.setTcPr(tcPr); }
        CTVerticalJc vjc = f.createCTVerticalJc();
        vjc.setVal(STVerticalJc.CENTER);
        tcPr.setVAlign(vjc);
        // 纯数字单元格水平居右（表头一律居中）
        String cellText = extractText(tc).trim();
        JcEnumeration jc = isHeader ? JcEnumeration.CENTER
                : (NUMERIC_CELL.matcher(cellText).matches() ? JcEnumeration.RIGHT : JcEnumeration.LEFT);
        for (Object po : tc.getContent()) {
            Object pu = org.docx4j.XmlUtils.unwrap(po);
            if (!(pu instanceof P)) continue;
            P p = (P) pu;
            PPr pPr = p.getPPr();
            if (pPr == null) { pPr = f.createPPr(); p.setPPr(pPr); }
            PPrBase.Spacing spacing = f.createPPrBaseSpacing();
            spacing.setBefore(java.math.BigInteger.valueOf(48));  // 0.2 行 ≈ 2.4 磅
            spacing.setAfter(java.math.BigInteger.valueOf(48));
            spacing.setLine(java.math.BigInteger.valueOf(240));   // 行距最小值 12 磅
            spacing.setLineRule(STLineSpacingRule.AT_LEAST);
            pPr.setSpacing(spacing);
            Jc jcEl = f.createJc();
            jcEl.setVal(jc);
            pPr.setJc(jcEl);
            PPrBase.Ind ind = f.createPPrBaseInd();
            ind.setFirstLineChars(java.math.BigInteger.ZERO);
            ind.setFirstLine(java.math.BigInteger.ZERO);
            pPr.setInd(ind);
            for (Object ro : p.getContent()) {
                Object ru = org.docx4j.XmlUtils.unwrap(ro);
                if (!(ru instanceof R)) continue;
                R r = (R) ru;
                RPr rPr = r.getRPr();
                if (rPr == null) { rPr = f.createRPr(); r.setRPr(rPr); }
                HpsMeasure sz = f.createHpsMeasure();
                sz.setVal(java.math.BigInteger.valueOf(20)); // 10 号
                rPr.setSz(sz);
                HpsMeasure szCs = f.createHpsMeasure();
                szCs.setVal(java.math.BigInteger.valueOf(20));
                rPr.setSzCs(szCs);
                if (isHeader) {
                    BooleanDefaultTrue b = f.createBooleanDefaultTrue();
                    b.setVal(true);
                    rPr.setB(b);
                    rPr.setBCs(b);
                }
            }
        }
    }

    private static String extractText(Object node) {
        StringBuilder sb = new StringBuilder();
        collectText(node, sb);
        return sb.toString();
    }

    private static void collectText(Object node, StringBuilder sb) {
        Object u = org.docx4j.XmlUtils.unwrap(node);
        if (u instanceof Text) {
            sb.append(((Text) u).getValue());
        } else if (u instanceof ContentAccessor) {
            for (Object child : ((ContentAccessor) u).getContent()) collectText(child, sb);
        }
    }
}
