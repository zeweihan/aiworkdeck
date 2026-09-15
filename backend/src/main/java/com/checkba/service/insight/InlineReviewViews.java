// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.insight;

import java.util.List;
import java.util.Map;

/** DTOs for the live, non-persistent document review endpoint. */
public final class InlineReviewViews {
    private InlineReviewViews() {}

    public record ParagraphInput(int index, String text) {}

    public record ReviewResult(List<Fact> findings, Map<String, Object> summary,
                               boolean truncated, String scope, boolean deep) {}

    public record Fact(String id, String kind, String severity, String title, String message,
                       int paragraphIndex, Integer start, Integer end, String quote,
                       String expectedParagraph, List<Related> related, String replacement) {}

    public record Related(int paragraphIndex, String quote) {}
}
