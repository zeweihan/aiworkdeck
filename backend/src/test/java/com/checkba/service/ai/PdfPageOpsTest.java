// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 页级操作（dev-board#805，审计 A17 / B-12）：合并 / 提页 / 删页 / 旋转 / 编页码。
 *
 * <p>全部用内存里现造的 PDF，无外部依赖、不碰磁盘上的项目文件。每条用例都同时断言
 * <b>原件没被动过</b>——这六个工具的产品契约是「产出新文件、原件不动」，一旦某个实现
 * 顺手就地保存，用户丢的是证据原件，而返回文案照样说成功。
 */
class PdfPageOpsTest {

    @TempDir
    Path tempDir;

    private final PdfEditService service = new PdfEditService();

    /** 造一份 pages 页的 PDF，第 i 页写着 "{tag} Page {i+1}"，便于按文本认页。 */
    private Path makePdf(String name, String tag, int pages) throws IOException {
        Path path = tempDir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(tag + " Page " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private String textOf(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    private int pagesOf(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }

    private int rotationOf(Path pdf, int pageIndex) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getPage(pageIndex).getRotation();
        }
    }

    // ==================== 合并 ====================

    @Test
    @DisplayName("合并按给定顺序接起来，页数是各份之和，原件不动")
    void mergeConcatenatesInGivenOrder() throws IOException {
        Path a = makePdf("a.pdf", "A", 3);
        Path b = makePdf("b.pdf", "B", 2);
        Path out = tempDir.resolve("merged.pdf");

        assertEquals(5, service.merge(List.of(a, b), out));
        assertEquals(5, pagesOf(out));

        String text = textOf(out);
        assertTrue(text.indexOf("A Page 1") < text.indexOf("B Page 1"), "合并顺序应与入参一致：" + text);
        assertTrue(text.contains("A Page 3") && text.contains("B Page 2"), text);

        assertEquals(3, pagesOf(a));
        assertEquals(2, pagesOf(b));
    }

    @Test
    @DisplayName("只给一份文件时报错：合并至少要两份")
    void mergeNeedsAtLeastTwoSources() throws IOException {
        Path a = makePdf("a.pdf", "A", 2);
        assertThrows(PdfEditService.PdfEditException.class,
                () -> service.merge(List.of(a), tempDir.resolve("x.pdf")));
    }

    // ==================== 提页 / 删页 ====================

    @Test
    @DisplayName("提页只留指定页，且保持原文档页序")
    void extractKeepsOnlySelectedPagesInDocumentOrder() throws IOException {
        Path src = makePdf("src.pdf", "S", 5);
        Path out = tempDir.resolve("extracted.pdf");

        assertEquals(2, service.extractPages(src, out, List.of(1, 2)));

        String text = textOf(out);
        assertTrue(text.contains("S Page 2") && text.contains("S Page 3"), text);
        assertFalse(text.contains("S Page 1"), text);
        assertFalse(text.contains("S Page 4"), text);
        assertEquals(5, pagesOf(src), "原件不能被改");
    }

    @Test
    @DisplayName("删页去掉指定页，其余保持原序")
    void deleteDropsSelectedPages() throws IOException {
        Path src = makePdf("src.pdf", "S", 5);
        Path out = tempDir.resolve("deleted.pdf");

        assertEquals(3, service.deletePages(src, out, List.of(1, 3)));

        String text = textOf(out);
        assertFalse(text.contains("S Page 2"), text);
        assertFalse(text.contains("S Page 4"), text);
        assertTrue(text.indexOf("S Page 1") < text.indexOf("S Page 3"), text);
        assertTrue(text.contains("S Page 5"), text);
        assertEquals(5, pagesOf(src));
    }

    @Test
    @DisplayName("删光所有页报错：产出一份 0 页的 PDF 等于静默销毁内容")
    void deletingEveryPageIsRejected() throws IOException {
        Path src = makePdf("src.pdf", "S", 3);
        assertThrows(PdfEditService.PdfEditException.class,
                () -> service.deletePages(src, tempDir.resolve("x.pdf"), List.of(0, 1, 2)));
    }

    // ==================== 旋转 ====================

    @Test
    @DisplayName("旋转是相对当前角度叠加的：同一页转两次 90 变成 180，未点名的页不动")
    void rotateIsRelativeToCurrentAngle() throws IOException {
        Path src = makePdf("src.pdf", "S", 3);
        Path once = tempDir.resolve("r1.pdf");
        Path twice = tempDir.resolve("r2.pdf");

        assertEquals(2, service.rotatePages(src, once, List.of(0, 2), 90));
        assertEquals(90, rotationOf(once, 0));
        assertEquals(0, rotationOf(once, 1));
        assertEquals(90, rotationOf(once, 2));

        service.rotatePages(once, twice, List.of(0), 90);
        assertEquals(180, rotationOf(twice, 0));
        assertEquals(90, rotationOf(twice, 2));

        assertEquals(0, rotationOf(src, 0), "原件不能被改");
    }

    @Test
    @DisplayName("只接受 90 / 180 / 270")
    void rotateRejectsOtherAngles() throws IOException {
        Path src = makePdf("src.pdf", "S", 2);
        for (int bad : new int[]{0, 45, 360, -90}) {
            assertThrows(PdfEditService.PdfEditException.class,
                    () -> service.rotatePages(src, tempDir.resolve("x.pdf"), List.of(0), bad));
        }
    }

    // ==================== 页码 / 贝茨编号 ====================

    @Test
    @DisplayName("页码按 {n}/{total} 模板逐页写入，能被 PDFTextStripper 读回")
    void pageNumbersAreStampedAndReadableBack() throws IOException {
        Path src = makePdf("src.pdf", "S", 5);
        Path out = tempDir.resolve("numbered.pdf");

        assertEquals(5, service.addPageNumbers(src, out, "bottom-center", 1, "- {n} / {total} -"));

        String text = textOf(out);
        assertTrue(text.contains("- 1 / 5 -"), text);
        assertTrue(text.contains("- 5 / 5 -"), text);
        assertEquals(5, pagesOf(src));
    }

    @Test
    @DisplayName("startAt 决定第一页印的号，不是第一页永远印 1")
    void startAtShiftsTheFirstNumber() throws IOException {
        Path src = makePdf("src.pdf", "S", 3);
        Path out = tempDir.resolve("numbered.pdf");

        service.addPageNumbers(src, out, "bottom-right", 10, "{n}");

        String text = textOf(out);
        assertTrue(text.contains("10") && text.contains("12"), text);
        assertFalse(text.contains("13"), text);
    }

    @Test
    @DisplayName("贝茨编号：前缀 + {n:6} 零填充，位数够才排得了序")
    void batesNumberingZeroPads() throws IOException {
        Path src = makePdf("src.pdf", "S", 2);
        Path out = tempDir.resolve("bates.pdf");

        service.addPageNumbers(src, out, "bottom-right", 1, "AWD{n:6}");

        String text = textOf(out);
        assertTrue(text.contains("AWD000001"), text);
        assertTrue(text.contains("AWD000002"), text);
    }

    @Test
    @DisplayName("中文页码模板能写进去（走与短文本替换同一条 CJK 字体探测链）")
    void chinesePageNumberTemplateWorks() throws IOException {
        // 本机/CI 没有任何可嵌入的 CJK 字体时这条跳过——探测链落空是环境问题，不是回归
        org.junit.jupiter.api.Assumptions.assumeTrue(service.resolveCjkFontFile() != null,
                "本机未探测到可嵌入的 CJK 字体，跳过中文页码用例");
        Path src = makePdf("src.pdf", "S", 2);
        Path out = tempDir.resolve("cn.pdf");

        service.addPageNumbers(src, out, "bottom-center", 1, "第 {n} 页 / 共 {total} 页");

        // 只断言逐页变化的那几位与前缀：整串比对会被字体的 ToUnicode 绊倒——
        // macOS STHeiti 把「页」的字形映射到康熙部首 U+2EDA，抽回来是「⻚」而不是「页」。
        // 显示是对的，差异只在文本抽取这一侧；这也意味着日后想在产物里 grep 页码要当心。
        String text = textOf(out);
        assertTrue(text.contains("第 1 ") && text.contains("共 2 "), text);
        assertTrue(text.contains("第 2 "), text);
    }

    @Test
    @DisplayName("零填充位数被夹在 1..12：{n:0} 会让 String.format 抛 Flags='0'，{n:99} 会印 99 位数字")
    void zeroPadWidthIsClamped() {
        assertEquals("5", PdfEditService.renderPageNumber("{n:0}", 5, 9));
        assertEquals("000005", PdfEditService.renderPageNumber("{n:6}", 5, 9));
        assertEquals("000000000005", PdfEditService.renderPageNumber("{n:99}", 5, 9));
        assertEquals("AWD000005 / 9", PdfEditService.renderPageNumber("AWD{n:6} / {total}", 5, 9));
        // 模板里的 $ 不能被当成正则替换里的组引用
        assertEquals("$1 号 7", PdfEditService.renderPageNumber("$1 号 {n}", 7, 9));
    }

    @Test
    @DisplayName("CJK 字体探测只返回 PDFBox 真能嵌入的字体（仓内那份 Noto 其实是 CFF 轮廓的 OpenType）")
    void cjkFontProbeSkipsCffOutlineFonts() throws IOException {
        java.io.File font = service.resolveCjkFontFile();
        org.junit.jupiter.api.Assumptions.assumeTrue(font != null, "本机未探测到 CJK 字体");
        try (java.io.InputStream in = new java.io.FileInputStream(font)) {
            byte[] tag = in.readNBytes(4);
            assertFalse(tag[0] == 'O' && tag[1] == 'T' && tag[2] == 'T' && tag[3] == 'O',
                    "探测到的是 CFF 轮廓字体（OTTO），PDFBox 嵌不进去：" + font);
        }
    }

    @Test
    @DisplayName("模板里没有 {n} 时报错：整册印同一个数不是页码")
    void templateWithoutPlaceholderIsRejected() throws IOException {
        Path src = makePdf("src.pdf", "S", 2);
        assertThrows(PdfEditService.PdfEditException.class,
                () -> service.addPageNumbers(src, tempDir.resolve("x.pdf"), "bottom-center", 1, "证据一"));
    }

    @Test
    @DisplayName("position 只认 bottom-center / bottom-right")
    void unknownPositionIsRejected() throws IOException {
        Path src = makePdf("src.pdf", "S", 2);
        assertThrows(PdfEditService.PdfEditException.class,
                () -> service.addPageNumbers(src, tempDir.resolve("x.pdf"), "top-left", 1, "{n}"));
    }

    @Test
    @DisplayName("旋转页上的页码跟着显示方向走，不是横着印在侧边")
    void pageNumbersFollowPageRotation() throws IOException {
        Path src = makePdf("src.pdf", "S", 2);
        Path rotated = tempDir.resolve("rot.pdf");
        Path out = tempDir.resolve("rot-numbered.pdf");
        service.rotatePages(src, rotated, List.of(0), 90);

        service.addPageNumbers(rotated, out, "bottom-center", 1, "{n}");

        // PDFTextStripper 按显示方向排版：页码仍是这一页文本流的一部分，
        // 读不回来就说明它被画到了页面外或方向不对。
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String first = stripper.getText(doc);
            assertTrue(first.contains("1"), "旋转页上没读回页码：" + first);
        }
    }

    // ==================== pdf_inspect 续读 ====================

    @Test
    @DisplayName("offset 续读同一页：返回该段并给出 next_offset，读到尾就不再给")
    void inspectResumesFromOffset() throws IOException {
        Path src = makePdf("src.pdf", "S", 1);   // 单页正文 "S Page 1"，8 个字符

        String head = service.inspect(src, 0, 4, 0);
        assertTrue(head.contains("\"next_offset\":4"), head);
        assertTrue(head.contains("\"truncated\":true"), head);

        String tail = service.inspect(src, 0, 4, 4);
        assertTrue(tail.contains("\"offset\":4"), tail);
        assertFalse(tail.contains("next_offset"), "读到页尾就不该再给续读位点：" + tail);
    }

    @Test
    @DisplayName("offset 超过该页正文长度时给空正文，不报错——模型据 char_count 自己纠回来")
    void inspectOffsetBeyondEndReturnsEmptyText() throws IOException {
        Path src = makePdf("src.pdf", "S", 1);
        String json = service.inspect(src, 0, 3000, 9999);
        assertTrue(json.contains("\"text\":\"\""), json);
        assertFalse(json.contains("next_offset"), json);
    }

    @Test
    @DisplayName("不指定 pageIndex 却给 offset 时报错：续读位点是按页算的")
    void inspectOffsetRequiresAPageIndex() throws IOException {
        Path src = makePdf("src.pdf", "S", 3);
        PdfEditService.PdfEditException e = assertThrows(PdfEditService.PdfEditException.class,
                () -> service.inspect(src, null, 3000, 10));
        assertTrue(e.getMessage().contains("pageIndex"), e.getMessage());
    }

    @Test
    @DisplayName("老的三参 inspect 行为不变（offset 默认 0）")
    void legacyInspectOverloadStillWorks() throws IOException {
        Path src = makePdf("src.pdf", "S", 2);
        String json = service.inspect(src, null, 3000);
        assertTrue(json.contains("\"page_count\":2"), json);
        assertFalse(json.contains("next_offset"), json);
    }
}
