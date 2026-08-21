package com.checkba.util.style;

import com.checkba.service.ai.AiDocxExportService;
import com.checkba.util.DocxStyleHelper;
import com.checkba.util.style.StyleProfile.Length;
import com.checkba.util.style.StyleProfile.LineSpacing;
import com.fasterxml.jackson.databind.JsonNode;
import com.vladsch.flexmark.docx.converter.DocxRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * applyProfile 写端：用样本级画像（楷体 12 / 无首行 / 段后 18 / 三级自动编号 / 单元格边框 /
 * 列宽 2019+7007 / TOC 1-2 / 页脚页码）渲染一段 markdown，保存后用 DocxProfileReader 回读，字段相等。
 */
class DocxStyleHelperProfileTest {

    static final String SAMPLE_PROFILE = """
            {
              "body": {"font": {"eastAsia": "KaiTi_GB2312", "western": "Arial"}, "size": {"value": 12, "unit": "pt"},
                       "alignment": "justify", "lineSpacing": {"rule": "atLeast", "value": 16, "unit": "pt"},
                       "spaceBefore": {"value": 0, "unit": "pt"}, "spaceAfter": {"value": 18, "unit": "pt"},
                       "firstLineIndent": {"value": 0, "unit": "pt"}, "afterTableSpaceBefore": {"value": 18, "unit": "pt"}},
              "headings": [
                {"level": 1, "font": {"eastAsia": "KaiTi_GB2312", "western": "Arial"}, "size": {"value": 12, "unit": "pt"}, "bold": true,
                 "alignment": "justify", "firstLineIndent": {"value": 0, "unit": "pt"},
                 "numbering": {"kind": "auto", "numFmt": "chineseCountingThousand", "lvlText": "%1、", "suffix": "nothing"}},
                {"level": 2, "font": {"eastAsia": "KaiTi_GB2312", "western": "Arial"}, "size": {"value": 12, "unit": "pt"}, "bold": true,
                 "alignment": "justify", "firstLineIndent": {"value": 0, "unit": "pt"},
                 "numbering": {"kind": "auto", "numFmt": "chineseCountingThousand", "lvlText": "（%2）", "suffix": "nothing"}},
                {"level": 3, "font": {"eastAsia": "KaiTi_GB2312", "western": "Arial"}, "size": {"value": 12, "unit": "pt"}, "bold": true,
                 "alignment": "justify", "firstLineIndent": {"value": 0, "unit": "pt"},
                 "numbering": {"kind": "auto", "numFmt": "decimal", "lvlText": "%3.", "suffix": "space"}}
              ],
              "table": {
                "borders": {"source": "cell",
                            "outside": {"style": "single", "width": {"value": 0.5, "unit": "pt"}, "color": "#000000"},
                            "insideH": {"style": "single", "width": {"value": 0.5, "unit": "pt"}, "color": "#000000"},
                            "insideV": {"style": "single", "width": {"value": 0.5, "unit": "pt"}, "color": "#000000"}},
                "header": {"rows": 1, "repeatOnEachPage": true, "bold": true, "alignment": "center", "verticalAlign": "center", "fill": "#D9D9D9"},
                "cell": {"size": {"value": 10, "unit": "pt"}, "lineSpacing": {"rule": "atLeast", "value": 12, "unit": "pt"},
                         "spaceBefore": {"value": 2.4, "unit": "pt"}, "spaceAfter": {"value": 2.4, "unit": "pt"},
                         "firstLineIndent": {"value": 0, "unit": "pt"}, "verticalAlign": "center",
                         "byContentType": {"text": {"alignment": "left"}, "number": {"alignment": "right"}, "serial": {"alignment": "center"}}},
                "columnWidths": {"mode": "twips", "samples": [[2019, 7007]]}
              },
              "page": {"size": {"width": {"value": 210, "unit": "mm"}, "height": {"value": 297, "unit": "mm"}, "orientation": "portrait"},
                       "margins": {"top": {"value": 2.54, "unit": "cm"}, "bottom": {"value": 2.54, "unit": "cm"}, "left": {"value": 3.17, "unit": "cm"}, "right": {"value": 3.17, "unit": "cm"}}},
              "headerFooter": {"footer": {"enabled": true, "pageNumber": {"enabled": true, "pattern": "第 {PAGE} 页 共 {NUMPAGES} 页", "alignment": "center"}}},
              "toc": {"enabled": true, "levels": "1-2", "hyperlinks": true, "title": "目  录"}
            }
            """;

    static final String MD = """
            # 法律尽职调查报告

            ## 公司基本情况

            正文一段。

            ### 设立情况

            正文两段。

            #### 第一次增资

            正文三段。

            | 项目 | 金额（万元） |
            |---|---|
            | 注册资本 | 1,000.00 |
            | 实缴资本 | 800.00 |

            表后首段。
            """;

    static byte[] render(String md, StyleProfile profile) throws Exception {
        MutableDataSet options = AiDocxExportService.markdownOptions();
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxStyleHelper.addMissingStyles(pkg);
        DocxRenderer.builder(options).build().render(Parser.builder(options).build().parse(md), pkg);
        DocxStyleHelper.applyProfile(pkg, profile);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        pkg.save(bos);
        return bos.toByteArray();
    }

    @Test
    @DisplayName("样本级画像写入后回读：正文/标题编号/表格/页面/页脚/目录字段相等")
    void roundTrip() throws Exception {
        StyleProfile profile = StyleProfiles.houseDefault().merge(StyleProfiles.parse(SAMPLE_PROFILE));
        byte[] docx = render(MD, profile);
        StyleProfile back = DocxProfileReader.read(new ByteArrayInputStream(docx));

        StyleProfile.Block body = back.body();
        assertEquals("KaiTi_GB2312", body.font().eastAsia());
        assertEquals("Arial", body.font().western());
        assertEquals(new Length(12, "pt"), body.size());
        assertEquals("justify", body.alignment());
        assertEquals(new Length(18, "pt"), body.spaceAfter());
        assertEquals(new LineSpacing("atLeast", 16, "pt"), body.lineSpacing());
        assertEquals(new Length(0, "pt"), body.firstLineIndent());
        assertEquals(new Length(18, "pt"), body.length("afterTableSpaceBefore"));

        StyleProfile.Block h1 = back.heading(1);
        assertEquals("auto", h1.numbering().string("kind"));
        assertEquals("chineseCountingThousand", h1.numbering().string("numFmt"));
        assertEquals("%1、", h1.numbering().string("lvlText"));
        assertEquals("nothing", h1.numbering().string("suffix"));
        assertEquals(new Length(12, "pt"), h1.size());
        assertTrue(h1.bold());
        assertEquals("auto", back.heading(2).numbering().string("kind"));
        assertEquals("（%2）", back.heading(2).numbering().string("lvlText"));
        assertEquals("auto", back.heading(3).numbering().string("kind"));
        assertEquals("decimal", back.heading(3).numbering().string("numFmt"));
        assertEquals("%3.", back.heading(3).numbering().string("lvlText"));
        assertEquals("space", back.heading(3).numbering().string("suffix"));
        // 画像没学到的 4 级：HOUSE 补（与正文同款但加粗），不带编号
        assertEquals("none", back.heading(4).numbering().string("kind"));
        assertTrue(back.heading(4).bold());

        StyleProfile.Block table = back.table();
        assertEquals("cell", table.sub("borders").string("source"));
        assertEquals(new Length(0.5, "pt"), table.sub("borders").border("outside").width());
        assertEquals(new Length(0.5, "pt"), table.sub("borders").border("insideH").width());
        JsonNode sample = table.node().get("columnWidths").get("samples").get(0);
        assertEquals(2019, sample.get(0).asInt());
        assertEquals(7007, sample.get(1).asInt());
        assertTrue(table.sub("header").bool("bold"));
        assertTrue(table.sub("header").bool("repeatOnEachPage"));
        assertEquals("#D9D9D9", table.sub("header").string("fill"));
        assertEquals("center", table.sub("header").alignment());
        assertEquals(new Length(10, "pt"), table.sub("cell").size());
        assertEquals(new LineSpacing("atLeast", 12, "pt"), table.sub("cell").lineSpacing());
        assertEquals(new Length(2.4, "pt"), table.sub("cell").spaceBefore());
        assertEquals("center", table.sub("cell").string("verticalAlign"));
        assertEquals("right", table.sub("cell").sub("byContentType").sub("number").alignment());
        assertEquals("left", table.sub("cell").sub("byContentType").sub("text").alignment());

        StyleProfile.Block page = back.page();
        assertEquals(new Length(210, "mm"), page.sub("size").length("width"));
        assertEquals(new Length(3.17, "cm"), page.sub("margins").length("left"));

        StyleProfile.Block footer = back.headerFooter().sub("footer");
        assertTrue(footer.sub("pageNumber").bool("enabled"));
        assertEquals("第 {PAGE} 页 共 {NUMPAGES} 页", footer.sub("pageNumber").string("pattern"));
        assertEquals("center", footer.sub("pageNumber").string("alignment"));

        StyleProfile.Block toc = back.toc();
        assertTrue(toc.bool("enabled"));
        assertEquals("1-2", toc.string("levels"));
        assertTrue(toc.bool("hyperlinks"));
        assertEquals("目  录", toc.string("title"));
    }

    @Test
    @DisplayName("literal 编号：按 lvlText 拼字面前缀，计数逐级复位，文字自带编号的不重复拼")
    void literalNumbering() throws Exception {
        String profileJson = """
                {"headings": [
                  {"level": 1, "numbering": {"kind": "literal", "numFmt": "chineseCounting", "lvlText": "%1、", "suffix": "nothing"}},
                  {"level": 2, "numbering": {"kind": "literal", "numFmt": "chineseCounting", "lvlText": "（%2）", "suffix": "nothing"}},
                  {"level": 3, "numbering": {"kind": "literal", "numFmt": "decimal", "lvlText": "%3.", "suffix": "space"}}
                ]}
                """;
        String md = """
                # 总标题

                ## 公司基本情况

                ### 设立

                ### 增资

                ## 二、历史沿革

                ### 改制

                #### 不编号的四级
                """;
        StyleProfile profile = StyleProfiles.houseDefault().merge(StyleProfiles.parse(profileJson));
        WordprocessingMLPackage pkg = WordprocessingMLPackage.load(new ByteArrayInputStream(render(md, profile)));
        List<String> headings = new ArrayList<>();
        for (Object o : pkg.getMainDocumentPart().getContent()) {
            Object u = XmlUtils.unwrap(o);
            if (u instanceof P p && p.getPPr() != null && p.getPPr().getPStyle() != null
                    && p.getPPr().getPStyle().getVal().startsWith("Heading")) {
                headings.add(text(p));
            }
        }
        assertEquals(List.of("一、总标题", "（一）公司基本情况", "1. 设立", "2. 增资", "二、历史沿革", "1. 改制", "不编号的四级"), headings);
    }

    @Test
    @DisplayName("auto 编号：markdown 里手打的「一、」被剥掉，由自动编号接管")
    void autoStripsLiteral() throws Exception {
        String profileJson = """
                {"headings": [{"level": 1, "numbering": {"kind": "auto", "numFmt": "chineseCounting", "lvlText": "%1、"}}]}
                """;
        StyleProfile profile = StyleProfiles.houseDefault().merge(StyleProfiles.parse(profileJson));
        WordprocessingMLPackage pkg = WordprocessingMLPackage.load(new ByteArrayInputStream(render("# 一、公司基本情况\n\n正文。\n", profile)));
        P first = null;
        for (Object o : pkg.getMainDocumentPart().getContent()) {
            Object u = XmlUtils.unwrap(o);
            if (u instanceof P p) { first = p; break; }
        }
        assertNotNull(first);
        assertEquals("公司基本情况", text(first));
        assertNotNull(pkg.getMainDocumentPart().getNumberingDefinitionsPart(), "建了 numbering part");
        StyleProfile back = DocxProfileReader.readOne(pkg);
        assertEquals("auto", back.heading(1).numbering().string("kind"));
        assertEquals("chineseCounting", back.heading(1).numbering().string("numFmt"));
    }

    @Test
    @DisplayName("中文数字与编号格式化")
    void numberFormatting() {
        assertEquals("一", DocxStyleHelper.chinese(1));
        assertEquals("十", DocxStyleHelper.chinese(10));
        assertEquals("十一", DocxStyleHelper.chinese(11));
        assertEquals("二十三", DocxStyleHelper.chinese(23));
        assertEquals("一百零五", DocxStyleHelper.chinese(105));
        assertEquals("c", DocxStyleHelper.formatNumber("lowerLetter", 3));
        assertEquals("IV", DocxStyleHelper.formatNumber("upperRoman", 4));
        assertEquals("③", DocxStyleHelper.formatNumber("decimalEnclosedCircle", 3));
        assertEquals("公司", DocxStyleHelper.stripLiteralPrefix("一、公司"));
        assertEquals("设立", DocxStyleHelper.stripLiteralPrefix("（一）设立"));
        assertEquals("增资", DocxStyleHelper.stripLiteralPrefix("1. 增资"));
        assertEquals("增资", DocxStyleHelper.stripLiteralPrefix("1.2.3 增资"));
        assertEquals("2024年度报告", DocxStyleHelper.stripLiteralPrefix("2024年度报告"));
    }

    private static String text(P p) {
        StringBuilder sb = new StringBuilder();
        collect(p, sb);
        return sb.toString();
    }

    private static void collect(Object node, StringBuilder sb) {
        if (node instanceof javax.xml.bind.JAXBElement<?> je && !"t".equals(je.getName().getLocalPart())
                && je.getValue() instanceof org.docx4j.wml.Text) return;
        Object u = XmlUtils.unwrap(node);
        if (u instanceof org.docx4j.wml.Text t) sb.append(t.getValue());
        else if (u instanceof org.docx4j.wml.ContentAccessor ca) for (Object c : ca.getContent()) collect(c, sb);
    }
}
