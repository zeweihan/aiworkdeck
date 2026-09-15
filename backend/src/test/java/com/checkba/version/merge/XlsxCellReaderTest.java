// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XlsxCellReaderTest {

    @Test
    @DisplayName("公式单元格取公式字符串并带上等号，不取算出来的值")
    void formulasKeepLeadingEquals() throws Exception {
        byte[] bytes = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells(
                "A2", "=1+2",
                "B7", "合同金额"));

        Map<String, String> cells = XlsxCellReader.read(bytes);

        assertEquals("=1+2", cells.get("Sheet1!A2"));
        assertEquals("合同金额", cells.get("Sheet1!B7"));
    }

    @Test
    @DisplayName("空单元格不进 map")
    void emptyCellsAreAbsent() throws Exception {
        byte[] bytes = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells(
                "A1", "甲",
                "B1", "",
                "C1", "丙"));

        Map<String, String> cells = XlsxCellReader.read(bytes);

        assertTrue(cells.containsKey("Sheet1!A1"));
        assertFalse(cells.containsKey("Sheet1!B1"), "空字符串单元格不该出现在 map 里");
        assertFalse(cells.containsKey("Sheet1!D1"), "从没写过的单元格不该出现在 map 里");
        assertEquals(2, cells.size());
    }
}
