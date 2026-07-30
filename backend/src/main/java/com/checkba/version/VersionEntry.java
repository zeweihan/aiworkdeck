package com.checkba.version;

import java.time.Instant;
import java.util.List;

/**
 * 一条版本记录。kind 取自提交消息尾注 X-AWD-Kind：
 *   auto    = 工作段内的自动存档
 *   session = 工作段本身（合并节点）
 *
 * milestone 非空即该版本被标记为「重要版本」，取自附注标签
 * refs/tags/awd/milestone/{sha 前 12 位} 的 message（见 ProjectRepoService.tagMilestone）。
 */
public record VersionEntry(
        String sha,
        String message,
        String authorName,
        Instant when,
        String kind,
        String note,
        List<String> parents,
        String milestone
) {}
