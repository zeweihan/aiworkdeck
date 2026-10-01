// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.checkba.service.DocxComparisonFinalizerTest.*;

class DocxComparisonTablesTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static String table(String type, String... cells) {
        String marker = type.isEmpty() ? "" : "<w:" + type + " w:id=\"10\" w:author=\"比较\" w:date=\"2026-10-01T00:00:00Z\"/>";
        StringBuilder out = new StringBuilder("<w:tbl><w:tblPr/><w:tblGrid/><w:tr><w:trPr>" + marker + "</w:trPr>");
        for (String cell : cells) out.append("<w:tc><w:tcPr/>").append(p(type.isEmpty() ? run(cell) : revision(type, cell))).append("</w:tc>");
        return out.append("</w:tr></w:tbl>").toString();
    }
    @Test void oneChangedDigitProducesCellRevisionsInsteadOfDuplicatingTheTable() throws Exception {
        byte[] old = doc(table("", "付款日期保持不变", "100万元"), null);
        byte[] updated = doc(table("", "付款日期保持不变", "120万元"), null);
        byte[] nativeBytes = doc(table("ins", "付款日期保持不变", "120万元") + table("del", "付款日期保持不变", "100万元"), null);
        Document result = xml(DocxComparisonFinalizer.finalizeComparison(old, updated, nativeBytes), "word/document.xml");
        assertEquals(1, result.getElementsByTagNameNS(W, "tbl").getLength());
        assertEquals(0, bodyParagraphCount(result));
        assertEquals(1, result.getElementsByTagNameNS(W, "ins").getLength());
        assertEquals(1, result.getElementsByTagNameNS(W, "del").getLength());
        assertEquals("0", text(result.getElementsByTagNameNS(W, "del").item(0)));
        assertEquals("2", text(result.getElementsByTagNameNS(W, "ins").item(0)));
        assertEquals(0, result.getElementsByTagNameNS(W, "moveFrom").getLength());
    }
    @Test void structuralTableReplacementDoesNotLabelUnchangedCellsAsMoved() throws Exception {
        String unchanged = "合同约定的付款日期";
        byte[] old = doc(table("", unchanged, "100万元"), null);
        byte[] updated = doc(table("", unchanged, "100万元", "新增列"), null);
        byte[] nativeBytes = doc(table("ins", unchanged, "100万元", "新增列") + table("del", unchanged, "100万元"), null);
        Document result = xml(DocxComparisonFinalizer.finalizeComparison(old, updated, nativeBytes), "word/document.xml");
        assertEquals(2, result.getElementsByTagNameNS(W, "tbl").getLength());
        assertEquals(1, bodyParagraphCount(result), "separate native replacement tables without adding text");
        assertEquals(0, result.getElementsByTagNameNS(W, "moveFrom").getLength());
    }
    @Test void unchangedTextWithChangedBoldKeepsNativeTablesForRejection() throws Exception {
        String oldTable = table("", "标题", "100万元");
        String newTable = table("", "标题", "120万元").replace("<w:b/>", "");
        String nativeNew = table("ins", "标题", "120万元").replace("<w:b/>", "");
        Document result = xml(DocxComparisonFinalizer.finalizeComparison(doc(oldTable, null), doc(newTable, null), doc(nativeNew + table("del", "标题", "100万元"), null)), "word/document.xml");
        assertEquals(2, result.getElementsByTagNameNS(W, "tbl").getLength(), "native table replacement retains old bold on rejection");
    }
    @Test void equivalentRunSplitsStillRefineTables() throws Exception {
        String oldTable = table("", "标题甲", "100万元");
        String newer = table("", "标题甲", "120万元").replace("<w:t>标题甲</w:t>", "<w:t>标题</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>甲</w:t>");
        String nativeNew = table("ins", "标题甲", "120万元").replace("<w:t>标题甲</w:t>", "<w:t>标题</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>甲</w:t>");
        Document result = xml(DocxComparisonFinalizer.finalizeComparison(doc(oldTable, null), doc(newer, null), doc(nativeNew + table("del", "标题甲", "100万元"), null)), "word/document.xml");
        assertEquals(1, result.getElementsByTagNameNS(W, "tbl").getLength());
    }

    @Test void hyperlinkIdentityChangesKeepNativeTableReplacement() throws Exception {
        String before = table("", "标题", "100万元").replace(run("标题"), "<w:hyperlink w:anchor=\"oldTarget\">" + run("标题") + "</w:hyperlink>");
        String after = table("", "标题", "120万元").replace(run("标题"), "<w:hyperlink w:anchor=\"newTarget\">" + run("标题") + "</w:hyperlink>");
        String oldNative = table("del", "标题", "100万元").replace(run("标题"), "<w:hyperlink w:anchor=\"oldTarget\">" + run("标题") + "</w:hyperlink>");
        String newNative = table("ins", "标题", "120万元").replace(run("标题"), "<w:hyperlink w:anchor=\"newTarget\">" + run("标题") + "</w:hyperlink>");
        Document result = xml(DocxComparisonFinalizer.finalizeComparison(doc(before, null), doc(after, null), doc(newNative + oldNative, null)), "word/document.xml");
        assertEquals(2, result.getElementsByTagNameNS(W, "tbl").getLength());
    }

    @Test void symbolAttributesKeepNativeTableReplacement() throws Exception {
        String oldSymbol = run("标题").replace("</w:r>", "<w:sym w:font=\"Wingdings\" w:char=\"F0A3\"/></w:r>");
        String newSymbol = oldSymbol.replace("F0A3", "F0B7");
        String before = table("", "标题", "100万元").replace(run("标题"), oldSymbol);
        String after = table("", "标题", "120万元").replace(run("标题"), newSymbol);
        String nativeResult = table("ins", "标题", "120万元").replace(run("标题"), newSymbol) + table("del", "标题", "100万元").replace(run("标题"), oldSymbol);
        Document result = xml(DocxComparisonFinalizer.finalizeComparison(doc(before, null), doc(after, null), doc(nativeResult, null)), "word/document.xml");
        assertEquals(2, result.getElementsByTagNameNS(W, "tbl").getLength());
    }

    private static long bodyParagraphCount(Document document) {
        Node body = document.getElementsByTagNameNS(W, "body").item(0);
        long count = 0;
        for (Node node = body.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element e && W.equals(e.getNamespaceURI()) && "p".equals(e.getLocalName())) count++;
        return count;
    }
    @Test void ordinaryAdjacentTablesAreNotSeparated() throws Exception {
        byte[] original = doc(table("", "第一张表") + table("", "第二张表"), null);
        Document result = xml(DocxComparisonFinalizer.finalizeComparison(original, original, original), "word/document.xml");
        assertEquals(2, result.getElementsByTagNameNS(W, "tbl").getLength());
        assertEquals(0, bodyParagraphCount(result));
    }

}
