// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.util;

import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B4：POI 落盘路径写 extended properties 的 Application。
 *
 * 两条红线都在这里守：① 值必须等于 {@link ProductIdentity#applicationName()}（调用处
 * 不许拼字面量）；② Company / Manager / creator / lastModifiedBy 这些可能带身份的字段
 * 不许因为这次打标而改变——判据是「开关开与关两份产物里这些字段完全一致」，不是
 * 「它们必须为空」：POI 自己就会把 creator 写成 "Apache POI"，那是既有行为，与我们无关。
 */
class DocumentGeneratorStampTest {

    private static final String VERSION_PROPERTY = "awd.app.version";
    private String previousVersion;

    @BeforeEach
    void setUp() {
        previousVersion = System.getProperty(VERSION_PROPERTY);
        System.setProperty(VERSION_PROPERTY, "9.9.9-test");
    }

    @AfterEach
    void tearDown() {
        if (previousVersion == null) System.clearProperty(VERSION_PROPERTY);
        else System.setProperty(VERSION_PROPERTY, previousVersion);
    }

    private byte[] writeDocx(boolean enabled) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("正文");
            DocumentGeneratorStamp.apply(doc, enabled);
            doc.write(out);
            return out.toByteArray();
        }
    }

    private byte[] writeXlsx(boolean enabled) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Sheet1");
            DocumentGeneratorStamp.apply(wb, enabled);
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void docx_writes_application_when_enabled() throws Exception {
        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(writeDocx(true)))) {
            POIXMLProperties props = reopened.getProperties();
            assertEquals("AI WorkDeck 9.9.9-test",
                    props.getExtendedProperties().getUnderlyingProperties().getApplication());
        }
        assertOnlyApplicationDiffers(identityOf(writeDocx(false), true), identityOf(writeDocx(true), true));
    }

    @Test
    void docx_writes_nothing_when_disabled() throws Exception {
        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(writeDocx(false)))) {
            String app = reopened.getProperties().getExtendedProperties()
                    .getUnderlyingProperties().getApplication();
            assertTrue(app == null || app.isEmpty() || !app.contains("AI WorkDeck"),
                    "开关关掉后不许出现产品标识，实际: " + app);
        }
    }

    @Test
    void xlsx_writes_application_when_enabled() throws Exception {
        try (XSSFWorkbook reopened = new XSSFWorkbook(new ByteArrayInputStream(writeXlsx(true)))) {
            POIXMLProperties props = reopened.getProperties();
            assertEquals(ProductIdentity.applicationName(),
                    props.getExtendedProperties().getUnderlyingProperties().getApplication());
        }
        assertOnlyApplicationDiffers(identityOf(writeXlsx(false), false), identityOf(writeXlsx(true), false));
    }

    @Test
    void xlsx_writes_nothing_when_disabled() throws Exception {
        try (XSSFWorkbook reopened = new XSSFWorkbook(new ByteArrayInputStream(writeXlsx(false)))) {
            String app = reopened.getProperties().getExtendedProperties()
                    .getUnderlyingProperties().getApplication();
            assertTrue(app == null || app.isEmpty() || !app.contains("AI WorkDeck"),
                    "开关关掉后不许出现产品标识，实际: " + app);
        }
    }

    @Test
    void null_document_is_a_no_op() {
        assertDoesNotThrow(() -> DocumentGeneratorStamp.apply(null, true));
    }

    @Test
    void null_settings_means_enabled() {
        assertTrue(DocumentGeneratorStamp.enabled(null));
    }

    /** 身份类字段快照：Company / Manager / creator / lastModifiedBy。 */
    private java.util.Map<String, String> identityOf(byte[] bytes, boolean docx) throws Exception {
        try (var doc = docx
                ? (org.apache.poi.ooxml.POIXMLDocument) new XWPFDocument(new ByteArrayInputStream(bytes))
                : new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            POIXMLProperties props = doc.getProperties();
            var ext = props.getExtendedProperties().getUnderlyingProperties();
            var m = new java.util.LinkedHashMap<String, String>();
            m.put("Company", String.valueOf(ext.getCompany()));
            m.put("Manager", String.valueOf(ext.getManager()));
            m.put("creator", String.valueOf(props.getCoreProperties().getCreator()));
            m.put("lastModifiedBy", String.valueOf(props.getCoreProperties().getLastModifiedByUser()));
            return m;
        }
    }

    /** 打标只许动 Application：身份四件套两份产物里必须一模一样。 */
    private void assertOnlyApplicationDiffers(java.util.Map<String, String> off,
                                              java.util.Map<String, String> on) {
        assertEquals(off, on, "打标不许碰任何可能带身份的字段");
        for (var e : on.entrySet()) {
            assertFalse(String.valueOf(e.getValue()).contains("AI WorkDeck"),
                    e.getKey() + " 里不该出现产品标识，实际: " + e.getValue());
        }
    }
}
