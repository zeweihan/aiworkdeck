// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页码范围语法（dev-board#805，审计 A17）。
 *
 * <p>这一层是全部页操作工具共用的唯一解析口径：ranges 串用 <b>1 基</b>页码
 * （与用户在 PDF 阅读器里看到的一致），解析结果一律是 <b>0 基</b>下标。
 * 两套基数同时存在是本卡最容易出错的地方——边界差一页在证据卷宗里就是抽错一页，
 * 所以越界、倒序、0 页、空串一律<b>报错</b>而不是就近夹取：夹取会静默产出一份
 * 看起来正常、内容却不对的卷宗。
 */
class PdfPageRangeTest {

    private static List<Integer> flat(String ranges, int pageCount) {
        return PdfEditService.parsePageRanges(ranges, pageCount);
    }

    @Test
    @DisplayName("1-3,5,8- 在 10 页文档上切成三段，末段开区间到最后一页")
    void parsesTheDocumentedSyntaxIntoSegments() {
        List<PdfEditService.PageRange> segs = PdfEditService.parsePageRangeSegments("1-3,5,8-", 10);
        assertEquals(3, segs.size());
        assertEquals(List.of(0, 1, 2), segs.get(0).pages());
        assertEquals("1-3", segs.get(0).raw());
        assertEquals(List.of(4), segs.get(1).pages());
        assertEquals(List.of(7, 8, 9), segs.get(2).pages());
        assertEquals("8-", segs.get(2).raw());
    }

    @Test
    @DisplayName("展平结果去重并按原文档顺序升序：提页永远不改变页序")
    void flattenedRangesAreDedupedAndAscending() {
        assertEquals(List.of(0, 1, 2), flat("3,1-2,1", 5));
    }

    @Test
    @DisplayName("单页写法")
    void singlePage() {
        assertEquals(List.of(2), flat("3", 5));
    }

    @Test
    @DisplayName("空串与 null 报错，并给出语法示例")
    void blankIsRejectedWithAnExample() {
        for (String bad : new String[]{null, "", "   ", ","}) {
            PdfEditService.PdfEditException e = assertThrows(PdfEditService.PdfEditException.class,
                    () -> flat(bad, 5));
            assertTrue(e.getMessage().contains("1-3"), "错误文案要带语法示例：" + e.getMessage());
        }
    }

    @Test
    @DisplayName("越界报错并说明总页数——夹到最后一页会静默抽错页")
    void outOfRangeIsRejected() {
        PdfEditService.PdfEditException e = assertThrows(PdfEditService.PdfEditException.class,
                () -> flat("4-6", 5));
        assertTrue(e.getMessage().contains("5"), e.getMessage());
        assertThrows(PdfEditService.PdfEditException.class, () -> flat("6", 5));
    }

    @Test
    @DisplayName("0 页报错并点明页码从 1 开始（pdf_inspect 的 pageIndex 是 0 基，模型最容易在这里混）")
    void zeroIsRejectedBecauseRangesAreOneBased() {
        PdfEditService.PdfEditException e = assertThrows(PdfEditService.PdfEditException.class,
                () -> flat("0-2", 5));
        assertTrue(e.getMessage().contains("1"), e.getMessage());
    }

    @Test
    @DisplayName("倒序报错，不擅自交换端点")
    void reversedRangeIsRejected() {
        PdfEditService.PdfEditException e = assertThrows(PdfEditService.PdfEditException.class,
                () -> flat("5-3", 10));
        assertTrue(e.getMessage().contains("5-3"), e.getMessage());
    }

    @Test
    @DisplayName("缺起始页的 -3 报错并列出支持的写法，不猜成 1-3")
    void openStartIsRejected() {
        PdfEditService.PdfEditException e = assertThrows(PdfEditService.PdfEditException.class,
                () -> flat("-3", 10));
        assertTrue(e.getMessage().contains("1-3"), e.getMessage());
    }

    @Test
    @DisplayName("全角逗号与各种破折号归一：中文输入法下写出来的范围串也能用")
    void fullWidthPunctuationIsNormalised() {
        assertEquals(List.of(0, 1, 4), flat("1－2，5", 10));
        assertEquals(List.of(0, 1), flat("1–2", 10));
    }

    @Test
    @DisplayName("非数字报错")
    void garbageIsRejected() {
        assertThrows(PdfEditService.PdfEditException.class, () -> flat("a-b", 5));
        assertThrows(PdfEditService.PdfEditException.class, () -> flat("1-2-3", 5));
    }
}
