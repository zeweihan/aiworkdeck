package com.checkba.service.insight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条号转换与引用字样剥离（dev-board#181 升级件二）。
 *
 * <p>法宝 {@code adjust_provisions} 只收阿拉伯数字条号，转不动必须返回 null 让调用方跳过——
 * <b>猜一个条号打上去比不打更糟</b>：拿错条号问回来的权威原文会被当成「这条就是这么写的」。
 */
class LawArticleNumbersTest {

    @Test
    @DisplayName("十位组合：第二十条 / 第十条 / 二十一")
    void 十位() {
        assertEquals("20", LawArticleNumbers.toArabic("第二十条"));
        assertEquals("10", LawArticleNumbers.toArabic("第十条"));
        assertEquals("21", LawArticleNumbers.toArabic("二十一"));
        assertEquals("11", LawArticleNumbers.toArabic("第十一条"));
        assertEquals("99", LawArticleNumbers.toArabic("第九十九条"));
    }

    @Test
    @DisplayName("百千组合与「零」")
    void 百千() {
        assertEquals("108", LawArticleNumbers.toArabic("第一百零八条"));
        assertEquals("100", LawArticleNumbers.toArabic("第一百条"));
        assertEquals("115", LawArticleNumbers.toArabic("第一百一十五条"));
        assertEquals("3500", LawArticleNumbers.toArabic("第三千五百条"));
        assertEquals("1260", LawArticleNumbers.toArabic("第一千二百六十条"));
    }

    @Test
    @DisplayName("「之N」变成小数位，绝不丢")
    void 之号() {
        assertEquals("20.1", LawArticleNumbers.toArabic("第二十条之一"));
        assertEquals("133.2", LawArticleNumbers.toArabic("第一百三十三条之二"));
        assertEquals("20.1", LawArticleNumbers.toArabic("第20条之1"));
    }

    @Test
    @DisplayName("已经是阿拉伯数字的原样返回（含全角与「20.1」形态）")
    void 阿拉伯() {
        assertEquals("9999", LawArticleNumbers.toArabic("第9999条"));
        assertEquals("15", LawArticleNumbers.toArabic("15"));
        assertEquals("20.1", LawArticleNumbers.toArabic("20.1"));
        assertEquals("15", LawArticleNumbers.toArabic("１５"));   // 全角数字（compact 走 NFKC）
        assertEquals("15", LawArticleNumbers.toArabic(" 第 15 条 "));
    }

    @Test
    @DisplayName("转不动一律 null：空、认不出的字、零条")
    void 转不动() {
        assertNull(LawArticleNumbers.toArabic(null));
        assertNull(LawArticleNumbers.toArabic(""));
        assertNull(LawArticleNumbers.toArabic("   "));
        assertNull(LawArticleNumbers.toArabic("第若干条"));
        assertNull(LawArticleNumbers.toArabic("本条"));
        assertNull(LawArticleNumbers.toArabic("第零条"));
        assertNull(LawArticleNumbers.toArabic("第二十条之零"));
    }

    @Test
    @DisplayName("内容线索：剥掉书名号与条号引用字样，只留正文内容")
    void 内容线索() {
        assertEquals("公司股东应当遵守法律、行政法规和公司章程",
                LawArticleNumbers.contentClue(
                        "依据《中华人民共和国公司法》第二十条，公司股东应当遵守法律、行政法规和公司章程"));
        assertEquals("董事、监事不得利用职权收受贿赂",
                LawArticleNumbers.contentClue("《公司法》第一百四十七条第一款规定：董事、监事不得利用职权收受贿赂"));
    }

    @Test
    @DisplayName("整句都是引用字样时线索为空（调用方据此不发 answerlaw）")
    void 没有线索() {
        assertEquals("", LawArticleNumbers.contentClue("《中华人民共和国公司法》第二十条"));
        assertEquals("", LawArticleNumbers.contentClue("根据《公司法》第二十条，"));
        assertEquals("", LawArticleNumbers.contentClue(null));
        assertTrue(LawArticleNumbers.contentClue("依据《公司法》第二十条").length() < 12);
    }
}
