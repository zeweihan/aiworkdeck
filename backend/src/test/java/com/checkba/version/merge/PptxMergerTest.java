// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PptxMergerTest {

    private static final List<String> TITLES = List.of("第一页", "第二页", "第三页");
    private static final List<String> BODIES = List.of(
            "甲方提供的第一份材料说明。",
            "关于付款期限与违约责任的约定。",
            "附件清单与交付方式。");

    @Test
    @DisplayName("自动模式只换另一侧改过的那几页，主线改过的页不动")
    void autoReplacesOtherChangedSlides() throws Exception {
        byte[] base = MergeFixtures.pptx(TITLES, BODIES);
        byte[] main = MergeFixtures.editSlide(base, 0, "第一页", "主线改过的第一页正文。");
        byte[] other = MergeFixtures.editSlide(base, 2, "第三页", "另一侧改过的第三页正文。");

        Analysis analysis = ThreeWayAnalyzer.analyze("提纲.pptx", base, main, other);
        assertEquals(MergeDecision.AUTO, analysis.decision(), "两边改的不是同一页，应当能自动合");

        byte[] merged = PptxMerger.merge(main, other, analysis, List.of());

        List<String> texts = MergeFixtures.slideTexts(merged);
        assertEquals(3, texts.size());
        assertTrue(texts.get(0).contains("主线改过的第一页正文。"), "主线改过的页应当保持主线的内容：" + texts.get(0));
        assertTrue(texts.get(1).contains(BODIES.get(1)), "两边都没动的页应当原样留着：" + texts.get(1));
        assertTrue(texts.get(2).contains("另一侧改过的第三页正文。"), "另一侧改过的页应当合进来：" + texts.get(2));
        assertFalse(texts.get(2).contains(BODIES.get(2)), "换页是替换内容，不是把两边的文字叠在一起：" + texts.get(2));
    }

    @Test
    @DisplayName("另一侧删掉的页跟着删、新增的页跟着加")
    void insertsAndDeletesSlides() throws Exception {
        byte[] base = MergeFixtures.pptx(TITLES, BODIES);
        byte[] main = MergeFixtures.editSlide(base, 0, "第一页", "主线改过的第一页正文。");
        byte[] other = MergeFixtures.appendSlide(MergeFixtures.removeSlide(base, 1), "第四页", "另一侧新增的一页。");

        Analysis analysis = ThreeWayAnalyzer.analyze("提纲.pptx", base, main, other);
        assertEquals(MergeDecision.AUTO, analysis.decision());

        byte[] merged = PptxMerger.merge(main, other, analysis, List.of());

        assertEquals(List.of("第一页", "第三页", "第四页"), MergeFixtures.slideTitles(merged));
        assertTrue(MergeFixtures.slideTexts(merged).get(0).contains("主线改过的第一页正文。"));
        assertTrue(MergeFixtures.slideTexts(merged).get(2).contains("另一侧新增的一页。"));
    }

    @Test
    @DisplayName("逐页裁决：判给另一侧的换成另一侧的、判给主线的原样不动")
    void manualPerSlideChoice() throws Exception {
        byte[] base = MergeFixtures.pptx(TITLES, BODIES);
        byte[] main = MergeFixtures.editSlide(
                MergeFixtures.editSlide(base, 1, "第二页", "主线写的第二页。"), 2, "第三页", "主线写的第三页。");
        byte[] other = MergeFixtures.editSlide(
                MergeFixtures.editSlide(base, 1, "第二页", "同事写的第二页。"), 2, "第三页", "同事写的第三页。");

        Analysis analysis = ThreeWayAnalyzer.analyze("提纲.pptx", base, main, other);
        assertEquals(MergeDecision.MANUAL, analysis.decision(), "同一页两边都改了，应当交给律师逐页裁决");

        byte[] merged = PptxMerger.merge(main, other, analysis, List.of(
                new Decision("s2", "T", "A"),
                new Decision("s3", "M", "A")));

        List<String> texts = MergeFixtures.slideTexts(merged);
        assertTrue(texts.get(1).contains("同事写的第二页。"), "第二页判给了另一侧：" + texts.get(1));
        assertTrue(texts.get(2).contains("主线写的第三页。"), "第三页判给了主线：" + texts.get(2));
    }

    @Test
    @DisplayName("导出件没保留页的稳定身份时，退回按标题加文本相似度对齐")
    void fallsBackToSimilarityWhenSldIdMissing() throws Exception {
        byte[] base = MergeFixtures.pptx(TITLES, BODIES);
        byte[] main = base;
        byte[] edited = MergeFixtures.editSlide(base, 1, "第二页", BODIES.get(1) + "补充：以上金额均含税。");
        // 页序打乱 + 全部换成对不上的 sldId，模拟「引擎导出件不保留 sldId」
        byte[] other = MergeFixtures.withSlideIds(MergeFixtures.reorder(edited, 2, 0, 1), 900, 901, 902);

        Analysis analysis = ThreeWayAnalyzer.analyze("提纲.pptx", base, main, other);
        byte[] merged = PptxMerger.merge(main, other, analysis, List.of());

        assertEquals(List.of("第一页", "第二页", "第三页"), MergeFixtures.slideTitles(merged),
                "sldId 对不上时不该把整份当成「删光再新增」");
        List<String> texts = MergeFixtures.slideTexts(merged);
        assertTrue(texts.get(1).contains("补充：以上金额均含税。"),
                "另一侧改过的那一页应当按相似度对上并合进来：" + texts.get(1));
        assertTrue(texts.get(0).contains(BODIES.get(0)));
        assertTrue(texts.get(2).contains(BODIES.get(2)));
    }
}
