package com.checkba.service.ai.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * claim_link 不变式：证据缺失绝不允许被改写为矛盾。
 */
class ClaimLinkTest {

    @Test
    void missingEvidenceMustNeverBecomeContradiction() {
        // 没有任何援引证据时标 CONTRADICTS —— 契约必须拒绝
        assertThrows(IllegalArgumentException.class, () -> new ClaimLink(
                "claim-1", List.of(), ClaimLink.Relation.CONTRADICTS, 0.9, null, List.of("缺少工商底档")));
    }

    @Test
    void missingEvidenceIsExpressedViaMissingList() {
        ClaimLink link = new ClaimLink(
                "claim-1", List.of(), ClaimLink.Relation.CONTEXT, 0.3, null, List.of("缺少工商底档"));
        assertEquals(List.of("缺少工商底档"), link.missingEvidence());
    }

    @Test
    void contradictionWithRealEvidenceIsAllowed() {
        ClaimLink link = new ClaimLink(
                "claim-1", List.of("memory:42"), ClaimLink.Relation.CONTRADICTS, 0.8, "reviewer-a", List.of());
        assertEquals(ClaimLink.Relation.CONTRADICTS, link.relation());
    }

    @Test
    void confidenceOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ClaimLink(
                "claim-1", List.of("memory:1"), ClaimLink.Relation.SUPPORTS, 1.5, null, List.of()));
    }
}
