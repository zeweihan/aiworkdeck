package com.checkba.plugin.api;

/** 一个 OCR 文本块：page 从 1 起；x/y/w/h 为页面归一化坐标（0..1）。 */
public record OcrBlock(String text, int page, double x, double y, double w, double h) {}
