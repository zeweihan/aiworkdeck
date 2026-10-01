// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Completes LOWA's comparison without flattening the document's runs or layout. */
public final class DocxComparisonFinalizer {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String CT = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String DOCUMENT = "word/document.xml", COMMENTS = "word/comments.xml";

    private DocxComparisonFinalizer() {}

    public static byte[] finalizeComparison(byte[] base, byte[] revised, byte[] comparison) throws Exception {
        Package a = new Package(base), b = new Package(revised), result = new Package(comparison);
        Document document = result.xml(DOCUMENT);
        // The comparison must actually describe these inputs. Never publish a partial comparison.
        requireProjection(a.xml(DOCUMENT), document, false);
        requireProjection(b.xml(DOCUMENT), document, true);
        List<MovePair> moves = promoteMoves(document);
        mergeComments(a, b, result, document, moves);
        requireProjection(a.xml(DOCUMENT), document, false);
        requireProjection(b.xml(DOCUMENT), document, true);
        result.putXml(DOCUMENT, document);
        return result.bytes();
    }

    private static void requireProjection(Document source, Document result, boolean finalSide) {
        if (!scan(source, true).text.equals(scan(result, finalSide).text)) {
            throw new IllegalArgumentException("比对稿与原文内容未能完整对应，请重试；未创建文件");
        }
    }

    private record Token(Element element, int start, String text) {}
    private record Range(int start, int end) {}
    private static final class Scan {
        String text = "";
        final List<Token> tokens = new ArrayList<>();
        final Map<String, Integer> starts = new LinkedHashMap<>(), ends = new LinkedHashMap<>();
    }

    // Paragraph separators are not text characters. In particular, Writer can relocate a tracked
    // paragraph mark to the preceding paragraph during export; comment offsets must survive this.
    private static Scan scan(Node root, boolean finalSide) {
        Scan out = new Scan();
        StringBuilder text = new StringBuilder();
        scanNode(root, finalSide, true, out, text);
        out.text = text.toString();
        return out;
    }

    private static void scanNode(Node n, boolean finalSide, boolean visible, Scan out, StringBuilder text) {
        if (n instanceof Element e && W.equals(e.getNamespaceURI())) {
            String name = e.getLocalName();
            if (Set.of("pPr", "rPr", "sectPr").contains(name)) return;
            if ((finalSide && Set.of("del", "moveFrom").contains(name)) ||
                    (!finalSide && Set.of("ins", "moveTo").contains(name))) visible = false;
            if (name.equals("commentRangeStart")) out.starts.put(id(e), text.length());
            if (name.equals("commentRangeEnd")) out.ends.put(id(e), text.length());
            if (name.equals("commentReference")) {
                out.starts.putIfAbsent(id(e), text.length()); out.ends.putIfAbsent(id(e), text.length());
            }
            String value = switch (name) {
                case "t", "delText" -> e.getTextContent();
                case "tab" -> "\t";
                case "br", "cr" -> tokenText(e);
                default -> null;
            };
            if (value != null) {
                if (visible && !value.isEmpty()) { out.tokens.add(new Token(e, text.length(), value)); text.append(value); }
                return;
            }
        }
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) scanNode(c, finalSide, visible, out, text);
    }

    private record Comment(Element definition, Range range, boolean revised) {}
    private record MovePair(Element from, Element to) {}
    private record MoveOffsets(int from, int to, int length) {}

    private static void mergeComments(Package a, Package b, Package result, Document document, List<MovePair> moves) throws Exception {
        List<Comment> comments = new ArrayList<>();
        collectComments(a, false, comments);
        collectComments(b, true, comments);
        // Native CompareDocuments copies comments, but can collapse old ranges to a point.
        // Rebuild the union from each input's own anchors against its matching projection.
        for (String tag : List.of("commentRangeStart", "commentRangeEnd", "commentReference"))
            for (Element e : elements(document, tag)) e.getParentNode().removeChild(e);
        Document definitions = parse(("<w:comments xmlns:w=\"" + W + "\"/>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Set<String> seen = new HashSet<>();
        Scan oldProjection = scan(document, false), newProjection = scan(document, true);
        List<MoveOffsets> movedRanges = moveOffsets(moves, oldProjection, newProjection);
        List<Element> texts = new ArrayList<>(); collectTextElements(document, texts);
        Map<Element, Integer> displayedOffsets = new IdentityHashMap<>();
        int displayedLength = 0;
        for (Element e : texts) { displayedOffsets.put(e, displayedLength); displayedLength += tokenText(e).length(); }
        Map<Element, NavigableMap<Integer, List<Element>>> markers = new IdentityHashMap<>();
        List<Element> emptyMarkers = new ArrayList<>();
        int nextId = 0;
        for (Comment comment : comments) {
            boolean revisedSide = comment.revised;
            Range range = comment.range;
            // Attach old comments on moved text to its destination. Writer otherwise leaves an
            // empty paragraph at the deleted position solely to host the comment on acceptance.
            if (!revisedSide) for (MoveOffsets move : movedRanges) {
                if (range.start >= move.from && range.end <= move.from + move.length) {
                    range = new Range(move.to + range.start - move.from, move.to + range.end - move.from);
                    revisedSide = true; break;
                }
            }
            Scan projection = revisedSide ? newProjection : oldProjection;
            if (range.end > projection.text.length()) throw new IllegalArgumentException("批注位置超出比对正文");
            // Map both ends before inserting markers (splitting runs preserves the projections).
            // Identical shared comments at the same displayed range occur in both saved versions.
            String location = boundaryKey(displayedOffsets, projection, range.start, true) + ":"
                    + boundaryKey(displayedOffsets, projection, range.end, false);
            Element def = comment.definition;
            String key = def.getAttributeNS(W, "author") + "\u0000" + def.getAttributeNS(W, "date")
                    + "\u0000" + def.getTextContent() + "\u0000" + location;
            if (!seen.add(key)) continue;
            String newId = Integer.toString(nextId++);
            Element copied = (Element) definitions.importNode(def, true);
            copied.setAttributeNS(W, "w:id", newId);
            definitions.getDocumentElement().appendChild(copied);
            Element start = word(document, "commentRangeStart"); start.setAttributeNS(W, "w:id", newId);
            Element end = word(document, "commentRangeEnd"); end.setAttributeNS(W, "w:id", newId);
            Element reference = word(document, "r"), ref = word(document, "commentReference");
            ref.setAttributeNS(W, "w:id", newId); reference.appendChild(ref);
            addMarker(markers, emptyMarkers, projection, range.start, true, start);
            // A point between two runs must put all three markers on the same boundary;
            // opposite start/end affinities would otherwise serialize end before start.
            boolean point = range.start == range.end;
            addMarker(markers, emptyMarkers, projection, range.end, point, end);
            addMarker(markers, emptyMarkers, projection, range.end, point, reference);
        }
        insertMarkers(document, texts, markers, emptyMarkers);
        result.putXml(COMMENTS, definitions);
        Document rels = result.entries.containsKey("word/_rels/document.xml.rels")
                ? result.xml("word/_rels/document.xml.rels") : parse(("<Relationships xmlns=\"" + R + "\"/>").getBytes());
        boolean found = false;
        Set<String> ids = new HashSet<>();
        for (Element e : children(rels.getDocumentElement())) {
            ids.add(e.getAttribute("Id"));
            if ((OFFICE_R + "/comments").equals(e.getAttribute("Type"))) { e.setAttribute("Target", "comments.xml"); found = true; }
        }
        if (!found) {
            int id = 1; while (ids.contains("awdComments" + id)) id++;
            Element rel = rels.createElementNS(R, "Relationship");
            rel.setAttribute("Id", "awdComments" + id); rel.setAttribute("Type", OFFICE_R + "/comments"); rel.setAttribute("Target", "comments.xml");
            rels.getDocumentElement().appendChild(rel);
        }
        result.putXml("word/_rels/document.xml.rels", rels);
        Document types = result.xml("[Content_Types].xml");
        boolean registered = false;
        for (Element e : children(types.getDocumentElement())) if ("/word/comments.xml".equals(e.getAttribute("PartName"))) registered = true;
        if (!registered) {
            Element type = types.createElementNS(CT, "Override"); type.setAttribute("PartName", "/word/comments.xml");
            type.setAttribute("ContentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"); types.getDocumentElement().appendChild(type);
        }
        result.putXml("[Content_Types].xml", types);
        // Native exports do not retain Word's comment threads. Do not leave stale metadata referring
        // to the old comment IDs after replacing the definitions.
        for (String part : List.of("word/commentsExtended.xml", "word/commentsIds.xml", "word/commentsExtensible.xml")) {
            if (result.entries.containsKey(part)) throw new IllegalArgumentException("比对结果包含尚不能安全重建的批注线程信息");
        }
    }

    private static List<MoveOffsets> moveOffsets(List<MovePair> moves, Scan oldProjection, Scan newProjection) {
        Map<Element, Integer> oldStarts = new IdentityHashMap<>(), newStarts = new IdentityHashMap<>();
        for (Token token : oldProjection.tokens) oldStarts.put(token.element, token.start);
        for (Token token : newProjection.tokens) newStarts.put(token.element, token.start);
        List<MoveOffsets> result = new ArrayList<>();
        for (MovePair move : moves) {
            List<Element> from = new ArrayList<>(), to = new ArrayList<>();
            collectTextElements(move.from, from); collectTextElements(move.to, to);
            if (!from.isEmpty() && !to.isEmpty() && oldStarts.containsKey(from.get(0)) && newStarts.containsKey(to.get(0)))
                result.add(new MoveOffsets(oldStarts.get(from.get(0)), newStarts.get(to.get(0)), scan(move.from, false).text.length()));
        }
        return result;
    }

    private static void collectComments(Package source, boolean revised, List<Comment> out) throws Exception {
        if (!source.entries.containsKey(COMMENTS)) return;
        Scan scan = scan(source.xml(DOCUMENT), true);
        List<Element> definitions = elements(source.xml(COMMENTS), "comment");
        Map<String, String> parents = commentParents(source, definitions);
        for (Element def : definitions) {
            String id = id(def);
            String anchorId = id;
            Set<String> visited = new HashSet<>();
            while (!scan.starts.containsKey(anchorId) && parents.containsKey(anchorId) && visited.add(anchorId))
                anchorId = parents.get(anchorId);
            Integer start = scan.starts.get(anchorId), end = scan.ends.get(anchorId);
            if (start == null || end == null || end < start)
                throw new IllegalArgumentException("文档含无法定位的批注，未创建比对稿");
            // Copying a relationship without its resource would silently corrupt rich comments.
            if (hasRelationship(def)) throw new IllegalArgumentException("批注含链接或附件，当前无法完整合并该批注");
            out.add(new Comment(def, new Range(start, end), revised));
        }
    }

    private static Map<String, String> commentParents(Package source, List<Element> definitions) throws Exception {
        Map<String, String> parents = new HashMap<>();
        if (!source.entries.containsKey("word/commentsExtended.xml")) return parents;
        Map<String, String> paragraphIds = new HashMap<>();
        for (Element definition : definitions) for (Element p : elements(definition, "p")) {
            String paraId = attribute(p, "paraId");
            if (!paraId.isEmpty()) paragraphIds.put(paraId, id(definition));
        }
        for (Element ex : children(source.xml("word/commentsExtended.xml").getDocumentElement())) {
            String child = paragraphIds.get(attribute(ex, "paraId")), parent = paragraphIds.get(attribute(ex, "paraIdParent"));
            if (child != null && parent != null) parents.put(child, parent);
        }
        return parents;
    }
    private static String attribute(Element e, String name) {
        for (int i = 0; i < e.getAttributes().getLength(); i++) {
            Node a = e.getAttributes().item(i); if (name.equals(a.getLocalName())) return a.getNodeValue();
        }
        return "";
    }

    private static boolean hasRelationship(Element e) {
        for (int i = 0; i < e.getAttributes().getLength(); i++)
            if (OFFICE_R.equals(e.getAttributes().item(i).getNamespaceURI())) return true;
        for (Element c : children(e)) if (hasRelationship(c)) return true;
        return false;
    }

    private static int boundaryKey(Map<Element, Integer> offsets, Scan scan, int offset, boolean start) {
        Token t = boundary(scan, offset, start);
        return t == null ? 0 : offsets.get(t.element) + offset - t.start;
    }
    private static void collectTextElements(Node n, List<Element> out) {
        if (n instanceof Element e && W.equals(e.getNamespaceURI())) {
            if (Set.of("pPr", "rPr", "sectPr").contains(e.getLocalName())) return;
            if (Set.of("t", "delText", "tab", "br", "cr").contains(e.getLocalName())) {
                if (!tokenText(e).isEmpty()) out.add(e);
                return;
            }
        }
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) collectTextElements(c, out);
    }
    private static String tokenText(Element e) {
        // Writer may serialize pageBreakBefore as a run break. These layout markers do not
        // occupy a text offset, unlike a real line break; keep their XML untouched.
        return switch (e.getLocalName()) {
            case "tab" -> "\t";
            case "br" -> Set.of("page", "column").contains(e.getAttributeNS(W, "type")) ? "" : "\n";
            case "cr" -> "\n";
            default -> e.getTextContent();
        };
    }
    private static Token boundary(Scan scan, int position, boolean start) {
        // Binary search keeps comment reconstruction linearithmic for long, heavily annotated files.
        int low = 0, high = scan.tokens.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1; Token t = scan.tokens.get(mid);
            if (position < t.start || (!start && position == t.start)) high = mid - 1;
            else if (position > t.start + t.text.length() || (start && position == t.start + t.text.length())) low = mid + 1;
            else return t;
        }
        return scan.tokens.isEmpty() ? null : position == 0 ? scan.tokens.get(0) : scan.tokens.get(scan.tokens.size() - 1);
    }
    private static void addMarker(Map<Element, NavigableMap<Integer, List<Element>>> markers, List<Element> empty,
                                  Scan scan, int position, boolean start, Element marker) {
        Token token = boundary(scan, position, start);
        if (token == null) { empty.add(marker); return; }
        markers.computeIfAbsent(token.element, e -> new TreeMap<>())
                .computeIfAbsent(position - token.start, i -> new ArrayList<>()).add(marker);
    }
    private static void insertMarkers(Document doc, List<Element> texts,
                                      Map<Element, NavigableMap<Integer, List<Element>>> markers, List<Element> empty) {
        Set<Element> runs = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Element text : texts) {
            if (!markers.containsKey(text)) continue;
            if (!(text.getParentNode() instanceof Element run) || !is(run, "r"))
                throw new IllegalArgumentException("不支持的批注文字结构");
            if (!runs.add(run)) continue;
            Node parent = run.getParentNode();
            Element part = emptyRun(run);
            for (Element child : children(run)) {
                if (is(child, "rPr")) continue;
                var positions = markers.get(child);
                if (positions == null) { part.appendChild(child.cloneNode(true)); continue; }
                String value = tokenText(child); int offset = 0;
                for (var entry : positions.entrySet()) {
                    int end = entry.getKey();
                    if (end > offset) part.appendChild(piece(child, value.substring(offset, end)));
                    if (hasContent(part)) parent.insertBefore(part, run);
                    for (Element marker : entry.getValue()) parent.insertBefore(marker, run);
                    part = emptyRun(run); offset = end;
                }
                if (offset < value.length()) part.appendChild(piece(child, value.substring(offset)));
            }
            if (hasContent(part)) parent.insertBefore(part, run);
            parent.removeChild(run);
        }
        if (!empty.isEmpty()) {
            List<Element> paragraphs = elements(doc, "p");
            if (paragraphs.isEmpty()) throw new IllegalArgumentException("批注没有可用的段落锚点");
            for (Element marker : empty) paragraphs.get(0).appendChild(marker);
        }
    }
    private static Element emptyRun(Element original) {
        Element run = (Element) original.cloneNode(false);
        for (Element e : children(original)) if (is(e, "rPr")) run.appendChild(e.cloneNode(true));
        return run;
    }
    private static Element piece(Element original, String value) {
        Element e = (Element) original.cloneNode(true);
        if (is(e, "t") || is(e, "delText")) {
            e.setTextContent(value); e.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
        }
        return e;
    }
    private static boolean hasContent(Element run) { return children(run).stream().anyMatch(e -> !is(e, "rPr")); }

    private static List<MovePair> promoteMoves(Document doc) {
        // Writer splits one change at script/font boundaries (e.g. Chinese + a page number).
        // Rejoin adjacent wrappers with identical metadata, keeping every formatted run intact.
        for (Element paragraph : elements(doc, "p")) {
            Element previous = null;
            for (Element e : children(paragraph)) {
                if (!is(e, "ins") && !is(e, "del")) { previous = null; continue; }
                if (previous != null && sameRevisionMetadata(previous, e)) {
                    while (e.hasChildNodes()) previous.appendChild(e.getFirstChild());
                    paragraph.removeChild(e);
                } else previous = e;
            }
        }
        List<MovePair> pairs = new ArrayList<>();
        Map<String, List<Element>> deletes = revisionsByText(doc, "del"), inserts = revisionsByText(doc, "ins");
        String oldText = scan(doc, false).text, newText = scan(doc, true).text;
        int next = 1;
        for (String tag : List.of("ins", "del", "moveFrom", "moveTo", "moveFromRangeStart", "moveToRangeStart"))
            for (Element e : elements(doc, tag)) try { next = Math.max(next, Integer.parseInt(id(e)) + 1); } catch (NumberFormatException ignored) {}
        for (var entry : deletes.entrySet()) {
            List<Element> other = inserts.get(entry.getKey());
            if (entry.getValue().size() != 1 || other == null || other.size() != 1) continue;
            String passage = entry.getKey();
            if (oldText.indexOf(passage) != oldText.lastIndexOf(passage)
                    || newText.indexOf(passage) != newText.lastIndexOf(passage)) continue;
            Element from = entry.getValue().get(0), to = other.get(0);
            // Repeated occurrences are deliberately left as delete/insert; unique sentences may
            // move within a paragraph as well as between paragraphs.
            String name = "awdMove" + next;
            move(doc, from, "moveFrom", next++, name);
            move(doc, to, "moveTo", next++, name);
            pairs.add(new MovePair(from, to));
        }
        return pairs;
    }
    private static boolean sameRevisionMetadata(Element a, Element b) {
        if (!a.getLocalName().equals(b.getLocalName()) || a.getAttributes().getLength() != b.getAttributes().getLength()) return false;
        for (int i = 0; i < a.getAttributes().getLength(); i++) {
            Node attr = a.getAttributes().item(i);
            if (W.equals(attr.getNamespaceURI()) && "id".equals(attr.getLocalName())) continue;
            if (!attr.getNodeValue().equals(b.getAttributeNS(attr.getNamespaceURI(), attr.getLocalName()))) return false;
        }
        return true;
    }
    private static Map<String, List<Element>> revisionsByText(Document doc, String tag) {
        Map<String, List<Element>> out = new LinkedHashMap<>();
        for (Element e : elements(doc, tag)) {
            if (is(e.getParentNode(), "rPr")) continue;
            Scan s = scan(e, tag.equals("ins"));
            String text = s.text;
            if (text.strip().codePointCount(0, text.strip().length()) < 6) continue;
            out.computeIfAbsent(text, k -> new ArrayList<>()).add(e);
        }
        return out;
    }
    private static void move(Document doc, Element revision, String type, int id, String name) {
        Element start = word(doc, type + "RangeStart"), end = word(doc, type + "RangeEnd");
        for (Element e : List.of(start, end)) e.setAttributeNS(W, "w:id", Integer.toString(id));
        start.setAttributeNS(W, "w:name", name);
        for (String attr : List.of("author", "date")) start.setAttributeNS(W, "w:" + attr, revision.getAttributeNS(W, attr));
        Node parent = revision.getParentNode();
        parent.insertBefore(start, revision); parent.insertBefore(end, revision.getNextSibling());
        doc.renameNode(revision, W, "w:" + type);
        for (Element e : elements(revision, "delText")) doc.renameNode(e, W, "w:t");
    }

    private static String id(Element e) { return e.getAttributeNS(W, "id"); }
    private static boolean is(Node n, String name) { return n != null && W.equals(n.getNamespaceURI()) && name.equals(n.getLocalName()); }
    private static Element word(Document d, String name) { return d.createElementNS(W, "w:" + name); }
    private static List<Element> children(Node parent) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e) result.add(e);
        return result;
    }
    private static List<Element> elements(Node root, String name) {
        NodeList nodes = root instanceof Document d ? d.getElementsByTagNameNS(W, name) : ((Element) root).getElementsByTagNameNS(W, name);
        List<Element> result = new ArrayList<>(); for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i)); return result;
    }
    private static Document parse(byte[] bytes) throws Exception {
        var f = DocumentBuilderFactory.newDefaultInstance(); f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private static final class Package {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        Package(byte[] bytes) throws Exception {
            long total = 0;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    byte[] data = zip.readNBytes(32 * 1024 * 1024 + 1); total += data.length;
                    if (data.length > 32 * 1024 * 1024 || total > 128 * 1024 * 1024 || entries.size() >= 4096)
                        throw new IllegalArgumentException("文档解压后过大，请拆分比对");
                    if (entries.putIfAbsent(entry.getName(), data) != null) throw new IllegalArgumentException("DOCX 含重复条目");
                }
            }
            if (!entries.containsKey(DOCUMENT) || !entries.containsKey("[Content_Types].xml")) throw new IllegalArgumentException("不是有效的 DOCX 文件");
        }
        Document xml(String name) throws Exception { return parse(entries.get(name)); }
        void putXml(String name, Document d) throws Exception {
            var f = TransformerFactory.newDefaultInstance(); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var bytes = new ByteArrayOutputStream(); f.newTransformer().transform(new DOMSource(d), new StreamResult(bytes)); entries.put(name, bytes.toByteArray());
        }
        byte[] bytes() throws IOException {
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes)) { for (var e : entries.entrySet()) { zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue()); zip.closeEntry(); } }
            return bytes.toByteArray();
        }
    }
}
