package com.checkba.util.style;

import com.checkba.util.style.StyleProfile.Length;
import com.checkba.util.style.StyleProfile.LineSpacing;
import org.docx4j.wml.STLineSpacingRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 画像模型：解析 HOUSE 单源、叶子合并、单位换算向量。
 */
class StyleProfileTest {

    @Test
    @DisplayName("house-default.json 能解析，且数值与 DocxStyleHelper 旧常量一致")
    void houseDefaultParses() {
        StyleProfile p = StyleProfiles.houseDefault();
        assertEquals(1, p.schemaVersion());

        StyleProfile.Block body = p.body();
        assertNotNull(body);
        assertEquals("楷体_GB2312", body.font().eastAsia());
        assertEquals("Arial", body.font().western());
        assertEquals(new Length(12, "pt"), body.size());
        assertEquals("justify", body.alignment());
        assertEquals(new LineSpacing("atLeast", 16, "pt"), body.lineSpacing());
        assertEquals(new Length(0, "pt"), body.spaceBefore());
        assertEquals(new Length(18, "pt"), body.spaceAfter());
        assertEquals(new Length(2, "chars"), body.firstLineIndent());
        assertEquals(new Length(18, "pt"), body.length("afterTableSpaceBefore"));

        StyleProfile.Block h1 = p.heading(1);
        assertEquals(new Length(16, "pt"), h1.size());
        assertTrue(h1.bold());
        assertEquals("center", h1.alignment());
        assertEquals(new Length(0, "pt"), h1.firstLineIndent());
        assertEquals("none", h1.numbering().string("kind"));

        for (int i = 2; i <= 6; i++) {
            StyleProfile.Block h = p.heading(i);
            assertNotNull(h, "Heading" + i);
            assertEquals(new Length(12, "pt"), h.size());
            assertTrue(h.bold());
            assertEquals("justify", h.alignment());
            assertEquals(new Length(2, "chars"), h.firstLineIndent());
        }
        assertNull(p.heading(7));

        StyleProfile.Block table = p.table();
        StyleProfile.Border outside = table.sub("borders").border("outside");
        assertEquals("single", outside.style());
        assertEquals(new Length(1.5, "pt"), outside.width());
        assertEquals("#000000", outside.color());
        assertEquals(new Length(10, "pt"), table.sub("cell").size());
        assertEquals(new LineSpacing("atLeast", 12, "pt"), table.sub("cell").lineSpacing());
        assertEquals(new Length(0.2, "lines"), table.sub("cell").spaceBefore());
        assertEquals("right", table.sub("cell").sub("byContentType").sub("number").alignment());
        assertTrue(table.sub("header").bold());

        // 旧 applyStandardFormat 不写页面/页眉页脚/目录——缺省即不约束
        assertNull(p.page());
        assertNull(p.headerFooter());
        assertNull(p.toc());
    }

    @Test
    @DisplayName("merge：叶子覆盖、对象递归、headings 按 level 合并不丢其余级")
    void mergeLeafOverride() {
        StyleProfile house = StyleProfiles.houseDefault();
        StyleProfile over = StyleProfiles.parse("""
                {"body": {"font": {"eastAsia": "宋体"}, "firstLineIndent": {"value": 0, "unit": "pt"}},
                 "headings": [{"level": 1, "numbering": {"kind": "auto", "numFmt": "chineseCounting", "lvlText": "%1、"}}],
                 "toc": {"enabled": true, "levels": "1-2"}}
                """);
        StyleProfile merged = house.merge(over);

        assertEquals("宋体", merged.body().font().eastAsia());
        assertEquals("Arial", merged.body().font().western(), "未覆盖的叶子保留");
        assertEquals(new Length(0, "pt"), merged.body().firstLineIndent());
        assertEquals(new Length(18, "pt"), merged.body().spaceAfter());

        assertEquals("auto", merged.heading(1).numbering().string("kind"));
        assertEquals("%1、", merged.heading(1).numbering().string("lvlText"));
        assertEquals(new Length(16, "pt"), merged.heading(1).size(), "同级其它叶子保留");
        assertNotNull(merged.heading(6), "未覆盖的级别保留");
        assertEquals(6, merged.headings().size());

        assertTrue(merged.toc().bool("enabled"));
        assertEquals("1-2", merged.toc().string("levels"));

        // 两边都没被改
        assertEquals("楷体_GB2312", house.body().font().eastAsia());
        assertNull(over.body().spaceAfter());
    }

    @Test
    @DisplayName("单位换算向量")
    void unitVectors() {
        assertEquals(480, Units.toTwips(new Length(2, "chars"), 12));
        assertEquals(200, Units.charsToFirstLineChars(new Length(2, "chars")));
        assertNull(Units.charsToFirstLineChars(new Length(2, "pt")));

        assertEquals(360, Units.toTwips(new Length(18, "pt"), 12));
        assertEquals(24, Units.toHalfPoints(new Length(12, "pt")));
        assertEquals(12, Units.toEighthPoints(new Length(1.5, "pt")));
        assertEquals(48, Units.toTwips(new Length(0.2, "lines"), 10), "0.2 行 @10pt = 2.4 磅 = 48 twips");
        assertEquals(20, Units.linesToHundredths(new Length(0.2, "lines")));
        assertEquals(1440, Units.toTwips(new Length(2.54, "cm"), 12));
        assertEquals(567, Units.toTwips(new Length(10, "mm"), 12));
        assertEquals(new Length(1.5, "pt"), Units.twipsToPt(30));

        // 16pt atLeast → spacing line=320 lineRule=AT_LEAST
        LineSpacing ls = new LineSpacing("atLeast", 16, "pt");
        DocxStyleWriter.SpacingValue sv = DocxStyleWriter.lineSpacing(ls, 12);
        assertEquals(320, sv.line());
        assertEquals(STLineSpacingRule.AT_LEAST, sv.rule());
        // 1.5 倍 → 360 AUTO
        sv = DocxStyleWriter.lineSpacing(new LineSpacing("auto", 1.5, null), 12);
        assertEquals(360, sv.line());
        assertEquals(STLineSpacingRule.AUTO, sv.rule());
        sv = DocxStyleWriter.lineSpacing(new LineSpacing("exactly", 20, "pt"), 12);
        assertEquals(400, sv.line());
        assertEquals(STLineSpacingRule.EXACT, sv.rule());
    }

    @Test
    @DisplayName("parse 拒绝空串与非对象")
    void parseRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> StyleProfiles.parse(""));
        assertThrows(IllegalArgumentException.class, () -> StyleProfiles.parse("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> StyleProfiles.parse("{not json"));
    }
}
