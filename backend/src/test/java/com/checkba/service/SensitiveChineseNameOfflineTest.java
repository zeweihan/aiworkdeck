// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 姓名识别恢复后仍须保护普通正文，且 DOCX 链路也不能回到全汉字打码。 */
class SensitiveChineseNameOfflineTest {

    private final SensitiveService service = new SensitiveService();

    /** 一段完全正常的中文文书正文，一个人名都没有——旧规则会把它涂得满目疮痍。 */
    private static final String PROSE =
            "本合同由甲方北京市朝阳区某科技有限公司与乙方签订，被告应于判决生效之日起十日内履行。";

    @Test
    @DisplayName("正文替换：无姓名的普通正文一个字都不许改")
    void plainChineseProseSurvivesChineseNameStrategy() {
        assertEquals(PROSE, service.replaceSensitiveData(PROSE, "CHINESE_NAME"),
                "普通中文词被当成姓名涂黑了，整篇文书就不再逐字可引");
    }

    @Test
    @DisplayName("枚举值保留：存量数据/老客户端传的 code 仍能解析")
    void enumValueIsKeptForBackwardCompatibility() {
        assertNotNull(com.checkba.model.SensitiveType.fromCode("CHINESE_NAME"),
                "枚举值不能删——存量记录里可能存着这个 code");
    }

    @Test
    @DisplayName("docx 整条链路：勾选姓名规则也保持无姓名的普通正文")
    void docxWithChineseNameStrategyIsUntouched() throws Exception {
        File src = File.createTempFile("cn-name-src-", ".docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(PROSE);
            try (FileOutputStream out = new FileOutputStream(src)) {
                doc.write(out);
            }
        }

        String outPath = service.processFile(src.getAbsolutePath(), List.of("CHINESE_NAME"));

        try (XWPFDocument out = new XWPFDocument(Files.newInputStream(new File(outPath).toPath()))) {
            String text = out.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining());
            assertEquals(PROSE, text, "docx 正文被自动姓名规则改坏了");
        }
    }
}
