// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

/**
 * 两边都动过的一个单元：裁决界面「共同的上一版 / 你的 / 律师乙的」三栏文字的数据源。
 *
 * <p>pptx 的页序冲突用保留键 {@code "order"} 单列一条，三栏是三侧的页序。
 */
public record Overlap(String key, String baseText, String mainText, String otherText) {
}
