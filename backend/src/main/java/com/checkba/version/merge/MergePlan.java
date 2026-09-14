// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import java.util.List;

/** 引擎重放计划：两侧各自相对基线的改动块。引擎只重放 {@code otherChunks} 里 {@code conflict=false} 的块。 */
public record MergePlan(List<Chunk> mainChunks, List<Chunk> otherChunks) {
}
