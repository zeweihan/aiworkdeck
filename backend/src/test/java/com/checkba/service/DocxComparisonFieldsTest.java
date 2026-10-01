// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/** 与 DocxComparisonFields 配套的独立单测；fixture 产物仅在本地存在时生成（真实 LOWA 验收由主任务执行）。 */
class DocxComparisonFieldsTest {
    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String FIXTURES = System.getProperty("docx.compare.fixtures", "target/docx-compare-fixtures");

    static String run(String text) { return "<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>"; }
    static String p(String content) { return "<w:p>" + content + "</w:p>"; }
    static String fldSimple(String instr, String display) {
        return "<w:fldSimple w:instr=\"" + instr + "\">" + run(display) + "</w:fldSimple>";
    }
    /** 模拟 native CompareDocuments 输出：fldSimple 被展开为 complex 字段 run，且无任何修订标记。 */
    static String complexField(String instr, String display) {
        return "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
             + "<w:r><w:instrText xml:space=\"preserve\">" + instr + "</w:instrText></w:r>"
             + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
             + run(display)
             + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
    }
    static String bookmarks() {
        return p("<w:bookmarkStart w:id=\"1\" w:name=\"TargetA\"/>" + run("条款甲") + "<w:bookmarkEnd w:id=\"1\"/>")
             + p("<w:bookmarkStart w:id=\"2\" w:name=\"TargetB\"/>" + run("条款乙") + "<w:bookmarkEnd w:id=\"2\"/>");
    }
    static String ins(String id, String content) {
        return "<w:ins w:id=\"" + id + "\" w:author=\"比较\" w:date=\"2026-10-01T00:00:00Z\">" + content + "</w:ins>";
    }

    static Element story(String body) throws Exception {
        var f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        var doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(
                ("<w:document xmlns:w=\"" + W + "\"><w:body>" + body + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8)));
        return (Element) doc.getElementsByTagNameNS(W, "body").item(0);
    }

    static String serialize(Element e) throws Exception {
        var out = new ByteArrayOutputStream();
        var t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.transform(new DOMSource(e), new StreamResult(out));
        return out.toString(StandardCharsets.UTF_8);
    }

    static List<Element> paragraphs(Node n, List<Element> out) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e) {
                if ("p".equals(e.getLocalName())) out.add(e);
                else paragraphs(e, out);
            }
        }
        return out;
    }

    static void text(Node n, boolean finalSide, StringBuilder b) {
        if (n instanceof Element e) {
            String name = e.getLocalName();
            if (finalSide ? ("del".equals(name) || "moveFrom".equals(name)) : ("ins".equals(name) || "moveTo".equals(name))) return;
            if ("t".equals(name) || (!finalSide && "delText".equals(name))) { b.append(e.getTextContent()); return; }
            if ("tab".equals(name)) { b.append('\t'); return; }
            if ("br".equals(name) || "cr".equals(name)) { b.append('\u0002'); return; }
        }
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) text(c, finalSide, b);
    }

    static String projection(Element body, boolean finalSide) {
        StringBuilder b = new StringBuilder();
        for (Element p : paragraphs(body, new ArrayList<>())) { text(p, finalSide, b); b.append('\n'); }
        return b.toString();
    }

    static void instrCodes(Node n, boolean finalSide, List<String> out) {
        if (n instanceof Element e) {
            String name = e.getLocalName();
            if (finalSide ? ("del".equals(name) || "moveFrom".equals(name)) : ("ins".equals(name) || "moveTo".equals(name))) return;
            if (finalSide && "instrText".equals(name)) { out.add(e.getTextContent().trim()); return; }
            if (!finalSide && "delInstrText".equals(name)) { out.add(e.getTextContent().trim()); return; }
        }
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) instrCodes(c, finalSide, out);
    }

    static List<Element> byName(Document doc, String localName) {
        var out = new ArrayList<Element>();
        var nodes = doc.getElementsByTagNameNS(W, localName);
        for (int i = 0; i < nodes.getLength(); i++) out.add((Element) nodes.item(i));
        return out;
    }

    static Document docOf(Element e) { return e.getOwnerDocument(); }

    // ---- 覆盖三个真实 fixture 的漏比：REF / PAGEREF / CITATION ----

    @Test void refTargetChangeBecomesStandardFieldRevision() throws Exception {
        completeAndAssert("REF TargetA \\h", "REF TargetB \\h", "引用显示", "引用显示");
    }

    @Test void pagerefTargetChangeWithSameCachedPageNumber() throws Exception {
        completeAndAssert("PAGEREF TargetA \\h", "PAGEREF TargetB \\h", "1", "1");
    }

    @Test void citationSourceChange() throws Exception {
        completeAndAssert(" CITATION SourceA \\l 2052 ", " CITATION SourceB \\l 2052 ", "(作者，年份)", "(作者，年份)");
    }

    void completeAndAssert(String oldInstr, String newInstr, String oldDisplay, String newDisplay) throws Exception {
        Element base = story(bookmarks() + p(run("引用：") + fldSimple(oldInstr, oldDisplay)) + p(run("尾")));
        Element revised = story(bookmarks() + p(run("引用：") + fldSimple(newInstr, newDisplay)) + p(run("尾注")));
        // native 比较输出：complex 字段无修订；另有 native 已标 id=7 的正文插入修订
        Element result = story(bookmarks() + p(run("引用：") + complexField(newInstr, newDisplay)) + p(run("尾") + ins("7", run("注"))));
        String baseBefore = serialize(base), revisedBefore = serialize(revised);
        int next = DocxComparisonFields.completeStory(base, revised, result);
        Document d = docOf(result);
        List<Element> dels = byName(d, "del"), inss = byName(d, "ins");
        assertEquals(1, dels.size(), "应恰好新增一个旧字段删除修订");
        assertEquals(2, inss.size(), "native id=7 之外应恰好新增一个字段插入修订");
        Element del = dels.get(0), fieldIns = inss.stream().filter(e -> e.getElementsByTagNameNS(W, "instrText").getLength() > 0).findFirst().orElseThrow();
        // 旧字段：delInstrText + delText，控制标记完整
        assertEquals(oldInstr.trim(), del.getElementsByTagNameNS(W, "delInstrText").item(0).getTextContent().trim());
        assertEquals(oldDisplay, del.getElementsByTagNameNS(W, "delText").item(0).getTextContent());
        for (String marker : new String[]{"begin", "separate", "end"}) {
            boolean found = false;
            var chars = del.getElementsByTagNameNS(W, "fldChar");
            for (int i = 0; i < chars.getLength(); i++) found |= marker.equals(((Element) chars.item(i)).getAttributeNS(W, "fldCharType"));
            assertTrue(found, "del 内缺少 fldChar " + marker);
        }
        // 新字段：instrText 保留新指令与缓存显示
        assertEquals(newInstr.trim(), fieldIns.getElementsByTagNameNS(W, "instrText").item(0).getTextContent().trim());
        assertEquals(newDisplay, fieldIns.getElementsByTagNameNS(W, "t").item(0).getTextContent());
        // del 在 ins 之前、同段落
        assertSame(del.getParentNode(), fieldIns.getParentNode());
        boolean before = false;
        for (Node n = del.getNextSibling(); n != null; n = n.getNextSibling()) if (n == fieldIns) before = true;
        assertTrue(before, "旧字段删除修订应位于新字段插入修订之前");
        // 元数据沿用 result 既有比较修订；id 唯一（含既有 id=7）
        assertEquals("比较", del.getAttributeNS(W, "author"));
        assertEquals("2026-10-01T00:00:00Z", del.getAttributeNS(W, "date"));
        assertEquals("比较", fieldIns.getAttributeNS(W, "author"));
        Set<String> ids = new HashSet<>();
        for (String name : new String[]{"ins", "del"}) for (Element e : byName(d, name))
            assertTrue(ids.add(e.getAttributeNS(W, "id")), "修订 w:id 冲突: " + e.getAttributeNS(W, "id"));
        assertTrue(next > 7, "返回的下一个可用 id 必须大于既有 id");
        // 输入 DOM 不变
        assertEquals(baseBefore, serialize(base));
        assertEquals(revisedBefore, serialize(revised));
        // accept/reject 投影精确
        assertEquals(projection(base, true), projection(result, false), "OLD(拒绝) 投影应等于 base 最终投影");
        assertEquals(projection(revised, true), projection(result, true), "FINAL(接受) 投影应等于 revised 最终投影");
        List<String> oldCode = new ArrayList<>(), newCode = new ArrayList<>();
        instrCodes(result, false, oldCode);
        instrCodes(result, true, newCode);
        assertEquals(List.of(oldInstr.trim()), oldCode, "拒绝后应恢复旧字段指令");
        assertEquals(List.of(newInstr.trim()), newCode, "接受后应保留新字段指令");
        // 字段语义未丢：run 格式（w:b）保留
        assertTrue(fieldIns.getElementsByTagNameNS(W, "b").getLength() > 0, "字段 run 格式应保留");
    }

    static boolean followsBefore(Node a, Node b) { return a.compareDocumentPosition(b) == Node.DOCUMENT_POSITION_PRECEDING; }

    // ---- 防误配与不伪造修订 ----

    @Test void insertedFieldInMiddleDoesNotMispairFollowingOnes() throws Exception {
        String f1 = fldSimple("REF A \\h", "甲"), fx = fldSimple("REF X \\h", "新"), f2 = fldSimple("REF C \\h", "丙");
        Element base = story(p(run("前") + f1 + run("中") + f2));
        Element revised = story(p(run("前") + f1 + fx + run("中") + f2));
        Element result = story(p(run("前") + complexField("REF A \\h", "甲") + complexField("REF X \\h", "新") + run("中") + complexField("REF C \\h", "丙")));
        DocxComparisonFields.completeStory(base, revised, result);
        Document d = docOf(result);
        // 只有新插入字段被标 ins；前后字段不被误配；没有凭空 del
        assertEquals(0, byName(d, "del").size());
        List<Element> inss = byName(d, "ins");
        assertEquals(1, inss.size());
        assertEquals("REF X \\h", inss.get(0).getElementsByTagNameNS(W, "instrText").item(0).getTextContent().trim());
        List<String> finalCodes = new ArrayList<>();
        instrCodes(result, true, finalCodes);
        assertEquals(List.of("REF A \\h", "REF X \\h", "REF C \\h"), finalCodes);
    }

    @Test void sameInstrDifferentCachedDisplayFakesNothing() throws Exception {
        Element base = story(p(run("见") + fldSimple("REF TargetA \\h", "旧缓存")));
        Element revised = story(p(run("见") + fldSimple("REF TargetA \\h", "新缓存")));
        Element result = story(p(run("见") + complexField("REF TargetA \\h", "新缓存")));
        DocxComparisonFields.completeStory(base, revised, result);
        assertEquals(0, byName(docOf(result), "del").size());
        assertEquals(0, byName(docOf(result), "ins").size());
    }

    @Test void deletedFieldGetsDelBlockWithoutIns() throws Exception {
        Element base = story(p(run("见") + fldSimple("REF TargetA \\h", "甲") + fldSimple("REF Z \\h", "尾")));
        Element revised = story(p(run("见") + fldSimple("REF TargetA \\h", "甲")));
        Element result = story(p(run("见") + complexField("REF TargetA \\h", "甲")));
        DocxComparisonFields.completeStory(base, revised, result);
        Document d = docOf(result);
        assertEquals(0, byName(d, "ins").size());
        List<Element> dels = byName(d, "del");
        assertEquals(1, dels.size());
        assertEquals("REF Z \\h", dels.get(0).getElementsByTagNameNS(W, "delInstrText").item(0).getTextContent().trim());
        assertSame(byName(d, "p").get(0), dels.get(0).getParentNode());
        assertEquals(projection(base, true), projection(result, false));
        assertEquals(projection(revised, true), projection(result, true));
    }

    @Test void unchangedFieldsKeepNativeResultUntouched() throws Exception {
        Element base = story(p(run("见") + fldSimple("REF TargetA \\h", "甲")));
        Element revised = story(p(run("见") + fldSimple("REF TargetA \\h", "甲")));
        Element result = story(p(run("见") + complexField("REF TargetA \\h", "甲")));
        String before = serialize(result);
        DocxComparisonFields.completeStory(base, revised, result);
        assertEquals(before, serialize(result), "不变字段不应产生任何修订");
    }

    @Test void preMarkedFieldChangeIsNotDoubleWrapped() throws Exception {
        Element base = story(bookmarks() + p(run("引用：") + fldSimple("REF TargetA \\h", "引用显示")));
        Element revised = story(bookmarks() + p(run("引用：") + fldSimple("REF TargetB \\h", "引用显示")));
        String marked = "<w:del w:id=\"30\" w:author=\"比对\" w:date=\"2026-10-01T08:00:00Z\">"
             + "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
             + "<w:r><w:delInstrText xml:space=\"preserve\"> REF TargetA \\h </w:delInstrText></w:r>"
             + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
             + "<w:r><w:delText>引用显示</w:delText></w:r>"
             + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:del>"
             + "<w:ins w:id=\"31\" w:author=\"比对\" w:date=\"2026-10-01T08:00:00Z\">"
             + "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
             + "<w:r><w:instrText xml:space=\"preserve\"> REF TargetB \\h </w:instrText></w:r>"
             + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
             + "<w:r><w:t>引用显示</w:t></w:r>"
             + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:ins>";
        Element result = story(bookmarks() + p(run("引用：") + marked));
        DocxComparisonFields.completeStory(base, revised, result);
        assertEquals(1, byName(docOf(result), "del").size());
        assertEquals(1, byName(docOf(result), "ins").size());
    }

    @Test void oldTrackedInputTreatedAsFinalProjection() throws Exception {
        // base 含历史删除修订：被删的 REF Old 字段不参与比较
        Element base = story(p("<w:del w:id=\"1\" w:author=\"旧\" w:date=\"2026-01-01T00:00:00Z\">" + fldSimple("REF Old \\h", "旧") + "</w:del>" + fldSimple("REF TargetA \\h", "甲")));
        Element revised = story(p(fldSimple("REF TargetB \\h", "甲")));
        Element result = story(p(complexField("REF TargetB \\h", "甲")));
        DocxComparisonFields.completeStory(base, revised, result);
        Document d = docOf(result);
        assertEquals(1, byName(d, "del").size());
        assertEquals("REF TargetA \\h", byName(d, "del").get(0).getElementsByTagNameNS(W, "delInstrText").item(0).getTextContent().trim());
        assertEquals(1, byName(d, "ins").size());
    }

    // ---- 不支持结构必须显式失败 ----

    @Test void crossParagraphFieldIsRejected() throws Exception {
        Element base = story(p("<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>") + p("<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"));
        Element revised = story(p(run("x")));
        Element result = story(p(run("x")));
        assertThrows(IllegalStateException.class, () -> DocxComparisonFields.completeStory(base, revised, result));
    }

    @Test void nestedFieldIsRejected() throws Exception {
        String nested = "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
             + "<w:r><w:instrText>REF A</w:instrText></w:r>"
             + "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>";
        Element base = story(p(nested)), revised = story(p(run("x"))), result = story(p(run("x")));
        assertThrows(IllegalStateException.class, () -> DocxComparisonFields.completeStory(base, revised, result));
    }

    @Test void resultFieldsMismatchingRevisedStoryIsRejected() throws Exception {
        Element base = story(p(fldSimple("REF A \\h", "甲")));
        Element revised = story(p(fldSimple("REF B \\h", "甲")));
        Element result = story(p(complexField("REF C \\h", "甲")));
        assertThrows(IllegalStateException.class, () -> DocxComparisonFields.completeStory(base, revised, result));
    }

    @Test void complexOldFieldHasExactlyOneEndMarker() throws Exception {
        Element base = story(p(complexField("REF A", "甲"))), revised = story(p(complexField("REF B", "乙"))), result = story(p(complexField("REF B", "乙")));
        DocxComparisonFields.completeStory(base, revised, result);
        for (Element block : byName(docOf(result), "del")) {
            NodeList controls = block.getElementsByTagNameNS(W, "fldChar");
            assertEquals(3, controls.getLength());
            assertEquals("end", ((Element) controls.item(2)).getAttributeNS(W, "fldCharType"));
        }
        assertSemantic(base, revised, result);
    }

    @Test void replacingTwoOldFieldsWithOneNewFieldPreservesOldOrder() throws Exception {
        Element base = story(p(fldSimple("REF A", "甲") + fldSimple("REF B", "乙")));
        Element revised = story(p(fldSimple("REF C", "丙"))), result = story(p(complexField("REF C", "丙")));
        DocxComparisonFields.completeStory(base, revised, result); assertSemantic(base, revised, result);
        assertEquals(2, byName(docOf(result), "del").size()); assertEquals(1, byName(docOf(result), "ins").size());
    }

    @Test void deletedFieldInMiddleOfMergedRunSplitsAtExactCharacter() throws Exception {
        Element base = story(p(run("前😀") + fldSimple("REF A", "域缓存") + run("后文")));
        Element revised = story(p(run("前😀后文"))), result = story(p(run("前😀后文")));
        DocxComparisonFields.completeStory(base, revised, result); assertSemantic(base, revised, result);
        Element del = byName(docOf(result), "del").get(0);
        assertEquals("前😀", del.getPreviousSibling().getTextContent());
        assertEquals("后文", del.getNextSibling().getTextContent());
        assertEquals(1, ((Element) del.getPreviousSibling()).getElementsByTagNameNS(W, "b").getLength());
        assertEquals(1, ((Element) del.getNextSibling()).getElementsByTagNameNS(W, "b").getLength());
    }

    @Test void terminalDeletedFieldStaysInPreviousParagraph() throws Exception {
        Element base = story(p(run("首") + fldSimple("REF A", "域")) + p(run("尾")));
        Element revised = story(p(run("首")) + p(run("尾"))), result = story(p(run("首")) + p(run("尾")));
        DocxComparisonFields.completeStory(base, revised, result); assertSemantic(base, revised, result);
        assertSame(byName(docOf(result), "p").get(0), byName(docOf(result), "del").get(0).getParentNode());
    }

    @Test void nativeDeletedFieldIsNotDuplicatedWhenAnotherFieldChanges() throws Exception {
        String deleted = "<w:del w:id=\"7\">" + complexField("REF A", "甲").replace("w:instrText", "w:delInstrText").replace("w:t", "w:delText") + "</w:del>";
        Element base = story(p(fldSimple("REF A", "甲") + run("中") + fldSimple("REF A", "甲")));
        Element revised = story(p(run("中") + fldSimple("REF B", "乙")));
        Element result = story(p(deleted + run("中") + complexField("REF B", "乙")));
        DocxComparisonFields.completeStory(base, revised, result); assertSemantic(base, revised, result);
        assertEquals(2, byName(docOf(result), "del").size());
        String once = serialize(result); DocxComparisonFields.completeStory(base, revised, result); assertEquals(once, serialize(result));
    }

    @Test void simpleFieldConversionRetainsBookmarksAndHyperlinkDisplayContainer() throws Exception {
        String contents = "<w:bookmarkStart w:id=\"9\" w:name=\"CacheBookmark\"/><w:hyperlink w:anchor=\"Target\">" + run("缓存") + "</w:hyperlink><w:bookmarkEnd w:id=\"9\"/>";
        Element base = story(p("<w:fldSimple w:instr=\"REF A\">" + contents + "</w:fldSimple>"));
        Element revised = story(p("<w:fldSimple w:instr=\"REF B\">" + contents + "</w:fldSimple>"));
        Element result = story(p("<w:fldSimple w:instr=\"REF B\">" + contents + "</w:fldSimple>"));
        DocxComparisonFields.completeStory(base, revised, result); assertSemantic(base, revised, result);
        assertEquals(2, byName(docOf(result), "hyperlink").size());
        assertEquals(2, byName(docOf(result), "bookmarkStart").size());
        assertEquals(2, byName(docOf(result), "bookmarkEnd").size());
    }

    @Test void sourceFieldCacheHistoricalRevisionsAreProjectedBeforeCloning() throws Exception {
        Element base = story(p("<w:fldSimple w:instr=\"REF A\">" + ins("4", run("旧版当前缓存")) + "<w:del w:id=\"5\">" + run("历史删除") + "</w:del></w:fldSimple>"));
        Element revised = story(p(fldSimple("REF B", "新缓存"))), result = story(p(complexField("REF B", "新缓存")));
        DocxComparisonFields.completeStory(base, revised, result);
        assertEquals(projection(base, true), projection(result, false));
        assertEquals(1, byName(docOf(result), "del").size());
        assertEquals(1, byName(docOf(result), "ins").size());
    }

    @Test void severalDeletedFieldsSplitOneResultRunAtDistinctAndSharedOffsets() throws Exception {
        Element base = story(p(fldSimple("REF Start1", "始一") + fldSimple("REF Start2", "始二")
                + run("甲") + fldSimple("REF Middle", "中") + run("乙😀") + fldSimple("REF End", "末")));
        Element revised = story(p(run("甲乙😀"))), result = story(p(run("甲乙😀")));
        DocxComparisonFields.completeStory(base, revised, result); assertSemantic(base, revised, result);
        assertEquals(4, byName(docOf(result), "del").size());
        assertEquals(projection(base, true), projection(result, false));
    }

    @Test void fieldPositionChangePreservesBothAnchorsEvenWhenCodeIsIdentical() throws Exception {
        Element base = story(p(run("甲") + fldSimple("REF A", "域") + run("乙丙")));
        Element revised = story(p(run("甲乙") + fldSimple("REF A", "域") + run("丙")));
        Element result = story(p(run("甲乙") + complexField("REF A", "域") + run("丙")));
        DocxComparisonFields.completeStory(base, revised, result); assertSemantic(base, revised, result);
        assertEquals(projection(base, true), projection(result, false));
        assertEquals(projection(revised, true), projection(result, true));
    }

    @Test void largeStoryKeepsFourFieldsAndOrdinaryTextExact() throws Exception {
        String text = "合同条款应逐项核验并保持原文完整。".repeat(25);
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            a.append(p(run(text) + (i % 500 == 0 ? fldSimple("PAGEREF Old" + i, "1") : "")));
            b.append(p(run(text) + (i % 500 == 0 ? fldSimple("PAGEREF New" + i, "100") : "")));
        }
        Element base = story(a.toString()), revised = story(b.toString()), result = story(b.toString());
        DocxComparisonFields.completeStory(base, revised, result); assertSemantic(base, revised, result);
        assertEquals(4, byName(docOf(result), "del").size()); assertEquals(4, byName(docOf(result), "ins").size());
    }

    @Test void displayAxisCollapsesAllCacheRunsButPointsToLastLeaf() throws Exception {
        Element result = story(p(complexField("REF A", "首缓存").replace(run("首缓存"), run("首缓存") + run("尾缓存")) + run("普通文字")));
        Map<Element, DocxComparisonFields.DisplayUnit> units = DocxComparisonFields.displayUnits(result, true);
        List<Element> texts = byName(docOf(result), "t");
        assertEquals(2, units.size()); assertEquals("\ufffc", units.get(texts.get(0)).text());
        assertSame(texts.get(1), units.get(texts.get(0)).end()); assertEquals("", units.get(texts.get(1)).text());
        assertFalse(units.containsKey(texts.get(2)));
        assertTrue(DocxComparisonFields.displayUnits(story(p(run("无字段"))), true).isEmpty());
    }

    @Test void cacheControlsAreOneDisplayUnitAndEndAtLastVisibleLeaf() throws Exception {
        String controls = "<w:r><w:tab/><w:br/><w:cr/></w:r>";
        Element result = story(p("<w:fldSimple w:instr=\"REF A\">" + run("A") + controls + "</w:fldSimple>" + run("普通文字")));
        Map<Element, DocxComparisonFields.DisplayUnit> units = DocxComparisonFields.displayUnits(result, true);
        Element first = byName(docOf(result), "t").get(0), last = byName(docOf(result), "cr").get(0);
        assertEquals(4, units.size()); assertEquals("\ufffc", units.get(first).text()); assertSame(last, units.get(first).end());
        for (String name : List.of("tab", "br", "cr")) assertEquals("", units.get(byName(docOf(result), name).get(0)).text());
    }

    @Test void quotedInstructionSpacesAreMeaningful() {
        assertNotEquals(DocxComparisonFields.normalize("CITATION A \\p \"两个  空格\""), DocxComparisonFields.normalize("CITATION A \\p \"两个 空格\""));
        assertEquals("REF A \\h", DocxComparisonFields.normalize("  REF   A   \\h  "));
    }

    static void assertSemantic(Element base, Element revised, Element result) {
        assertEquals(DocxComparisonFields.projection(base, true), DocxComparisonFields.projection(result, false));
        assertEquals(DocxComparisonFields.projection(revised, true), DocxComparisonFields.projection(result, true));
    }

    // ---- 真实 fixture 补足产物（供主任务用真实 LOWA 验收；本机不存在时跳过） ----

    @Test void writesCompletedFixturesForRealLowaAcceptance() throws Exception {
        Path dir = Path.of(FIXTURES);
        assumeTrue(Files.isDirectory(dir), "本地无合成 fixture，跳过产物生成");
        Path outDir = Path.of("target", "handoff");
        Files.createDirectories(outDir);
        for (String name : new String[]{"field-ref", "field-pageref", "field-citation"}) {
            Path fixture = dir.resolve(name);
            assumeTrue(Files.exists(fixture.resolve("base.docx")), "缺少 " + name);
            Element base = xml(fixture.resolve("base.docx"));
            Element revised = xml(fixture.resolve("revised.docx"));
            Element result = xml(fixture.resolve("compared.docx"));
            DocxComparisonFields.completeStory(base, revised, result);
            Document d = result.getOwnerDocument();
            assertTrue(d.getElementsByTagNameNS(W, "del").getLength() >= 1, name + " 应产生旧字段删除修订");
            assertTrue(d.getElementsByTagNameNS(W, "ins").getLength() >= 1, name + " 应产生新字段插入修订");
            Path out = outDir.resolve(name + "-completed.docx");
            rewrite(fixture.resolve("compared.docx"), out, serialize(result.getOwnerDocument().getDocumentElement()));
            assertTrue(Files.exists(out));
        }
    }

    static void assumeTrue(boolean cond, String msg) { org.junit.jupiter.api.Assumptions.assumeTrue(cond, msg); }

    static Element xml(Path docx) throws Exception {
        byte[] bytes = zipEntry(docx, "word/document.xml");
        var f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        return (Element) doc.getElementsByTagNameNS(W, "body").item(0);
    }

    static byte[] zipEntry(Path docx, String name) throws Exception {
        try (var zip = new ZipInputStream(Files.newInputStream(docx))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;)
                if (e.getName().equals(name)) return zip.readAllBytes();
        }
        throw new FileNotFoundException(name + " in " + docx);
    }

    static void rewrite(Path src, Path dst, String documentXml) throws Exception {
        try (var zip = new ZipInputStream(Files.newInputStream(src)); var out = new ZipOutputStream(Files.newOutputStream(dst))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                out.putNextEntry(new ZipEntry(e.getName()));
                if (e.getName().equals("word/document.xml")) out.write(documentXml.getBytes(StandardCharsets.UTF_8));
                else zip.transferTo(out);
                out.closeEntry();
            }
        }
    }
}
