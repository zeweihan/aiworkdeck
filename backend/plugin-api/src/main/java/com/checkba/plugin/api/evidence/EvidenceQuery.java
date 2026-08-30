package com.checkba.plugin.api.evidence;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 证据检索请求（evidence.retrieve.v1 §2，字段与宿主内部契约一一对应）。
 *
 * <p>只加不改：新增字段永远追加在 record 末位。
 *
 * @param workspaceId   工作区标识；宿主传项目 ID 的字符串形式
 * @param query         检索文本
 * @param asOf          时点语义：只返回该日期（含）之前生效的证据；null = 现在
 * @param sourceFilters 来源过滤，空 = 不过滤（宿主保证非 null）
 * @param accessContext 访问上下文（userId / conversationId 等），实现据此做权限裁剪
 * @param limit         结果上限；非正数由实现自行兜底
 */
public record EvidenceQuery(
        String workspaceId,
        String query,
        LocalDate asOf,
        List<String> sourceFilters,
        Map<String, String> accessContext,
        int limit) {

    public EvidenceQuery {
        sourceFilters = sourceFilters == null ? List.of() : List.copyOf(sourceFilters);
        accessContext = accessContext == null ? Map.of() : Map.copyOf(accessContext);
    }
}
