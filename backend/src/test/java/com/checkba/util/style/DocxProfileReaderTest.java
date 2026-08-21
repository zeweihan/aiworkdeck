package com.checkba.util.style;

import com.checkba.util.style.StyleProfile.Length;
import com.checkba.util.style.StyleProfile.LineSpacing;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对 fixtures/template-sample.docx（gen-template-sample.py 生成）断言 SPEC §3.1 的样本级字段。
 * fixture 刻意与 HOUSE 不同：楷体 12pt 无首行缩进、三级自动编号、单元格级边框、TOC 1-2、页脚页码。
 */
class DocxProfileReaderTest {

    private static InputStream fixture() {
        InputStream in = DocxProfileReaderTest.class.getResourceAsStream("/fixtures/template-sample.docx");
        assertNotNull(in, "fixtures/template-sample.docx 缺失：运行 gen-template-sample.py 生成");
        return in;
    }

    @Test
    @DisplayName("正文：字体槽、字号、对齐、段前后、行距、首行缩进 0")
    void body() throws Exception {
        StyleProfile p = DocxProfileReader.read(fixture());
        StyleProfile.Block body = p.body();
        assertNotNull(body);
        assertEquals("Normal", body.string("styleId"));
        assertEquals("楷体_GB2312", body.font().eastAsia());
        assertEquals("Arial", body.font().western());
        assertEquals(new Length(12, "pt"), body.size());
        assertFalse(body.bold());
        assertEquals("justify", body.alignment());
        assertEquals(new Length(0, "pt"), body.spaceBefore());
        assertEquals(new Length(18, "pt"), body.spaceAfter());
        assertEquals(new LineSpacing("atLeast", 16, "pt"), body.lineSpacing());
        assertEquals(new Length(0, "pt"), body.firstLineIndent());
        assertTrue(body.integer("samples") > 10, "正文段实例数");
        assertEquals(new Length(18, "pt"), body.length("afterTableSpaceBefore"));
    }

    @Test
    @DisplayName("标题：三级自动编号 一、（一）1.，numFmt/lvlText/suffix 来自 numbering.xml")
    void headingsAutoNumbering() throws Exception {
        StyleProfile p = DocxProfileReader.read(fixture());
        assertEquals(3, p.headings().size());

        StyleProfile.Block h1 = p.heading(1);
        assertEquals("Heading1", h1.string("styleId"));
        assertEquals("黑体", h1.font().eastAsia());
        assertEquals(new Length(16, "pt"), h1.size());
        assertTrue(h1.bold());
        assertEquals("center", h1.alignment());
        assertEquals(3, h1.integer("samples"));
        StyleProfile.Block n1 = h1.numbering();
        assertEquals("auto", n1.string("kind"));
        assertEquals("chineseCountingThousand", n1.string("numFmt"));
        assertEquals("%1、", n1.string("lvlText"));
        assertEquals("nothing", n1.string("suffix"));
        assertEquals(1, n1.integer("start"));

        StyleProfile.Block h2 = p.heading(2);
        assertEquals("楷体_GB2312", h2.font().eastAsia());
        assertEquals(new Length(12, "pt"), h2.size());
        assertTrue(h2.bold());
        assertEquals("auto", h2.numbering().string("kind"));
        assertEquals("chineseCountingThousand", h2.numbering().string("numFmt"));
        assertEquals("（%2）", h2.numbering().string("lvlText"));
        assertEquals(6, h2.integer("samples"));

        StyleProfile.Block h3 = p.heading(3);
        assertEquals("auto", h3.numbering().string("kind"));
        assertEquals("decimal", h3.numbering().string("numFmt"));
        assertEquals("%3.", h3.numbering().string("lvlText"));
        assertEquals("space", h3.numbering().string("suffix"));
        assertEquals(12, h3.integer("samples"));

        StyleProfile.Block numbering = p.numbering();
        assertNotNull(numbering, "numbering 块记录标题用的 abstractNum");
        assertTrue(numbering.bool("multilevelLinked"));
        assertEquals(3, numbering.node().get("levels").size());
        assertEquals("Heading2", numbering.node().get("levels").get(1).get("pStyle").asText());
    }

    @Test
    @DisplayName("表格：单元格级边框、gridCol twips、表头粗+底纹+重复、10pt、数字居右、序号居中、垂直居中")
    void table() throws Exception {
        StyleProfile p = DocxProfileReader.read(fixture());
        StyleProfile.Block t = p.table();
        assertNotNull(t);
        assertEquals(1, t.integer("samples"));
        StyleProfile.Block borders = t.sub("borders");
        assertEquals("cell", borders.string("source"));
        assertEquals("single", borders.border("outside").style());
        assertEquals(new Length(0.5, "pt"), borders.border("outside").width());
        assertEquals("#000000", borders.border("outside").color());
        assertEquals(new Length(0.5, "pt"), borders.border("insideH").width());
        assertEquals(new Length(0.5, "pt"), borders.border("insideV").width());

        JsonNode cw = t.node().get("columnWidths");
        assertEquals("twips", cw.get("mode").asText());
        JsonNode sample = cw.get("samples").get(0);
        assertEquals(2019, sample.get(0).asInt());
        assertEquals(3500, sample.get(1).asInt());
        assertEquals(3507, sample.get(2).asInt());

        StyleProfile.Block header = t.sub("header");
        assertTrue(header.bool("bold"));
        assertTrue(header.bool("repeatOnEachPage"));
        assertEquals("#D9D9D9", header.string("fill"));
        assertEquals("center", header.alignment());
        assertEquals("center", header.string("verticalAlign"));
        assertEquals(new Length(10, "pt"), header.size());

        StyleProfile.Block cell = t.sub("cell");
        assertEquals(new Length(10, "pt"), cell.size());
        assertEquals(new LineSpacing("atLeast", 12, "pt"), cell.lineSpacing());
        assertEquals(new Length(2.4, "pt"), cell.spaceBefore());
        assertEquals(new Length(2.4, "pt"), cell.spaceAfter());
        assertEquals(new Length(0, "pt"), cell.firstLineIndent());
        assertEquals("center", cell.string("verticalAlign"));
        StyleProfile.Block byType = cell.sub("byContentType");
        assertEquals("right", byType.sub("number").alignment());
        assertEquals("center", byType.sub("serial").alignment());
        assertEquals("left", byType.sub("text").alignment());
        assertFalse(t.sub("zebra").bool("enabled"));
    }

    @Test
    @DisplayName("页面 / 目录域 / 页脚页码")
    void pageTocFooter() throws Exception {
        StyleProfile p = DocxProfileReader.read(fixture());

        StyleProfile.Block page = p.page();
        assertEquals(new Length(210, "mm"), page.sub("size").length("width"));
        assertEquals(new Length(297, "mm"), page.sub("size").length("height"));
        assertEquals("portrait", page.sub("size").string("orientation"));
        assertEquals(new Length(2.54, "cm"), page.sub("margins").length("top"));
        assertEquals(new Length(3.17, "cm"), page.sub("margins").length("left"));

        StyleProfile.Block toc = p.toc();
        assertNotNull(toc);
        assertTrue(toc.bool("enabled"));
        assertEquals("1-2", toc.string("levels"));
        assertTrue(toc.bool("hyperlinks"));
        assertEquals("目  录", toc.string("title"));

        StyleProfile.Block hf = p.headerFooter();
        assertNotNull(hf);
        assertFalse(hf.sub("header").bool("enabled"));
        StyleProfile.Block footer = hf.sub("footer");
        assertTrue(footer.bool("enabled"));
        assertTrue(footer.sub("pageNumber").bool("enabled"));
        assertEquals("第 {PAGE} 页", footer.sub("pageNumber").string("pattern"));
        assertEquals("center", footer.sub("pageNumber").string("alignment"));
        assertEquals("decimal", footer.sub("pageNumber").string("format"));
        assertEquals(1, footer.sub("pageNumber").integer("start"));
        assertFalse(hf.bool("differentFirstPage"));
    }

    @Test
    @DisplayName("多份：同一份读两遍 → 逐叶子一致、confidence 全 1、learnedFrom 两条")
    void multiVote() throws Exception {
        StyleProfile p = DocxProfileReader.read(List.of(
                new DocxProfileReader.Source(1L, "a.docx", fixture()),
                new DocxProfileReader.Source(2L, "b.docx", fixture())));
        assertEquals(2, p.root().get("learnedFrom").size());
        assertEquals("楷体_GB2312", p.body().font().eastAsia());
        assertEquals("%1、", p.heading(1).numbering().string("lvlText"));
        assertEquals("cell", p.table().sub("borders").string("source"));
        assertEquals(1.0, p.root().get("confidence").get("body").asDouble(), 1e-9);
        assertEquals(1.0, p.root().get("confidence").get("headings").asDouble(), 1e-9);
        assertNotNull(p.root().get("learnedAt"));
    }

    @Test
    @DisplayName("literal 编号：标题文字自带「（一）」且无 numPr → kind=literal")
    void literalNumbering() throws Exception {
        org.docx4j.openpackaging.packages.WordprocessingMLPackage pkg =
                org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
        var mdp = pkg.getMainDocumentPart();
        mdp.addStyledParagraphOfText("Heading1", "一、公司基本情况");
        mdp.addStyledParagraphOfText("Normal", "正文。");
        mdp.addStyledParagraphOfText("Heading1", "二、历史沿革");
        mdp.addStyledParagraphOfText("Heading2", "（一）设立");
        mdp.addStyledParagraphOfText("Heading2", "（二）增资");
        mdp.addStyledParagraphOfText("Heading3", "1. 第一次增资");
        StyleProfile p = DocxProfileReader.readOne(pkg);

        assertEquals("literal", p.heading(1).numbering().string("kind"));
        assertEquals("chineseCounting", p.heading(1).numbering().string("numFmt"));
        assertEquals("%1、", p.heading(1).numbering().string("lvlText"));
        assertEquals("literal", p.heading(2).numbering().string("kind"));
        assertEquals("（%2）", p.heading(2).numbering().string("lvlText"));
        assertEquals("literal", p.heading(3).numbering().string("kind"));
        assertEquals("decimal", p.heading(3).numbering().string("numFmt"));
        assertEquals("%3.", p.heading(3).numbering().string("lvlText"));
        assertTrue(p.root().has("notes"));
    }

    @Test
    @DisplayName("单元格内容分类：数字 / 日期 / 序号 / 文字")
    void cellClassification() {
        assertEquals("number", DocxProfileReader.classifyCell("1,000.00"));
        assertEquals("number", DocxProfileReader.classifyCell("-12.5%"));
        assertEquals("number", DocxProfileReader.classifyCell("（3,200）"));
        assertEquals("date", DocxProfileReader.classifyCell("2024年1月1日"));
        assertEquals("date", DocxProfileReader.classifyCell("2024-01-01"));
        assertEquals("serial", DocxProfileReader.classifyCell("1"));
        assertEquals("serial", DocxProfileReader.classifyCell("（1）"));
        assertEquals("serial", DocxProfileReader.classifyCell("①"));
        assertEquals("text", DocxProfileReader.classifyCell("注册资本"));
    }
}
