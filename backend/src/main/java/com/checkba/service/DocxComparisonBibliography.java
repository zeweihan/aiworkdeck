// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URI;
import java.util.*;

/** Retains bibliography definitions needed by either side's tracked citation fields. */
final class DocxComparisonBibliography {
    private static final String B = "http://schemas.openxmlformats.org/officeDocument/2006/bibliography";
    private static final String DS = "http://schemas.openxmlformats.org/officeDocument/2006/customXml";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private static final String CT = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String PROPS_TYPE = "application/vnd.openxmlformats-officedocument.customXmlProperties+xml";
    private record Part(String path, Document document) {}

    static void merge(DocxComparisonFinalizer.Package base, DocxComparisonFinalizer.Package revised,
                      DocxComparisonFinalizer.Package result) throws Exception {
        List<Part> current = parts(result), old = parts(base), newer = parts(revised);
        Map<String, Element> definitions = new LinkedHashMap<>();
        // Validate all collisions before modifying the result. Native definitions take precedence only
        // when semantically identical; an old citation must never silently resolve to a different work.
        for (List<Part> group : List.of(current, old, newer)) for (Part part : group)
            for (Element source : children(part.document.getDocumentElement())) {
                if (!is(source, B, "Source")) continue;
                String tag = children(source).stream().filter(e -> is(e, B, "Tag"))
                        .map(Element::getTextContent).findFirst().orElse("").strip();
                if (tag.isEmpty()) throw new IllegalArgumentException("参考文献来源缺少 Tag，无法安全合并");
                Element previous = definitions.putIfAbsent(tag, source);
                if (previous != null && !canonical(previous).equals(canonical(source)))
                    throw new IllegalArgumentException("两份文档的参考文献来源标识相同但内容不同：" + tag);
            }
        if (definitions.isEmpty()) return;
        Set<String> existing = new HashSet<>();
        for (Part part : current) for (Element source : children(part.document.getDocumentElement()))
            if (is(source, B, "Source")) for (Element child : children(source))
                if (is(child, B, "Tag")) existing.add(child.getTextContent().strip());
        Part target;
        if (current.isEmpty()) {
            String name = unused(result, "customXml/awdBibliography", ".xml");
            Document d = document(B, "b:Sources");
            Element metadata = (!newer.isEmpty() ? newer : old).get(0).document.getDocumentElement();
            d.replaceChild(d.importNode(metadata, false), d.getDocumentElement());
            target = new Part(name, d);
        } else target = current.get(0);
        for (var entry : definitions.entrySet()) if (!existing.contains(entry.getKey()))
            target.document.getDocumentElement().appendChild(target.document.importNode(entry.getValue(), true));
        result.putXml(target.path, target.document);
        ensureRelationship(result, "word/document.xml", OFFICE_REL + "customXml", target.path);
        ensureContentType(result, target.path, "application/xml");
        ensureProperties(result, target.path);
    }

    private static List<Part> parts(DocxComparisonFinalizer.Package source) throws Exception {
        List<Part> parts = new ArrayList<>();
        for (String name : source.entries.keySet()) {
            if (!name.startsWith("customXml/") || !name.endsWith(".xml") || name.contains("/_rels/")) continue;
            Document d = source.xml(name);
            if (is(d.getDocumentElement(), B, "Sources")) parts.add(new Part(name, d));
        }
        return parts;
    }

    private static void ensureProperties(DocxComparisonFinalizer.Package pkg, String part) throws Exception {
        String relsName = relationships(part);
        if (pkg.entries.containsKey(relsName)) for (Element rel : children(pkg.xml(relsName).getDocumentElement()))
            if (is(rel, REL, "Relationship") && (OFFICE_REL + "customXmlProps").equals(rel.getAttribute("Type"))
                    && !"External".equals(rel.getAttribute("TargetMode"))) {
                String props = resolve(part, rel.getAttribute("Target"));
                if (pkg.entries.containsKey(props)) {
                    ensureContentType(pkg, props, PROPS_TYPE);
                    return;
                }
            }
        String props = unused(pkg, "customXml/awdBibliographyProps", ".xml");
        Document d = document(DS, "ds:datastoreItem");
        d.getDocumentElement().setAttributeNS(DS, "ds:itemID", "{" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT) + "}");
        Element refs = d.createElementNS(DS, "ds:schemaRefs"), ref = d.createElementNS(DS, "ds:schemaRef");
        ref.setAttributeNS(DS, "ds:uri", B); refs.appendChild(ref); d.getDocumentElement().appendChild(refs);
        pkg.putXml(props, d);
        ensureRelationship(pkg, part, OFFICE_REL + "customXmlProps", props);
        ensureContentType(pkg, props, PROPS_TYPE);
    }

    private static void ensureRelationship(DocxComparisonFinalizer.Package pkg, String from, String type, String to) throws Exception {
        String name = relationships(from);
        Document d = pkg.entries.containsKey(name) ? pkg.xml(name) : document(REL, "Relationships");
        Set<String> ids = new HashSet<>();
        for (Element rel : children(d.getDocumentElement())) if (is(rel, REL, "Relationship")) {
            ids.add(rel.getAttribute("Id"));
            if (type.equals(rel.getAttribute("Type")) && !"External".equals(rel.getAttribute("TargetMode"))
                    && to.equals(resolve(from, rel.getAttribute("Target")))) return;
        }
        int index = 1; while (ids.contains("rIdBibliography" + index)) index++;
        Element rel = d.createElementNS(REL, "Relationship");
        rel.setAttribute("Id", "rIdBibliography" + index); rel.setAttribute("Type", type);
        String parent = from.substring(0, from.lastIndexOf('/') + 1);
        // Both callers originate in word/ or customXml/; absolute package targets avoid path ambiguity.
        rel.setAttribute("Target", to.startsWith(parent) ? to.substring(parent.length()) : "/" + to);
        d.getDocumentElement().appendChild(rel); pkg.putXml(name, d);
    }

    private static void ensureContentType(DocxComparisonFinalizer.Package pkg, String part, String type) throws Exception {
        Document d = pkg.xml("[Content_Types].xml");
        for (Element e : children(d.getDocumentElement())) if (is(e, CT, "Override") && ("/" + part).equals(e.getAttribute("PartName"))) {
            if (!type.equals(e.getAttribute("ContentType"))) throw new IllegalArgumentException("参考文献部件类型不匹配：" + part);
            return;
        }
        Element e = d.createElementNS(CT, "Override"); e.setAttribute("PartName", "/" + part); e.setAttribute("ContentType", type);
        d.getDocumentElement().appendChild(e); pkg.putXml("[Content_Types].xml", d);
    }

    private static String resolve(String from, String target) { return URI.create("https://package/" + from).resolve(target).getPath().substring(1); }
    private static String relationships(String part) { int i = part.lastIndexOf('/'); return part.substring(0, i + 1) + "_rels/" + part.substring(i + 1) + ".rels"; }
    private static String unused(DocxComparisonFinalizer.Package pkg, String stem, String suffix) {
        String name = stem + suffix; for (int i = 1; pkg.entries.containsKey(name); i++) name = stem + i + suffix; return name;
    }
    private static Document document(String ns, String name) throws Exception {
        Document d = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().newDocument();
        d.appendChild(d.createElementNS(ns, name)); return d;
    }
    private static boolean is(Node n, String ns, String local) { return ns.equals(n.getNamespaceURI()) && local.equals(n.getLocalName()); }
    private static List<Element> children(Node parent) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e) result.add(e);
        return result;
    }
    private static String canonical(Element e) {
        StringBuilder b = new StringBuilder("{" + e.getNamespaceURI() + "}" + e.getLocalName());
        List<String> attrs = new ArrayList<>();
        for (int i = 0; i < e.getAttributes().getLength(); i++) {
            Node a = e.getAttributes().item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI()))
                attrs.add("{" + a.getNamespaceURI() + "}" + a.getLocalName() + "=" + a.getNodeValue());
        }
        Collections.sort(attrs); b.append(attrs).append('[');
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) b.append(canonical(child));
            else if ((n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) && !n.getNodeValue().isBlank())
                b.append(n.getNodeValue().length()).append(':').append(n.getNodeValue());
        }
        return b.append(']').toString();
    }
}
