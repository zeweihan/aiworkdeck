package com.checkba.plugin.api.evidence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 单条证据（evidence.retrieve.v1 §3，字段与宿主内部契约一一对应）。
 *
 * <p>硬约束在构造器强制：{@code evidenceId} / {@code sourceUri} / {@code locator}
 * 任一为空即抛 {@link IllegalArgumentException}——缺定位符的内容必须丢弃，不得编造。
 * {@code excerpt} 超过 500 字符按 UTF-16 代理对边界截断。
 *
 * <p>只加不改：新增字段永远追加在 record 末位。
 *
 * @param evidenceId    稳定证据 ID：同一来源同一内容重复检索必须相同（幂等重放的基础）
 * @param sourceUri     来源 URI
 * @param contentHash   内容哈希（sha256），内容变即哈希变；可 null
 * @param retrievedAt   本次检索时间；可 null
 * @param effectiveDate 生效日期；可 null
 * @param locator       精确定位符（段落号/条文号/记录主键），必填
 * @param excerpt       有界摘录（≤500 字符，超长截断）；可 null
 * @param mimeType      内容类型；可 null
 * @param accessPolicy  访问策略标注（如 {@code project}、{@code user:protected}）；可 null
 * @param provenance    溯源链（有序）；null 归一为空列表
 * @param supersedes    本条取代的证据 ID；可 null
 * @param revokes       本条吊销的证据 ID；可 null
 * @param supersededAt  本条已有更晚版本时，最新版本时间；可 null
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
                    "evidence item requires non-blank evidenceId, sourceUri and locator");
        }
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
        if (excerpt != null && excerpt.length() > MAX_EXCERPT_LENGTH) {
            excerpt = truncateAtCharBoundary(excerpt, MAX_EXCERPT_LENGTH);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 按 UTF-16 代理对边界截断，避免劈开增补平面字符（与宿主内部实现同规则） */
    private static String truncateAtCharBoundary(String s, int max) {
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
