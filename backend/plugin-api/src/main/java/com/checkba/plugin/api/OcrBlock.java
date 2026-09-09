// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** 一个 OCR 文本块：page 从 1 起；x/y/w/h 为页面归一化坐标（0..1）。 */
public record OcrBlock(String text, int page, double x, double y, double w, double h) {}
