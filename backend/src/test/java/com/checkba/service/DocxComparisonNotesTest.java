// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.checkba.service.DocxComparisonFinalizer.Package;
import static org.junit.jupiter.api.Assertions.*;

class DocxComparisonNotesTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String O = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    @Test void changesChineseAndEmojiByCodePointRetainingUnchangedRunFormatting() throws Exception {
        for (String type : List.of("footnote", "endnote")) {
            Package a = pkg(type, body(type, "71"), Map.of("71", p("付款为", "30日😀。")), null);
            Package b = pkg(type, body(type, "19"), Map.of("19", p("付款为", "60日😃。")), null);
            Package out = pkg(type, body(type, "2"), Map.of("2", p("付款为", "60日😃。")), null);
            complete(a, b, out);
            Element note = note(out, type, "2");
            assertEquals(List.of("付款为30日😀。"), text(note, false));
            assertEquals(List.of("付款为60日😃。"), text(note, true));
            assertEquals("3😀", textOfTag(note, "delText"));
            assertEquals(1, note.getElementsByTagNameNS(W, "b").getLength(), "Unchanged bold run retained once");
        }
    }

    @Test void matchesThroughProjectedAnchorsWhenMiddleInsertionRenumbersFollowingNotes() throws Exception {
        String type = "footnote";
        Package a = pkg(type, refs(type, new String[]{"a", "c"}, new String[]{"2", "3"}, -1), Map.of("2", p("第一"), "3", p("末尾旧")), null);
        Package b = pkg(type, refs(type, new String[]{"a", "b", "c"}, new String[]{"11", "12", "13"}, -1), Map.of("11", p("第一"), "12", p("中间新增"), "13", p("末尾新")), null);
        // Main body contains unchanged a/b/c text on both sides; only the new zero-width reference is tracked.
        String baseBody = "<w:p>" + run("a") + reference(type, "2") + run("bc") + reference(type, "3") + "</w:p>";
        a = pkg(type, baseBody, Map.of("2", p("第一"), "3", p("末尾旧")), null);
        Package out = pkg(type, refs(type, new String[]{"a", "b", "c"}, new String[]{"21", "22", "23"}, 1), Map.of("21", p("第一"), "22", p("中间新增"), "23", p("末尾新")), null);
        complete(a, b, out);
        assertEquals(List.of("末尾旧"), text(note(out, type, "23"), false));
        assertEquals(List.of("末尾新"), text(note(out, type, "23"), true));
        assertEquals(List.of("中间新增"), text(note(out, type, "22"), true));
        assertEquals(0, note(out, type, "22").getElementsByTagNameNS(W, "ins").getLength(), "Native added note not needlessly rebuilt");
        assertEquals(1, out.xml("word/document.xml").getElementsByTagNameNS(W, "ins").getLength());
    }

    @Test void rejectsSameCountReferencesAtWrongBodyPosition() throws Exception {
        Package a = pkg("footnote", body("footnote", "2"), Map.of("2", p("旧")), null);
        Package b = pkg("footnote", body("footnote", "2"), Map.of("2", p("新")), null);
        Package out = pkg("footnote", "<w:p>" + reference("footnote", "2") + run("正文") + "</w:p>", Map.of("2", p("新")), null);
        assertThrows(IllegalArgumentException.class, () -> complete(a, b, out));
    }

    @Test void retainsDeletedHyperlinkTargetWithConflictingRelationshipId() throws Exception {
        String old = "<w:p><w:hyperlink r:id=\"rId1\"><w:r><w:rPr><w:i/></w:rPr><w:t>旧链接</w:t></w:r></w:hyperlink></w:p>";
        String newer = "<w:p><w:hyperlink r:id=\"rId1\">" + run("新链接") + "</w:hyperlink></w:p>";
        Package a = pkg("footnote", body("footnote", "2"), Map.of("2", old), relationship("https://old.example/path"));
        Package b = pkg("footnote", body("footnote", "3"), Map.of("3", newer), relationship("https://new.example/path"));
        Package out = pkg("footnote", body("footnote", "4"), Map.of("4", newer), relationship("https://new.example/path"));
        complete(a, b, out);
        Element note = note(out, "footnote", "4");
        Element deletion = (Element) note.getElementsByTagNameNS(W, "del").item(0);
        Element link = (Element) deletion.getElementsByTagNameNS(W, "hyperlink").item(0);
        assertNotEquals("rId1", link.getAttributeNS(O, "id"));
        Document relationships = out.xml("word/_rels/footnotes.xml.rels");
        Map<String, String> targets = new HashMap<>(); NodeList list = relationships.getElementsByTagNameNS(R, "Relationship");
        for (int i = 0; i < list.getLength(); i++) { Element e = (Element) list.item(i); targets.put(e.getAttribute("Id"), e.getAttribute("Target")); }
        assertEquals("https://old.example/path", targets.get(link.getAttributeNS(O, "id")));
        assertEquals("https://new.example/path", targets.get("rId1"));
        assertEquals(1, deletion.getElementsByTagNameNS(W, "i").getLength());
    }

    @Test void copiesOnlyUsedInternalRelationshipClosureWithoutOverwritingExistingPart() throws Exception {
        String old = "<w:p><w:hyperlink r:id=\"rId1\">" + run("旧附件") + "</w:hyperlink></w:p>";
        Package a = pkg("footnote", body("footnote", "2"), Map.of("2", old), relationship("attachments/legal.xml").replace(" TargetMode=\"External\"", ""));
        Package b = pkg("footnote", body("footnote", "2"), Map.of("2", p("新附件")), null);
        Package out = pkg("footnote", body("footnote", "2"), Map.of("2", p("新附件")), null);
        a.entries.put("word/attachments/legal.xml", "<old/>".getBytes(StandardCharsets.UTF_8));
        a.entries.put("word/attachments/_rels/legal.xml.rels", relationship("https://source.example/attachment").getBytes(StandardCharsets.UTF_8));
        out.entries.put("word/attachments/legal.xml", "<new/>".getBytes(StandardCharsets.UTF_8));
        a.entries.put("word/attachments/unused.xml", "<unused/>".getBytes(StandardCharsets.UTF_8));
        complete(a, b, out);
        assertEquals("<new/>", new String(out.entries.get("word/attachments/legal.xml"), StandardCharsets.UTF_8));
        assertEquals("<old/>", new String(out.entries.get("word/attachments/legal-compare-old-1.xml"), StandardCharsets.UTF_8));
        assertTrue(out.entries.containsKey("word/attachments/_rels/legal-compare-old-1.xml.rels"));
        assertFalse(out.entries.containsKey("word/attachments/unused.xml"));
        Element relation = (Element) out.xml("word/_rels/footnotes.xml.rels").getElementsByTagNameNS(R, "Relationship").item(0);
        assertEquals("attachments/legal-compare-old-1.xml", relation.getAttribute("Target"));
        assertTrue(new String(out.entries.get("[Content_Types].xml"), StandardCharsets.UTF_8).contains("/word/attachments/legal-compare-old-1.xml"));
    }

    @Test void retainsBalancedFieldWhileComparingSurroundingNoteText() throws Exception {
        String field = "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> REF Clause </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
        Package a = pkg("footnote", body("footnote", "2"), Map.of("2", "<w:p>" + field + run("旧") + "</w:p>"), null);
        Package b = pkg("footnote", body("footnote", "2"), Map.of("2", "<w:p>" + field + run("新") + "</w:p>"), null);
        Package out = pkg("footnote", body("footnote", "2"), Map.of("2", "<w:p>" + field + run("新") + "</w:p>"), null);
        complete(a, b, out);
        assertEquals(2, note(out, "footnote", "2").getElementsByTagNameNS(W, "fldChar").getLength());
        assertEquals(List.of("旧"), text(note(out, "footnote", "2"), false));
        assertEquals(List.of("新"), text(note(out, "footnote", "2"), true));
    }

    @Test void supplementsNoteFieldTargetAndTextTogetherWithoutSplittingField() throws Exception {
        String old = "<w:p>" + run("旧正文") + "<w:fldSimple w:instr=\"REF ClauseA\">" + run("旧缓存") + "</w:fldSimple></w:p>";
        String newer = "<w:p>" + run("新正文") + "<w:fldSimple w:instr=\"REF ClauseB\">" + run("新缓存重新计算更长") + "</w:fldSimple></w:p>";
        Package a = pkg("footnote", body("footnote", "2"), Map.of("2", old), null);
        Package b = pkg("footnote", body("footnote", "2"), Map.of("2", newer), null);
        Package out = pkg("footnote", body("footnote", "2"), Map.of("2", newer), null);
        complete(a, b, out);
        assertEquals(DocxComparisonFields.projection(note(a, "footnote", "2"), true), DocxComparisonFields.projection(note(out, "footnote", "2"), false));
        assertEquals(DocxComparisonFields.projection(note(b, "footnote", "2"), true), DocxComparisonFields.projection(note(out, "footnote", "2"), true));
        assertEquals(1, note(out, "footnote", "2").getElementsByTagNameNS(W, "delInstrText").getLength());
    }

    @Test void bodyCacheLengthChangeBeforeReferenceDoesNotMisalignNoteMapping() throws Exception {
        String before = "<w:p><w:fldSimple w:instr=\"PAGEREF Clause\">" + run("1") + "</w:fldSimple>" + reference("footnote", "2") + "</w:p>";
        String after = before.replace("<w:t>1</w:t>", "<w:t>12345</w:t>");
        Package a = pkg("footnote", before, Map.of("2", p("旧注释")), null);
        Package b = pkg("footnote", before, Map.of("2", p("新注释")), null);
        Package out = pkg("footnote", after, Map.of("2", p("新注释")), null);
        complete(a, b, out);
        assertEquals(List.of("旧注释"), text(note(out, "footnote", "2"), false));
        assertEquals(List.of("新注释"), text(note(out, "footnote", "2"), true));
    }

    @Test void bodyCacheControlRefreshBeforeReferenceUsesSameLogicalAxis() throws Exception {
        for (String control : List.of("tab", "br", "cr")) {
            String before = "<w:p><w:fldSimple w:instr=\"REF Clause\">" + run("A") + "<w:r><w:" + control + "/></w:r>" + run("B")
                    + "</w:fldSimple>" + reference("footnote", "2") + "</w:p>";
            String after = "<w:p><w:fldSimple w:instr=\"REF Clause\">" + run("AB") + "</w:fldSimple>" + reference("footnote", "2") + "</w:p>";
            Package a = pkg("footnote", before, Map.of("2", p("旧注释")), null);
            Package b = pkg("footnote", after, Map.of("2", p("新注释")), null);
            Package out = pkg("footnote", after, Map.of("2", p("新注释")), null);
            complete(a, b, out);
            assertEquals(List.of("旧注释"), text(note(out, "footnote", "2"), false));
            assertEquals(List.of("新注释"), text(note(out, "footnote", "2"), true));
        }
    }

    @Test void insertsAndDeletesWholeParagraphsWithTrackedParagraphMarks() throws Exception {
        String old = p("相同首段") + p("旧段删除") + p("共同末段"), newer = p("相同首段") + p("共同末段") + p("新段增加") + p("又一新段");
        Package a = pkg("footnote", body("footnote", "2"), Map.of("2", old), null);
        Package b = pkg("footnote", body("footnote", "2"), Map.of("2", newer), null);
        Package out = pkg("footnote", body("footnote", "2"), Map.of("2", newer), null);
        complete(a, b, out);
        assertEquals(List.of("相同首段", "旧段删除", "共同末段"), text(note(out, "footnote", "2"), false));
        assertEquals(List.of("相同首段", "共同末段", "新段增加", "又一新段"), text(note(out, "footnote", "2"), true));
        Path probe = Path.of("target", "docx-notes-probe"); Files.createDirectories(probe);
        Files.write(probe.resolve("base.docx"), a.bytes()); Files.write(probe.resolve("revised.docx"), b.bytes()); Files.write(probe.resolve("compared.docx"), out.bytes());
    }

    @Test void unchangedNotesRemainUnmarkedAndNativeDeletedNotesRetained() throws Exception {
        Package a = pkg("footnote", "<w:p>" + run("正文") + reference("footnote", "2") + reference("footnote", "3") + "</w:p>", Map.of("2", p("相同"), "3", p("删除的note")), null);
        Package b = pkg("footnote", body("footnote", "2"), Map.of("2", p("相同")), null);
        Package out = pkg("footnote", "<w:p>" + run("正文") + reference("footnote", "2") + "<w:del w:id=\"9\">" + reference("footnote", "3") + "</w:del></w:p>", Map.of("2", p("相同"), "3", p("删除的note")), null);
        complete(a, b, out);
        assertEquals(List.of("相同"), text(note(out, "footnote", "2"), true));
        assertEquals(List.of("删除的note"), text(note(out, "footnote", "3"), false));
        assertEquals(0, out.xml("word/footnotes.xml").getElementsByTagNameNS(W, "ins").getLength());
    }

    private static void complete(Package a, Package b, Package out) throws Exception { DocxComparisonNotes.complete(a, b, out, out.xml("word/document.xml")); }
    private static Element note(Package p, String type, String id) throws Exception {
        NodeList nodes = p.xml("word/" + type + "s.xml").getElementsByTagNameNS(W, type);
        for (int i = 0; i < nodes.getLength(); i++) { Element e = (Element) nodes.item(i); if (id.equals(e.getAttributeNS(W, "id"))) return e; }
        throw new AssertionError("note missing " + id);
    }
    private static List<String> text(Element note, boolean accept) {
        List<String> texts = new ArrayList<>();
        for (Node n = note.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element p) {
            Element props = direct(p, "pPr"), mark = props == null ? null : direct(props, "rPr");
            if (mark != null && direct(mark, accept ? "del" : "ins") != null) continue;
            StringBuilder s = new StringBuilder(); project(p, accept, s); texts.add(s.toString());
        }
        return texts;
    }
    private static void project(Node n, boolean accept, StringBuilder s) {
        if (n instanceof Element e && W.equals(e.getNamespaceURI())) {
            String tag = e.getLocalName(); if (tag.equals("pPr") || tag.equals("rPr") || tag.equals(accept ? "del" : "ins")) return;
            if (tag.equals("t") || tag.equals("delText")) { s.append(e.getTextContent()); return; }
        }
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) project(c, accept, s);
    }
    private static Element direct(Element p, String name) { for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && name.equals(e.getLocalName())) return e; return null; }
    private static String textOfTag(Element node, String tag) { StringBuilder s = new StringBuilder(); NodeList list = node.getElementsByTagNameNS(W, tag); for (int i = 0; i < list.getLength(); i++) s.append(list.item(i).getTextContent()); return s.toString(); }
    private static String p(String text) { return "<w:p>" + run(text) + "</w:p>"; }
    private static String p(String bold, String plain) { return "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>" + bold + "</w:t></w:r>" + run(plain) + "</w:p>"; }
    private static String run(String text) { return "<w:r><w:t>" + text + "</w:t></w:r>"; }
    private static String reference(String type, String id) { return "<w:r><w:" + type + "Reference w:id=\"" + id + "\"/></w:r>"; }
    private static String body(String type, String id) { return "<w:p>" + run("正文") + reference(type, id) + "</w:p>"; }
    private static String refs(String type, String[] text, String[] ids, int inserted) { StringBuilder b = new StringBuilder("<w:p>"); for (int i = 0; i < ids.length; i++) b.append(run(text[i])).append(i == inserted ? "<w:ins w:id=\"99\">" : "").append(reference(type, ids[i])).append(i == inserted ? "</w:ins>" : ""); return b.append("</w:p>").toString(); }
    private static String relationship(String target) { return "<Relationships xmlns=\"" + R + "\"><Relationship Id=\"rId1\" Type=\"" + O + "/hyperlink\" Target=\"" + target + "\" TargetMode=\"External\"/></Relationships>"; }
    private static Package pkg(String type, String body, Map<String, String> notes, String rels) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/" + type + "s.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml." + type + "s+xml\"/></Types>");
        entries.put("_rels/.rels", "<Relationships xmlns=\"" + R + "\"><Relationship Id=\"rDoc\" Type=\"" + O + "/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
        entries.put("word/document.xml", "<w:document xmlns:w=\"" + W + "\" xmlns:r=\"" + O + "\"><w:body>" + body + "<w:sectPr/></w:body></w:document>");
        entries.put("word/_rels/document.xml.rels", "<Relationships xmlns=\"" + R + "\"><Relationship Id=\"rNotes\" Type=\"" + O + "/" + type + "s\" Target=\"" + type + "s.xml\"/></Relationships>");
        StringBuilder definitions = new StringBuilder("<w:" + type + "s xmlns:w=\"" + W + "\" xmlns:r=\"" + O + "\">");
        notes.forEach((id, content) -> definitions.append("<w:").append(type).append(" w:id=\"").append(id).append("\">").append(content).append("</w:").append(type).append(">"));
        entries.put("word/" + type + "s.xml", definitions.append("</w:").append(type).append("s>").toString());
        if (rels != null) entries.put("word/_rels/" + type + "s.xml.rels", rels);
        var bytes = new ByteArrayOutputStream(); try (var zip = new ZipOutputStream(bytes)) { for (var e : entries.entrySet()) { zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); } }
        return new Package(bytes.toByteArray());
    }
}
