// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元键的语义是跨端契约：{@code p{i}} 的 i 必须与引擎 {@code get_paragraph} 的下标同构
 * （正文顶层段落枚举序，表格里的段落不计入），否则重放计划会写错段。
 */
class DocxUnitReaderTest {

    @Test
    @DisplayName("正文段落按引擎下标顺序给 p{i} 键，表格里的段落不占段号")
    void bodyParagraphsKeepEngineOrder() throws Exception {
        byte[] bytes = MergeFixtures.docxWithTable(
                List.of("第一段", "第二段"),
                new String[][]{{"甲", "乙"}},
                List.of("第三段"));

        List<Unit> units = DocxUnitReader.read(bytes);

        List<String> paragraphKeys = units.stream().map(Unit::key).filter(k -> k.startsWith("p")).toList();
        assertEquals(List.of("p0", "p1", "p2"), paragraphKeys);
        assertEquals("第一段", units.stream().filter(u -> u.key().equals("p0")).findFirst().orElseThrow().text());
        assertEquals("第二段", units.stream().filter(u -> u.key().equals("p1")).findFirst().orElseThrow().text());
        assertEquals("第三段", units.stream().filter(u -> u.key().equals("p2")).findFirst().orElseThrow().text());
    }

    @Test
    @DisplayName("表格单元紧跟它在正文里的位置，键是 t{表序}.{行}.{列}")
    void tableCellsFollowTheirTable() throws Exception {
        byte[] bytes = MergeFixtures.docxWithTable(
                List.of("第一段"),
                new String[][]{{"甲", "乙"}, {"丙", "丁"}},
                List.of("第二段"));

        List<Unit> units = DocxUnitReader.read(bytes);

        assertEquals(
                List.of("p0", "t0.0.0", "t0.0.1", "t0.1.0", "t0.1.1", "p1"),
                units.stream().map(Unit::key).toList());
        assertEquals("丙", units.get(3).text());
    }

    @Test
    @DisplayName("归一化：折叠连续空白、去首尾、NFC")
    void normalizeCollapsesWhitespaceAndNfc() {
        assertEquals("甲 乙 丙", DocxUnitReader.normalize("  甲 \t 乙　丙  "));
        assertEquals("甲乙", DocxUnitReader.normalize("甲乙"));
        assertEquals("", DocxUnitReader.normalize("   "));
        assertEquals("", DocxUnitReader.normalize(null));
        // e + 组合尖音符 → NFC 的单码点 é
        assertEquals("é", DocxUnitReader.normalize("é"));
        assertEquals(DocxUnitReader.normalize("é"), DocxUnitReader.normalize("é"));
    }
}
