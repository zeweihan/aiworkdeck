package com.checkba.plugin.api;

/** 文本抽取与 OCR。ocr 走平台网关（扣用户 Credits），插件不自带 key。 */
public interface Text {
    String extract(long projectId, long fileId, int maxChars);
    OcrResult ocr(long projectId, long fileId, OcrOptions o);
    /** PDF 分页文本；页码从 1 起、闭区间。 */
    java.util.List<String> pdfPageTexts(long projectId, long fileId, int fromPage, int toPage);
}
