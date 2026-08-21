package com.checkba.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正文替换这条路上的过度匹配：法律文书里到处是长数字（立案号、送达回证号、流水号），
 * 它们被当成身份证/银行卡打码，文书就不再逐字可引了，而用户看不到任何提示。
 */
class SensitiveTextReplaceTest {

    private final SensitiveService service = new SensitiveService();

    @Test
    @DisplayName("18 位立案号不动，真身份证照打码")
    void caseNumberSurvivesWhileRealIdIsMasked() {
        String text = "案号 202601011234567890，当事人身份证号 11010519491231002X。";
        String out = service.replaceSensitiveData(text, "ID_CARD");
        assertTrue(out.contains("202601011234567890"), "立案号被改坏了：" + out);
        assertTrue(!out.contains("11010519491231002X"), "真身份证没打码：" + out);
    }

    @Test
    @DisplayName("超长数字串整体不动，不许从中间切一段打码")
    void longDigitRunIsLeftAlone() {
        String text = "运单号 1234567890123456789012345 已签收。";
        assertEquals(text, service.replaceSensitiveData(text, "BANK_CARD"));
    }
}
