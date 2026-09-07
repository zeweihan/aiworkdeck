package com.checkba.util.style;

import com.checkba.util.DocxStyleHelper;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.CTCompat;
import org.docx4j.wml.CTCompatSetting;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedDocxCompatibilityTest {
    private static final String WORD_URI = "http://schemas.microsoft.com/office/word";

    @Test
    void generatedMarkdownKeepsRealGridTableAndModernCompatibilityAfterSave() throws Exception {
        byte[] bytes = DocxStyleHelperProfileTest.render(DocxStyleHelperProfileTest.MD, StyleProfiles.houseDefault());
        WordprocessingMLPackage saved = WordprocessingMLPackage.load(new ByteArrayInputStream(bytes));
        assertMode15(saved);
        var table = saved.getMainDocumentPart().getContent().stream()
                .map(org.docx4j.XmlUtils::unwrap).filter(org.docx4j.wml.Tbl.class::isInstance)
                .map(org.docx4j.wml.Tbl.class::cast).findFirst().orElseThrow();
        var borders = table.getTblPr().getTblBorders();
        assertNotNull(borders);
        for (var border : java.util.List.of(borders.getTop(), borders.getBottom(), borders.getLeft(),
                borders.getRight(), borders.getInsideH(), borders.getInsideV())) {
            assertEquals(org.docx4j.wml.STBorder.SINGLE, border.getVal());
            assertTrue(border.getSz().intValue() > 0);
        }
    }

    @Test
    void blankStreamPackageStaysBlankAndSetsOnlyOneModeWithoutDroppingOtherSettings() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        var before = pkg.getMainDocumentPart().getContent().size();
        DocxStyleHelper.setModernCompatibility(pkg);
        var compat = pkg.getMainDocumentPart().getDocumentSettingsPart().getJaxbElement().getCompat();
        compat.setCompatSetting("compatibilityMode", WORD_URI, "12");
        compat.setCompatSetting("doNotExpandShiftReturn", WORD_URI, "1");
        DocxStyleHelper.setModernCompatibility(pkg);
        DocxStyleHelper.setModernCompatibility(pkg);
        assertEquals(before, pkg.getMainDocumentPart().getContent().size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pkg.save(out);
        var saved = WordprocessingMLPackage.load(new ByteArrayInputStream(out.toByteArray()));
        assertMode15(saved);
        CTCompat savedCompat = saved.getMainDocumentPart().getDocumentSettingsPart().getJaxbElement().getCompat();
        assertEquals("1", savedCompat.getCompatSetting("doNotExpandShiftReturn", WORD_URI).getVal());
        assertEquals(1, savedCompat.getCompatSetting().stream().filter(v -> "compatibilityMode".equals(v.getName())).count());
    }

    private static void assertMode15(WordprocessingMLPackage pkg) {
        CTCompat compat = pkg.getMainDocumentPart().getDocumentSettingsPart().getJaxbElement().getCompat();
        assertNotNull(compat);
        CTCompatSetting mode = compat.getCompatSetting("compatibilityMode", WORD_URI);
        assertNotNull(mode);
        assertEquals("15", mode.getVal());
    }
}
