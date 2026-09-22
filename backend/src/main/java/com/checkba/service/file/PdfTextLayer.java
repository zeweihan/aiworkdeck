// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.file;

/**
 * 「这份 PDF 的文字层够不够用」——<b>全仓唯一的判据</b>（dev-board#800）。
 *
 * <p>为什么要有这么一个类：PDF 抽取在本仓一度分叉成三条口径（LegalTools/FileTools 无条件逐页
 * 云端 OCR、ProjectFileTextExtractor 文字层优先、FileContextLoader 只抽文字层），
 * 同一份合同走不同入口得到的正文与账单都不一样。收敛的前提是「可用」只有一个定义；
 * 各处各写一份 {@code StringUtils.hasText} 或各设一个阈值，过几个月又会漂开。
 *
 * <p>判据：<b>非空，且实义字符（字母/数字/汉字等）不少于 {@link #MIN_MEANINGFUL_CHARS} 个</b>。
 * 只判 {@code hasText} 不够——扫描件的 PDF 常常带一层残留的页码、书签名或空白 XObject 文本，
 * PDFTextStripper 会抽出几个孤零零的字符；把那几个字符当正文返回，模型看到的就是
 * 「这份文件的全文是『1』」，比走 OCR 坏得多。
 *
 * <p>两个方向的代价不对称，所以阈值刻意取小（16）：判成「不可用」的代价是白跑一次 OCR
 *（慢、平台档还按页扣 Credits）；判成「可用」的代价是把噪声当全文喂给模型。
 * 但真正只有十几个字的 PDF（一纸签收单、一页授权书）本来就该按文字层直接返回，
 * 阈值再往上抬就会把它们赶去 OCR。
 */
public final class PdfTextLayer {

    /**
     * 判「可用」所需的实义字符下限。
     *
     * <p>16 的依据：一页正常排版的中文合同正文远超它（几百到上千字），
     * 而扫描件残留的页码/书签/水印文本通常只有个位数到十几个字符。
     */
    public static final int MIN_MEANINGFUL_CHARS = 16;

    private PdfTextLayer() {
    }

    /**
     * 文字层是否可用。
     *
     * @param text PDFTextStripper / Tika 抽出的原文，可为 null
     */
    public static boolean isUsable(String text) {
        return meaningfulChars(text) >= MIN_MEANINGFUL_CHARS;
    }

    /**
     * 实义字符数：字母、数字、汉字等都算，空白与纯标点不算。
     *
     * <p>数到阈值就停：一份几十万字的招股书没必要为了一个布尔值遍历到底。
     */
    static int meaningfulChars(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                count++;
                if (count >= MIN_MEANINGFUL_CHARS) {
                    return count;
                }
            }
        }
        return count;
    }
}
