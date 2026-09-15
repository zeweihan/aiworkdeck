// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把 docx 拆成比对单元。
 *
 * <p>段落键 {@code p{i}} 的 i 是**正文顶层段落**的枚举序（表格里的段落不占号），
 * 与引擎 {@code get_paragraph}/{@code select_paragraph} 的下标同构——重放计划按这个坐标写，
 * 错一位就写错段，所以这条是跨端契约，不是实现细节。
 *
 * <p>表格单元 {@code t{表序}.{行}.{列}} 紧跟在该表在 body 元素序里的位置之后，
 * 与段落混在同一条序列里。于是「同事改了表里一格、我改了表上面那段」天然是相邻，
 * 按 {@link ThreeWayAnalyzer} 的口径进人工裁决——这是刻意的。
 *
 * <p>不读 {@code w14:paraId}：引擎导出 docx 会整类丢弃它（spike B1），靠它对齐必然假绿。
 */
public final class DocxUnitReader {

    /** Unicode 分隔符 + 常规空白，折叠成一个半角空格。 */
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");

    private DocxUnitReader() {
    }

    public static List<Unit> read(byte[] docx) throws IOException {
        List<Unit> units = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            int paragraphIndex = 0;
            int tableIndex = 0;
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    units.add(unit("p" + paragraphIndex, paragraph.getText()));
                    paragraphIndex++;
                } else if (element instanceof XWPFTable table) {
                    List<XWPFTableRow> rows = table.getRows();
                    for (int r = 0; r < rows.size(); r++) {
                        List<XWPFTableCell> cells = rows.get(r).getTableCells();
                        for (int c = 0; c < cells.size(); c++) {
                            units.add(unit("t" + tableIndex + "." + r + "." + c, cells.get(c).getText()));
                        }
                    }
                    tableIndex++;
                }
            }
        }
        return units;
    }

    /** 去首尾空白、连续空白折一个、NFC。比对与溯源一律按这个结果。 */
    public static String normalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String nfc = Normalizer.normalize(s, Normalizer.Form.NFC);
        return WHITESPACE.matcher(nfc).replaceAll(" ").trim();
    }

    private static Unit unit(String key, String text) {
        String raw = text == null ? "" : text;
        return new Unit(key, raw, normalize(raw));
    }
}
