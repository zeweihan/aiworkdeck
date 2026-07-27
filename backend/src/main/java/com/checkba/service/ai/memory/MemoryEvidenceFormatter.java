package com.checkba.service.ai.memory;

import com.checkba.model.entity.MemoryEntry;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 记忆证据账本格式化（借鉴 HMS "答案时证据组织"思想）：
 * 注入上下文/返回工具结果时，不再是松散的记忆文本堆叠，而是每条附带
 * 记录时间、作用域、来源文件与更新信号，让模型能够：
 * - 区分旧状态与最新状态（同一 memoryKey 有更新记录时标注"已被更新"）
 * - 把"上个月""最近"等相对表述锚定到具体记录时间（顶部提供"今天"锚点）
 * - 对结论/金额/日期类信息按来源与时效做保守取舍
 */
public final class MemoryEvidenceFormatter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private MemoryEvidenceFormatter() {}

    /**
     * 将记忆列表格式化为证据账本。
     *
     * @param entries      按检索相关度排好序的记忆
     * @param supersededAt 已被更新条目的信息：entryId → 同 key 更新记录的创建时间
     *                     （见 {@link MemoryManager#findSupersededInfo}），可为 null
     * @param today        时间锚点（"今天"）
     */
    public static String format(List<MemoryEntry> entries, Map<Long, LocalDateTime> supersededAt, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        sb.append("今天是 ").append(today.format(DATE))
          .append("。以下记忆按检索相关度排序，每条附记录时间与来源；")
          .append("标注「已被更新」的条目是旧状态，请以更新时间较新的记录为准。\n");
        int index = 1;
        for (MemoryEntry mem : entries) {
            sb.append(index++).append(". [").append(mem.getMemoryType()).append("] ");
            if (mem.getMemoryKey() != null) {
                sb.append(mem.getMemoryKey()).append(": ");
            }
            sb.append(mem.getMemoryValue()).append("\n");
            sb.append("   （").append(provenance(mem, supersededAt)).append("）\n");
        }
        return sb.toString();
    }

    /** 单条记忆的溯源注记：记录时间 · 作用域 · 来源文件 · 受保护 · 更新信号 */
    private static String provenance(MemoryEntry mem, Map<Long, LocalDateTime> supersededAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("记录于").append(mem.getCreatedAt() != null ? mem.getCreatedAt().format(DATE) : "未知时间");
        if (mem.getScope() != null && !MemoryEntry.MemoryScope.PROJECT.equals(mem.getScope())) {
            sb.append(" · 作用域:").append(mem.getScope());
        }
        if (mem.getSourceFileId() != null) {
            sb.append(" · 来源文件#").append(mem.getSourceFileId());
        }
        if (Boolean.TRUE.equals(mem.getIsProtected())) {
            sb.append(" · 受保护");
        }
        LocalDateTime newer = supersededAt == null ? null : supersededAt.get(mem.getId());
        if (newer != null) {
            sb.append(" · 已被更新，最新记录于").append(newer.format(DATE));
        }
        return sb.toString();
    }
}
