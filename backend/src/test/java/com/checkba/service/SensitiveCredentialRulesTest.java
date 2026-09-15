// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.SensitiveType;
import com.checkba.service.sensitive.SensitiveTextEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一社会信用代码与律师执业证号两类内置规则（C1），以及"形似但校验失败"的候选
 * 必须单独报数而不是静默丢弃（C2）。
 *
 * <p>为什么号码类一定要带校验：合同正文里 18 位长号到处都是（案号、批文号、流水号），
 * 只按位数打码会把正文改坏——法律文书必须逐字可引，改坏正文的代价不比漏打码小。
 * 反过来，校验不过就一声不响地跳过，用户会以为文件里根本没有这类信息，
 * 所以这类候选要计入 suspects，由面板显示"未处理"，让律师自己补进敏感词。
 */
class SensitiveCredentialRulesTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;
    private final SensitiveService service = new SensitiveService();

    /** GB 32100-2015：校验位正确的统一社会信用代码（与下面那条真机样例只差最后一位）。 */
    private static final String VALID_USCC = "91370100MA3XK7L921";
    /**
     * 真机走查时合成合同里写的那一串。它的第 18 位按 GB 32100-2015 应当是 '1'，
     * 写成了 'Q'，所以**不是**一个有效的统一社会信用代码——本用例把这件事钉下来，
     * 免得日后有人把「这串没被打码」当成规则漏了。
     */
    private static final String FABRICATED_USCC = "91370100MA3XK7L92Q";
    /** 1 位类别 + 5 位行政区划(11010) + 4 位年份(2009) + 1 位性质(1) + 6 位序号。 */
    private static final String LICENSE_17 = "11101020091234567";
    /** 同上，行政区划用 6 位(110105)，合计 18 位。 */
    private static final String LICENSE_18 = "111010520091234560";

    private static SensitiveTextEngine engine(String text, String... strategies) {
        SensitiveTextEngine engine = new SensitiveTextEngine(List.of(strategies), false, List.of(), List.of());
        engine.learn(text);
        return engine;
    }

    private static int total(java.util.Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    @DisplayName("校验位正确的统一社会信用代码会被遮蔽")
    void unifiedSocialCreditIsRedactedWhenTheCheckDigitHolds() {
        String text = "甲方：某某科技有限公司，统一社会信用代码：" + VALID_USCC + "。";
        SensitiveTextEngine engine = engine(text, "UNIFIED_SOCIAL_CREDIT");
        String result = engine.apply(text);
        assertFalse(result.contains(VALID_USCC), "有效信用代码必须被遮蔽");
        assertTrue(result.endsWith("。"), "正文标点不能被吞掉");
        assertEquals(1, engine.counts().getOrDefault("UNIFIED_SOCIAL_CREDIT", 0));
        assertEquals(0, total(engine.suspects()), "校验通过的号不该再报「疑似」");
    }

    @Test
    @DisplayName("校验位对不上的 18 位代码不改正文，但要单独报数")
    void unifiedSocialCreditWithABrokenCheckDigitIsLeftAloneButReported() {
        String text = "统一社会信用代码：" + FABRICATED_USCC + "。";
        SensitiveTextEngine engine = engine(text, "UNIFIED_SOCIAL_CREDIT");
        assertEquals(text, engine.apply(text), "校验不过的号不是有效代码，不能改正文");
        assertEquals(0, total(engine.counts()));
        assertEquals(1, total(engine.suspects()), "静默丢弃会让用户以为文件里没有信用代码");
        assertEquals(1, engine.suspects().getOrDefault("UNIFIED_SOCIAL_CREDIT", 0));
    }

    @Test
    @DisplayName("律师执业证号 17 位与 18 位两种长度都识别")
    void lawyerLicenseCoversBothSeventeenAndEighteenDigits() {
        for (String license : List.of(LICENSE_17, LICENSE_18)) {
            String text = "代理人：某律师，执业证号 " + license + "，特此授权。";
            SensitiveTextEngine engine = engine(text, "LAWYER_LICENSE");
            String result = engine.apply(text);
            assertFalse(result.contains(license), license + " 未被遮蔽");
            assertTrue(result.endsWith("，特此授权。"), "正文被改坏了：" + result);
            assertEquals(1, engine.counts().getOrDefault("LAWYER_LICENSE", 0), license);
        }
    }

    @Test
    @DisplayName("区划码、年份、执业性质三项任一不成立的长数字不当执业证号")
    void lawyerLicenseRejectsOrdinaryLongNumbers() {
        // 区划码 02 不是省级代码；年份 0000 不成立；性质位 9 超出 1-6。
        for (String number : List.of("10201020091234567", "11101000001234567", "11101020099234567")) {
            String text = "运单号 " + number + " 已签收。";
            SensitiveTextEngine engine = engine(text, "LAWYER_LICENSE");
            assertEquals(text, engine.apply(text), number + " 被误判成执业证号，正文被改坏");
        }
    }

    @Test
    @DisplayName("同一段数字只报一次，已经被遮蔽的不再报「疑似」")
    void failedVerificationIsCountedOncePerSpanAndOnlyWhenNothingRedactedIt() {
        // 18 位案号：既不是有效身份证也不是有效银行卡，两条规则命中的是同一段，只报一次。
        String caseNumber = "202601010000012345";
        SensitiveTextEngine both = engine("案号 " + caseNumber + " 已立案。", "ID_CARD", "BANK_CARD");
        both.apply("案号 " + caseNumber + " 已立案。");
        assertEquals(1, total(both.suspects()), "同一段数字被两条规则各报一次会虚报");

        // 有效执业证号会被 LAWYER_LICENSE 遮蔽；银行卡规则在同一段上的落选候选不该再冒出来。
        String text = "执业证号 " + LICENSE_17 + "。";
        SensitiveTextEngine engine = engine(text, "LAWYER_LICENSE", "BANK_CARD");
        assertNotEquals(text, engine.apply(text), "执业证号应当被遮蔽");
        assertEquals(0, total(engine.suspects()), "已经遮蔽掉的位置再提示「未处理」会让用户白找");
    }

    @Test
    @DisplayName("两类新规则出现在面板可勾选清单里")
    void newTypesAreOfferedInTheOptionList() {
        List<String> codes = SensitiveType.autoDetectTypes().stream().map(SensitiveType::getCode).toList();
        assertTrue(codes.contains("UNIFIED_SOCIAL_CREDIT"), "面板勾不到的规则等于没加");
        assertTrue(codes.contains("LAWYER_LICENSE"));
        for (SensitiveType type : List.of(SensitiveType.UNIFIED_SOCIAL_CREDIT, SensitiveType.LAWYER_LICENSE)) {
            assertFalse(type.getLabel().isBlank(), "缺中文名");
            assertFalse(type.getExample().isBlank(), "缺示例");
            assertFalse(type.getDescription().isBlank(), "缺说明");
        }
    }

    @Test
    @DisplayName("全策略跑普通合同正文，新规则不碰金额与订单号")
    void contractProseIsUntouchedByTheNewRules() {
        String text = "本合同由甲乙双方协商订立，合同金额为1000000元，订单编号 OrderAbc12345。";
        SensitiveTextEngine engine = new SensitiveTextEngine(
                SensitiveType.autoDetectTypes().stream().map(SensitiveType::getCode).toList(),
                false, List.of(), List.of());
        engine.learn(text);
        assertEquals(text, engine.apply(text));
        assertEquals(0, total(engine.suspects()));
    }

    @Test
    @DisplayName("服务层把 suspects 一路带到预览与生成结果里")
    void serviceCarriesSuspectsToPreviewAndResult() throws Exception {
        java.nio.file.Path source = dir.resolve("contract.txt");
        java.nio.file.Files.writeString(source, "统一社会信用代码：" + FABRICATED_USCC + "，另有 " + VALID_USCC + "。");
        SensitiveService.Options options = new SensitiveService.Options(
                List.of("UNIFIED_SOCIAL_CREDIT"), "MASK", List.of(), List.of(), null);
        SensitiveService.Preview preview = service.previewFile(source.toString(), options);
        assertEquals(1, total(preview.suspects()), "预览必须带上疑似数，面板才有东西可显示");
        assertEquals(1, total(preview.counts()));
        SensitiveService.Result result = service.processFile(source.toString(), options);
        assertEquals(1, total(result.suspects()));
    }
}
