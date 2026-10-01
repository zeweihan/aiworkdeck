// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import org.eclipse.jgit.diff.*;
import org.w3c.dom.*;
import java.time.Instant;
import java.util.*;

/** Completes ignored field instructions without treating cached display values as document text. */
public final class DocxComparisonFields {
    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private DocxComparisonFields() {}

    @FunctionalInterface interface FragmentImporter { void remap(Element fragment) throws Exception; }

    public static int completeStory(Element base, Element revised, Element result) {
        return completeStory(base, revised, result, maxRevisionId(result) + 1);
    }
    public static int completeStory(Element base, Element revised, Element result, int startId) {
        try { return completeStory(base, revised, result, startId, ignored -> {}); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("无法保留旧字段的引用关系", e); }
    }
    static int completeStory(Element base, Element revised, Element result, int startId, FragmentImporter importer) throws Exception {
        if (!hasFields(base) && !hasFields(revised) && !hasFields(result)) return startId;
        List<Token> sourceOld = scan(base, true), sourceNew = scan(revised, true), resultOld = scan(result, false), resultNew = scan(result, true);
        if (!sameProjection(sourceNew, resultNew)) throw unsupported("比对稿的字段或正文与新版不对应");
        if (!plainText(sourceOld).equals(plainText(resultOld))) throw unsupported("字段以外的正文未能对应");
        List<Token> wantedFields = fieldTokens(sourceOld), actualFields = fieldTokens(resultOld);
        int[] next = {Math.max(startId, maxRevisionId(result) + 1)};
        Meta meta = metadata(result);
        List<Edit> edits = diff(fieldKeys(wantedFields), fieldKeys(actualFields));
        for (int n = edits.size() - 1; n >= 0; n--) {
            Edit edit = edits.get(n);
            List<Token> removed = wantedFields.subList(edit.getBeginA(), edit.getEndA());
            List<Token> inserted = actualFields.subList(edit.getBeginB(), edit.getEndB());
            for (Token token : inserted) requireMutable(token.field);
            // Work from later text offsets so splitting a run cannot invalidate an earlier span.
            // At the same offset keep source field order and reuse one physical insertion boundary.
            for (int end = removed.size(); end > 0;) {
                int begin = end - 1;
                while (begin > 0 && removed.get(begin - 1).offset == removed.get(end - 1).offset) begin--;
                Boundary boundary = deletionBoundary(sourceOld, resultOld, actualFields, edit.getBeginB(), removed.get(begin), result);
                for (int i = begin; i < end; i++) {
                    Element deletion = revision(result.getOwnerDocument(), "del", meta, next);
                    appendField(deletion, removed.get(i).field, false); importer.remap(deletion);
                    boundary.parent.insertBefore(deletion, boundary.before);
                }
                end = begin;
            }
            for (Token token : inserted) markInserted(token.field, meta, next);
        }
        if (!sameProjection(sourceOld, scan(result, false)) || !sameProjection(sourceNew, scan(result, true)))
            throw unsupported("字段修订未能还原两份原文");
        return next[0];
    }

    static boolean hasFields(Element root) {
        return root.getElementsByTagNameNS(W, "fldChar").getLength() > 0 || root.getElementsByTagNameNS(W, "fldSimple").getLength() > 0
                || root.getLocalName().equals("fldSimple");
    }
    /** Semantic projection for guards: field caches are deliberately excluded. */
    static String projection(Element root, boolean finalSide) {
        Map<Element, String> replacements = new IdentityHashMap<>();
        if (hasFields(root)) for (FieldAtom field : fields(root, finalSide)) {
            for (Element node : field.nodes) replacements.put(node, "");
            replacements.put(field.nodes.get(0), "\u0000FIELD:" + field.instruction + "\u0000");
        }
        StringBuilder out = new StringBuilder(); semanticText(root, finalSide, replacements, out); return out.toString();
    }
    private static void semanticText(Node node, boolean side, Map<Element, String> replacements, StringBuilder out) {
        if (node instanceof Element e && W.equals(e.getNamespaceURI())) {
            String name = e.getLocalName(); if (hidden(name, side) || Set.of("pPr", "rPr", "sectPr").contains(name)) return;
            if (replacements.containsKey(e)) { out.append(replacements.get(e)); return; }
            if (Set.of("t", "delText").contains(name)) { out.append(e.getTextContent()); return; }
            if (name.equals("tab")) out.append('\t');
            else if (name.equals("cr") || name.equals("br") && !Set.of("page", "column").contains(e.getAttributeNS(W, "type"))) out.append('\n');
        }
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) semanticText(c, side, replacements, out);
    }
    record DisplayUnit(String text, Element end) {}
    static Map<Element, DisplayUnit> displayUnits(Node root, boolean finalSide) {
        Element story = root instanceof Document d ? d.getDocumentElement() : (Element) root;
        if (!hasFields(story)) return Map.of();
        Map<Element, DisplayUnit> result = new IdentityHashMap<>();
        for (FieldAtom field : fields(story, finalSide)) {
            List<Element> text = new ArrayList<>();
            for (Element node : field.nodes) cachedLeaves(node, finalSide, text);
            if (text.isEmpty()) continue;
            for (Element leaf : text) result.put(leaf, new DisplayUnit("", leaf));
            result.put(text.get(0), new DisplayUnit("\ufffc", text.get(text.size() - 1)));
        }
        return result;
    }
    private static void cachedLeaves(Element node, boolean side, List<Element> leaves) {
        if (W.equals(node.getNamespaceURI())) {
            if (hidden(node.getLocalName(), side)) return;
            String name = node.getLocalName();
            if (Set.of("t", "delText", "tab", "cr").contains(name)
                    || name.equals("br") && !Set.of("page", "column").contains(node.getAttributeNS(W, "type"))) { leaves.add(node); return; }
        }
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e) cachedLeaves(e, side, leaves);
    }

    static final class FieldAtom {
        final Element paragraph;
        final List<Element> nodes = new ArrayList<>();
        Element simple;
        String rawInstruction = "", instruction;
        boolean separated;
        FieldAtom(Element paragraph) { this.paragraph = paragraph; }
    }
    private record Token(String value, Element leaf, int offset, Element paragraph, FieldAtom field) {}
    private record Boundary(Node parent, Node before) {}
    private record Meta(String author, String date) {}

    static List<FieldAtom> fields(Element story, boolean finalSide) {
        List<Token> out = new ArrayList<>();
        int[] offset = {0};
        for (Element p : paragraphs(story, finalSide)) scanContainer(p, p, finalSide, out, false, offset);
        return out.stream().map(t -> t.field).toList();
    }
    private static List<Token> scan(Element story, boolean finalSide) {
        List<Token> out = new ArrayList<>();
        int[] offset = {0};
        for (Element p : paragraphs(story, finalSide)) scanContainer(p, p, finalSide, out, true, offset);
        return out;
    }
    private static List<Element> paragraphs(Element root, boolean finalSide) {
        List<Element> out = new ArrayList<>(); collectParagraphs(root, finalSide, out); return out;
    }
    private static void collectParagraphs(Element e, boolean side, List<Element> out) {
        if (W.equals(e.getNamespaceURI()) && hidden(e.getLocalName(), side)) return;
        if (W.equals(e.getNamespaceURI()) && e.getLocalName().equals("p")) { out.add(e); return; }
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element child) collectParagraphs(child, side, out);
    }
    private static void scanContainer(Element container, Element p, boolean side, List<Token> out, boolean includeText, int[] offset) {
        FieldAtom active = null;
        for (Node c = container.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof Element e) || !W.equals(e.getNamespaceURI())) continue;
            String name = e.getLocalName();
            if (hidden(name, side) || Set.of("pPr", "rPr").contains(name)) continue;
            if (name.equals("fldSimple")) {
                if (active != null || hasNestedFields(e)) throw unsupported("嵌套字段");
                FieldAtom field = new FieldAtom(p); field.simple = e; field.nodes.add(e);
                field.rawInstruction = e.getAttributeNS(W, "instr"); addField(out, field, offset[0]); continue;
            }
            if (name.equals("r")) {
                List<Element> controls = directChildren(e, "fldChar");
                if (!controls.isEmpty()) {
                    if (controls.size() != 1 || payloadCount(e) != 1) throw unsupported("同一 run 混合字段控制符与其他内容");
                    String kind = controls.get(0).getAttributeNS(W, "fldCharType");
                    if (kind.equals("begin")) {
                        if (active != null) throw unsupported("嵌套字段");
                        active = new FieldAtom(p); active.nodes.add(e);
                    } else {
                        if (active == null) throw unsupported("字段缺少 begin");
                        active.nodes.add(e);
                        if (kind.equals("end")) { addField(out, active, offset[0]); active = null; }
                        else if (kind.equals("separate") && !active.separated) active.separated = true;
                        else throw unsupported("字段控制符顺序错误");
                    }
                    continue;
                }
                List<Element> instructions = new ArrayList<>(directChildren(e, "instrText")); instructions.addAll(directChildren(e, "delInstrText"));
                if (!instructions.isEmpty()) {
                    if (active == null || active.separated || payloadCount(e) != instructions.size()) throw unsupported("字段指令位置错误");
                    for (Element instruction : instructions) active.rawInstruction += instruction.getTextContent();
                }
                if (active != null) { active.nodes.add(e); continue; }
                if (includeText) for (Node r = e.getFirstChild(); r != null; r = r.getNextSibling()) if (r instanceof Element leaf) {
                    String tag = leaf.getLocalName();
                    if (Set.of("t", "delText").contains(tag)) {
                        String text = leaf.getTextContent();
                        if (!text.isEmpty()) out.add(new Token(text, leaf, offset[0], p, null)); offset[0] += text.length();
                    } else if (tag.equals("tab")) { out.add(new Token("\t", leaf, offset[0], p, null)); offset[0]++; }
                    else if (tag.equals("cr") || tag.equals("br") && !Set.of("page", "column").contains(leaf.getAttributeNS(W, "type"))) { out.add(new Token("\n", leaf, offset[0], p, null)); offset[0]++; }
                }
                continue;
            }
            if (active != null) {
                if (hasFields(e)) throw unsupported("字段跨容器或嵌套");
                // Keep bookmarks, hyperlinks and cached display containers between begin/end intact.
                active.nodes.add(e);
            } else if (Set.of("ins", "del", "moveTo", "moveFrom", "hyperlink", "smartTag", "sdt", "sdtContent").contains(name)) scanContainer(e, p, side, out, includeText, offset);
        }
        if (active != null) throw unsupported("字段跨段或跨容器，缺少 end");
    }
    private static boolean hasNestedFields(Element simple) { return simple.getElementsByTagNameNS(W, "fldChar").getLength() != 0 || simple.getElementsByTagNameNS(W, "fldSimple").getLength() != 0; }
    private static int payloadCount(Element run) { int count = 0; for (Node c = run.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e && !e.getLocalName().equals("rPr")) count++; return count; }
    private static void addField(List<Token> out, FieldAtom field, int offset) {
        field.instruction = normalize(field.rawInstruction); if (field.instruction.isEmpty()) throw unsupported("空字段指令");
        out.add(new Token("\u0000FIELD:" + field.instruction + "\u0000", field.nodes.get(0), offset, field.paragraph, field));
    }
    static String normalize(String instruction) {
        StringBuilder out = new StringBuilder(); boolean quoted = false, gap = false;
        for (int i = 0; i < instruction.length(); i++) {
            char ch = instruction.charAt(i);
            if (!quoted && (Character.isWhitespace(ch) || ch == '\u00a0')) { gap = out.length() > 0; continue; }
            if (gap) { out.append(' '); gap = false; }
            out.append(ch); if (ch == '"' && (i == 0 || instruction.charAt(i - 1) != '\\')) quoted = !quoted;
        }
        return out.toString();
    }
    private static boolean hidden(String name, boolean side) { return side ? Set.of("del", "moveFrom").contains(name) : Set.of("ins", "moveTo").contains(name); }
    private static List<Token> fieldTokens(List<Token> tokens) { return tokens.stream().filter(t -> t.field != null).toList(); }
    private static List<String> fieldKeys(List<Token> fields) { return fields.stream().map(t -> t.offset + ":" + t.value).toList(); }
    private static String plainText(List<Token> tokens) { StringBuilder out = new StringBuilder(); for (Token token : tokens) if (token.field == null) out.append(token.value); return out.toString(); }
    private static boolean sameProjection(List<Token> a, List<Token> b) { return plainText(a).equals(plainText(b)) && fieldKeys(fieldTokens(a)).equals(fieldKeys(fieldTokens(b))); }
    private static final class Values extends Sequence { final List<String> values; Values(List<String> values) { this.values = values; } public int size() { return values.size(); } }
    private static List<Edit> diff(List<String> a, List<String> b) {
        return new HistogramDiff().diff(new SequenceComparator<Values>() {
            public boolean equals(Values x, int i, Values y, int j) { return x.values.get(i).equals(y.values.get(j)); }
            public int hash(Values x, int i) { return x.values.get(i).hashCode(); }
        }, new Values(a), new Values(b));
    }

    private static void requireMutable(FieldAtom field) {
        Node parent = field.nodes.get(0).getParentNode();
        if (!(parent instanceof Element e) || !W.equals(e.getNamespaceURI()) || !e.getLocalName().equals("p"))
            throw unsupported("变化字段不在独立段落容器内");
        for (Element node : field.nodes) if (node.getParentNode() != parent) throw unsupported("字段跨容器");
    }
    private static Boundary deletionBoundary(List<Token> source, List<Token> output, List<Token> fields, int fieldIndex, Token removed, Element story) {
        if (fieldIndex < fields.size() && fields.get(fieldIndex).offset == removed.offset) return around(fields.get(fieldIndex).field, false);
        if (fieldIndex > 0 && fields.get(fieldIndex - 1).offset == removed.offset) return around(fields.get(fieldIndex - 1).field, true);
        List<Token> spans = output.stream().filter(t -> t.field == null).toList();
        boolean left = source.stream().anyMatch(t -> t.field == null && t.paragraph == removed.paragraph && t.offset < removed.offset);
        int lo = 0, hi = spans.size();
        while (lo < hi) { int middle = (lo + hi) >>> 1; if (spans.get(middle).offset < removed.offset) lo = middle + 1; else hi = middle; }
        Token at = null;
        if (lo > 0) { Token previous = spans.get(lo - 1); if (removed.offset < previous.offset + previous.value.length() || left && removed.offset == previous.offset + previous.value.length()) at = previous; }
        if (at == null && lo < spans.size()) at = spans.get(lo);
        if (at == null && !spans.isEmpty()) at = spans.get(spans.size() - 1);
        if (at != null) {
            int position = removed.offset - at.offset;
            if (position < 0 || position > at.value.length()) throw unsupported("无法确定被删字段的文本位置");
            boolean isText = Set.of("t", "delText").contains(at.leaf.getLocalName());
            return splitAt(new Token(at.value, at.leaf, isText ? position : 0, at.paragraph, null), !isText && position > 0);
        }
        List<Element> paragraphs = paragraphs(story, false);
        if (paragraphs.size() != 1) throw unsupported("无法确定被删字段的段落位置");
        return new Boundary(paragraphs.get(0), null);
    }
    private static Boundary around(FieldAtom field, boolean after) {
        Node node = field.nodes.get(after ? field.nodes.size() - 1 : 0);
        while (node.getParentNode() != field.paragraph) {
            if (!(node.getParentNode() instanceof Element parent) || !Set.of("ins", "del", "moveTo", "moveFrom").contains(parent.getLocalName())) throw unsupported("字段锚点位于复杂容器内");
            node = parent;
        }
        return new Boundary(node.getParentNode(), after ? node.getNextSibling() : node);
    }
    private static Boundary splitAt(Token token, boolean after) {
        Element leaf = token.leaf;
        Node runNode = leaf.getParentNode();
        if (!(runNode instanceof Element run) || !run.getLocalName().equals("r") || !(run.getParentNode() instanceof Element parent) || !parent.getLocalName().equals("p"))
            throw unsupported("删除字段位于复杂文本容器内");
        boolean text = Set.of("t", "delText").contains(leaf.getLocalName());
        int position = token.offset + (after && text ? token.value.length() : 0);
        Document doc = leaf.getOwnerDocument(); Element right = (Element) run.cloneNode(false);
        Element properties = firstChild(run, "rPr"); if (properties != null) right.appendChild(properties.cloneNode(true));
        Node next;
        if (text && position > 0 && position < leaf.getTextContent().length()) {
            String value = leaf.getTextContent(); Element tail = (Element) leaf.cloneNode(false); tail.setTextContent(value.substring(position));
            tail.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve"); leaf.setTextContent(value.substring(0, position));
            leaf.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve"); right.appendChild(tail); next = leaf.getNextSibling();
        } else next = text ? (position == 0 ? leaf : leaf.getNextSibling()) : (after ? leaf.getNextSibling() : leaf);
        while (next != null) { Node move = next; next = next.getNextSibling(); right.appendChild(move); }
        if (payloadCount(right) > 0) { parent.insertBefore(right, run.getNextSibling()); return new Boundary(parent, right); }
        return new Boundary(parent, run.getNextSibling());
    }
    private static void markInserted(FieldAtom field, Meta meta, int[] next) {
        requireMutable(field);
        Element first = field.nodes.get(0); Node parent = first.getParentNode(); Element ins = revision(first.getOwnerDocument(), "ins", meta, next);
        parent.insertBefore(ins, first); appendField(ins, field, true);
        for (Element node : field.nodes) parent.removeChild(node);
    }
    private static void appendField(Element target, FieldAtom field, boolean inserted) {
        Document doc = target.getOwnerDocument();
        if (field.simple != null) {
            Element begin = fldRun(doc, "begin"); Element control = firstChild(begin, "fldChar");
            for (String attr : List.of("dirty", "fldLock")) if (field.simple.hasAttributeNS(W, attr)) control.setAttributeNS(W, "w:" + attr, field.simple.getAttributeNS(W, attr));
            target.appendChild(begin);
            Element run = word(doc, "r"), instruction = word(doc, inserted ? "instrText" : "delInstrText");
            instruction.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve"); instruction.setTextContent(field.rawInstruction); run.appendChild(instruction); target.appendChild(run);
            target.appendChild(fldRun(doc, "separate"));
            for (Node c = field.simple.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element) appendFinalCopy(target, (Element) c);
            target.appendChild(fldRun(doc, "end"));
        } else for (Element node : field.nodes) appendFinalCopy(target, node);
        convert(target, inserted);
    }
    private static void appendFinalCopy(Element parent, Element source) {
        if (W.equals(source.getNamespaceURI()) && hidden(source.getLocalName(), true)) return;
        if (W.equals(source.getNamespaceURI()) && Set.of("ins", "moveTo").contains(source.getLocalName())) {
            for (Node c = source.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e) appendFinalCopy(parent, e);
            return;
        }
        Element copy = (Element) parent.getOwnerDocument().importNode(source, false); parent.appendChild(copy);
        for (Node c = source.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e) appendFinalCopy(copy, e);
            else copy.appendChild(parent.getOwnerDocument().importNode(c, true));
        }
    }
    private static void convert(Element root, boolean inserted) {
        for (String[] pair : new String[][]{{inserted ? "delText" : "t", inserted ? "t" : "delText"}, {inserted ? "delInstrText" : "instrText", inserted ? "instrText" : "delInstrText"}}) {
            NodeList nodes = root.getElementsByTagNameNS(W, pair[0]); List<Node> snapshot = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) snapshot.add(nodes.item(i));
            for (Node node : snapshot) root.getOwnerDocument().renameNode(node, W, "w:" + pair[1]);
        }
    }
    private static Element revision(Document doc, String kind, Meta meta, int[] next) {
        Element e = word(doc, kind); e.setAttributeNS(W, "w:id", Integer.toString(next[0]++)); e.setAttributeNS(W, "w:author", meta.author); e.setAttributeNS(W, "w:date", meta.date); return e;
    }
    private static Element fldRun(Document doc, String kind) { Element run = word(doc, "r"), marker = word(doc, "fldChar"); marker.setAttributeNS(W, "w:fldCharType", kind); run.appendChild(marker); return run; }
    private static Element word(Document doc, String name) { return doc.createElementNS(W, "w:" + name); }
    static Element firstChild(Element parent, String name) { List<Element> children = directChildren(parent, name); return children.isEmpty() ? null : children.get(0); }
    private static List<Element> directChildren(Element parent, String name) { List<Element> list = new ArrayList<>(); for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e && W.equals(e.getNamespaceURI()) && e.getLocalName().equals(name)) list.add(e); return list; }
    static int maxRevisionId(Element story) { int max = 0; NodeList nodes = story.getElementsByTagNameNS(W, "*"); for (int i = 0; i < nodes.getLength(); i++) try { max = Math.max(max, Integer.parseInt(((Element) nodes.item(i)).getAttributeNS(W, "id"))); } catch (NumberFormatException ignored) { } return max; }
    private static Meta metadata(Element story) {
        NodeList nodes = story.getElementsByTagNameNS(W, "*");
        for (int i = 0; i < nodes.getLength(); i++) { Element e = (Element) nodes.item(i); if (Set.of("ins", "del").contains(e.getLocalName()) && !e.getAttributeNS(W, "author").isEmpty()) return new Meta(e.getAttributeNS(W, "author"), e.hasAttributeNS(W, "date") ? e.getAttributeNS(W, "date") : Instant.now().toString()); }
        return new Meta("版本对比", Instant.now().toString());
    }
    private static IllegalStateException unsupported(String detail) { return new IllegalStateException(detail + "，未创建不完整的比对稿"); }
}
