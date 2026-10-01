// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class DocxComparisonBibliographyTest {
    private static final String B = "http://schemas.openxmlformats.org/officeDocument/2006/bibliography";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OR = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private static String source(String tag, String title) { return "<b:Source><b:Tag>" + tag + "</b:Tag><b:Title>" + title + "</b:Title></b:Source>"; }
    private static String sources(String xml) { return "<b:Sources xmlns:b=\"" + B + "\">" + xml + "</b:Sources>"; }
    private static DocxComparisonFinalizer.Package pkg(Map<String, String> extra) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>");
        files.put("word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body/></w:document>");
        files.putAll(extra); var bytes = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(bytes)) { for (var e : files.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue().getBytes(StandardCharsets.UTF_8)); z.closeEntry(); } }
        return new DocxComparisonFinalizer.Package(bytes.toByteArray());
    }
    private static DocxComparisonFinalizer.Package bib(String xml) throws Exception { return pkg(Map.of("customXml/item1.xml", sources(xml))); }
    private static Set<String> tags(DocxComparisonFinalizer.Package p, String part) throws Exception {
        NodeList nodes = p.xml(part).getElementsByTagNameNS(B, "Tag"); Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < nodes.getLength(); i++) result.add(nodes.item(i).getTextContent()); return result;
    }

    @Test void unionsExclusiveSourcesWithoutCopyingUnrelatedCustomXml() throws Exception {
        var old = pkg(Map.of("customXml/item1.xml", sources(source("Old", "旧文献")), "customXml/private.xml", "<private>not copied</private>"));
        var revised = bib(source("New", "新文献"));
        byte[] unrelated = "<private>existing unrelated value</private>".getBytes(StandardCharsets.UTF_8);
        var result = bib(source("New", "新文献")); result.entries.put("customXml/unrelated.xml", unrelated);
        DocxComparisonBibliography.merge(old, revised, result);
        assertEquals(Set.of("Old", "New"), tags(result, "customXml/item1.xml"));
        assertArrayEquals(unrelated, result.entries.get("customXml/unrelated.xml"));
        assertFalse(result.entries.containsKey("customXml/private.xml"));
        assertEquals(2, result.xml("customXml/item1.xml").getElementsByTagNameNS(B, "Source").getLength());
        assertEquals("New", result.xml("customXml/item1.xml").getElementsByTagNameNS(B, "Tag").item(0).getTextContent());
    }

    @Test void createsDiscoverablePartsAndAvoidsExistingPartAndRelationshipNames() throws Exception {
        var old = bib(source("Old", "旧文献")); var revised = bib(source("New", "新文献"));
        var result = pkg(Map.of("customXml/awdBibliography.xml", "<private/>", "word/_rels/document.xml.rels",
                "<Relationships xmlns=\"" + REL + "\"><Relationship Id=\"rIdBibliography1\" Type=\"other\" Target=\"other.xml\"/></Relationships>"));
        DocxComparisonBibliography.merge(old, revised, result);
        assertEquals(Set.of("Old", "New"), tags(result, "customXml/awdBibliography1.xml"));
        assertEquals("<private/>", new String(result.entries.get("customXml/awdBibliography.xml"), StandardCharsets.UTF_8));
        Element rel = (Element) result.xml("word/_rels/document.xml.rels").getElementsByTagNameNS(REL, "Relationship").item(1);
        assertEquals(OR + "customXml", rel.getAttribute("Type")); assertEquals("rIdBibliography2", rel.getAttribute("Id"));
        assertEquals("/customXml/awdBibliography1.xml", rel.getAttribute("Target"));
        Element prop = (Element) result.xml("customXml/_rels/awdBibliography1.xml.rels").getElementsByTagNameNS(REL, "Relationship").item(0);
        assertEquals(OR + "customXmlProps", prop.getAttribute("Type"));
        assertTrue(result.entries.containsKey("customXml/" + prop.getAttribute("Target")));
        assertTrue(new String(result.entries.get("[Content_Types].xml"), StandardCharsets.UTF_8).contains("customXmlProperties+xml"));
    }

    @Test void namespaceAndWhitespaceDifferencesDoNotDuplicateAnIdenticalDefinition() throws Exception {
        var old = bib(source("Same", "同一文献"));
        var revised = pkg(Map.of("customXml/source.xml", "<x:Sources xmlns:x=\"" + B + "\">\n<x:Source>\n<x:Tag>Same</x:Tag><x:Title>同一文献</x:Title>\n</x:Source></x:Sources>"));
        var result = bib(source("Same", "同一文献")); DocxComparisonBibliography.merge(old, revised, result);
        assertEquals(1, result.xml("customXml/item1.xml").getElementsByTagNameNS(B, "Source").getLength());
    }

    @Test void conflictingSameTagFailsBeforeMutatingTheResult() throws Exception {
        var old = bib(source("Same", "旧文献")); var revised = bib(source("Same", "新文献")); var result = bib(source("Same", "新文献"));
        byte[] before = result.bytes();
        var error = assertThrows(IllegalArgumentException.class, () -> DocxComparisonBibliography.merge(old, revised, result));
        assertTrue(error.getMessage().contains("Same")); assertArrayEquals(before, result.bytes());
    }

    @Test void recognizesBibliographyNamespaceRatherThanElementNamesOrFileNames() throws Exception {
        var old = pkg(Map.of("customXml/item1.xml", "<Sources xmlns=\"urn:other\"><Source><Tag>Unrelated</Tag></Source></Sources>"));
        var empty = pkg(Map.of()); byte[] before = empty.bytes();
        DocxComparisonBibliography.merge(old, pkg(Map.of()), empty); assertArrayEquals(before, empty.bytes());
    }

    @Test void retainsExistingPropertiesAndDoesNotDuplicatePackageLinks() throws Exception {
        var result = pkg(Map.of("customXml/item9.xml", sources(source("New", "新文献")),
                "word/_rels/document.xml.rels", "<Relationships xmlns=\"" + REL + "\"><Relationship Id=\"rId7\" Type=\"" + OR + "customXml\" Target=\"../customXml/item9.xml\"/></Relationships>",
                "customXml/_rels/item9.xml.rels", "<Relationships xmlns=\"" + REL + "\"><Relationship Id=\"rId1\" Type=\"" + OR + "customXmlProps\" Target=\"itemProps9.xml\"/></Relationships>",
                "customXml/itemProps9.xml", "<ds:datastoreItem xmlns:ds=\"http://schemas.openxmlformats.org/officeDocument/2006/customXml\" ds:itemID=\"{ORIGINAL}\"/>"));
        byte[] props = result.entries.get("customXml/itemProps9.xml");
        DocxComparisonBibliography.merge(bib(source("Old", "旧文献")), bib(source("New", "新文献")), result);
        assertArrayEquals(props, result.entries.get("customXml/itemProps9.xml"));
        assertEquals(1, result.xml("word/_rels/document.xml.rels").getElementsByTagNameNS(REL, "Relationship").getLength());
        assertEquals(1, result.xml("customXml/_rels/item9.xml.rels").getElementsByTagNameNS(REL, "Relationship").getLength());
    }
}
