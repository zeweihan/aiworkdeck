// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.util.style.StyleProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PDF 抽出来的文字里常带 XML 1.0 不允许的控制字符（如 U+0002，字体私有编码的残留）。
 * docx4j 不替你挡：原样写进 word/document.xml，产出的 docx 编辑器打不开、预览也渲染不出
 * （dev-board#1016/#1018 的直接原因）。落盘前必须剔除，且 \t \n \r 保留。
 */
class AiDocxExportXmlCharsTest {

    private static AiDocxExportService service(AtomicReference<byte[]> saved) throws Exception {
        ProjectFileService files = mock(ProjectFileService.class);
        ProjectFile pf = new ProjectFile();
        pf.setId(11L);
        pf.setName("out.docx");
        pf.setFilePath("projects/3/out.docx");
        when(files.createFile(eq(3L), any(), anyString(), anyString(), any(), any(), anyString(), anyLong(),
                eq(ProjectFileService.ConflictPolicy.RENAME))).thenReturn(pf);
        StorageService storage = mock(StorageService.class);
        when(storage.save(anyString(), any(InputStream.class))).thenAnswer(inv -> {
            saved.set(inv.getArgument(1, InputStream.class).readAllBytes());
            return inv.getArgument(0, String.class);
        });
        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        when(factory.getStorageService()).thenReturn(storage);
        return new AiDocxExportService(files, factory, mock(ProjectRagService.class),
                mock(StyleProfileResolver.class));
    }

    /** 解出 word/document.xml 并用标准 XML 解析器读一遍：非法字符在这里抛 SAXParseException。 */
    private static String parseDocumentXml(byte[] docx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    byte[] xml = zip.readAllBytes();
                    DocumentBuilderFactory.newInstance().newDocumentBuilder()
                            .parse(new ByteArrayInputStream(xml));
                    return new String(xml, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("docx 里没有 word/document.xml");
    }

    @Test
    @DisplayName("markdown 里带 U+0002 等控制字符：导出的 document.xml 仍是合法 XML，正文照常保留")
    void controlCharsAreStrippedBeforeSave() throws Exception {
        AtomicReference<byte[]> saved = new AtomicReference<>();
        String md = "# 标题\u0002\n\n第一条\u0001 甲方\u0008应当\u000B支付\t价款\u001F。";
        service(saved).exportMarkdownToDocx(3L, null, 1L, "out.docx", md, StyleProfiles.houseDefault());

        assertNotNull(saved.get(), "没有落盘");
        String xml = assertDoesNotThrow(() -> parseDocumentXml(saved.get()),
                "document.xml 不是合法 XML，编辑器打不开这份 docx");
        assertTrue(xml.contains("甲方"), xml);
        assertTrue(xml.contains("支付"), xml);
    }

    @Test
    @DisplayName("stripXmlInvalidChars：剔除 C0 控制字符、孤立代理与 U+FFFE/U+FFFF，保留 \\t \\n \\r 与正常增补字符")
    void stripperKeepsWhitespaceAndSupplementary() {
        String in = "a\u0000b\u0002c\td\ne\rf\u000Bg￾h￿i\uD800j😀k";
        assertEquals("abc\td\ne\rfghij😀k", AiDocxExportService.stripXmlInvalidChars(in));
        assertEquals("", AiDocxExportService.stripXmlInvalidChars(""));
        assertEquals(null, AiDocxExportService.stripXmlInvalidChars(null));
        String clean = "普通正文，无需改动。";
        assertTrue(clean == AiDocxExportService.stripXmlInvalidChars(clean), "干净文本应原样返回同一实例");
    }
}
