package com.checkba.util.style;

import com.checkba.util.style.StyleProfile.Length;

/**
 * 画像长度单位 → docx 数值的换算。
 *
 * <p>docx 约定：twips = 磅 × 20；字号 sz 用半磅；边框 sz 用 1/8 磅；
 * {@code firstLineChars}/{@code beforeLines} 用 1/100 字符、1/100 行。
 * chars 按当前字号折算（1 字符 = 1 em）；lines 按字号 × 1.2 折算（与编辑器 worker
 * 「0.2 行 ≈ 2.4 磅（10 号字）」同一口径）。
 */
public final class Units {

    private static final double TWIPS_PER_PT = 20.0;
    private static final double TWIPS_PER_MM = 1440.0 / 25.4;
    private static final double LINE_FACTOR = 1.2;

    private Units() {
    }

    public static double toPt(Length len, double fontSizePt) {
        if (len == null) return 0;
        return switch (len.unit() == null ? "pt" : len.unit()) {
            case "pt" -> len.value();
            case "twips" -> len.value() / TWIPS_PER_PT;
            case "mm" -> len.value() * TWIPS_PER_MM / TWIPS_PER_PT;
            case "cm" -> len.value() * 10 * TWIPS_PER_MM / TWIPS_PER_PT;
            case "chars" -> len.value() * fontSizePt;
            case "lines" -> len.value() * fontSizePt * LINE_FACTOR;
            default -> throw new IllegalArgumentException("不能把 " + len.unit() + " 换算成磅");
        };
    }

    public static long toTwips(Length len, double fontSizePt) {
        return Math.round(toPt(len, fontSizePt) * TWIPS_PER_PT);
    }

    /** 字号半磅值；只接受 pt/twips。 */
    public static long toHalfPoints(Length len) {
        return Math.round(toPt(len, 0) * 2);
    }

    /** 边框 1/8 磅值。 */
    public static long toEighthPoints(Length len) {
        return Math.round(toPt(len, 0) * 8);
    }

    /** chars → firstLineChars（1/100 字符）；非 chars 单位返回 null。 */
    public static Long charsToFirstLineChars(Length len) {
        if (len == null || !"chars".equals(len.unit())) return null;
        return Math.round(len.value() * 100);
    }

    public static Length twipsToPt(long twips) {
        return Length.of(round2(twips / TWIPS_PER_PT), "pt");
    }

    /** 纸张尺寸用 1 位小数：Word 把 A4 写成 11906 twips，算回来是 210.01mm。 */
    public static Length twipsToMm(long twips) {
        return Length.of(Math.round(twips / TWIPS_PER_MM * 10.0) / 10.0, "mm");
    }

    public static Length twipsToCm(long twips) {
        return Length.of(round2(twips / TWIPS_PER_MM / 10), "cm");
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
