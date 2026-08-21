package com.checkba.util.style;

import com.checkba.service.ai.AiDocxExportService;
import com.checkba.util.DocxStyleHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.vladsch.flexmark.docx.converter.DocxRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HOUSE 单源对拍：改造前 {@code applyStandardFormat()} 常量版的输出画像（fixtures/house-before.json，
 * 改造前用 DocxProfileReader 读出来存的）必须与改造后「house-default.json → applyProfile」的输出画像逐字段一致。
 *
 * <p>这条红了有两种可能：house-default.json 改了数值（那要同步 worker/插件端），或 applyProfile 的落法变了。
 * 两种都不许静默通过。
 */
class HouseProfileParityTest {

    /** 不参与对拍：时间戳、来源、以及多份投票才有的置信度。 */
    private static final Set<String> VOLATILE = Set.of("learnedAt", "learnedFrom", "confidence");

    static byte[] renderHouse(String md) throws Exception {
        MutableDataSet options = AiDocxExportService.markdownOptions();
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxStyleHelper.addMissingStyles(pkg);
        DocxRenderer.builder(options).build().render(Parser.builder(options).build().parse(md), pkg);
        DocxStyleHelper.applyStandardFormat(pkg);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        pkg.save(bos);
        return bos.toByteArray();
    }

    @Test
    @DisplayName("houseDefault() 渲染结果与改造前 applyStandardFormat 输出逐字段一致")
    void parity() throws Exception {
        String md = new String(getClass().getResourceAsStream("/fixtures/house-parity.md").readAllBytes(), StandardCharsets.UTF_8);
        JsonNode before = StyleProfiles.mapper().readTree(getClass().getResourceAsStream("/fixtures/house-before.json"));
        JsonNode after = DocxProfileReader.read(new ByteArrayInputStream(renderHouse(md))).root();

        List<String> diffs = new ArrayList<>();
        diff("", before, after, diffs);
        assertTrue(diffs.isEmpty(), "改造前后画像不一致：\n" + String.join("\n", diffs));
    }

    @Test
    @DisplayName("house-default.json 的数值与改造前输出一致（单源本身没被改坏）")
    void houseDefaultMatchesBefore() throws Exception {
        JsonNode before = StyleProfiles.mapper().readTree(getClass().getResourceAsStream("/fixtures/house-before.json"));
        StyleProfile house = StyleProfiles.houseDefault();
        JsonNode body = before.get("body");
        assertEquals(body.get("font").get("eastAsia").asText(), house.body().font().eastAsia());
        assertEquals(body.get("font").get("western").asText(), house.body().font().western());
        assertEquals(StyleProfile.Length.read(body.get("size")), house.body().size());
        assertEquals(body.get("alignment").asText(), house.body().alignment());
        assertEquals(StyleProfile.LineSpacing.read(body.get("lineSpacing")), house.body().lineSpacing());
        assertEquals(StyleProfile.Length.read(body.get("spaceAfter")), house.body().spaceAfter());
        assertEquals(StyleProfile.Length.read(body.get("firstLineIndent")), house.body().firstLineIndent());
        for (JsonNode h : before.get("headings")) {
            int level = h.get("level").asInt();
            StyleProfile.Block hb = house.heading(level);
            assertEquals(StyleProfile.Length.read(h.get("size")), hb.size(), "Heading" + level + " size");
            assertEquals(h.get("bold").asBoolean(), hb.bold(), "Heading" + level + " bold");
            assertEquals(h.get("alignment").asText(), hb.alignment(), "Heading" + level + " alignment");
            assertEquals(StyleProfile.Length.read(h.get("firstLineIndent")), hb.firstLineIndent(), "Heading" + level + " indent");
        }
        JsonNode table = before.get("table");
        assertEquals(StyleProfile.Border.read(table.get("borders").get("outside")), house.table().sub("borders").border("outside"));
        assertEquals(StyleProfile.Length.read(table.get("cell").get("size")), house.table().sub("cell").size());
        assertEquals(StyleProfile.LineSpacing.read(table.get("cell").get("lineSpacing")), house.table().sub("cell").lineSpacing());
    }

    static void diff(String path, JsonNode a, JsonNode b, List<String> out) {
        if (a == null || b == null) {
            if (a != b) out.add(path + ": " + a + " -> " + b);
            return;
        }
        if (a.isObject() && b.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = a.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (path.isEmpty() && VOLATILE.contains(e.getKey())) continue;
                diff(path.isEmpty() ? e.getKey() : path + "." + e.getKey(), e.getValue(), b.get(e.getKey()), out);
            }
            Iterator<String> names = b.fieldNames();
            while (names.hasNext()) {
                String n = names.next();
                if (path.isEmpty() && VOLATILE.contains(n)) continue;
                if (!a.has(n)) out.add((path.isEmpty() ? n : path + "." + n) + ": (absent) -> " + b.get(n));
            }
        } else if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) out.add(path + ": size " + a.size() + " -> " + b.size());
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) diff(path + "[" + i + "]", a.get(i), b.get(i), out);
        } else if (!a.equals(b)) {
            if (a.isNumber() && b.isNumber() && Math.abs(a.asDouble() - b.asDouble()) < 1e-9) return;
            out.add(path + ": " + a + " -> " + b);
        }
    }
}
