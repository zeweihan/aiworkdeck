// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import java.util.List;

/**
 * 一个冲突路径的结构化三方比对结果。
 *
 * @param mainChanges  主线侧改了几个单元
 * @param otherChanges 另一侧改了几个单元
 * @param mainOnly     只有主线侧改过的单元键（xlsx/pptx 拼合并文件用；docx 为空）
 * @param otherOnly    只有另一侧改过的单元键（同上）
 * @param baseUnits    基线单元序列，引擎核对对齐用
 */
public record Analysis(MergeKind kind, MergeDecision decision, MergeReason reason, int mainChanges, int otherChanges,
                       List<Overlap> overlaps, List<String> mainOnly, List<String> otherOnly, MergePlan plan,
                       List<Unit> baseUnits) {
}
