// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** OCR 选项：blocks=true 时尽量返回带坐标的文本块（宿主网关不返回坐标时 blocks 为空列表）。 */
public record OcrOptions(boolean blocks, String language) {
    public static OcrOptions text() { return new OcrOptions(false, "zh"); }
}
