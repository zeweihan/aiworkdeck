package com.checkba.version;

import java.util.List;

/**
 * 合并结果。conflictingPaths 非空即冲突（success=false），但仓库落地状态取决于
 * 调用的是哪个合并原语：{@code ProjectRepoService.merge} 会 reset --hard 回到合并前
 * 状态（spec 第七节：合并失败要保证两份稿件都还在）；
 * {@code ProjectRepoService.mergeKeepingConflicts} 则保留 MERGING 态、索引记录冲突
 * 路径，交由上层裁决（abortMerge 中止 / commitMergeResolution 提交裁决结果）。
 */
public record MergeOutcome(
        boolean success,
        boolean fastForward,
        List<String> conflictingPaths,
        String mergeSha
) {}
