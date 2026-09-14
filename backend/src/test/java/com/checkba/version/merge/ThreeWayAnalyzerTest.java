// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三方比对的判定口径。
 *
 * <p>最要紧的一条是「相邻也算冲突」：同事改了第 3 段、我改了第 4 段，中间没有未改动的单元隔开时
 * 一律进人工裁决。宁可多问律师一次，也不能把两处挨着的改动静默叠成病句
 * （spike A3 里 {@code .uno:MergeDocuments} 正是这样静默叠加的，所以被否决）。
 */
class ThreeWayAnalyzerTest {

    private static final List<String> BASE_PARAGRAPHS = List.of(
            "第一条 甲方",
            "第二条 乙方",
            "第三条 标的",
            "第四条 价款",
            "第五条 交付",
            "第六条 违约");

    private static byte[] paragraphs(java.util.function.Consumer<java.util.List<String>> mutate) {
        java.util.List<String> list = new java.util.ArrayList<>(BASE_PARAGRAPHS);
        mutate.accept(list);
        return MergeFixtures.docx(list.toArray(new String[0]));
    }

    private static Chunk chunkCovering(List<Chunk> chunks, int baseStart) {
        return chunks.stream().filter(c -> c.baseStart() == baseStart).findFirst()
                .orElseThrow(() -> new AssertionError("没有覆盖基线 " + baseStart + " 的块：" + chunks));
    }

    // ---------------------------------------------------------------- docx

    @Test
    @DisplayName("两边改了互不相邻的段落：自动合并")
    void disjointParagraphEditsAreAuto() {
        byte[] base = MergeFixtures.docx(BASE_PARAGRAPHS.toArray(new String[0]));
        byte[] main = paragraphs(l -> l.set(1, "第二条 乙方（已核对）"));
        byte[] other = paragraphs(l -> l.set(4, "第五条 交付（改为货到付款）"));

        Analysis a = ThreeWayAnalyzer.analyze("合同.docx", base, main, other);

        assertEquals(MergeKind.DOCX, a.kind());
        assertEquals(MergeDecision.AUTO, a.decision());
        assertEquals(MergeReason.CLEAN, a.reason());
        assertEquals(1, a.mainChanges());
        assertEquals(1, a.otherChanges());
        assertTrue(a.overlaps().isEmpty());
        assertEquals(BASE_PARAGRAPHS.size(), a.baseUnits().size());

        Chunk otherChunk = chunkCovering(a.plan().otherChunks(), 4);
        assertEquals("MODIFY", otherChunk.type());
        assertEquals(5, otherChunk.baseEnd());
        assertEquals(List.of("第五条 交付（改为货到付款）"), otherChunk.texts());
        assertFalse(otherChunk.conflict());

        Chunk mainChunk = chunkCovering(a.plan().mainChunks(), 1);
        assertEquals(List.of("第二条 乙方（已核对）"), mainChunk.texts());
    }

    @Test
    @DisplayName("同一段两边都改了：人工裁决，overlap 带三栏文字")
    void sameParagraphIsManualWithOverlapTexts() {
        byte[] base = MergeFixtures.docx(BASE_PARAGRAPHS.toArray(new String[0]));
        byte[] main = paragraphs(l -> l.set(2, "第三条 标的（我改的）"));
        byte[] other = paragraphs(l -> l.set(2, "第三条 标的（律师乙改的）"));

        Analysis a = ThreeWayAnalyzer.analyze("合同.docx", base, main, other);

        assertEquals(MergeDecision.MANUAL, a.decision());
        assertEquals(MergeReason.OVERLAP, a.reason());
        assertEquals(1, a.overlaps().size());
        Overlap o = a.overlaps().get(0);
        assertEquals("p2", o.key());
        assertEquals("第三条 标的", o.baseText());
        assertEquals("第三条 标的（我改的）", o.mainText());
        assertEquals("第三条 标的（律师乙改的）", o.otherText());
        assertTrue(a.plan().otherChunks().stream().anyMatch(Chunk::conflict),
                "冲突块要在计划里标出来，引擎才知道这一段不重放");
    }

    @Test
    @DisplayName("相邻两段各改一边：也算冲突，进人工裁决")
    void adjacentParagraphsAreManual() {
        byte[] base = MergeFixtures.docx(BASE_PARAGRAPHS.toArray(new String[0]));
        byte[] main = paragraphs(l -> l.set(2, "第三条 标的（我改的）"));
        byte[] other = paragraphs(l -> l.set(3, "第四条 价款（律师乙改的）"));

        Analysis a = ThreeWayAnalyzer.analyze("合同.docx", base, main, other);

        assertEquals(MergeDecision.MANUAL, a.decision());
        assertEquals(MergeReason.OVERLAP, a.reason());
        assertEquals(List.of("p2", "p3"), a.overlaps().stream().map(Overlap::key).toList());
    }

    @Test
    @DisplayName("表格单元与它紧挨着的段落分属两边：相邻，进人工裁决")
    void tableCellNextToEditedParagraphIsManual() {
        byte[] base = MergeFixtures.docxWithTable(
                List.of("表前说明"), new String[][]{{"注册资本 100 万"}}, List.of("表后说明"));
        byte[] main = MergeFixtures.docxWithTable(
                List.of("表前说明（我改的）"), new String[][]{{"注册资本 100 万"}}, List.of("表后说明"));
        byte[] other = MergeFixtures.docxWithTable(
                List.of("表前说明"), new String[][]{{"注册资本 200 万"}}, List.of("表后说明"));

        Analysis a = ThreeWayAnalyzer.analyze("尽调表.docx", base, main, other);

        assertEquals(MergeDecision.MANUAL, a.decision());
        assertEquals(List.of("p0", "t0.0.0"), a.overlaps().stream().map(Overlap::key).toList());
    }

    @Test
    @DisplayName("另一边在没人动过的两段之间插了一段：自动合并，计划里是 INSERT 块")
    void otherInsertsParagraphBetweenUntouchedOnes() {
        byte[] base = MergeFixtures.docx(BASE_PARAGRAPHS.toArray(new String[0]));
        byte[] main = paragraphs(l -> l.set(0, "第一条 甲方（我改的）"));
        byte[] other = paragraphs(l -> l.add(4, "第四条之一 付款方式"));

        Analysis a = ThreeWayAnalyzer.analyze("合同.docx", base, main, other);

        assertEquals(MergeDecision.AUTO, a.decision());
        Chunk insert = chunkCovering(a.plan().otherChunks(), 4);
        assertEquals("INSERT", insert.type());
        assertEquals(4, insert.baseEnd());
        assertEquals(List.of("第四条之一 付款方式"), insert.texts());
        assertFalse(insert.conflict());
    }

    @Test
    @DisplayName("另一边删了一段：自动合并，计划里是 DELETE 块")
    void otherDeletesParagraph() {
        byte[] base = MergeFixtures.docx(BASE_PARAGRAPHS.toArray(new String[0]));
        byte[] main = paragraphs(l -> l.set(0, "第一条 甲方（我改的）"));
        byte[] other = paragraphs(l -> l.remove(4));

        Analysis a = ThreeWayAnalyzer.analyze("合同.docx", base, main, other);

        assertEquals(MergeDecision.AUTO, a.decision());
        Chunk delete = chunkCovering(a.plan().otherChunks(), 4);
        assertEquals("DELETE", delete.type());
        assertEquals(5, delete.baseEnd());
        assertTrue(delete.texts().isEmpty());
    }

    // ---------------------------------------------------------------- xlsx

    @Test
    @DisplayName("表格两边改了不同的格：自动合并")
    void xlsxDisjointCellsAuto() {
        byte[] base = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells("A1", "甲", "B1", "乙"));
        byte[] main = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells("A1", "甲（我改的）", "B1", "乙"));
        byte[] other = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells("A1", "甲", "B1", "乙（律师乙改的）"));

        Analysis a = ThreeWayAnalyzer.analyze("台账.xlsx", base, main, other);

        assertEquals(MergeKind.XLSX, a.kind());
        assertEquals(MergeDecision.AUTO, a.decision());
        assertEquals(List.of("Sheet1!A1"), a.mainOnly());
        assertEquals(List.of("Sheet1!B1"), a.otherOnly());
        assertTrue(a.overlaps().isEmpty());
    }

    @Test
    @DisplayName("表格两边改了同一格：人工裁决")
    void xlsxSameCellManual() {
        byte[] base = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells("A1", "甲", "B1", "乙"));
        byte[] main = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells("A1", "甲（我改的）", "B1", "乙"));
        byte[] other = MergeFixtures.xlsx("Sheet1", MergeFixtures.cells("A1", "甲（律师乙改的）", "B1", "乙"));

        Analysis a = ThreeWayAnalyzer.analyze("台账.xlsx", base, main, other);

        assertEquals(MergeDecision.MANUAL, a.decision());
        assertEquals(MergeReason.OVERLAP, a.reason());
        assertEquals(1, a.overlaps().size());
        Overlap o = a.overlaps().get(0);
        assertEquals("Sheet1!A1", o.key());
        assertEquals("甲", o.baseText());
        assertEquals("甲（我改的）", o.mainText());
        assertEquals("甲（律师乙改的）", o.otherText());
    }

    // ---------------------------------------------------------------- pptx

    @Test
    @DisplayName("演示文稿两边改了不同的页：自动合并")
    void pptxDisjointSlidesAuto() {
        byte[] base = MergeFixtures.pptx(
                List.of("一", "二", "三"), List.of("正文一", "正文二", "正文三"));
        byte[] main = MergeFixtures.pptx(
                List.of("一", "二", "三"), List.of("正文一（我改的）", "正文二", "正文三"));
        byte[] other = MergeFixtures.pptx(
                List.of("一", "二", "三"), List.of("正文一", "正文二", "正文三（律师乙改的）"));

        Analysis a = ThreeWayAnalyzer.analyze("汇报.pptx", base, main, other);

        assertEquals(MergeKind.PPTX, a.kind());
        assertEquals(MergeDecision.AUTO, a.decision());
        assertEquals(List.of("s1"), a.mainOnly());
        assertEquals(List.of("s3"), a.otherOnly());
        assertEquals(1, a.plan().otherChunks().size());
        assertFalse(a.plan().otherChunks().get(0).ids().isEmpty(), "pptx 的块要带 sldId");
    }

    @Test
    @DisplayName("两边都调了页序：人工裁决，overlap 里单列一条「页序」")
    void pptxBothReorderIsManual() {
        byte[] base = MergeFixtures.pptx(
                List.of("一", "二", "三"), List.of("正文一", "正文二", "正文三"));
        byte[] main = MergeFixtures.reorder(base, 1, 0, 2);
        byte[] other = MergeFixtures.reorder(base, 0, 2, 1);

        Analysis a = ThreeWayAnalyzer.analyze("汇报.pptx", base, main, other);

        assertEquals(MergeDecision.MANUAL, a.decision());
        assertTrue(a.overlaps().stream().anyMatch(o -> o.key().equals("order")),
                "两边都调了页序要给出一条 order overlap：" + a.overlaps());
    }

    // ---------------------------------------------------------------- 降级

    @Test
    @DisplayName("PDF 没有可比对的单元：整份")
    void pdfIsWholeBinary() {
        byte[] bytes = "%PDF-1.7\n".getBytes(StandardCharsets.UTF_8);

        Analysis a = ThreeWayAnalyzer.analyze("扫描件.pdf", bytes, bytes, bytes);

        assertEquals(MergeKind.WHOLE, a.kind());
        assertEquals(MergeDecision.WHOLE, a.decision());
        assertEquals(MergeReason.BINARY, a.reason());
        assertTrue(a.plan().mainChunks().isEmpty());
        assertTrue(a.baseUnits().isEmpty());
    }

    @Test
    @DisplayName("没有共同的上一版：整份")
    void missingBaseIsWholeNoBase() {
        byte[] main = MergeFixtures.docx("甲");
        byte[] other = MergeFixtures.docx("乙");

        Analysis a = ThreeWayAnalyzer.analyze("合同.docx", null, main, other);

        assertEquals(MergeDecision.WHOLE, a.decision());
        assertEquals(MergeReason.NO_BASE, a.reason());
    }

    @Test
    @DisplayName("读不开的文件：整份，原因写明解析失败")
    void unparseableIsWholeParseFailed() {
        byte[] base = MergeFixtures.docx("甲");
        byte[] broken = "这不是 docx".getBytes(StandardCharsets.UTF_8);

        Analysis a = ThreeWayAnalyzer.analyze("合同.docx", base, broken, base);

        assertEquals(MergeKind.DOCX, a.kind());
        assertEquals(MergeDecision.WHOLE, a.decision());
        assertEquals(MergeReason.PARSE_FAILED, a.reason());
    }

    @Test
    @DisplayName("按扩展名认类型，大小写不敏感")
    void kindOfReadsExtension() {
        assertEquals(MergeKind.DOCX, ThreeWayAnalyzer.kindOf("a/b/合同.DOCX"));
        assertEquals(MergeKind.DOCX, ThreeWayAnalyzer.kindOf("合同.docm"));
        assertEquals(MergeKind.XLSX, ThreeWayAnalyzer.kindOf("台账.xlsm"));
        assertEquals(MergeKind.PPTX, ThreeWayAnalyzer.kindOf("汇报.pptx"));
        assertEquals(MergeKind.WHOLE, ThreeWayAnalyzer.kindOf("扫描件.pdf"));
        assertEquals(MergeKind.WHOLE, ThreeWayAnalyzer.kindOf("没有扩展名"));
        assertEquals(MergeKind.WHOLE, ThreeWayAnalyzer.kindOf(null));
    }
}
