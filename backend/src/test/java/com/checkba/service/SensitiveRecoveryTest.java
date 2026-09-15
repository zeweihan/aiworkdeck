// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveRecoveryTest {
    @TempDir Path dir;
    private final SensitiveService service = new SensitiveService();
    private final String password = "test-passphrase-2026";

    private SensitiveService.Options options() {
        return new SensitiveService.Options(List.of("COMPANY", "PHONE", "EMAIL"),
                "TOKEN", List.of("星河", "张三"), List.of(), password);
    }

    @Test
    void reversibleTextAndEditedOutputUseExactTokens() throws Exception {
        String text = "甲方：北京星河科技有限公司。联系人：张三。电话13800001111。\n张三代表星河签字。";
        Path source = dir.resolve("company.txt");
        Files.writeString(source, text);
        var result = service.processFile(source.toString(), options());
        String masked = Files.readString(Path.of(result.path()));
        assertFalse(masked.contains("张三"));
        assertFalse(masked.contains("星河"));
        assertFalse(result.recoveryKit().contains("张三"));
        assertFalse(result.recoveryKit().contains("13800001111"));
        String restored = service.restoreFile(result.path(), result.recoveryKit(), password).path();
        assertEquals(text, Files.readString(Path.of(restored)));
        Path edited = dir.resolve("edited.md");
        Files.writeString(edited, "审阅意见：\n" + masked + "\n条款有效。");
        assertEquals("审阅意见：\n" + text + "\n条款有效。",
                Files.readString(Path.of(service.restoreFile(edited.toString(), result.recoveryKit(), password).path())));
    }

    @Test
    void wrongPasswordAndUnrelatedMapNeverProduceAFile() throws Exception {
        Path source = dir.resolve("input.txt");
        Files.writeString(source, "电话13800001111");
        var result = service.processFile(source.toString(), options());
        assertThrows(IllegalArgumentException.class,
                () -> service.restoreFile(result.path(), result.recoveryKit(), "wrong-password"));
        var other = service.processFile(source.toString(), options());
        assertThrows(IllegalArgumentException.class,
                () -> service.restoreFile(result.path(), other.recoveryKit(), password));
        try (var files = Files.list(dir)) {
            assertEquals(3, files.count());
        }
    }

    @Test
    void customTermsAreLiteralAndExclusionsAreRespected() throws Exception {
        Path source = dir.resolve("custom.txt");
        String text = "Project(A)+ 是代号。北京星河科技有限公司保留。13800001111";
        Files.writeString(source, text);
        var result = service.processFile(source.toString(), new SensitiveService.Options(
                List.of("COMPANY", "PHONE"), "TOKEN", List.of("Project(A)+"),
                List.of("北京星河科技有限公司"), password));
        String masked = Files.readString(Path.of(result.path()));
        assertFalse(masked.contains("Project(A)+"));
        assertTrue(masked.contains("北京星河科技有限公司保留。"));
        assertFalse(masked.contains("13800001111"));
    }

    @Test
    void docxPreservesUnrelatedRunFormattingAndAllTextNodes() throws Exception {
        Path source = dir.resolve("runs.docx");
        try (var doc = new XWPFDocument()) {
            var p = doc.createParagraph();
            p.createRun().setText("电话：");
            var a = p.createRun(); a.setText("138"); a.setText("0000", 1);
            p.createRun().setText("1111");
            var last = p.createRun(); last.setBold(true); last.setText(" 合同正文");
            doc.createParagraph().createRun().setText("13800001111");
            try (var out = Files.newOutputStream(source)) { doc.write(out); }
        }
        var result = service.processFile(source.toString(), options());
        try (var doc = new XWPFDocument(Files.newInputStream(Path.of(result.path())))) {
            var p = doc.getParagraphs().get(0);
            assertFalse(p.getText().contains("138"));
            assertTrue(p.getRuns().get(3).isBold());
            assertEquals(" 合同正文", p.getRuns().get(3).text());
            assertTrue(p.getText().contains(doc.getParagraphs().get(1).getText()));
        }
        var restored = service.restoreFile(result.path(), result.recoveryKit(), password);
        try (var doc = new XWPFDocument(Files.newInputStream(Path.of(restored.path())))) {
            assertEquals("电话：13800001111 合同正文", doc.getParagraphs().get(0).getText());
        }
    }

    @Test
    void previewDoesNotWriteFilesAndReportsZeroMatches() throws Exception {
        Path source = dir.resolve("plain.txt");
        Files.writeString(source, "双方应当履行合同。");
        var preview = service.previewFile(source.toString(), options());
        assertEquals("双方应当履行合同。", preview.text());
        assertTrue(preview.counts().isEmpty());
        try (var files = Files.list(dir)) { assertEquals(1, files.count()); }
    }

    @Test
    void tamperedKitAndForeignTokensAreHandledWithoutInventingOriginals() throws Exception {
        Path source = dir.resolve("source.txt"); Files.writeString(source, "电话13800001111");
        var result = service.processFile(source.toString(), options());
        String kit = result.recoveryKit();
        int index = kit.length() - 12;
        String broken = kit.substring(0, index) + (kit.charAt(index) == 'A' ? 'B' : 'A') + kit.substring(index + 1);
        assertThrows(IllegalArgumentException.class, () -> service.restoreFile(result.path(), broken, password));
        Path edited = dir.resolve("mixed.txt");
        String foreign = "[[公司1_0123456789ab]]";
        Files.writeString(edited, Files.readString(Path.of(result.path())) + foreign);
        var restored = service.restoreFile(edited.toString(), kit, password);
        assertEquals("电话13800001111" + foreign, Files.readString(Path.of(restored.path())));
        assertTrue(restored.warnings().stream().anyMatch(w -> w.contains("不属于")));
    }

    @Test
    void restorationIsOnePassEvenIfOriginalContainsAnotherToken() {
        String a = "[[公司1_0123456789ab]]", b = "[[公司2_0123456789ab]]";
        var map = java.util.Map.of(a, b, b, "original");
        assertEquals(b + "original", com.checkba.service.sensitive.SensitiveTextEngine.apply(a + b,
                com.checkba.service.sensitive.SensitiveTextEngine.restoreEdits(a + b, map)));
    }

    @Test
    void docxTextBoxesAreProcessedAndRestored() throws Exception {
        Path source = dir.resolve("textbox.docx");
        byte[] original;
        try (var doc = new XWPFDocument(); var out = new java.io.ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("BODY"); doc.write(out); original = out.toByteArray();
        }
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(original));
             var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(source))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] bytes = in.readAllBytes();
                if (entry.getName().equals("word/document.xml")) {
                    String xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    xml = xml.replace("</w:body>", "<w:p><w:r><w:pict><v:shape xmlns:v=\"urn:schemas-microsoft-com:vml\"><v:textbox><w:txbxContent><w:p><w:r><w:t>电话13800001111</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p></w:body>");
                    bytes = xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName())); out.write(bytes); out.closeEntry();
            }
        }
        var result = service.processFile(source.toString(), options());
        var masked = new com.checkba.service.sensitive.SensitiveDocx(Path.of(result.path())).texts();
        assertTrue(masked.stream().anyMatch(t -> t.equals("BODY")));
        assertFalse(String.join("", masked).contains("13800001111"));
        var restored = service.restoreFile(result.path(), result.recoveryKit(), password);
        assertTrue(String.join("", new com.checkba.service.sensitive.SensitiveDocx(Path.of(restored.path())).texts()).contains("电话13800001111"));
    }
}
