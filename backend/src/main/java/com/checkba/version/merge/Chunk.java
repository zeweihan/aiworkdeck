// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import java.util.List;

/**
 * 一侧相对共同上一版的一处改动，坐标永远落在**基线**上。
 *
 * @param type      {@code MODIFY} | {@code INSERT} | {@code DELETE}
 * @param baseStart 基线单元区间起点（含）
 * @param baseEnd   基线单元区间终点（不含）；与 {@code baseStart} 相等即插入
 * @param texts     该侧这一处的新文本（docx 重放要写回去的原文），删除时为空
 * @param ids       pptx 用：该侧这几页的 sldId；docx/xlsx 为空
 * @param conflict  两边都动过这一块（含相邻），引擎不重放它，交给律师裁决
 */
public record Chunk(String type, int baseStart, int baseEnd, List<String> texts, List<String> ids, boolean conflict) {
}
