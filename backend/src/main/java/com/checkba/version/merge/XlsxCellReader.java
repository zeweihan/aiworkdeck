// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 xlsx 拆成 {@code Sheet1!B7 -> 值} 的单元格表。
 *
 * <p>公式格取**公式字符串**（带前导等号）而不是算出来的值：两位律师改的是公式本身，
 * 比对算出来的值会把「公式换了但结果碰巧一样」判成没改。空格子不进 map，
 * 于是「新增一格」与「删除一格」在键集上就是有/无，判定不需要额外分支。
 */
public final class XlsxCellReader {

    private XlsxCellReader() {
    }

    public static Map<String, String> read(byte[] xlsx) throws IOException {
        Map<String, String> cells = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            for (Sheet sheet : workbook) {
                String sheetName = sheet.getSheetName();
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String value = valueOf(cell, formatter);
                        if (value.isEmpty()) {
                            continue;
                        }
                        cells.put(sheetName + "!" + cell.getAddress().formatAsString(), value);
                    }
                }
            }
        }
        return cells;
    }

    private static String valueOf(Cell cell, DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            return "=" + cell.getCellFormula();
        }
        String text = formatter.formatCellValue(cell);
        return text == null ? "" : text;
    }
}
