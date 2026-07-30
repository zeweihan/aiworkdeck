package com.checkba.version;

import java.util.List;

/**
 * 合并结果。conflictingPaths 非空即冲突，此时 success=false 且仓库已回到合并前状态
 * （spec 第七节：合并失败要保证两份稿件都还在）。
 */
public record MergeOutcome(
        boolean success,
        boolean fastForward,
        List<String> conflictingPaths,
        String mergeSha
) {}
