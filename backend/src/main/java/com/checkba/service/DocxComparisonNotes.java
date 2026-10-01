// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import org.eclipse.jgit.diff.*;
import org.w3c.dom.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import com.checkba.service.DocxComparisonFinalizer.Package;

/** Adds revisions inside surviving notes; Writer already compares their body references. */
final class DocxComparisonNotes {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String O = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String R = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String CT = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String MAIN = "word/document.xml";
    private String date = Instant.now().toString(), author = "文档比对";
    private long revision;
    private LongSupplier ids = () -> ++revision;

    @FunctionalInterface
    interface FragmentImporter { void remap(Element fragment) throws Exception; }

    static Element trackedParagraph(Element baseP, Element resultP, String author, String date,
                                    LongSupplier ids, FragmentImporter importer) throws Exception {
        var worker = new DocxComparisonNotes();
        worker.author = author; worker.date = date; worker.ids = ids;
        return worker.compareParagraph(baseP, resultP, importer);
    }

    static void complete(Package base, Package revised, Package result, Document body) throws Exception {
        var worker = new DocxComparisonNotes();
        worker.revision = highestId(body);
        for (String type : List.of("footnote", "endnote")) worker.completeType(base, revised, result, body, type);
    }

    private void completeType(Package base, Package revised, Package result, Document body, String type) throws Exception {
        String path = "word/" + type + "s.xml";
        if (!base.entries.containsKey(path) && !revised.entries.containsKey(path)) return;
        List<Reference> oldRefs = references(base.xml(MAIN), type, true);
        List<Reference> newRefs = references(revised.xml(MAIN), type, true);
        Map<Element, Reference> oldMap = mapReferences(oldRefs, references(body, type, false));
        Map<Element, Reference> newMap = mapReferences(newRefs, references(body, type, true));
        if (oldRefs.isEmpty() && newRefs.isEmpty()) return;
        if (!result.entries.containsKey(path)) throw incomplete();
        Document output = result.xml(path);
        revision = Math.max(revision, highestId(output));
        Map<String, Element> oldNotes = definitions(base, path, type), newNotes = definitions(revised, path, type);
        Map<String, Element> outputNotes = definitions(output, type);
        var relationships = new Relationships(base, result, path);
        Set<String> processed = new HashSet<>();
        for (var entry : oldMap.entrySet()) {
            Element ref = entry.getKey();
            Reference newRef = newMap.get(ref);
            if (newRef == null) continue; // Native deletion; retain its already imported note.
            String resultId = ref.getAttributeNS(W, "id");
            if (!processed.add(resultId)) throw incomplete();
            Element a = oldNotes.get(entry.getValue().id), b = newNotes.get(newRef.id), out = outputNotes.get(resultId);
            if (a == null || b == null || out == null) throw incomplete();
            List<Element> oldParagraphs = paragraphs(a), newParagraphs = paragraphs(b), outputParagraphs = paragraphs(out);
            List<String> oldText = paragraphKeys(oldParagraphs), newText = paragraphKeys(newParagraphs);
            if (!newText.equals(paragraphKeys(outputParagraphs))) throw incomplete();
            if (oldText.equals(newText)) {
                revision = DocxComparisonFields.completeStory(a, b, out, Math.toIntExact(revision + 1), relationships::remap);
                continue;
            }
            Element replacement = (Element) out.cloneNode(false);
            int at = 0;
            for (Edit edit : diff(oldText, newText)) {
                for (; at < edit.getBeginB(); at++) replacement.appendChild(outputParagraphs.get(at).cloneNode(true));
                int paired = Math.min(edit.getLengthA(), edit.getLengthB());
                for (int i = 0; i < paired; i++) replacement.appendChild(compareParagraph(oldParagraphs.get(edit.getBeginA() + i), outputParagraphs.get(edit.getBeginB() + i), relationships));
                for (int i = edit.getBeginA() + paired; i < edit.getEndA(); i++) replacement.appendChild(wholeParagraph(oldParagraphs.get(i), output, false, relationships));
                for (int i = edit.getBeginB() + paired; i < edit.getEndB(); i++) replacement.appendChild(wholeParagraph(outputParagraphs.get(i), output, true, relationships));
                at = edit.getEndB();
            }
            for (; at < outputParagraphs.size(); at++) replacement.appendChild(outputParagraphs.get(at).cloneNode(true));
            revision = DocxComparisonFields.completeStory(a, b, replacement, Math.toIntExact(revision + 1), relationships::remap);
            out.getParentNode().replaceChild(replacement, out);
            if (!projectParagraphs(replacement, false).equals(oldText) || !projectParagraphs(replacement, true).equals(newText)) throw incomplete();
        }
        relationships.save();
        result.putXml(path, output);
    }

    private static long highestId(Document doc) {
        long maximum = 0; NodeList nodes = doc.getElementsByTagNameNS(W, "*");
        for (int i = 0; i < nodes.getLength(); i++) try { maximum = Math.max(maximum, Long.parseLong(((Element) nodes.item(i)).getAttributeNS(W, "id"))); } catch (NumberFormatException ignored) { }
        return maximum;
    }

    private record Reference(Element element, String id, int offset) {}
    private static List<Reference> references(Document doc, String type, boolean finalSide) {
        List<Reference> result = new ArrayList<>();
        collectReferences(doc, type + "Reference", finalSide, new int[1], result, DocxComparisonFields.displayUnits(doc, finalSide));
        return result;
    }
    private static void collectReferences(Node node, String name, boolean finalSide, int[] offset, List<Reference> refs, Map<Element, DocxComparisonFields.DisplayUnit> displays) {
        if (node instanceof Element e && W.equals(e.getNamespaceURI())) {
            String tag = e.getLocalName();
            if (Set.of("pPr", "rPr", "sectPr").contains(tag) || hidden(tag, finalSide)) return;
            if (tag.equals(name)) refs.add(new Reference(e, e.getAttributeNS(W, "id"), offset[0]));
            if (Set.of("t", "delText").contains(tag)) { offset[0] += displays.containsKey(e) ? displays.get(e).text().length() : e.getTextContent().length(); return; }
            if (tag.equals("tab") || tag.equals("cr") || tag.equals("br") && !Set.of("page", "column").contains(e.getAttributeNS(W, "type")))
                offset[0] += displays.containsKey(e) ? displays.get(e).text().length() : 1;
        }
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) collectReferences(c, name, finalSide, offset, refs, displays);
    }
    private static Map<Element, Reference> mapReferences(List<Reference> source, List<Reference> output) {
        if (source.size() != output.size()) throw incomplete();
        Map<Element, Reference> map = new IdentityHashMap<>();
        for (int i = 0; i < source.size(); i++) {
            // Ordered matching is allowed only after the complete projected anchor sequence agrees.
            if (source.get(i).offset != output.get(i).offset) throw incomplete();
            map.put(output.get(i).element, source.get(i));
        }
        return map;
    }
    private static boolean hidden(String tag, boolean finalSide) {
        return finalSide ? Set.of("del", "moveFrom").contains(tag) : Set.of("ins", "moveTo").contains(tag);
    }
    private static Map<String, Element> definitions(Package p, String path, String type) throws Exception {
        return p.entries.containsKey(path) ? definitions(p.xml(path), type) : Map.of();
    }
    private static Map<String, Element> definitions(Document doc, String type) {
        Map<String, Element> map = new HashMap<>();
        NodeList nodes = doc.getElementsByTagNameNS(W, type);
        for (int i = 0; i < nodes.getLength(); i++) { Element e = (Element) nodes.item(i); map.put(e.getAttributeNS(W, "id"), e); }
        return map;
    }
    private static List<Element> paragraphs(Element note) {
        List<Element> list = new ArrayList<>();
        for (Node c = note.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e) {
            if (!W.equals(e.getNamespaceURI()) || !e.getLocalName().equals("p")) throw new IllegalArgumentException("注释包含尚不能完整比对的结构，未创建文件");
            list.add(e);
        }
        return list;
    }
    private static List<String> paragraphKeys(List<Element> paragraphs) { return paragraphs.stream().map(p -> new Units(p, true).key()).toList(); }
    private static List<String> projectParagraphs(Element note, boolean finalSide) {
        List<String> result = new ArrayList<>();
        for (Element p : paragraphs(note)) {
            Element properties = child(p, "pPr"), mark = properties == null ? null : child(properties, "rPr");
            if (mark != null && child(mark, finalSide ? "del" : "ins") != null) continue;
            result.add(new Units(p, finalSide).key());
        }
        return result;
    }

    private Element compareParagraph(Element old, Element newer, FragmentImporter relationships) throws Exception {
        Units a = new Units(old, true), b = new Units(newer, true);
        Element output = (Element) newer.cloneNode(false);
        Element pPr = child(newer, "pPr"); if (pPr != null) output.appendChild(pPr.cloneNode(true));
        int at = 0;
        for (Edit edit : diff(a.keys, b.keys)) {
            appendSlice(output, b, at, edit.getBeginB(), null, false, relationships);
            appendSlice(output, a, edit.getBeginA(), edit.getEndA(), "del", true, relationships);
            appendSlice(output, b, edit.getBeginB(), edit.getEndB(), "ins", false, relationships);
            at = edit.getEndB();
        }
        appendSlice(output, b, at, b.keys.size(), null, false, relationships);
        return output;
    }
    private Element wholeParagraph(Element source, Document doc, boolean insert, FragmentImporter relationships) throws Exception {
        Element output = (Element) doc.importNode(source, false);
        Element properties = child(source, "pPr");
        properties = properties == null ? element(doc, "pPr") : (Element) doc.importNode(properties, true);
        Element mark = child(properties, "rPr"); if (mark == null) { mark = element(doc, "rPr"); properties.appendChild(mark); }
        mark.appendChild(revision(doc, insert ? "ins" : "del")); output.appendChild(properties);
        Units units = new Units(source, true);
        appendSlice(output, units, 0, units.keys.size(), insert ? "ins" : "del", !insert, relationships);
        return output;
    }
    private void appendSlice(Element output, Units units, int start, int end, String change, boolean old, FragmentImporter relationships) throws Exception {
        if (start == end) return;
        Document doc = output.getOwnerDocument();
        Element fragment = slice(units.root, units, start, end, doc);
        if (fragment == null) return;
        if (old) relationships.remap(fragment);
        if ("del".equals(change)) {
            rename(doc, fragment, "t", "delText"); rename(doc, fragment, "instrText", "delInstrText");
        }
        Element parent = change == null ? output : revision(doc, change);
        while (fragment.hasChildNodes()) {
            Node c = fragment.getFirstChild(); fragment.removeChild(c);
            if (!(c instanceof Element e && W.equals(e.getNamespaceURI()) && e.getLocalName().equals("pPr"))) parent.appendChild(c);
        }
        if (change != null) output.appendChild(parent);
    }
    private Element revision(Document doc, String name) {
        Element e = element(doc, name); e.setAttributeNS(W, "w:id", Long.toString(ids.getAsLong()));
        e.setAttributeNS(W, "w:author", author); e.setAttributeNS(W, "w:date", date); return e;
    }
    private record Span(int start, int end, boolean text) {}
    private static final class Units {
        final Element root;
        final List<String> keys = new ArrayList<>();
        final Map<Element, Span> spans = new IdentityHashMap<>();
        final Map<Element, List<Element>> fieldStarts = new IdentityHashMap<>();
        final Set<Element> fieldMembers = Collections.newSetFromMap(new IdentityHashMap<>());
        Units(Element root, boolean finalSide) {
            this.root = root;
            if (DocxComparisonFields.hasFields(root)) for (var field : DocxComparisonFields.fields(root, finalSide)) {
                fieldStarts.put(field.nodes.get(0), field.nodes); fieldMembers.addAll(field.nodes);
            }
            collect(root, finalSide);
        }
        String key() { return String.join("", keys); }
        private void collect(Element node, boolean finalSide) {
            if (fieldStarts.containsKey(node)) {
                int start = keys.size(); keys.add("\u0000FIELD\u0000");
                for (Element member : fieldStarts.get(node)) spans.put(member, new Span(start, start + 1, false));
                return;
            }
            if (fieldMembers.contains(node)) return;
            String name = node.getLocalName();
            if (W.equals(node.getNamespaceURI()) && (Set.of("pPr", "rPr").contains(name) || hidden(name, finalSide))) return;
            int start = keys.size();
            boolean text = W.equals(node.getNamespaceURI()) && Set.of("t", "delText", "instrText", "delInstrText").contains(name);
            if (text) node.getTextContent().codePoints().forEach(cp -> keys.add(new String(Character.toChars(cp))));
            else {
                boolean hasElement = false;
                for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e) { hasElement = true; collect(e, finalSide); }
                if (!hasElement) keys.add("\u0000" + node.getNamespaceURI() + ":" + name + "\u0000");
            }
            spans.put(node, new Span(start, keys.size(), text));
        }
    }
    private static Element slice(Element node, Units units, int start, int end, Document doc) {
        Span span = units.spans.get(node);
        if (span == null || span.start >= end || span.end <= start) return null;
        if (units.fieldMembers.contains(node)) return (Element) doc.importNode(node, true);
        Element result = (Element) doc.importNode(node, false);
        if (span.text) {
            String value = node.getTextContent(); int a = Math.max(start, span.start) - span.start, b = Math.min(end, span.end) - span.start;
            result.setTextContent(value.substring(value.offsetByCodePoints(0, a), value.offsetByCodePoints(0, b)));
            result.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve");
        } else for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e) {
            if (W.equals(e.getNamespaceURI()) && Set.of("pPr", "rPr").contains(e.getLocalName())) result.appendChild(doc.importNode(e, true));
            else {
                Element part = slice(e, units, start, end, doc);
                if (part != null) {
                    if (W.equals(part.getNamespaceURI()) && Set.of("ins", "moveTo").contains(part.getLocalName())) while (part.hasChildNodes()) result.appendChild(part.getFirstChild());
                    else result.appendChild(part);
                }
            }
        }
        return result;
    }
    private static class Values extends Sequence {
        final List<String> values; Values(List<String> values) { this.values = values; }
        @Override public int size() { return values.size(); }
    }
    private static EditList diff(List<String> a, List<String> b) {
        return new HistogramDiff().diff(new SequenceComparator<Values>() {
            public boolean equals(Values x, int i, Values y, int j) { return x.values.get(i).equals(y.values.get(j)); }
            public int hash(Values x, int i) { return x.values.get(i).hashCode(); }
        }, new Values(a), new Values(b));
    }
    private static Element element(Document doc, String name) { return doc.createElementNS(W, "w:" + name); }
    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && W.equals(e.getNamespaceURI()) && name.equals(e.getLocalName())) return e;
        return null;
    }
    private static void rename(Document doc, Element root, String from, String to) {
        NodeList nodes = root.getElementsByTagNameNS(W, from);
        List<Node> matching = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) matching.add(nodes.item(i));
        for (Node node : matching) doc.renameNode(node, W, "w:" + to);
    }
    private static IllegalArgumentException incomplete() { return new IllegalArgumentException("脚注或尾注与原文未能完整对应，未创建文件"); }

    /** Only dependencies actually used by imported old fragments are copied. */
    static final class Relationships implements FragmentImporter {
        final Package source, output;
        final String owner, relsPath;
        final Document oldRels, newRels;
        final Map<String, String> ids = new HashMap<>(), copied = new HashMap<>();
        boolean changed;
        Relationships(Package source, Package output, String owner) throws Exception {
            this.source = source; this.output = output; this.owner = owner; this.relsPath = rels(owner);
            oldRels = source.entries.containsKey(relsPath) ? source.xml(relsPath) : null;
            newRels = output.entries.containsKey(relsPath) ? output.xml(relsPath) : output.xml(MAIN).getImplementation().createDocument(R, "Relationships", null);
        }
        public void remap(Element node) throws Exception {
            NamedNodeMap attributes = node.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Attr a = (Attr) attributes.item(i);
                if (O.equals(a.getNamespaceURI())) a.setValue(importRelationship(a.getValue()));
            }
            for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e) remap(e);
        }
        String importRelationship(String id) throws Exception {
            if (ids.containsKey(id)) return ids.get(id);
            Element old = findRelationship(oldRels, id); if (old == null) throw incomplete();
            String target = old.getAttribute("Target");
            if (!"External".equals(old.getAttribute("TargetMode"))) target = relative(owner, copyPart(resolve(owner, target)));
            NodeList existing = newRels.getElementsByTagNameNS(R, "Relationship");
            for (int i = 0; i < existing.getLength(); i++) {
                Element e = (Element) existing.item(i);
                if (target.equals(e.getAttribute("Target")) && old.getAttribute("Type").equals(e.getAttribute("Type")) && old.getAttribute("TargetMode").equals(e.getAttribute("TargetMode"))) { ids.put(id, e.getAttribute("Id")); return e.getAttribute("Id"); }
            }
            String next = "compareOld" + (existing.getLength() + 1);
            while (findRelationship(newRels, next) != null) next += "x";
            Element added = (Element) newRels.importNode(old, true); added.setAttribute("Id", next); added.setAttribute("Target", target);
            newRels.getDocumentElement().appendChild(added); changed = true; ids.put(id, next); return next;
        }
        String copyPart(String name) throws Exception {
            if (copied.containsKey(name)) return copied.get(name);
            byte[] data = source.entries.get(name); if (data == null) throw incomplete();
            // A relationship-bearing part can differ through its dependencies even if its own bytes match.
            if (Arrays.equals(data, output.entries.get(name)) && !source.entries.containsKey(rels(name))) { copied.put(name, name); return name; }
            String destination = name; int number = 1, dot = name.lastIndexOf('.');
            while (output.entries.containsKey(destination)) destination = (dot < 0 ? name : name.substring(0, dot)) + "-compare-old-" + number++ + (dot < 0 ? "" : name.substring(dot));
            copied.put(name, destination); output.entries.put(destination, data); copyContentType(name, destination);
            if (source.entries.containsKey(rels(name))) {
                Document dependencies = source.xml(rels(name)); NodeList list = dependencies.getElementsByTagNameNS(R, "Relationship");
                for (int i = 0; i < list.getLength(); i++) { Element e = (Element) list.item(i); if (!"External".equals(e.getAttribute("TargetMode"))) e.setAttribute("Target", relative(destination, copyPart(resolve(name, e.getAttribute("Target"))))); }
                output.putXml(rels(destination), dependencies);
            }
            return destination;
        }
        void copyContentType(String from, String to) throws Exception {
            Document a = source.xml("[Content_Types].xml"), b = output.xml("[Content_Types].xml");
            String type = null, extension = from.substring(from.lastIndexOf('.') + 1);
            for (Node n = a.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e) {
                if (e.getLocalName().equals("Default") && extension.equals(e.getAttribute("Extension"))) type = e.getAttribute("ContentType");
                if (e.getLocalName().equals("Override") && ("/" + from).equals(e.getAttribute("PartName"))) { type = e.getAttribute("ContentType"); break; }
            }
            if (type == null) throw incomplete();
            Element override = b.createElementNS(CT, "Override"); override.setAttribute("PartName", "/" + to); override.setAttribute("ContentType", type); b.getDocumentElement().appendChild(override); output.putXml("[Content_Types].xml", b);
        }
        void save() throws Exception { if (changed) output.putXml(relsPath, newRels); }
        static Element findRelationship(Document doc, String id) {
            if (doc == null) return null;
            NodeList nodes = doc.getElementsByTagNameNS(R, "Relationship");
            for (int i = 0; i < nodes.getLength(); i++) { Element e = (Element) nodes.item(i); if (id.equals(e.getAttribute("Id"))) return e; }
            return null;
        }
        static String rels(String path) { int slash = path.lastIndexOf('/'); return path.substring(0, slash + 1) + "_rels/" + path.substring(slash + 1) + ".rels"; }
        static String resolve(String owner, String target) {
            URI uri = URI.create("/" + owner).resolve(target).normalize();
            if (uri.isAbsolute() || uri.getFragment() != null || uri.getQuery() != null || uri.getPath().contains("/../")) throw incomplete();
            return uri.getPath().substring(1);
        }
        static String relative(String owner, String target) {
            String directory = owner.substring(0, owner.lastIndexOf('/') + 1);
            return java.nio.file.Path.of(directory).relativize(java.nio.file.Path.of(target)).toString().replace('\\', '/');
        }
    }
}
