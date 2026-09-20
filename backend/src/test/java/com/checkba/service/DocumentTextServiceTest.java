// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DocumentTextService 的 Tika 解析能力：docx 与 xlsx 都要能抽出文本。
 * 不依赖 Spring 上下文，parse(InputStream) 为纯函数式入口。
 */
class DocumentTextServiceTest {

    private final DocumentTextService service = new DocumentTextService(null);

    @Test
    void extractsTextFromDocx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("本次股东会的召集程序符合规定");
            doc.write(out);
        }
        String text = service.parse(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(text.contains("本次股东会的召集程序符合规定"), "docx 正文应被抽取，实际: " + text);
    }

    @Test
    void extractsTailFromThreeHundredPageBreaksAndThirtyThousandCharacters() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            for (int page = 0; page < 300; page++) {
                var paragraph = doc.createParagraph();
                paragraph.setPageBreak(page > 0);
                paragraph.createRun().setText("第" + page + "页：" + "文".repeat(100));
            }
            doc.createParagraph().createRun().setText("DOCUMENT_TAIL_300");
            doc.write(out);
        }
        String text = service.parse(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(text.length() > 30000);
        assertTrue(text.contains("第0页："));
        assertTrue(text.contains("第299页："));
        assertTrue(text.contains("DOCUMENT_TAIL_300"), "末页文字必须完整抽取");
    }

    @Test
    void extractsTextFromPdfViaPdfbox3() throws Exception {
        // Tika 2.9.x 的 PDFParser 依赖 PDFBox 2.x API，与项目的 PDFBox 3.0.1 冲突
        // （NoSuchMethodError）；PDF 必须走 parsePdf 的 PDFBox 3 原生路径。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Shareholders Meeting Notice 2026");
                cs.endText();
            }
            doc.save(out);
        }
        String text = service.parsePdf(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(text.contains("Shareholders Meeting Notice 2026"), "PDF 文本应被抽取，实际: " + text);
    }

    // ==== 抽取缓存（dev-board#729 ⑤）====
    // read_document 本机中位 1128ms，而一轮对话里同一份文档常被读多次（模型读一次、
    // 上下文组装注入一次、审计/勾稽工具再读一次），此前每次都重新跑一遍 Tika。

    /** 计数用的存储：记录同一路径被真正打开了几次。 */
    private static final class CountingStorage implements com.checkba.storage.StorageService {
        final java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        byte[] bytes;
        long lastModified = 1_700_000_000_000L;

        CountingStorage(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public org.springframework.core.io.Resource load(String path) {
            return new org.springframework.core.io.ByteArrayResource(bytes) {
                @Override
                public long lastModified() {
                    return lastModified;
                }

                @Override
                public java.io.InputStream getInputStream() {
                    opens.incrementAndGet();
                    return new ByteArrayInputStream(bytes);
                }
            };
        }

        @Override public String save(String fileId, java.io.InputStream in) { throw new UnsupportedOperationException(); }
        @Override public void delete(String fileId) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(String fileId) { return true; }
        @Override public String getUrl(String fileId) { return null; }
        @Override public String append(String fileId, java.io.InputStream in) { throw new UnsupportedOperationException(); }
        @Override public long getSize(String fileId) { return bytes.length; }
    }

    private static com.checkba.model.entity.ProjectFile docxFile(Long id) {
        com.checkba.model.entity.ProjectFile f = new com.checkba.model.entity.ProjectFile();
        f.setId(id);
        f.setName("合同.docx");
        f.setFileType("docx");
        f.setFilePath("p/1/合同.docx");
        return f;
    }

    private static byte[] docxBytes(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(text);
            doc.write(out);
        }
        return out.toByteArray();
    }

    @Test
    void secondExtractOfTheSameUnchangedFileIsServedFromCache() throws Exception {
        CountingStorage storage = new CountingStorage(docxBytes("缓存命中测试正文"));
        var factory = org.mockito.Mockito.mock(com.checkba.storage.StorageServiceFactory.class);
        org.mockito.Mockito.when(factory.getStorageService()).thenReturn(storage);
        DocumentTextService svc = new DocumentTextService(factory);

        String first = svc.extractText(docxFile(1L));
        String second = svc.extractText(docxFile(1L));

        assertTrue(first.contains("缓存命中测试正文"));
        assertTrue(second.contains("缓存命中测试正文"));
        org.junit.jupiter.api.Assertions.assertEquals(1, storage.opens.get(),
                "同一份没变过的文件不应被抽取第二遍");
    }

    @Test
    void changedFileIsReExtractedNotServedStale() throws Exception {
        CountingStorage storage = new CountingStorage(docxBytes("第一版正文"));
        var factory = org.mockito.Mockito.mock(com.checkba.storage.StorageServiceFactory.class);
        org.mockito.Mockito.when(factory.getStorageService()).thenReturn(storage);
        DocumentTextService svc = new DocumentTextService(factory);

        assertTrue(svc.extractText(docxFile(1L)).contains("第一版正文"));

        // 编辑器保存 / 版本回退 / 插件写回都只动磁盘，不一定动 project_file 那一行——
        // 所以指纹必须取物理文件的 mtime 与长度，不能取 DB 的 updatedAt
        storage.bytes = docxBytes("改过之后的正文");
        storage.lastModified += 1000;

        String after = svc.extractText(docxFile(1L));
        assertTrue(after.contains("改过之后的正文"), "改过的文件必须重抽，实际: " + after);
        org.junit.jupiter.api.Assertions.assertEquals(2, storage.opens.get());
    }

    @Test
    void unreadableTimestampFallsBackToAlwaysExtracting() throws Exception {
        CountingStorage storage = new CountingStorage(docxBytes("没有时间戳"));
        storage.lastModified = 0L; // 某些 Resource 实现拿不到 mtime
        var factory = org.mockito.Mockito.mock(com.checkba.storage.StorageServiceFactory.class);
        org.mockito.Mockito.when(factory.getStorageService()).thenReturn(storage);
        DocumentTextService svc = new DocumentTextService(factory);

        svc.extractText(docxFile(1L));
        svc.extractText(docxFile(1L));

        org.junit.jupiter.api.Assertions.assertEquals(2, storage.opens.get(),
                "拿不到指纹时宁可重抽一次，也不能给出可能陈旧的正文");
    }

    @Test
    void extractsTextFromXlsx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("表决结果");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("议案1");
            row.createCell(1).setCellValue("同意票数");
            row.createCell(2).setCellValue(123456789);
            wb.write(out);
        }
        String text = service.parse(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(text.contains("同意票数"), "xlsx 单元格应被抽取，实际: " + text);
        assertTrue(text.contains("123456789"), "xlsx 数字应被抽取，实际: " + text);
    }
}
