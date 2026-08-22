package com.checkba.service.evidence.webverify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 站点枚举的识别口径：认 code/枚举名/中文名/别名；认不出是 OTHER；没写是 null。 */
class WebVerifySiteTest {

    @Test
    @DisplayName("认 code、枚举名与中文短名（全等）")
    void parsesExactForms() {
        assertEquals(WebVerifySite.CREDIT_PUBLICITY, WebVerifySite.parse("credit_publicity"));
        assertEquals(WebVerifySite.CREDIT_PUBLICITY, WebVerifySite.parse("CREDIT_PUBLICITY"));
        assertEquals(WebVerifySite.CREDIT_PUBLICITY, WebVerifySite.parse("企业信用信息公示"));
        assertEquals(WebVerifySite.JUDGMENT_DOCS, WebVerifySite.parse("judgment_docs"));
    }

    @Test
    @DisplayName("认别名（子串命中），环保处罚不会被一般行政处罚抢走")
    void parsesAliases() {
        assertEquals(WebVerifySite.CREDIT_PUBLICITY, WebVerifySite.parse("国家企业信用信息公示系统"));
        assertEquals(WebVerifySite.JUDGMENT_DOCS, WebVerifySite.parse("中国裁判文书网"));
        assertEquals(WebVerifySite.DISHONEST_EXECUTEE, WebVerifySite.parse("失信被执行人名单"));
        assertEquals(WebVerifySite.ENV_PENALTY, WebVerifySite.parse("环保行政处罚"));
        assertEquals(WebVerifySite.ADMIN_PENALTY, WebVerifySite.parse("行政处罚"));
    }

    @Test
    @DisplayName("认不出是 OTHER；空是 null（「没写」与「写了但不认识」不是一回事）")
    void unknownAndBlank() {
        assertEquals(WebVerifySite.OTHER, WebVerifySite.parse("某个没听过的库"));
        assertNull(WebVerifySite.parse(null));
        assertNull(WebVerifySite.parse("  "));
    }

    @Test
    @DisplayName("枚举里不带任何 URL——网核只留接口，不做自动爬取")
    void carriesNoUrls() {
        for (WebVerifySite s : WebVerifySite.values()) {
            assertTrue(!s.label().contains("http") && !s.code().contains("http"), s.name());
            for (String a : s.aliases()) assertTrue(!a.contains("http"), s.name() + ": " + a);
        }
    }
}
