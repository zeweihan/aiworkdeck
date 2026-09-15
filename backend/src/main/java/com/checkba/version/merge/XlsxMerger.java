// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCell;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把另一侧的单元格合进主线侧的 xlsx（spec 2026-09-14 §4.5）。
 *
 * <p>以**主线侧文件为底**逐格改写，不是重新拼一份：表格里除了单元格值以外的一切
 * （条件格式、数据验证、图表、打印区域、冻结窗格、透视表）没人动过就不该被合并动到，
 * 而 POI 重建工作簿必丢这些。所以这里只 {@code setCellValue}/{@code setCellFormula}
 * 那几格，别的一律不碰。
 *
 * <p>{@code decisions} 为空 = 自动合：把 {@link Analysis#otherOnly()} 里的格子全部合入
 * （只有另一侧动过的格子，主线这边不会有东西被盖掉）。非空 = 逐格裁决：
 * 只有明确判给另一侧的格子（{@code side=T action=A}，或等价的「拒绝主线这边」
 * {@code side=M action=R}）才取另一侧的值，其余原样留主线的。
 *
 * <p>另一侧把某一格清空了，在 {@link XlsxCellReader} 的键集里就是「这个键没了」，
 * 所以合并时要把主线那一格删掉，而不是写一个空串进去——空串会在界面上留下一个
 * 「看着是空、其实有格子」的脏格。
 */
public final class XlsxMerger {

    private XlsxMerger() {
    }

    public static byte[] merge(byte[] main, byte[] other, Analysis analysis, List<Decision> decisions) {
        Set<String> keys = keysToTake(analysis, decisions);
        try (XSSFWorkbook mainBook = new XSSFWorkbook(new ByteArrayInputStream(main));
             XSSFWorkbook otherBook = new XSSFWorkbook(new ByteArrayInputStream(other));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            boolean touchedFormula = false;
            for (String key : keys) {
                int bang = key.lastIndexOf('!');
                if (bang <= 0 || bang == key.length() - 1) {
                    continue;
                }
                String sheetName = key.substring(0, bang);
                CellReference ref = new CellReference(key.substring(bang + 1));
                Cell source = cellAt(otherBook.getSheet(sheetName), ref, false);
                if (source == null || source.getCellType() == CellType.BLANK) {
                    removeCell(mainBook.getSheet(sheetName), ref);
                    continue;
                }
                Sheet target = mainBook.getSheet(sheetName);
                if (target == null) {
                    target = mainBook.createSheet(sheetName);
                }
                boolean fresh = cellAt(target, ref, false) == null;
                Cell cell = cellAt(target, ref, true);
                if (fresh) {
                    copyStyle(source, cell);
                }
                touchedFormula |= copyValue(source, cell);
            }
            if (touchedFormula) {
                // 公式格里缓存的是上一版算出来的值，改了公式必须让打开它的程序重算
                mainBook.setForceFormulaRecalculation(true);
            }
            mainBook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("表格合并失败", e);
        }
    }

    /** 自动模式取 {@code otherOnly} 全部；逐格裁决只取明确判给另一侧的那几格。 */
    private static Set<String> keysToTake(Analysis analysis, List<Decision> decisions) {
        Set<String> keys = new LinkedHashSet<>();
        if (decisions == null || decisions.isEmpty()) {
            keys.addAll(analysis.otherOnly());
            return keys;
        }
        // 逐格裁决时另一侧独有的改动仍然自动合入：律师只被问了两边都动过的那几格，
        // 没被问到的不该因为进了裁决界面就丢掉。
        List<String> asked = new ArrayList<>();
        for (Decision decision : decisions) {
            asked.add(decision.key());
            if (takesOther(decision)) {
                keys.add(decision.key());
            }
        }
        for (String key : analysis.otherOnly()) {
            if (!asked.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static boolean takesOther(Decision decision) {
        return ("T".equals(decision.side()) && "A".equals(decision.action()))
                || ("M".equals(decision.side()) && "R".equals(decision.action()));
    }

    private static Cell cellAt(Sheet sheet, CellReference ref, boolean create) {
        if (sheet == null) {
            return null;
        }
        Row row = sheet.getRow(ref.getRow());
        if (row == null) {
            if (!create) {
                return null;
            }
            row = sheet.createRow(ref.getRow());
        }
        Cell cell = row.getCell(ref.getCol());
        if (cell == null && create) {
            cell = row.createCell(ref.getCol());
        }
        return cell;
    }

    private static void removeCell(Sheet sheet, CellReference ref) {
        if (sheet == null) {
            return;
        }
        Row row = sheet.getRow(ref.getRow());
        if (row == null) {
            return;
        }
        Cell cell = row.getCell(ref.getCol());
        if (cell != null) {
            row.removeCell(cell);
        }
    }

    /** @return 这一格写的是公式 */
    private static boolean copyValue(Cell source, Cell target) {
        clearInlineString(target);
        switch (source.getCellType()) {
            case FORMULA -> {
                target.setCellFormula(source.getCellFormula());
                return true;
            }
            case NUMERIC -> target.setCellValue(source.getNumericCellValue());
            case BOOLEAN -> target.setCellValue(source.getBooleanCellValue());
            case ERROR -> target.setCellErrorValue(source.getErrorCellValue());
            default -> target.setCellValue(source.getStringCellValue());
        }
        return false;
    }

    /**
     * 写值前把目标格的<b>内联字符串</b>形态（{@code <c t="inlineStr"><is><t>…</t></is></c>}）拆掉。
     *
     * <p>SXSSF 的 inline string 模式和一部分 JS 导出库就是这么存字符串的。POI 的
     * {@code XSSFCell.setCellValue(String)} 遇到 {@code t="inlineStr"} 只往 {@code <v>} 里写，
     * 压根不动 {@code <is>}；而读回时走的又是 {@code <is>}——于是新值写进去了、读出来还是旧的，
     * 另一侧对这一格的改动被静默吞掉，合并「成功」但内容是错的。
     * 数值/布尔/公式分支不会读错（{@code t} 被改掉了），但旧的 {@code <is>} 会赖在 XML 里，
     * 下一轮读写又可能被翻出来，所以一律先清干净。
     *
     * <p>清成 {@code t="n"} 且没有 {@code <v>}（POI 眼里的空格子），随后的 {@code setCellValue}
     * 就会走正常的共享字符串/数值路径。样式引用 {@code s} 不碰，合并不该动格式。
     */
    private static void clearInlineString(Cell target) {
        if (!(target instanceof XSSFCell xssfCell)) {
            return;
        }
        CTCell ct = xssfCell.getCTCell();
        if (ct.getT() != STCellType.INLINE_STR) {
            return;
        }
        if (ct.isSetIs()) {
            ct.unsetIs();
        }
        if (ct.isSetV()) {
            ct.unsetV();
        }
        ct.setT(STCellType.N);
    }

    /** 主线这边本来就没有这一格（另一侧新增的格子/整张新表）时才带样式过来。 */
    private static void copyStyle(Cell source, Cell target) {
        try {
            XSSFCellStyle style = (XSSFCellStyle) target.getSheet().getWorkbook().createCellStyle();
            style.cloneStyleFrom(source.getCellStyle());
            target.setCellStyle(style);
        } catch (RuntimeException ignored) {
            // 样式带不过来不算合并失败：值合进来了就行，样式退回默认
        }
    }
}
