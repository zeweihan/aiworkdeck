// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineDeepReviewTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void acceptsOnlyTwoExactSourceQuotesForLogicIssues() {
        String source = "交割日为2026年1月1日。交割日为2026年2月1日。";
        InlineDeepReview.Result result = InlineDeepReview.parse("""
                {"claims":[],"issues":[
                  {"title":"日期不一致","message":"交割日有两种表述",
                   "quote1":"交割日为2026年1月1日","quote2":"交割日为2026年2月1日"},
                  {"title":"编造","message":"x","quote1":"正文不存在","quote2":"交割日为2026年2月1日"}]}
                """, source, om);
        assertTrue(result.valid());
        assertEquals(1, result.issues().size());
    }

    @Test
    void malformedModelOutputIsNotReportedAsCleanReview() {
        InlineDeepReview.Result result = InlineDeepReview.parse("not json", "正文", om);
        assertFalse(result.valid());
        assertTrue(result.claims().isEmpty());
        assertTrue(result.issues().isEmpty());
    }
}
