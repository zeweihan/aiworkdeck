// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class DocxComparisonFinalizerTest {
    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static String run(String text) { return "<w:r><w:rPr><w:b/></w:rPr><w:t>" + text + "</w:t></w:r>"; }
    static String p(String text) { return "<w:p>" + text + "</w:p>"; }
    static String revision(String type, String text) { return "<w:" + type + " w:id=\"4\" w:author=\"比较\" w:date=\"2026-10-01T00:00:00Z\">" + run(text) + "</w:" + type + ">"; }
    static String marked(String id, String text) { return "<w:commentRangeStart w:id=\"" + id + "\"/>" + run(text) + "<w:commentRangeEnd w:id=\"" + id + "\"/><w:r><w:commentReference w:id=\"" + id + "\"/></w:r>"; }
    static String comment(String id, String author, String text) { return "<w:comment w:id=\"" + id + "\" w:author=\"" + author + "\" w:date=\"2026-10-01T00:00:00Z\">" + p(run(text)) + "</w:comment>"; }
    static byte[] doc(String body, String comments) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
            put(zip, "word/document.xml", "<w:document xmlns:w=\"" + W + "\"><w:body>" + body + "</w:body></w:document>");
            if (comments != null) put(zip, "word/comments.xml", "<w:comments xmlns:w=\"" + W + "\">" + comments + "</w:comments>");
            put(zip, "word/media/preserved.bin", "unchanged-image-bytes");
        }
        return bytes.toByteArray();
    }
    static void put(ZipOutputStream zip, String name, String text) throws Exception { zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
    static byte[] entry(byte[] bytes, String name) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) { for (ZipEntry e; (e = zip.getNextEntry()) != null;) if (e.getName().equals(name)) return zip.readAllBytes(); }
        return null;
    }
    static Document xml(byte[] bytes, String name) throws Exception {
        var f = DocumentBuilderFactory.newDefaultInstance(); f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(entry(bytes, name)));
    }
    static String text(Node node) {
        StringBuilder b = new StringBuilder();
        if (node instanceof Element e && Set.of("t", "delText").contains(e.getLocalName())) return e.getTextContent();
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) b.append(text(c));
        return b.toString();
    }
    static Map<String, String> anchored(Document doc) {
        var active = new LinkedHashMap<String, StringBuilder>(); var done = new LinkedHashMap<String, String>();
        walk(doc, active, done); return done;
    }
    static void walk(Node n, Map<String, StringBuilder> active, Map<String, String> done) {
        if (n instanceof Element e) {
            String id = e.getAttributeNS(W, "id");
            if ("commentRangeStart".equals(e.getLocalName())) active.put(id, new StringBuilder());
            if ("commentRangeEnd".equals(e.getLocalName())) { assertTrue(active.containsKey(id), "end before start"); done.put(id, active.remove(id).toString()); }
            if (Set.of("t", "delText").contains(e.getLocalName())) { active.values().forEach(b -> b.append(e.getTextContent())); return; }
        }
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) walk(c, active, done);
    }

    @Test void promotesChineseMovesWithoutSpacesAndPreservesOtherParts() throws Exception {
        String moved = "双方应当严格保守商业秘密。";
        byte[] a = doc(p(run(moved)) + p(run("通知条款")), null), b = doc(p(run("通知条款")) + p(run(moved)), null);
        byte[] result = DocxComparisonFinalizer.finalizeComparison(a, b, doc(p(revision("del", moved)) + p(run("通知条款")) + p(revision("ins", moved)), null));
        Document d = xml(result, "word/document.xml");
        assertEquals(1, d.getElementsByTagNameNS(W, "moveFrom").getLength());
        assertEquals(1, d.getElementsByTagNameNS(W, "moveTo").getLength());
        Element from = (Element) d.getElementsByTagNameNS(W, "moveFromRangeStart").item(0), to = (Element) d.getElementsByTagNameNS(W, "moveToRangeStart").item(0);
        assertEquals(from.getAttributeNS(W, "name"), to.getAttributeNS(W, "name"));
        assertArrayEquals(entry(a, "word/media/preserved.bin"), entry(result, "word/media/preserved.bin"));
    }
    @Test void nativeMixedScriptRunSplitsStillFormOneUniqueMove() throws Exception {
        String common = "页。双方应当依约履行义务。", moved = "第0701" + common, stay = "第0702" + common;
        byte[] a = doc(p(run(moved)) + p(run(stay)), null), b = doc(p(run(stay)) + p(run(moved)), null);
        String from = revision("del", "第") + revision("del", "0701") + revision("del", common);
        String to = revision("ins", "第") + revision("ins", "0701") + revision("ins", common);
        byte[] result = DocxComparisonFinalizer.finalizeComparison(a, b, doc(p(from) + p(run(stay)) + p(to), null));
        Document d = xml(result, "word/document.xml");
        assertEquals(1, d.getElementsByTagNameNS(W, "moveFrom").getLength());
        assertEquals(moved, text(d.getElementsByTagNameNS(W, "moveFrom").item(0)));
        assertEquals(3, ((Element) d.getElementsByTagNameNS(W, "moveTo").item(0)).getElementsByTagNameNS(W, "r").getLength());
    }
    @Test void reconstructsBothCommentAnchorsAcrossChangedRunsAndRetainsAuthors() throws Exception {
        byte[] a = doc(p(run("前言") + marked("7", "通知期限") + run("尾文")), comment("7", "旧方", "原意见"));
        byte[] b = doc(p(run("前言") + marked("7", "送达期限") + run("尾文")), comment("7", "新方", "新意见"));
        byte[] nativeBytes = doc(p(run("前言") + revision("del", "通知") + revision("ins", "送达") + run("期限尾文")), null);
        byte[] result = DocxComparisonFinalizer.finalizeComparison(a, b, nativeBytes);
        Map<String, String> anchors = anchored(xml(result, "word/document.xml"));
        assertEquals("通知送达期限", anchors.get("0")); // all-markup range includes the replacing insertion
        assertEquals("送达期限", anchors.get("1"));
        Document comments = xml(result, "word/comments.xml");
        assertEquals(2, comments.getElementsByTagNameNS(W, "comment").getLength());
        assertEquals("旧方", ((Element) comments.getElementsByTagNameNS(W, "comment").item(0)).getAttributeNS(W, "author"));
        assertTrue(new String(entry(result, "word/document.xml"), StandardCharsets.UTF_8).contains("w:b"));
    }
    @Test void deduplicatesOnlySameSharedCommentAndSupportsMultipleBoundariesInOneRun() throws Exception {
        String body = p(marked("1", "甲乙") + marked("2", "丙丁"));
        String comments = comment("1", "律师", "一") + comment("2", "律师", "二");
        byte[] source = doc(body, comments);
        byte[] result = DocxComparisonFinalizer.finalizeComparison(source, source, doc(p(run("甲乙丙丁")), null));
        assertEquals(Map.of("0", "甲乙", "1", "丙丁"), anchored(xml(result, "word/document.xml")));
        assertEquals(2, xml(result, "word/comments.xml").getElementsByTagNameNS(W, "comment").getLength());
    }
    @Test void movedCommentAnchorsToDestinationAndKeepsBothProjections() throws Exception {
        String moved = "应当严格保守商业秘密。";
        byte[] a = doc(p(marked("1", moved)) + p(run("通知")), comment("1", "旧方", "保密意见"));
        byte[] b = doc(p(run("通知")) + p(run(moved)), null);
        byte[] result = DocxComparisonFinalizer.finalizeComparison(a, b,
                doc(p(revision("del", moved)) + p(run("通知")) + p(revision("ins", moved)), null));
        Document d = xml(result, "word/document.xml");
        Element start = (Element) d.getElementsByTagNameNS(W, "commentRangeStart").item(0);
        assertEquals("moveTo", start.getParentNode().getLocalName());
        assertEquals(Map.of("0", moved), anchored(d));
    }
    @Test void sentenceMoveWithinParagraphRemainsARealMove() throws Exception {
        String moved = "应当严格保守商业秘密。", stay = "双方书面通知。";
        byte[] a = doc(p(run(moved + stay)), null), b = doc(p(run(stay + moved)), null);
        byte[] result = DocxComparisonFinalizer.finalizeComparison(a, b, doc(p(revision("del", moved) + run(stay) + revision("ins", moved)), null));
        assertEquals(1, xml(result, "word/document.xml").getElementsByTagNameNS(W, "moveFrom").getLength());
    }
    @Test void pointCommentInEmptyDocumentHasStartBeforeEnd() throws Exception {
        byte[] source = doc(p(marked("1", "")), comment("1", "律师", "空白批注"));
        byte[] result = DocxComparisonFinalizer.finalizeComparison(source, source, doc(p(""), null));
        assertEquals(Map.of("0", ""), anchored(xml(result, "word/document.xml")));
    }
    @Test void pointCommentBetweenTwoRunsKeepsStartBeforeEnd() throws Exception {
        byte[] source = doc(p(run("甲乙") + "<w:r><w:commentReference w:id=\"1\"/></w:r>" + run("丙丁")), comment("1", "律师", "边界点批注"));
        byte[] result = DocxComparisonFinalizer.finalizeComparison(source, source, doc(p(run("甲乙") + run("丙丁")), null));
        assertEquals(Map.of("0", ""), anchored(xml(result, "word/document.xml")));
    }
    @Test void pointReferenceWithoutRangeIsPreserved() throws Exception {
        byte[] source = doc(p(run("正文") + "<w:r><w:commentReference w:id=\"1\"/></w:r>"), comment("1", "律师", "点批注"));
        byte[] result = DocxComparisonFinalizer.finalizeComparison(source, source, doc(p(run("正文")), null));
        assertEquals(Map.of("0", ""), anchored(xml(result, "word/document.xml")));
    }
    @Test void unchangedDuplicatePreventsFalseMovePairing() throws Exception {
        String clause = "重复条款不得猜测移动。";
        byte[] source = doc(p(run(clause)) + p(run(clause)), null);
        byte[] result = DocxComparisonFinalizer.finalizeComparison(source, source,
                doc(p(revision("del", clause)) + p(run(clause)) + p(revision("ins", clause)), null));
        assertEquals(0, xml(result, "word/document.xml").getElementsByTagNameNS(W, "moveFrom").getLength());
    }
    @Test void nativePageBreakNormalizationPreservesTextAndCommentOffsets() throws Exception {
        byte[] source = doc(p(run("首页")) + p("<w:pPr><w:pageBreakBefore/></w:pPr>" + marked("1", "第二页")), comment("1", "律师", "分页批注"));
        byte[] nativeBytes = doc(p(run("首页") + "<w:r><w:br w:type=\"page\"/></w:r>") + p(run("第二页")), null);
        byte[] result = DocxComparisonFinalizer.finalizeComparison(source, source, nativeBytes);
        Document d = xml(result, "word/document.xml");
        assertEquals(Map.of("0", "第二页"), anchored(d));
        assertEquals("page", ((Element) d.getElementsByTagNameNS(W, "br").item(0)).getAttributeNS(W, "type"));
    }
    @Test void realLineBreakStillParticipatesInContentValidation() throws Exception {
        byte[] source = doc(p(run("甲乙")), null);
        byte[] changed = doc(p(run("甲") + "<w:r><w:br/></w:r>" + run("乙")), null);
        assertThrows(IllegalArgumentException.class, () -> DocxComparisonFinalizer.finalizeComparison(source, source, changed));
    }
    @Test void refusesWrongDirectionOrTruncatedComparison() throws Exception {
        byte[] a = doc(p(run("原文")), null), b = doc(p(run("新文")), null);
        assertThrows(IllegalArgumentException.class, () -> DocxComparisonFinalizer.finalizeComparison(a, b, b));
    }
    @Test void doesNotGuessWhichRepeatedPassageWasMoved() throws Exception {
        String moved = "完全重复的长段落。";
        byte[] source = doc(p(run(moved)) + p(run(moved)), null);
        byte[] result = DocxComparisonFinalizer.finalizeComparison(source, source,
                doc(p(revision("del", moved)) + p(revision("del", moved)) + p(revision("ins", moved)) + p(revision("ins", moved)), null));
        assertEquals(0, xml(result, "word/document.xml").getElementsByTagNameNS(W, "moveFrom").getLength());
    }
    @Test void comparesFinalContentOfAlreadyTrackedInputs() throws Exception {
        byte[] a = doc(p(revision("del", "历史旧字") + run("基线")), null), b = doc(p(revision("ins", "新版")), null);
        assertDoesNotThrow(() -> DocxComparisonFinalizer.finalizeComparison(a, b, doc(p(revision("del", "基线") + revision("ins", "新版")), null)));
    }
    @Test void refusesUnanchoredCommentsInsteadOfSilentlyLosingThem() throws Exception {
        byte[] source = doc(p(run("正文")), comment("1", "律师", "必须保留"));
        assertThrows(IllegalArgumentException.class, () -> DocxComparisonFinalizer.finalizeComparison(source, source, source));
    }
    @Test void rejectsExternalEntitiesInXml() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) { put(zip, "[Content_Types].xml", "<Types/>"); put(zip, "word/document.xml", "<!DOCTYPE x [<!ENTITY p SYSTEM 'file:///private/no-read'>]><x>&p;</x>"); }
        assertThrows(Exception.class, () -> DocxComparisonFinalizer.finalizeComparison(out.toByteArray(), out.toByteArray(), out.toByteArray()));
    }
}
