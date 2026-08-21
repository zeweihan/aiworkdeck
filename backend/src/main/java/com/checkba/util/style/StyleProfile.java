package com.checkba.util.style;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 样式画像 styleProfile v1（schema 见 docs/superpowers/specs/2026-08-21-dd-style-learning-inventory.md 第 2 节）。
 *
 * <p>本质是一棵 Jackson 树 + 一层类型化访问器：每个叶子都可缺省（缺省 = 不约束，写端用
 * {@link StyleProfiles#houseDefault()} 补）。长度字段统一 {@code {value, unit}}，
 * 行距 {@code {rule, value, unit}}，字体分槽 {@code {eastAsia, western, cs, theme}}。
 *
 * <p>访问器全部是「视图」：返回的 {@link Block} 持有子树引用，读不到的字段返回 null，
 * 调用方自己决定回退；不在这里做任何默认值，避免把 HOUSE 常量又散落一处。
 */
public final class StyleProfile {

    private final ObjectNode root;

    StyleProfile(ObjectNode root) {
        this.root = root;
    }

    public ObjectNode root() {
        return root;
    }

    public int schemaVersion() {
        return root.path("schemaVersion").asInt(1);
    }

    public String name() {
        return text(root, "name");
    }

    /** 文档默认字体/字号（docDefaults），可为 null。 */
    public Block defaults() {
        return block(root, "defaults");
    }

    public Block body() {
        return block(root, "body");
    }

    /** 1 基标题级别；没有该级的画像时返回 null。 */
    public Block heading(int level) {
        JsonNode arr = root.get("headings");
        if (arr == null || !arr.isArray()) return null;
        for (JsonNode h : arr) {
            if (h.isObject() && h.path("level").asInt(-1) == level) return new Block((ObjectNode) h);
        }
        return null;
    }

    public List<Block> headings() {
        List<Block> out = new ArrayList<>();
        JsonNode arr = root.get("headings");
        if (arr != null && arr.isArray()) {
            for (JsonNode h : arr) if (h.isObject()) out.add(new Block((ObjectNode) h));
        }
        return out;
    }

    public Block numbering() {
        return block(root, "numbering");
    }

    public Block table() {
        return block(root, "table");
    }

    public Block page() {
        return block(root, "page");
    }

    public Block headerFooter() {
        return block(root, "headerFooter");
    }

    public Block toc() {
        return block(root, "toc");
    }

    /**
     * 叶子级覆盖合并：{@code overrides} 里出现的叶子覆盖本画像同路径的值，对象递归合并，
     * 数组与标量整体替换；{@code headings} 数组按 level 合并（模板只学到两级时不丢 HOUSE 的其余级）。
     * 返回新对象，两边都不改。
     */
    public StyleProfile merge(StyleProfile overrides) {
        ObjectNode merged = root.deepCopy();
        if (overrides != null) mergeInto(merged, overrides.root);
        return new StyleProfile(merged);
    }

    static void mergeInto(ObjectNode target, ObjectNode source) {
        Iterator<Map.Entry<String, JsonNode>> it = source.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            JsonNode sv = e.getValue();
            JsonNode tv = target.get(key);
            if ("headings".equals(key) && sv.isArray() && tv != null && tv.isArray()) {
                target.set(key, mergeHeadings((ArrayNode) tv, (ArrayNode) sv));
            } else if (sv.isObject() && tv != null && tv.isObject()) {
                mergeInto((ObjectNode) tv, (ObjectNode) sv);
            } else {
                target.set(key, sv.deepCopy());
            }
        }
    }

    private static ArrayNode mergeHeadings(ArrayNode base, ArrayNode over) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        List<ObjectNode> merged = new ArrayList<>();
        for (JsonNode b : base) if (b.isObject()) merged.add(b.deepCopy());
        for (JsonNode o : over) {
            if (!o.isObject()) continue;
            int level = o.path("level").asInt(-1);
            ObjectNode hit = null;
            for (ObjectNode m : merged) if (m.path("level").asInt(-2) == level) { hit = m; break; }
            if (hit == null) merged.add(o.deepCopy());
            else mergeInto(hit, (ObjectNode) o);
        }
        merged.sort((a, b) -> Integer.compare(a.path("level").asInt(0), b.path("level").asInt(0)));
        merged.forEach(out::add);
        return out;
    }

    // ------------------------------------------------------------------ 叶子类型

    /** 长度：{@code {value, unit}}，unit ∈ pt|chars|lines|percent|mm|cm|twips。 */
    public record Length(double value, String unit) {
        public static Length of(double value, String unit) {
            return new Length(value, unit);
        }

        public static Length read(JsonNode n) {
            if (n == null || n.isNull() || n.isMissingNode()) return null;
            if (n.isNumber()) return new Length(n.asDouble(), "pt");
            if (!n.isObject() || !n.has("value")) return null;
            return new Length(n.path("value").asDouble(), n.path("unit").asText("pt"));
        }

        public ObjectNode toNode() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            putNumber(o, "value", value);
            o.put("unit", unit);
            return o;
        }
    }

    /** 行距：rule ∈ auto|atLeast|exactly；auto 时 value 是倍数（无 unit）。 */
    public record LineSpacing(String rule, double value, String unit) {
        public static LineSpacing read(JsonNode n) {
            if (n == null || !n.isObject() || !n.has("rule")) return null;
            return new LineSpacing(n.path("rule").asText("auto"), n.path("value").asDouble(1.0),
                    n.hasNonNull("unit") ? n.get("unit").asText() : null);
        }

        public ObjectNode toNode() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.put("rule", rule);
            putNumber(o, "value", value);
            if (unit != null) o.put("unit", unit);
            return o;
        }
    }

    /** 字体分槽；theme 记槽位名（minorHAnsi 之类），eastAsia/western 记解析后的实际名。 */
    public record Font(String eastAsia, String western, String cs, String theme) {
        public static Font read(JsonNode n) {
            if (n == null || !n.isObject()) return null;
            String western = text(n, "western");
            if (western == null) western = text(n, "ascii");
            if (western == null) western = text(n, "hAnsi");
            return new Font(text(n, "eastAsia"), western, text(n, "cs"), text(n, "theme"));
        }

        public ObjectNode toNode() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            if (eastAsia != null) o.put("eastAsia", eastAsia);
            if (western != null) o.put("western", western);
            if (cs != null) o.put("cs", cs);
            if (theme != null) o.put("theme", theme);
            return o;
        }
    }

    /** 边框：{@code {style, width, color}}。 */
    public record Border(String style, Length width, String color) {
        public static Border read(JsonNode n) {
            if (n == null || !n.isObject()) return null;
            return new Border(text(n, "style"), Length.read(n.get("width")), text(n, "color"));
        }
    }

    /** 任意对象子树的类型化视图。 */
    public static final class Block {
        private final ObjectNode node;

        public Block(ObjectNode node) {
            this.node = node;
        }

        public ObjectNode node() {
            return node;
        }

        public boolean has(String key) {
            return node.hasNonNull(key);
        }

        public Block sub(String key) {
            return block(node, key);
        }

        public String string(String key) {
            return text(node, key);
        }

        public Boolean bool(String key) {
            JsonNode n = node.get(key);
            return n == null || !n.isBoolean() ? null : n.asBoolean();
        }

        public Integer integer(String key) {
            JsonNode n = node.get(key);
            return n == null || !n.isNumber() ? null : n.asInt();
        }

        public Length length(String key) {
            return Length.read(node.get(key));
        }

        public LineSpacing lineSpacing() {
            return LineSpacing.read(node.get("lineSpacing"));
        }

        public Font font() {
            return Font.read(node.get("font"));
        }

        public Border border(String key) {
            return Border.read(node.get(key));
        }

        public Length size() {
            return length("size");
        }

        public Boolean bold() {
            return bool("bold");
        }

        public String alignment() {
            return string("alignment");
        }

        public String color() {
            return string("color");
        }

        public Length spaceBefore() {
            return length("spaceBefore");
        }

        public Length spaceAfter() {
            return length("spaceAfter");
        }

        public Length firstLineIndent() {
            return length("firstLineIndent");
        }

        public Length leftIndent() {
            return length("leftIndent");
        }

        public Integer level() {
            return integer("level");
        }

        public Block numbering() {
            return sub("numbering");
        }
    }

    // ------------------------------------------------------------------ helpers

    static Block block(JsonNode parent, String key) {
        JsonNode n = parent == null ? null : parent.get(key);
        return n != null && n.isObject() ? new Block((ObjectNode) n) : null;
    }

    static String text(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
    }

    static void putNumber(ObjectNode o, String key, double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) o.put(key, (long) v);
        else o.put(key, Math.round(v * 1000.0) / 1000.0);
    }
}
