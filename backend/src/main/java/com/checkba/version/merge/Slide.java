// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

/**
 * pptx 的一页。
 *
 * @param sldId   {@code presentation.xml} 里 {@code <p:sldId id>} 的值，页的稳定身份（对齐用）
 * @param ordinal 1 基页序
 * @param title   标题占位符的文字，没有标题时为空串
 * @param text    该页全部形状文本按形状序拼接（换行分隔）
 */
public record Slide(String sldId, int ordinal, String title, String text) {
}
