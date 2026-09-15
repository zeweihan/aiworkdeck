// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.sensitive;

import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.*;

/** Read Word text at XML paragraph boundaries, including text boxes, notes and nested tables. */
public final class SensitiveDocx {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final Map<String, byte[]> entries = new LinkedHashMap<>();
    private final Map<String, Document> documents = new LinkedHashMap<>();
    private final List<List<Node>> paragraphs = new ArrayList<>();

    public SensitiveDocx(Path source) throws Exception {
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] bytes = zip.readNBytes(32 * 1024 * 1024 + 1);
                total += bytes.length;
                if (bytes.length > 32 * 1024 * 1024 || total > 128 * 1024 * 1024) {
                    throw new IllegalArgumentException("Word 文件解压后过大，请拆分处理");
                }
                if (entries.putIfAbsent(entry.getName(), bytes) != null) throw new IllegalArgumentException("Word 文件存在重复条目");
                if (entry.getName().startsWith("word/") && entry.getName().endsWith(".xml")) {
                    Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
                    documents.put(entry.getName(), document);
                    NodeList nodes = document.getElementsByTagNameNS(W, "p");
                    for (int i = 0; i < nodes.getLength(); i++) {
                        List<Node> texts = new ArrayList<>();
                        collect(nodes.item(i), texts, true);
                        if (!texts.isEmpty()) paragraphs.add(texts);
                    }
                }
            }
        }
        if (!documents.containsKey("word/document.xml")) throw new IllegalArgumentException("不是有效的 DOCX 文件");
    }

    private static void collect(Node node, List<Node> texts, boolean root) {
        if (!root && W.equals(node.getNamespaceURI()) && "p".equals(node.getLocalName())) return;
        if (W.equals(node.getNamespaceURI()) && Set.of("t", "delText", "instrText").contains(node.getLocalName())) {
            texts.add(node);
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) collect(child, texts, false);
    }

    public List<String> texts() {
        return paragraphs.stream().map(SensitiveDocx::text).toList();
    }

    private static String text(List<Node> nodes) {
        StringBuilder result = new StringBuilder();
        nodes.forEach(node -> result.append(node.getTextContent()));
        return result.toString();
    }

    public void transform(Function<String, List<SensitiveTextEngine.Edit>> transform) {
        for (List<Node> nodes : paragraphs) {
            String text = text(nodes);
            List<SensitiveTextEngine.Edit> edits = transform.apply(text);
            int[] starts = new int[nodes.size()];
            int length = 0;
            for (int i = 0; i < nodes.size(); i++) {
                starts[i] = length;
                length += nodes.get(i).getTextContent().length();
            }
            // Work backwards: only the characters covered by a match move; untouched runs keep their formatting.
            for (int k = edits.size() - 1; k >= 0; k--) {
                var edit = edits.get(k);
                for (int i = nodes.size() - 1; i >= 0; i--) {
                    Node node = nodes.get(i);
                    int end = i + 1 < nodes.size() ? starts[i + 1] : length;
                    if (starts[i] >= edit.end() || end <= edit.start()) continue;
                    String value = node.getTextContent();
                    int from = Math.max(0, edit.start() - starts[i]);
                    int to = Math.min(end, edit.end()) - starts[i];
                    String insertion = edit.start() >= starts[i] ? edit.replacement() : "";
                    node.setTextContent(value.substring(0, from) + insertion + value.substring(to));
                    ((Element) node).setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
                }
            }
        }
    }

    public void write(Path destination) throws Exception {
        TransformerFactory factory = TransformerFactory.newDefaultInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = factory.newTransformer();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                Document document = documents.get(entry.getKey());
                if (document == null) zip.write(entry.getValue());
                else transformer.transform(new DOMSource(document), new StreamResult(zip));
                zip.closeEntry();
            }
        }
    }
}
