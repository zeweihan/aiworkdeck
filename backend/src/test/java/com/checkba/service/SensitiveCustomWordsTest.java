// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.SensitiveType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补充敏感词保持逐字匹配，与自动中文姓名识别并存（dev-board#599）。
 *
 * <p>逐字面量匹配、逐字符遮蔽，不做任何模糊化——用户填什么就涂什么，可预期性比覆盖率重要。
 * docx / 纯文本 / PDF 三条路都要覆盖：PDF 走的是另一套（整页文本匹配 + 坐标画框 + 栅格化），
 * 只测 docx 会漏掉半条产品。
 */
class SensitiveCustomWordsTest {

    private final SensitiveService service = new SensitiveService();

    @Test
    @DisplayName("CHINESE_NAME 恢复为可勾选的自动检测类型")
    void chineseNameIsAnAutoDetectType() {
        assertTrue(SensitiveType.CHINESE_NAME.isAutoDetect());
        assertTrue(SensitiveType.autoDetectTypes().contains(SensitiveType.CHINESE_NAME));
        // 护栏：有客观特征的类型一个都不许被顺手带走
        for (SensitiveType kept : List.of(SensitiveType.PHONE, SensitiveType.ID_CARD,
                SensitiveType.BANK_CARD, SensitiveType.EMAIL)) {
            assertTrue(SensitiveType.autoDetectTypes().contains(kept), kept + " 不该被下线");
        }
    }

    @Test
    @DisplayName("纯文本：自定义词被涂黑，同段普通中文词一字不动")
    void customWordIsMaskedInPlainText() {
        String text = "本合同由甲方张三与乙方签订，北京市朝阳区。";
        String out = service.maskCustomWords(text, List.of("张三"));
        assertFalse(out.contains("张三"), "自定义词没被涂黑：" + out);
        assertTrue(out.contains("甲方"), "普通词被误伤了：" + out);
        assertTrue(out.contains("北京市朝阳区"), "普通词被误伤了：" + out);
        assertEquals("本合同由甲方**与乙方签订，北京市朝阳区。", out);
    }

    @Test
    @DisplayName("docx 整条链路：一个类型都没勾、只填自定义词，也要生效")
    void customWordIsMaskedInDocxWithoutAnyStrategy() throws Exception {
        String prose = "本合同由甲方张三与乙方李四签订，被告应于判决生效之日起十日内履行。";
        File src = File.createTempFile("custom-word-src-", ".docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(prose);
            try (FileOutputStream out = new FileOutputStream(src)) {
                doc.write(out);
            }
        }

        String outPath = service.processFile(src.getAbsolutePath(), List.of(), List.of("张三", "李四"));

        try (XWPFDocument out = new XWPFDocument(Files.newInputStream(new File(outPath).toPath()))) {
            String text = out.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining());
            assertFalse(text.contains("张三"), "docx 里的自定义词必须被涂黑：" + text);
            assertFalse(text.contains("李四"), "第二个自定义词也必须被涂黑：" + text);
            assertTrue(text.contains("被告应于判决生效之日起十日内履行"),
                    "正文其余部分必须逐字保留（这正是下线自动姓名检测要保住的东西）：" + text);
        }
    }

    @Test
    @DisplayName("PDF：自定义词进入黑框区域列表（与自动策略走的是两套匹配）")
    void customWordProducesRedactionAreaInPdf() throws Exception {
        // PDFBox 的 Standard 14 字体编不了中文，这里用拉丁文字面量验证「自定义词 -> 黑框」这条接线；
        // 匹配本身是 String.indexOf，与字符是不是汉字无关。
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Plaintiff Zhang San v. Defendant");
                cs.endText();
            }

            // 基线：不填自定义词、也不勾任何策略时，一个框都不该有——
            // 否则下面那条断言就算不上是「自定义词让它出现的」。
            assertTrue(service.computeRedactionAreas(doc, List.of(), List.of()).isEmpty(),
                    "没有任何策略与自定义词时不该产生黑框");

            List<SensitiveService.RedactionArea> areas =
                    service.computeRedactionAreas(doc, List.of(), List.of("Zhang San"));
            assertFalse(areas.isEmpty(), "PDF 里的自定义词必须产生黑框区域");
            assertTrue(areas.get(0).width > 0 && areas.get(0).height > 0,
                    "黑框得有实际尺寸，零宽零高等于没涂");
        }
    }

    @Test
    @DisplayName("空白与重复的自定义词被忽略，不会把整篇涂成星号")
    void blankAndDuplicateCustomWordsAreIgnored() {
        String text = "甲方张三。";
        String out = service.maskCustomWords(text, java.util.Arrays.asList("  ", null, "张三", "张三"));
        assertEquals("甲方**。", out);
    }
}
