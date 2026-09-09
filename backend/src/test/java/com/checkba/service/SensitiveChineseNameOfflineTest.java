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

/**
 * 自动中文姓名脱敏已下线（dev-board#531，维护者 2026-09-09 拍板）。
 *
 * <p>病灶：CHINESE_NAME 的模式是 [一-龥]{2,4}——中文文书里几乎每个词都是 2~4 个汉字，
 * 「甲方」「北京市」「有限公司」「被告」统统命中，整篇被涂成 甲*／被*／有**司。
 * 中文姓名没有身份证的 mod-11-2、银行卡的 Luhn 那样的客观校验位，纯正则分不出
 * 「张三」和「本条」，收紧规则做不到。
 *
 * <p>产品口径：<b>法律文书里漏涂比误涂安全</b>——文书必须逐字可引，把正文改坏的代价比漏一个
 * 名字更大，而漏涂还有人工复核兜底。所以姓名一律走用户手填的自定义词，自动检测只保留
 * 有客观特征的类型（身份证、手机、银行卡等）。
 *
 * <p>枚举值 CHINESE_NAME 本身保留（存量数据里可能记着这个 code、老客户端可能还会传它），
 * 但传进来不再产生任何改动。
 */
class SensitiveChineseNameOfflineTest {

    private final SensitiveService service = new SensitiveService();

    /** 一段完全正常的中文文书正文，一个人名都没有——旧规则会把它涂得满目疮痍。 */
    private static final String PROSE =
            "本合同由甲方北京市朝阳区某科技有限公司与乙方签订，被告应于判决生效之日起十日内履行。";

    @Test
    @DisplayName("正文替换：传 CHINESE_NAME 也一个字都不许改")
    void plainChineseProseSurvivesChineseNameStrategy() {
        assertEquals(PROSE, service.replaceSensitiveData(PROSE, "CHINESE_NAME"),
                "普通中文词被当成姓名涂黑了，整篇文书就不再逐字可引");
    }

    @Test
    @DisplayName("枚举值保留：存量数据/老客户端传的 code 仍能解析，只是不再生效")
    void enumValueIsKeptForBackwardCompatibility() {
        assertNotNull(com.checkba.model.SensitiveType.fromCode("CHINESE_NAME"),
                "枚举值不能删——存量记录里可能存着这个 code");
    }

    @Test
    @DisplayName("docx 整条链路：勾了 CHINESE_NAME 生成的新文件与原文逐字一致")
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
