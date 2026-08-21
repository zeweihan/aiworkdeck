package com.checkba.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数字类脱敏规则的过度匹配。
 *
 * <p>病灶两层：
 * <ol>
 *   <li>模式两端没有「不接数字」的断言，于是一串更长的数字（快递单号、流水号、
 *       拼接时间戳）里会被切出一段当成银行卡号/身份证号打码——原文被改坏，
 *       而用户看不到任何「这里可能误判了」的提示；</li>
 *   <li>即便长度正好，18 位的案号与 18 位的身份证号在纯正则眼里长得一模一样。
 *       身份证有 mod-11-2 校验位、银行卡有 Luhn 校验位，能把绝大多数误判挡掉，
 *       而校验不过的号本来就不是有效的身份证/银行卡，给它打码就是误伤。</li>
 * </ol>
 * 法律文书必须逐字可引，改坏正文的代价不比漏打码小。
 */
class SensitiveTypeMatchTest {

    /** 用给定前缀算出 Luhn 校验位，拼成一张合法卡号（测试自证，不写死魔数）。 */
    private static String withLuhn(String prefix) {
        int sum = 0;
        boolean doubleIt = true;
        for (int i = prefix.length() - 1; i >= 0; i--) {
            int d = prefix.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return prefix + ((10 - sum % 10) % 10);
    }

    private static String firstMatch(SensitiveType type, String text) {
        Matcher m = type.getPattern().matcher(text);
        return m.find() ? m.group() : null;
    }

    @Test
    @DisplayName("更长的数字串里不许切出一段当成银行卡号")
    void bankCardDoesNotMatchInsideALongerDigitRun() {
        String text = "运单号 1234567890123456789012345 已签收。";
        assertNull(firstMatch(SensitiveType.BANK_CARD, text),
                "长数字串里被切出一段打码，正文就被改坏了");
    }

    @Test
    @DisplayName("更长的数字串里不许切出一段当成手机号/身份证号")
    void phoneAndIdCardDoNotMatchInsideALongerDigitRun() {
        assertNull(firstMatch(SensitiveType.PHONE, "流水号 99913800001111999 结转。"));
        assertNull(firstMatch(SensitiveType.ID_CARD, "编号 9911010519491231002X9 备案。"));
    }

    @Test
    @DisplayName("身份证：校验位过得去的才算，18 位案号不算")
    void idCardRequiresChecksum() {
        assertTrue(SensitiveType.ID_CARD.isPlausible("11010519491231002X"), "这是标准的合法样例号");
        assertFalse(SensitiveType.ID_CARD.isPlausible("202601011234567890"),
                "18 位立案号不是身份证号，不该被当成身份证打码");
    }

    @Test
    @DisplayName("银行卡：Luhn 过得去的才算")
    void bankCardRequiresLuhn() {
        String valid = withLuhn("622202100112233");
        assertEquals(16, valid.length());
        assertTrue(SensitiveType.BANK_CARD.isPlausible(valid));
        assertFalse(SensitiveType.BANK_CARD.isPlausible("202601011234567890"),
                "18 位立案号不是卡号，不该被当成银行卡打码");
    }

    @Test
    @DisplayName("护栏：正常的手机号/身份证/卡号照样命中，别把该打的码打漏了")
    void realValuesStillMatch() {
        assertEquals("13800001111", firstMatch(SensitiveType.PHONE, "联系电话 13800001111。"));
        assertEquals("11010519491231002X",
                firstMatch(SensitiveType.ID_CARD, "身份证号 11010519491231002X。"));
        String card = withLuhn("622202100112233");
        assertEquals(card, firstMatch(SensitiveType.BANK_CARD, "卡号 " + card + "。"));
        assertTrue(SensitiveType.PHONE.isPlausible("13800001111"));
    }
}
