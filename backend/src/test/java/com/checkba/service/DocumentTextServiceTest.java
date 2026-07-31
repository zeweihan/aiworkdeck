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
