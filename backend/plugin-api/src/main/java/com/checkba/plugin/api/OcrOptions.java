package com.checkba.plugin.api;

/** OCR 选项：blocks=true 时尽量返回带坐标的文本块（宿主网关不返回坐标时 blocks 为空列表）。 */
public record OcrOptions(boolean blocks, String language) {
    public static OcrOptions text() { return new OcrOptions(false, "zh"); }
}
