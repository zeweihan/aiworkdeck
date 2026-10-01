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

/**
 * MS-DOCX CT_CommentEx: paraIdParent points to the parent's LAST comment paragraph.
 * https://learn.microsoft.com/en-us/openspecs/office_standards/ms-docx/9660dacc-2ceb-4352-87d2-42ba1184f522
 */
class DocxComparisonCommentThreadTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String W14 = "http://schemas.microsoft.com/office/word/2010/wordml";
    private static final String W15 = "http://schemas.microsoft.com/office/word/2012/wordml";
    private static final String R = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OR = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String DATE = "2026-10-01T01:02:03Z", TARGET = "付款期限为十个工作日";
    private record Comment(String id, String paraId, String parentParaId, String author, String body) {}

    @Test void unanchoredReplyInheritsParentLastParagraphAnchorAndRetainsIdentity() throws Exception {
        List<Comment> thread = List.of(
                new Comment("11", "000000A1", null, "甲方法务", "请核实期限。"),
                new Comment("29", "000000B1", "000000A1", "乙方法务", "已确认按十个工作日办理。"));
        byte[] source = document(thread);
        Document input = xml(source, "word/document.xml");
        assertEquals(1, input.getElementsByTagNameNS(W, "commentRangeStart").getLength());
        assertEquals("11", ((Element) input.getElementsByTagNameNS(W, "commentRangeStart").item(0)).getAttributeNS(W, "id"));
        byte[] clean = document(List.of());
        byte[] result = DocxComparisonFinalizer.finalizeComparison(source, clean, clean);
        assertCommentsAndAnchors(result, Map.of("背景说明。请核实期限。", "甲方法务", "已确认按十个工作日办理。", "乙方法务"));
        // The product intentionally exposes the retained replies as anchored, flat comments.
        assertNull(entry(result, "word/commentsExtended.xml"));
    }

    @Test void unionsBothThreadsDeduplicatingSharedParentAndReplyButKeepingDifferentReplies() throws Exception {
        byte[] base = document(List.of(
                new Comment("11", "000000A1", null, "共同作者", "共同的父意见。"),
                new Comment("12", "000000B1", "000000A1", "回复作者", "两份都有的回复。"),
                new Comment("13", "000000C1", "000000A1", "回复作者", "仅基础版保留的回复。")));
        byte[] revised = document(List.of(
                new Comment("91", "000000A9", null, "共同作者", "共同的父意见。"),
                new Comment("92", "000000B9", "000000A9", "回复作者", "两份都有的回复。"),
                new Comment("93", "000000C9", "000000A9", "回复作者", "仅新版新增的回复。")));
        byte[] result = DocxComparisonFinalizer.finalizeComparison(base, revised, document(List.of()));
        assertCommentsAndAnchors(result, Map.of("背景说明。共同的父意见。", "共同作者",
                "两份都有的回复。", "回复作者", "仅基础版保留的回复。", "回复作者", "仅新版新增的回复。", "回复作者"));
    }

    @Test void brokenReplyParentFailsInsteadOfDroppingReply() throws Exception {
        byte[] source = document(List.of(
                new Comment("11", "000000A1", null, "作者", "父意见。"),
                new Comment("12", "000000B1", "DEADF00D", "回复人", "不能丢的回复。")));
        byte[] clean = document(List.of());
        assertThrows(IllegalArgumentException.class, () -> DocxComparisonFinalizer.finalizeComparison(source, clean, clean));
    }

    private static void assertCommentsAndAnchors(byte[] result, Map<String, String> expected) throws Exception {
        Document comments = xml(result, "word/comments.xml"), document = xml(result, "word/document.xml");
        NodeList definitions = comments.getElementsByTagNameNS(W, "comment");
        assertEquals(expected.size(), definitions.getLength());
        Map<String, String> bodies = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < definitions.getLength(); i++) {
            Element c = (Element) definitions.item(i);
            assertTrue(ids.add(c.getAttributeNS(W, "id")), "Each merged comment has a distinct id");
            assertNull(bodies.put(c.getTextContent(), c.getAttributeNS(W, "author")), "Shared comments deduplicate once");
            assertEquals(DATE, c.getAttributeNS(W, "date"));
        }
        assertEquals(expected, bodies);
        Map<String, StringBuilder> active = new LinkedHashMap<>();
        Map<String, String> anchored = new HashMap<>();
        Set<String> references = new HashSet<>();
        walk(document, active, anchored, references);
        assertTrue(active.isEmpty(), "No unclosed comment anchors");
        assertEquals(ids, anchored.keySet()); assertEquals(ids, references);
        assertTrue(anchored.values().stream().allMatch(TARGET::equals), "Replies must inherit the actual parent text range");
    }

    private static void walk(Node node, Map<String, StringBuilder> active, Map<String, String> anchored, Set<String> references) {
        if (node instanceof Element e && W.equals(e.getNamespaceURI())) {
            String id = e.getAttributeNS(W, "id");
            switch (e.getLocalName()) {
                case "commentRangeStart" -> { assertNull(active.put(id, new StringBuilder())); }
                case "commentRangeEnd" -> { assertTrue(active.containsKey(id), "End must follow start"); assertNull(anchored.put(id, active.remove(id).toString())); }
                case "commentReference" -> { assertTrue(references.add(id), "One reference per merged comment"); }
                case "t" -> { active.values().forEach(b -> b.append(e.getTextContent())); return; }
            }
        }
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) walk(c, active, anchored, references);
    }

    private static byte[] document(List<Comment> comments) throws Exception {
        boolean threaded = !comments.isEmpty();
        String types = "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + (threaded ? "<Override PartName=\"/word/comments.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml\"/>"
                + "<Override PartName=\"/word/commentsExtended.xml\" ContentType=\"application/vnd.ms-word.commentsExt+xml\"/>" : "") + "</Types>";
        String root = threaded ? comments.get(0).id : "";
        String body = "<w:p>" + run("前言。") + (threaded ? "<w:commentRangeStart w:id=\"" + root + "\"/>" : "")
                + run(TARGET) + (threaded ? "<w:commentRangeEnd w:id=\"" + root + "\"/><w:r><w:commentReference w:id=\"" + root + "\"/></w:r>" : "")
                + run("后文。") + "</w:p>";
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", types);
            put(zip, "_rels/.rels", "<Relationships xmlns=\"" + R + "\"><Relationship Id=\"rDoc\" Type=\"" + OR + "/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            put(zip, "word/document.xml", "<w:document xmlns:w=\"" + W + "\"><w:body>" + body + "</w:body></w:document>");
            put(zip, "word/_rels/document.xml.rels", "<Relationships xmlns=\"" + R + "\">" + (threaded ?
                    "<Relationship Id=\"rComments\" Type=\"" + OR + "/comments\" Target=\"comments.xml\"/>"
                    + "<Relationship Id=\"rExtended\" Type=\"http://schemas.microsoft.com/office/2011/relationships/commentsExtended\" Target=\"commentsExtended.xml\"/>" : "") + "</Relationships>");
            if (threaded) {
                StringBuilder definitions = new StringBuilder(), extended = new StringBuilder();
                for (Comment c : comments) {
                    definitions.append("<w:comment w:id=\"").append(c.id).append("\" w:author=\"").append(c.author).append("\" w:date=\"").append(DATE).append("\">");
                    if (c.parentParaId == null) definitions.append("<w:p w14:paraId=\"DEADBEEF\">").append(run("背景说明。")).append("</w:p>");
                    definitions.append("<w:p w14:paraId=\"").append(c.paraId).append("\">").append(run(c.body)).append("</w:p></w:comment>");
                    extended.append("<w15:commentEx w15:paraId=\"").append(c.paraId).append("\"");
                    if (c.parentParaId != null) extended.append(" w15:paraIdParent=\"").append(c.parentParaId).append("\"");
                    extended.append(" w15:done=\"0\"/>");
                }
                put(zip, "word/comments.xml", "<w:comments xmlns:w=\"" + W + "\" xmlns:w14=\"" + W14 + "\">" + definitions + "</w:comments>");
                put(zip, "word/commentsExtended.xml", "<w15:commentsEx xmlns:w15=\"" + W15 + "\">" + extended + "</w15:commentsEx>");
            }
        }
        return out.toByteArray();
    }
    private static String run(String text) { return "<w:r><w:t>" + text + "</w:t></w:r>"; }
    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    private static byte[] entry(byte[] bytes, String name) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) if (e.getName().equals(name)) return zip.readAllBytes();
        }
        return null;
    }
    private static Document xml(byte[] bytes, String name) throws Exception {
        var factory = DocumentBuilderFactory.newDefaultInstance(); factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(Objects.requireNonNull(entry(bytes, name))));
    }
}
