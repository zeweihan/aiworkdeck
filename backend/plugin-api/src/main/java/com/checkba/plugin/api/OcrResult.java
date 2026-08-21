package com.checkba.plugin.api;

/** OCR 结果：全文 + 可选文本块。 */
public record OcrResult(String text, java.util.List<OcrBlock> blocks) {}
