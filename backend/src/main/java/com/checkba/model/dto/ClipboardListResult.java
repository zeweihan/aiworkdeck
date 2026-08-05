package com.checkba.model.dto;

import com.checkba.model.entity.ClipboardItem;

import java.util.List;

/**
 * GET /api/clipboard 的返回体（PR-C 起从裸数组改为对象）。
 *
 * <p>免费额度是**查询侧过滤**：超出额度的记录一条都不删，只是不返回。
 * {@code hiddenCount} 让前端能诚实地说出「另有 N 条历史记录」——
 * 用户付费后这 N 条会原样出现，这是本功能的核心不变式。</p>
 *
 * @param items         本次可见的记录（已按额度过滤 + 分页 limit 截断）
 * @param limited       是否处于免费额度下（拥有 clipboard.unlimited 时为 false）
 * @param hiddenCount   因免费额度而不可见的记录条数（不含仅被分页 limit 挡住的）
 * @param maxItems      免费额度的条数上限；不受限时为 null
 * @param retentionDays 免费额度的保留天数；不受限时为 null
 */
public record ClipboardListResult(
        List<ClipboardItem> items,
        boolean limited,
        long hiddenCount,
        Integer maxItems,
        Integer retentionDays
) {
    public static ClipboardListResult unlimited(List<ClipboardItem> items) {
        return new ClipboardListResult(items, false, 0L, null, null);
    }
}
