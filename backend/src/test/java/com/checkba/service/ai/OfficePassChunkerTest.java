package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整篇过卷的切块规则（dev-board#422）。纯函数，不碰 Spring。
 *
 * <p>切块只是「本轮请你处理的工作面」——模型的内联正文一字不动。所以这里钉住的
 * 全是机械不变式：段落不被切断、序号与内联正文一一对应、同输入两次结果相等。
 */
class OfficePassChunkerTest {

    /** 造一段长度可控的正文：n 个段落，每段 chars 个字。 */
    private static String doc(int paragraphs, int chars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            if (i > 0) sb.append('\n');
            sb.append("第").append(i + 1).append("段").append("字".repeat(Math.max(0, chars - 3)));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("段落边界不被切断：每块的段落序号区间连续且首尾相接，覆盖全文")
    void chunksCoverEveryParagraphWithoutSplitting() {
        String content = doc(40, 300); // 40 段 × 300 字 = 12000 字
        List<OfficePassChunker.Chunk> chunks = OfficePassChunker.chunk(content);

        assertFalse(chunks.isEmpty(), "切不出块说明规则坏了");
        assertEquals(1, chunks.get(0).fromNo(), "第一块必须从第 1 段开始");
        assertEquals(40, chunks.get(chunks.size() - 1).toNo(), "最后一块必须收在最后一段");
        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(chunks.get(i - 1).toNo() + 1, chunks.get(i).fromNo(),
                    "块与块之间不能有断档或重叠（第 " + i + " 块）");
        }
    }

    @Test
    @DisplayName("目标块大小 2500 字：正常段落按目标大小成块，不出现一段一块")
    void chunksAreAroundTargetSize() {
        String content = doc(40, 300);
        List<OfficePassChunker.Chunk> chunks = OfficePassChunker.chunk(content);
        // 12000 字 / 2500 ≈ 5 块上下；一段一块（40 块）说明累积逻辑没生效
        assertTrue(chunks.size() >= 3 && chunks.size() <= 8,
                "12000 字应切成个位数块，实际 " + chunks.size() + " 块");
    }

    @Test
    @DisplayName("超过 2 倍目标的单段独立成块，不拆段")
    void oversizedParagraphBecomesItsOwnChunk() {
        String huge = "巨".repeat(6000); // > 2 × 2500
        String content = "前面一段" + "\n" + huge + "\n" + "后面一段";
        List<OfficePassChunker.Chunk> chunks = OfficePassChunker.chunk(content);

        boolean standalone = chunks.stream().anyMatch(c -> c.fromNo() == 2 && c.toNo() == 2);
        assertTrue(standalone, "6000 字的第 2 段应独立成块，实际块为 " + chunks);
    }

    @Test
    @DisplayName("空段落计入序号但不计入块内容：序号与内联正文按 \\n 切出来的行一一对应")
    void blankParagraphsKeepNumberingButAddNoContent() {
        String content = "第一段\n\n\n第四段";
        List<OfficePassChunker.Paragraph> all = OfficePassChunker.paragraphs(content);
        assertEquals(4, all.size(), "按 \\n 切出四行，空行也要占序号");
        assertEquals(1, all.get(0).no());
        assertEquals(4, all.get(3).no());
        assertEquals("第四段", all.get(3).text());

        List<OfficePassChunker.Chunk> chunks = OfficePassChunker.chunk(content);
        assertEquals(1, chunks.size());
        List<OfficePassChunker.Paragraph> body = OfficePassChunker.paragraphsOf(content, chunks.get(0));
        assertEquals(2, body.size(), "空段落不进块内容");
        assertEquals(List.of(1, 4), body.stream().map(OfficePassChunker.Paragraph::no).toList());
    }

    @Test
    @DisplayName("块数上限 60：超长文档抬高目标块大小，而不是切出几百块")
    void veryLongDocumentIsCappedAtMaxChunks() {
        String content = doc(1200, 500); // 60 万字
        List<OfficePassChunker.Chunk> chunks = OfficePassChunker.chunk(content);
        assertTrue(chunks.size() <= 60, "块数上限 60，实际 " + chunks.size());
        assertTrue(chunks.size() >= 40, "抬高块大小不该抬到只剩几块，实际 " + chunks.size());
    }

    @Test
    @DisplayName("单段超长且段数超上限时仍然收敛到 60 块以内（兜底不死循环）")
    void manyOversizedParagraphsStillCapped() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            if (i > 0) sb.append('\n');
            sb.append("段".repeat(8000));
        }
        List<OfficePassChunker.Chunk> chunks = OfficePassChunker.chunk(sb.toString());
        assertTrue(chunks.size() <= 60, "块数上限 60，实际 " + chunks.size());
    }

    @Test
    @DisplayName("同输入两次切块结果完全相等（块边界在一次过卷内不变的前提）")
    void chunkingIsDeterministic() {
        String content = doc(137, 411);
        assertEquals(OfficePassChunker.chunk(content), OfficePassChunker.chunk(content));
    }

    @Test
    @DisplayName("单段短文档切成一块")
    void singleShortParagraph() {
        List<OfficePassChunker.Chunk> chunks = OfficePassChunker.chunk("就一句话。");
        assertEquals(1, chunks.size());
        assertEquals(1, chunks.get(0).fromNo());
        assertEquals(1, chunks.get(0).toNo());
    }
}
