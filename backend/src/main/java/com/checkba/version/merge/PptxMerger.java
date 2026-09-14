// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import java.util.List;

/**
 * 演示文稿的合并文件由后端拼（spec 2026-09-14 §4.5）：以 MAIN 侧为底，按 {@code sldId}
 * 对齐页，{@code analysis.otherOnly} 与决定为 {@code T} 的页用另一侧的内容换掉。
 *
 * <p><b>本文件目前只是接口占位</b>：真正的实现是 Task B5（与本任务并行开发），
 * 合并时以 B5 的版本为准。占位实现直接抛异常的理由同 {@link XlsxMerger}。
 */
public final class PptxMerger {

    private PptxMerger() {
    }

    /** {@code decisions} 为空即自动模式：把 {@code analysis.otherOnly} 全部合入。 */
    public static byte[] merge(byte[] main, byte[] other, Analysis analysis, List<Decision> decisions) {
        throw new UnsupportedOperationException("PptxMerger 由 Task B5 实现");
    }
}
