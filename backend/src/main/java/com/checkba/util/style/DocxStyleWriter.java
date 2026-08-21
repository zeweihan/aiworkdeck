package com.checkba.util.style;

import com.checkba.util.style.StyleProfile.Border;
import com.checkba.util.style.StyleProfile.Font;
import com.checkba.util.style.StyleProfile.Length;
import com.checkba.util.style.StyleProfile.LineSpacing;
import org.docx4j.wml.BooleanDefaultTrue;
import org.docx4j.wml.CTBorder;
import org.docx4j.wml.Color;
import org.docx4j.wml.HpsMeasure;
import org.docx4j.wml.Jc;
import org.docx4j.wml.JcEnumeration;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase;
import org.docx4j.wml.RFonts;
import org.docx4j.wml.RPr;
import org.docx4j.wml.STBorder;
import org.docx4j.wml.STLineSpacingRule;

import java.math.BigInteger;

/**
 * 画像叶子 → docx4j wml 对象的落法（无状态、纯换算）。{@code DocxStyleHelper.applyProfile}
 * 只负责「往哪里写」，数值怎么落全在这里，方便单测钉住换算向量。
 */
public final class DocxStyleWriter {

    private static final ObjectFactory F = new ObjectFactory();

    private DocxStyleWriter() {
    }

    public record SpacingValue(long line, STLineSpacingRule rule) {
    }

    /** 行距：auto 时 value 是倍数（240 = 单倍）；atLeast/exactly 按长度换算 twips。 */
    public static SpacingValue lineSpacing(LineSpacing ls, double fontSizePt) {
        if (ls == null) return null;
        String rule = ls.rule() == null ? "auto" : ls.rule();
        return switch (rule) {
            case "atLeast" -> new SpacingValue(Units.toTwips(Length.of(ls.value(), ls.unit() == null ? "pt" : ls.unit()), fontSizePt), STLineSpacingRule.AT_LEAST);
            case "exactly" -> new SpacingValue(Units.toTwips(Length.of(ls.value(), ls.unit() == null ? "pt" : ls.unit()), fontSizePt), STLineSpacingRule.EXACT);
            default -> new SpacingValue(Math.round(ls.value() * 240), STLineSpacingRule.AUTO);
        };
    }

    public static JcEnumeration jc(String alignment) {
        if (alignment == null) return null;
        return switch (alignment) {
            case "center" -> JcEnumeration.CENTER;
            case "right", "end" -> JcEnumeration.RIGHT;
            case "justify", "both" -> JcEnumeration.BOTH;
            case "distribute" -> JcEnumeration.DISTRIBUTE;
            default -> JcEnumeration.LEFT;
        };
    }

    public static String alignmentName(JcEnumeration jc) {
        if (jc == null) return null;
        return switch (jc) {
            case CENTER -> "center";
            case RIGHT -> "right";
            case BOTH -> "justify";
            case DISTRIBUTE -> "distribute";
            default -> "left";
        };
    }

    /** 颜色 "#RRGGBB"/"RRGGBB" → docx 六位十六进制；null/auto 原样。 */
    public static String hex(String color) {
        if (color == null) return null;
        String c = color.trim();
        if (c.startsWith("#")) c = c.substring(1);
        return c.isEmpty() ? null : c.toUpperCase();
    }

    // ------------------------------------------------------------------ RPr

    /** 写字体槽位、字号、颜色、加粗到 rPr；null 叶子不动。 */
    public static void applyRun(RPr rPr, Font font, Length size, String color, Boolean bold) {
        if (font != null) {
            RFonts fonts = rPr.getRFonts() == null ? F.createRFonts() : rPr.getRFonts();
            if (font.western() != null) {
                fonts.setAscii(font.western());
                fonts.setHAnsi(font.western());
                fonts.setAsciiTheme(null);
                fonts.setHAnsiTheme(null);
            }
            if (font.cs() != null) {
                fonts.setCs(font.cs());
                fonts.setCstheme(null);
            } else if (font.western() != null && fonts.getCs() == null) {
                fonts.setCs(font.western());
            }
            if (font.eastAsia() != null) {
                fonts.setEastAsia(font.eastAsia());
                fonts.setEastAsiaTheme(null);
            }
            rPr.setRFonts(fonts);
        }
        if (size != null) {
            long hp = Units.toHalfPoints(size);
            HpsMeasure sz = F.createHpsMeasure();
            sz.setVal(BigInteger.valueOf(hp));
            rPr.setSz(sz);
            HpsMeasure szCs = F.createHpsMeasure();
            szCs.setVal(BigInteger.valueOf(hp));
            rPr.setSzCs(szCs);
        }
        if (color != null) {
            Color c = F.createColor();
            c.setVal(hex(color));
            rPr.setColor(c);
        }
        if (bold != null) {
            BooleanDefaultTrue b = F.createBooleanDefaultTrue();
            b.setVal(bold);
            rPr.setB(b);
            rPr.setBCs(b);
        }
    }

    // ------------------------------------------------------------------ PPr

    /**
     * 写对齐、段前段后、行距、首行/左缩进到 pPr。chars/lines 单位分别落
     * {@code firstLineChars}/{@code beforeLines}（Word 原生语义，随字号缩放）；
     * 其余单位折 twips。fontSizePt 用于 chars/lines 折算磅值的场合。
     */
    public static void applyParagraph(PPr pPr, String alignment, Length before, Length after,
                                      LineSpacing ls, Length firstLine, Length left, double fontSizePt) {
        JcEnumeration jcv = jc(alignment);
        if (jcv != null) {
            Jc jcEl = F.createJc();
            jcEl.setVal(jcv);
            pPr.setJc(jcEl);
        }
        if (before != null || after != null || ls != null) {
            PPrBase.Spacing spacing = pPr.getSpacing() == null ? F.createPPrBaseSpacing() : pPr.getSpacing();
            if (before != null) {
                spacing.setBefore(BigInteger.valueOf(Units.toTwips(before, fontSizePt)));
                spacing.setBeforeLines(null);
            }
            if (after != null) {
                spacing.setAfter(BigInteger.valueOf(Units.toTwips(after, fontSizePt)));
                spacing.setAfterLines(null);
            }
            SpacingValue sv = lineSpacing(ls, fontSizePt);
            if (sv != null) {
                spacing.setLine(BigInteger.valueOf(sv.line()));
                spacing.setLineRule(sv.rule());
            }
            pPr.setSpacing(spacing);
        }
        if (firstLine != null || left != null) {
            PPrBase.Ind ind = pPr.getInd() == null ? F.createPPrBaseInd() : pPr.getInd();
            if (firstLine != null) {
                Long chars = Units.charsToFirstLineChars(firstLine);
                if (chars != null) {
                    ind.setFirstLineChars(BigInteger.valueOf(chars));
                    if (chars == 0) ind.setFirstLine(BigInteger.ZERO);
                    else ind.setFirstLine(null);
                } else {
                    ind.setFirstLineChars(BigInteger.ZERO);
                    ind.setFirstLine(BigInteger.valueOf(Units.toTwips(firstLine, fontSizePt)));
                }
                ind.setHanging(null);
                ind.setHangingChars(null);
            }
            if (left != null) {
                if ("chars".equals(left.unit())) {
                    ind.setLeftChars(BigInteger.valueOf(Math.round(left.value() * 100)));
                } else {
                    ind.setLeft(BigInteger.valueOf(Units.toTwips(left, fontSizePt)));
                }
            }
            pPr.setInd(ind);
        }
    }

    // ------------------------------------------------------------------ 边框

    public static CTBorder border(Border b) {
        if (b == null) return null;
        CTBorder ct = F.createCTBorder();
        ct.setVal(borderStyle(b.style()));
        if (b.width() != null) ct.setSz(BigInteger.valueOf(Units.toEighthPoints(b.width())));
        ct.setSpace(BigInteger.ZERO);
        ct.setColor(b.color() == null ? "000000" : hex(b.color()));
        return ct;
    }

    public static STBorder borderStyle(String style) {
        if (style == null) return STBorder.SINGLE;
        for (STBorder s : STBorder.values()) {
            if (s.value().equalsIgnoreCase(style)) return s;
        }
        return STBorder.SINGLE;
    }
}
