// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class XlsxMergerTest {

    @Test
    @DisplayName("自动模式把只有另一侧改过的格子合进来，主线那一格的样式原样留着")
    void autoAppliesOtherOnlyCellsKeepingStyles() throws Exception {
        byte[] base = MergeFixtures.xlsxBold("Sheet1", MergeFixtures.cells(
                "A1", "甲方",
                "B2", "100",
                "C3", "备注"), Set.of("B2"));
        byte[] main = MergeFixtures.xlsxBold("Sheet1", MergeFixtures.cells(
                "A1", "甲方（受让人）",
                "B2", "100",
                "C3", "备注"), Set.of("B2"));
        byte[] other = MergeFixtures.xlsxBold("Sheet1", MergeFixtures.cells(
                "A1", "甲方",
                "B2", "200"), Set.of("B2"));

        Analysis analysis = ThreeWayAnalyzer.analyze("台账.xlsx", base, main, other);
        assertEquals(MergeDecision.AUTO, analysis.decision(), "两边改的不是同一格，应当能自动合");

        byte[] merged = XlsxMerger.merge(main, other, analysis, List.of());

        Map<String, String> cells = XlsxCellReader.read(merged);
        assertEquals("甲方（受让人）", cells.get("Sheet1!A1"), "只有主线改过的格子保持主线的值");
        assertEquals("200", cells.get("Sheet1!B2"), "只有另一侧改过的格子应当合进来");
        assertFalse(cells.containsKey("Sheet1!C3"), "另一侧删掉的格子应当跟着删掉");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(merged))) {
            Cell b2 = wb.getSheet("Sheet1").getRow(1).getCell(1);
            assertTrue(((org.apache.poi.xssf.usermodel.XSSFCellStyle) b2.getCellStyle()).getFont().getBold(),
                    "合进来的值不该把这一格原有的样式冲掉");
        }
    }

    @Test
    @DisplayName("合进来的是公式本身，不是算出来的值")
    void formulaPreserved() throws Exception {
        byte[] base = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells(
                "A1", "10",
                "A2", "20",
                "A3", "=A1+A2"));
        byte[] main = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells(
                "A1", "11",
                "A2", "20",
                "A3", "=A1+A2"));
        byte[] other = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells(
                "A1", "10",
                "A2", "20",
                "A3", "=SUM(A1:A2)*2"));

        Analysis analysis = ThreeWayAnalyzer.analyze("台账.xlsx", base, main, other);
        byte[] merged = XlsxMerger.merge(main, other, analysis, List.of());

        Map<String, String> cells = XlsxCellReader.read(merged);
        assertEquals("=SUM(A1:A2)*2", cells.get("Sheet1!A3"));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(merged))) {
            assertEquals("SUM(A1:A2)*2", wb.getSheet("Sheet1").getRow(2).getCell(0).getCellFormula());
        }
    }

    @Test
    @DisplayName("逐格裁决：判给另一侧的取另一侧、判给主线的原样不动")
    void manualPerCellChoice() throws Exception {
        byte[] base = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells(
                "A1", "甲",
                "B2", "100"));
        byte[] main = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells(
                "A1", "甲方",
                "B2", "111"));
        byte[] other = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells(
                "A1", "甲乙",
                "B2", "222"));

        Analysis analysis = ThreeWayAnalyzer.analyze("台账.xlsx", base, main, other);
        assertEquals(MergeDecision.MANUAL, analysis.decision(), "同一格两边都改了，应当交给律师逐格裁决");

        byte[] merged = XlsxMerger.merge(main, other, analysis, List.of(
                new Decision("Sheet1!A1", "T", "A"),
                new Decision("Sheet1!B2", "M", "A")));

        Map<String, String> cells = XlsxCellReader.read(merged);
        assertEquals("甲乙", cells.get("Sheet1!A1"));
        assertEquals("111", cells.get("Sheet1!B2"));
    }

    @Test
    @DisplayName("另一侧新增的整张工作表整表复制过来")
    void newSheetCopied() throws Exception {
        Map<String, Map<String, String>> baseSheets = new LinkedHashMap<>();
        baseSheets.put("Sheet1", MergeFixtures.cells("A1", "甲方"));
        Map<String, Map<String, String>> mainSheets = new LinkedHashMap<>();
        mainSheets.put("Sheet1", MergeFixtures.cells("A1", "甲方（受让人）"));
        Map<String, Map<String, String>> otherSheets = new LinkedHashMap<>();
        otherSheets.put("Sheet1", MergeFixtures.cells("A1", "甲方"));
        otherSheets.put("附表", MergeFixtures.cells("A1", "标的清单", "B1", "=1+1"));

        byte[] base = MergeFixtures.xlsxSheets(baseSheets);
        byte[] main = MergeFixtures.xlsxSheets(mainSheets);
        byte[] other = MergeFixtures.xlsxSheets(otherSheets);

        Analysis analysis = ThreeWayAnalyzer.analyze("台账.xlsx", base, main, other);
        byte[] merged = XlsxMerger.merge(main, other, analysis, List.of());

        Map<String, String> cells = XlsxCellReader.read(merged);
        assertEquals("甲方（受让人）", cells.get("Sheet1!A1"));
        assertEquals("标的清单", cells.get("附表!A1"), "另一侧新增的工作表应当整表带过来");
        assertEquals("=1+1", cells.get("附表!B1"));
    }

    @Test
    @DisplayName("字符串存成内联形态（inlineStr）时，另一侧的改动照样合得进来")
    void inlineStringCellsTakeOtherSideValue() throws Exception {
        byte[] base = MergeFixtures.xlsxInline("Sheet1", MergeFixtures.cells(
                "A1", "甲方",
                "C3", "验收合格后六十日内"));
        byte[] main = MergeFixtures.xlsxInline("Sheet1", MergeFixtures.cells(
                "A1", "甲方",
                "C3", "验收合格后六十日内"));
        byte[] other = MergeFixtures.xlsxInline("Sheet1", MergeFixtures.cells(
                "A1", "甲方",
                "C3", "验收合格后三十日内"));

        // 夹具本身得先是内联形态，不然这条用例证明不了任何事
        assertTrue(isInlineString(main, "Sheet1", "C3"), "主线侧 C3 应当是 inlineStr 形态");
        assertTrue(isInlineString(other, "Sheet1", "C3"), "另一侧 C3 应当是 inlineStr 形态");

        Analysis analysis = ThreeWayAnalyzer.analyze("台账.xlsx", base, main, other);
        assertNotEquals(MergeDecision.MANUAL, analysis.decision(), "主线没动过这一格，不该要人裁决");
        assertTrue(analysis.otherOnly().contains("Sheet1!C3"), "C3 应当被判成只有另一侧改过");

        byte[] merged = XlsxMerger.merge(main, other, analysis, List.of());

        assertEquals("验收合格后三十日内", XlsxCellReader.read(merged).get("Sheet1!C3"),
                "内联字符串格被 POI 静默吞掉了另一侧的改动");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(merged))) {
            XSSFCell c3 = (XSSFCell) wb.getSheet("Sheet1").getRow(2).getCell(2);
            assertEquals("验收合格后三十日内", c3.getStringCellValue(), "直接读这一格也应当是新值");
            assertFalse(c3.getCTCell().isSetIs(), "写完新值后不该还留着旧的 <is> 内联串");
        }
        // 再往返导出一次：残留的 <is> 会在下一次读写里翻出来，所以回环也要验
        assertEquals("验收合格后三十日内", XlsxCellReader.read(roundTrip(merged)).get("Sheet1!C3"),
                "往返导出一次后内联字符串格又变回旧值");
    }

    @Test
    @DisplayName("内联字符串格被另一侧改成数字时，写进去的是数字、旧内联串不残留")
    void inlineStringCellOverwrittenByNumber() throws Exception {
        byte[] base = MergeFixtures.xlsxInline("Sheet1", MergeFixtures.cells(
                "A1", "工期",
                "B1", "60"));
        byte[] main = MergeFixtures.xlsxInline("Sheet1", MergeFixtures.cells(
                "A1", "工期",
                "B1", "60"));
        byte[] other = withNumber(MergeFixtures.xlsxInline("Sheet1", MergeFixtures.cells(
                "A1", "工期",
                "B1", "60")), "Sheet1", "B1", 30d);

        Analysis analysis = ThreeWayAnalyzer.analyze("台账.xlsx", base, main, other);
        assertTrue(analysis.otherOnly().contains("Sheet1!B1"), "B1 应当被判成只有另一侧改过");

        byte[] merged = XlsxMerger.merge(main, other, analysis, List.of());

        assertEquals("30", XlsxCellReader.read(merged).get("Sheet1!B1"));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(merged))) {
            XSSFCell b1 = (XSSFCell) wb.getSheet("Sheet1").getRow(0).getCell(1);
            assertEquals(CellType.NUMERIC, b1.getCellType(), "合进来的是数字，这一格就该是数值格");
            assertEquals(30d, b1.getNumericCellValue(), 1e-9);
            assertFalse(b1.getCTCell().isSetIs(), "数值格上不该挂着旧的 <is> 内联串");
        }
        assertEquals("30", XlsxCellReader.read(roundTrip(merged)).get("Sheet1!B1"));
    }

    /** 这一格是不是 {@code <c t="inlineStr"><is><t>…</t></is></c>} 形态。 */
    private static boolean isInlineString(byte[] xlsx, String sheetName, String address) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            CellReference ref = new CellReference(address);
            XSSFCell cell = (XSSFCell) wb.getSheet(sheetName).getRow(ref.getRow()).getCell(ref.getCol());
            return cell.getCTCell().getT() == STCellType.INLINE_STR && cell.getCTCell().isSetIs();
        }
    }

    /** 把某一格换成数值格（先清掉内联形态，免得夹具自己就踩上被测的坑）。 */
    private static byte[] withNumber(byte[] xlsx, String sheetName, String address, double value) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellReference ref = new CellReference(address);
            Row row = wb.getSheet(sheetName).getRow(ref.getRow());
            XSSFCell cell = (XSSFCell) row.getCell(ref.getCol());
            if (cell.getCTCell().isSetIs()) {
                cell.getCTCell().unsetIs();
            }
            cell.getCTCell().setT(STCellType.N);
            cell.setCellValue(value);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 原样读进来再写出去一遍。 */
    private static byte[] roundTrip(byte[] xlsx) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }
}
