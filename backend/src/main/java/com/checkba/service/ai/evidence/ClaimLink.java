package com.checkba.service.ai.evidence;

import java.util.List;

/**
 * 主张与证据的关联（claim_link）——与证据检索分层：检索只负责拿回证据，
 * 「这些证据支持/矛盾哪个主张」是独立的解释层对象。
 *
 * 不变式：证据缺失（missingEvidence）绝不允许被改写成矛盾（CONTRADICTS）——
 * CONTRADICTS 必须至少援引一条真实证据。"查无此据"和"有据反驳"是两回事，
 * 对法律工作流混淆二者是危险的。
 *
 * @param claimId         主张 ID
 * @param evidenceIds     援引的证据 ID 列表（{@link EvidenceItem#evidenceId()}）
 * @param relation        关系：支持 / 矛盾 / 背景
 * @param confidence      置信度 0-1
 * @param reviewer        复核人（可选，null = 未经人工复核）
 * @param missingEvidence 缺失证据清单（描述"还需要什么证据"，可为空）
 */
public record ClaimLink(
        String claimId,
        List<String> evidenceIds,
        Relation relation,
        double confidence,
        String reviewer,
        List<String> missingEvidence) {

    public enum Relation { SUPPORTS, CONTRADICTS, CONTEXT }

    public ClaimLink {
        if (claimId == null || claimId.isBlank()) {
            throw new IllegalArgumentException("claim_link: claimId 必填");
        }
        if (relation == null) {
            throw new IllegalArgumentException("claim_link: relation 必填");
        }
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        if (relation == Relation.CONTRADICTS && evidenceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "claim_link: 缺失证据不得改写为矛盾——CONTRADICTS 必须援引至少一条证据，查无此据请用 missingEvidence 表达");
        }
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("claim_link: confidence 必须在 [0,1] 区间");
        }
    }
}
