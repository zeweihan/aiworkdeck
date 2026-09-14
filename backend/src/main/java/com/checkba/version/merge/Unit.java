// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

/**
 * 结构化三方比对里的一个「单元」。
 *
 * <p>{@code key} 的写法按文件类型固定：docx 正文段落 {@code p12}、docx 表格单元 {@code t1.2.3}
 * （表序.行.列，都从 0 起）、xlsx 单元格 {@code Sheet1!B7}、pptx 页 {@code s3}（1 基页序）。
 * 这些键会原样进裁决清单与 {@code X-AWD-Merges} 尾注，属于对外契约，不要改写法。
 *
 * <p>{@code text} 是原文（引擎重放要拿它写回文档），{@code norm} 是归一化后的文字
 * （去首尾空白、连续空白折一个、NFC），比对与溯源一律按 {@code norm}。
 */
public record Unit(String key, String text, String norm) {
}
