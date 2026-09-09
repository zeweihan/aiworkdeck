// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** OCR 结果：全文 + 可选文本块。 */
public record OcrResult(String text, java.util.List<OcrBlock> blocks) {}
