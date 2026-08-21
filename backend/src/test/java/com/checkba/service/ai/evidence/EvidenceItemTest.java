package com.checkba.service.ai.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 审计条目：「Excerpt truncation cuts by UTF-16 char index and can split a surrogate pair,
 * corrupting the tail of an evidence excerpt」。excerpt 超过 500 字符时被
 * {@code substring(0, 500)} 截断——如果第 500 个字符位置恰好劈开一个 UTF-16 代理对
 * （增补平面字符，如罕见人名字/emoji），结果会以一个孤立代理项收尾。
 */
@DisplayName("EvidenceItem：excerpt 截断不能劈开代理对")
class EvidenceItemTest {

    private static EvidenceItem item(String excerpt) {
        return new EvidenceItem("ev-1", "checkba://file/1", "hash", LocalDateTime.now(),
                null, "第1段", excerpt, "text/plain", "project", List.of(), null, null, null);
    }

    @Test
    @DisplayName("修复：截断点恰好落在代理对中间时，整个代理对一起舍弃，不留孤立代理项")
    void truncationDoesNotSplitSurrogatePair() {
        // "𠮷"（U+20BB7）在 UTF-16 里是高/低两个 char 的代理对；放在第 499/500 个字符位置，
        // 恰好卡在 MAX_EXCERPT_LENGTH=500 的截断点中间
        String excerpt = "A".repeat(499) + "𠮷" + "更多正文内容在后面补到超过五百字".repeat(10);

        EvidenceItem evidence = item(excerpt);

        assertEquals(499, evidence.excerpt().length(),
                "应回退到代理对开始之前，不能截出一个孤立代理项: " + evidence.excerpt());
        assertEquals("A".repeat(499), evidence.excerpt());
        assertFalse(Character.isSurrogate(evidence.excerpt().charAt(evidence.excerpt().length() - 1)),
                "结尾不该是孤立的代理项");
    }

    @Test
    @DisplayName("对照：不含代理对的普通超长摘录，截断行为与此前一致（恰好 500 字符）")
    void truncationMatchesPlainSubstringWithoutSurrogates() {
        String excerpt = "A".repeat(600);

        EvidenceItem evidence = item(excerpt);

        assertEquals(EvidenceItem.MAX_EXCERPT_LENGTH, evidence.excerpt().length());
        assertEquals("A".repeat(500), evidence.excerpt());
    }

    @Test
    @DisplayName("缺定位符仍然拒绝（本次改动不影响既有硬约束）")
    void stillRejectsMissingLocator() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceItem(
                "ev-1", "checkba://file/1", "hash", LocalDateTime.now(),
                null, "", "excerpt", "text/plain", "project", List.of(), null, null, null));
    }
}
