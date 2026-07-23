package com.checkba.service.ai.evidence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * evidence.retrieve.v1 的单条证据（稳定契约字段，见 docs/EVIDENCE_CONTRACT.md）。
 *
 * 硬约束：evidenceId / sourceUri / locator 缺一不可——没有精确定位符的内容
 * 不允许伪装成证据返回（实现层必须丢弃并告警，而不是编造定位符）。
 *
 * @param evidenceId    稳定证据 ID（同一来源同一内容多次检索必须相同）
 * @param sourceUri     来源 URI（如 checkba://file/42、https://...）
 * @param contentHash   内容哈希（sha256 十六进制），内容变更即哈希变更
 * @param retrievedAt   本次检索时间
 * @param effectiveDate 生效日期（内容自何时起成立）
 * @param locator       精确定位符（段落号/条文号/记录主键等）
 * @param excerpt       有界摘录（不超过 {@link #MAX_EXCERPT_LENGTH} 字符）
 * @param mimeType      内容类型
 * @param accessPolicy  访问策略标注（如 "project"、"user:protected"）
 * @param provenance    溯源链（从原始来源到本条目的传递路径，有序）
 * @param supersedes    可选事件：本条证据取代的旧证据 ID
 * @param revokes       可选事件：本条证据吊销的证据 ID
 * @param supersededAt  可选信号：本条证据已有更新版本时，最新版本的时间
 */
public record EvidenceItem(
        String evidenceId,
        String sourceUri,
        String contentHash,
        LocalDateTime retrievedAt,
        LocalDate effectiveDate,
        String locator,
        String excerpt,
        String mimeType,
        String accessPolicy,
        List<String> provenance,
        String supersedes,
        String revokes,
        LocalDateTime supersededAt) {

    public static final int MAX_EXCERPT_LENGTH = 500;

    public EvidenceItem {
        if (isBlank(evidenceId) || isBlank(sourceUri) || isBlank(locator)) {
            throw new IllegalArgumentException(
                    "evidence.retrieve.v1: evidenceId/sourceUri/locator 为必填，缺定位符的内容不得作为证据返回");
        }
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
        if (excerpt != null && excerpt.length() > MAX_EXCERPT_LENGTH) {
            excerpt = excerpt.substring(0, MAX_EXCERPT_LENGTH);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
