// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import org.w3c.dom.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Refines native whole-table replacement when the cell structure has not changed. */
final class DocxComparisonTables {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private DocxComparisonTables() {}

    static void refine(Document base, Document revised, Document result) throws Exception {
        List<Element> originals = elements(base, "tbl"), updates = elements(revised, "tbl");
        Map<String, Integer> originalCounts = new HashMap<>(), updateCounts = new HashMap<>();
        for (Element table : originals) originalCounts.merge(text(table, true), 1, Integer::sum);
        for (Element table : updates) updateCounts.merge(text(table, true), 1, Integer::sum);
        AtomicLong ids = new AtomicLong(nextId(result));
        for (Element first : elements(result, "tbl")) {
            if (first.getParentNode() == null) continue;
            Node sibling = first.getNextSibling();
            while (sibling != null && sibling.getNodeType() == Node.TEXT_NODE && sibling.getTextContent().isBlank()) sibling = sibling.getNextSibling();
            if (!(sibling instanceof Element second) || !is(second, "tbl")) continue;
            Element oldTable, newTable;
            if (onlySide(first, false) && onlySide(second, true)) { oldTable = first; newTable = second; }
            else if (onlySide(first, true) && onlySide(second, false)) { oldTable = second; newTable = first; }
            else continue;
            String oldText = text(oldTable, false), newText = text(newTable, true);
            // Do not guess which of several identical tables is being compared.
            if (originalCounts.getOrDefault(oldText, 0) != 1 || updateCounts.getOrDefault(newText, 0) != 1) continue;
            Element before = (Element) oldTable.cloneNode(true), after = (Element) newTable.cloneNode(true);
            project(before, false); project(after, true);
            if (!shape(before).equals(shape(after)) || !sameProperties(before, after)) continue;
            List<Element> oldParagraphs = elements(before, "p"), newParagraphs = elements(after, "p");
            // Nested tables/fields need their own structural handling. Preserve the native result.
            if (!elements(before, "tbl").isEmpty() || !elements(after, "tbl").isEmpty()
                    || !elements(before, "fldChar").isEmpty() || !elements(after, "fldChar").isEmpty()
                    || !elements(before, "fldSimple").isEmpty() || !elements(after, "fldSimple").isEmpty()) continue;
            if (hasComplexRuns(before) || hasComplexRuns(after)) continue;
            Element expectedAfter = (Element) after.cloneNode(true);
            Element revision = firstRevision(newTable);
            if (revision == null) continue;
            String author = revision.getAttributeNS(W, "author"), date = revision.getAttributeNS(W, "date");
            for (int i = 0; i < newParagraphs.size(); i++) {
                Element p = newParagraphs.get(i);
                Element tracked = DocxComparisonNotes.trackedParagraph(oldParagraphs.get(i), p, author, date, ids::getAndIncrement, fragment -> {});
                p.getParentNode().replaceChild(tracked, p);
            }
            if (!text(after, false).equals(oldText) || !text(after, true).equals(newText))
                throw new IllegalArgumentException("表格比对未能完整对应原文，未创建文件");
            if (!sameRunFormatting(before, after, false) || !sameRunFormatting(expectedAfter, after, true)) continue;
            Node parent = newTable.getParentNode();
            parent.replaceChild(after, newTable);
            parent.removeChild(oldTable);
        }
        // Writer merges adjacent tables on import and inherits one table's width.
        // Keep both native replacement tables distinct; the empty separator has no text.
        for (Element first : elements(result, "tbl")) {
            Node next = first.getNextSibling();
            while (next != null && next.getNodeType() == Node.TEXT_NODE && next.getTextContent().isBlank()) next = next.getNextSibling();
            if (next instanceof Element second && is(second, "tbl")
                    && ((onlySide(first, false) && onlySide(second, true)) || (onlySide(first, true) && onlySide(second, false))))
                first.getParentNode().insertBefore(result.createElementNS(W, "w:p"), second);
        }
    }

    private static boolean hasComplexRuns(Element table) {
        return elements(table, "p").stream().anyMatch(p -> !plainTextOnly(p));
    }
    private static boolean plainTextOnly(Element node) {
        if (is(node, "pPr") || is(node, "rPr")) return true;
        // Other inline elements can carry identities, symbols or relationships.
        if (!is(node, "p") && !is(node, "r") && !is(node, "t") && !is(node, "delText")) return false;
        for (Element child : children(node)) if (!plainTextOnly(child)) return false;
        return true;
    }
    private record StyledText(StringBuilder value, Element properties) {}
    private static List<StyledText> styledText(Element root) {
        List<StyledText> out = new ArrayList<>();
        for (Element run : elements(root, "r")) {
            String value = text(run, true);
            if (value.isEmpty()) continue;
            Element properties = children(run).stream().filter(e -> is(e, "rPr")).findFirst().orElse(null);
            if (properties != null && !properties.hasChildNodes()) properties = null;
            if (!out.isEmpty() && sameStyle(out.get(out.size() - 1).properties, properties)) {
                out.get(out.size() - 1).value.append(value);
            } else out.add(new StyledText(new StringBuilder(value), properties));
        }
        return out;
    }
    private static boolean sameStyle(Element a, Element b) { return a == null ? b == null : b != null && a.isEqualNode(b); }
    private static boolean sameRunFormatting(Element expected, Element tracked, boolean finalSide) {
        Element projected = (Element) tracked.cloneNode(true);
        project(projected, finalSide);
        List<StyledText> a = styledText(expected), b = styledText(projected);
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).value.toString().contentEquals(b.get(i).value) || !sameStyle(a.get(i).properties, b.get(i).properties)) return false;
        return true;
    }

    private static boolean onlySide(Element table, boolean side) {
        return !text(table, side).isEmpty() && text(table, !side).isEmpty();
    }
    private static boolean sameProperties(Element a, Element b) {
        for (String tag : List.of("tblPr", "tblGrid", "trPr", "tcPr", "pPr")) {
            List<Element> left = elements(a, tag), right = elements(b, tag);
            if (left.size() != right.size()) return false;
            for (int i = 0; i < left.size(); i++) if (!left.get(i).isEqualNode(right.get(i))) return false;
        }
        return true;
    }
    private static String shape(Element table) {
        StringBuilder out = new StringBuilder();
        for (Element row : children(table)) if (is(row, "tr")) {
            out.append('|');
            for (Element cell : children(row)) if (is(cell, "tc")) {
                out.append('/').append(elements(cell, "p").size()).append(':');
                for (String property : List.of("gridSpan", "vMerge")) {
                    List<Element> values = elements(cell, property);
                    out.append(property).append('=');
                    for (Element value : values) out.append(value.getAttributeNS(W, "val")).append(',');
                    out.append(';');
                }
            }
        }
        return out.toString();
    }
    private static void project(Element root, boolean finalSide) {
        for (Element e : children(root)) {
            String name = e.getLocalName();
            if (W.equals(e.getNamespaceURI()) && Set.of("commentRangeStart", "commentRangeEnd", "commentReference", "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd").contains(name)) {
                root.removeChild(e); continue; // The finalizer reconstructs the comment union later.
            }
            boolean deleted = is(e, "del") || is(e, "moveFrom"), inserted = is(e, "ins") || is(e, "moveTo");
            if ((deleted && finalSide) || (inserted && !finalSide)) { root.removeChild(e); continue; }
            project(e, finalSide);
            if (deleted || inserted) {
                while (e.hasChildNodes()) root.insertBefore(e.getFirstChild(), e);
                root.removeChild(e);
            } else if (is(e, "delText") || is(e, "delInstrText"))
                e.getOwnerDocument().renameNode(e, W, is(e, "delText") ? "w:t" : "w:instrText");
        }
    }
    private static String text(Node node, boolean finalSide) {
        if (node instanceof Element e) {
            if (is(e, "pPr") || is(e, "rPr") || is(e, "trPr") || is(e, "tcPr")) return "";
            if ((finalSide && (is(e, "del") || is(e, "moveFrom"))) || (!finalSide && (is(e, "ins") || is(e, "moveTo")))) return "";
            if (is(e, "t") || is(e, "delText")) return e.getTextContent();
            if (is(e, "tab")) return "\t";
            if (is(e, "cr") || (is(e, "br") && !Set.of("page", "column").contains(e.getAttributeNS(W, "type")))) return "\n";
        }
        StringBuilder out = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) out.append(text(child, finalSide));
        return out.toString();
    }
    private static Element firstRevision(Element root) {
        for (Element e : elements(root, "ins")) return e;
        return null;
    }
    private static long nextId(Document document) {
        long next = 1;
        NodeList nodes = document.getElementsByTagNameNS(W, "*");
        for (int i = 0; i < nodes.getLength(); i++) try {
            next = Math.max(next, Long.parseLong(((Element) nodes.item(i)).getAttributeNS(W, "id")) + 1);
        } catch (NumberFormatException ignored) {}
        return next;
    }
    private static boolean is(Node n, String tag) { return n != null && W.equals(n.getNamespaceURI()) && tag.equals(n.getLocalName()); }
    private static List<Element> children(Node n) {
        List<Element> out = new ArrayList<>();
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e) out.add(e);
        return out;
    }
    private static List<Element> elements(Node root, String tag) {
        NodeList nodes = root instanceof Document d ? d.getElementsByTagNameNS(W, tag) : ((Element) root).getElementsByTagNameNS(W, tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) out.add((Element) nodes.item(i));
        return out;
    }
}
