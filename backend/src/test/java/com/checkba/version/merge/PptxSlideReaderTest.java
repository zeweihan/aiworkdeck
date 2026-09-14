// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PptxSlideReaderTest {

    @Test
    @DisplayName("逐页给出 sldId、1 基页序、标题与全页文本")
    void readsSldIdOrdinalTitleText() throws Exception {
        byte[] bytes = MergeFixtures.pptx(
                List.of("第一页标题", "第二页标题"),
                List.of("第一页正文", "第二页正文"));

        List<Slide> slides = PptxSlideReader.read(bytes);

        assertEquals(2, slides.size());
        assertEquals(1, slides.get(0).ordinal());
        assertEquals(2, slides.get(1).ordinal());
        assertEquals("第一页标题", slides.get(0).title());
        assertEquals("第二页标题", slides.get(1).title());
        assertTrue(slides.get(0).text().contains("第一页正文"), "页文本应含正文占位符的文字");
        assertTrue(slides.get(0).text().contains("第一页标题"), "页文本应含标题占位符的文字");
        assertNotNull(slides.get(0).sldId());
        assertFalse(slides.get(0).sldId().isBlank());
        assertNotEquals(slides.get(0).sldId(), slides.get(1).sldId());
    }
}
