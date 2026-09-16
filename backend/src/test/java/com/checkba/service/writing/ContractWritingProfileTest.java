// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.writing;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.checkba.service.writing.WritingTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractWritingProfileTest {
    private final ContractWritingProfile profile = new ContractWritingProfile();
    private static Context context(String before) { return new Context("contract", "技术服务合同", "第二条", before, "", "采购方", ""); }
    private static Source active(String text) { return new Source("active-document", 1L, "当前合同", "r1", "正文", text); }
    private static String fixture() throws Exception {
        try (var in = ContractWritingProfileTest.class.getResourceAsStream("/writing/contract-services.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private List<Fact> facts(String text) { return profile.facts(context(""), List.of(active(text))); }
    private Advice generated(String text, List<Fact> facts) {
        return new Advice(text, "sentence", facts.stream().map(Fact::id).toList(), List.of("active-document"), "候选草稿");
    }

    @Test void identifiesExplicitContractAndTitleWithoutMistakingClaims() {
        assertTrue(profile.score(context(""), List.of()) > 0);
        assertTrue(profile.score(new Context("", "技术服务协议", "", "", "", "", ""), List.of()) > 0);
        assertEquals(0, profile.score(new Context("litigation", "民事起诉状", "事实与理由", "双方签订合同", "", "", ""), List.of()));
    }
    @Test void extractsCurrentPartiesAndAliasesWithExactProvenance() throws Exception {
        String text = fixture(); var facts = facts(text);
        Fact buyer = facts.stream().filter(f -> f.kind().equals("PARTY") && f.role().equals("采购方")).findFirst().orElseThrow();
        assertEquals("岚序信息有限公司", buyer.value());
        assertEquals("active-document", buyer.sourceId());
        assertTrue(text.contains(buyer.quote()));
        assertTrue(facts.stream().anyMatch(f -> f.kind().equals("PARTY") && f.role().equals("甲方") && f.value().equals(buyer.value())));
    }
    @Test void historicalContractCannotReverseCurrentParties() throws Exception {
        var facts = profile.facts(context(""), List.of(active(fixture()), new Source("old", 2L, "旧合同", "v1", "正文", "甲方：砚桥技术有限公司\n乙方：岚序信息有限公司")));
        assertTrue(facts.stream().allMatch(f -> f.sourceId().equals("active-document")));
        assertFalse(facts.stream().anyMatch(f -> f.role().equals("甲方") && f.value().equals("砚桥技术有限公司")));
    }
    @Test void absentActiveDocumentDoesNotPromoteExternalDefinitions() {
        assertTrue(profile.facts(context(""), List.of(new Source("old", 2L, "合同", "v1", "正文", "甲方：旧主体有限公司"))).isEmpty());
    }
    @Test void localAppendixDefinitionDoesNotLeakIntoMainContract() throws Exception {
        var defs = facts(fixture()).stream().filter(f -> f.kind().equals("DEFINITION")).toList();
        assertEquals(1, defs.size());
        assertTrue(defs.get(0).value().contains("附件一列明的成果"));
        assertFalse(defs.get(0).value().contains("试验数据"));
    }
    @Test void localArticleDefinitionDoesNotLeakAcrossSections() {
        String text = "第一条\n“验收日”在本条中是指签字日。\n第二条\n合同总价为人民币100元。";
        assertFalse(facts(text).stream().anyMatch(f -> f.kind().equals("DEFINITION")));
    }
    @Test void extractsDateAndAmountAsContractTermsNotPerformance() throws Exception {
        var facts = facts(fixture());
        assertTrue(facts.stream().anyMatch(f -> f.kind().equals("AMOUNT") && f.value().equals("120,000元") && f.status().equals("CONTRACT_TERM")));
        assertTrue(facts.stream().anyMatch(f -> f.kind().equals("DATE") && f.value().equals("2027年3月15日") && f.status().equals("CONTRACT_TERM")));
    }
    @Test void roleSuffixUsesOnlyUniqueCurrentBinding() throws Exception {
        var result = profile.deterministic(context("采购方：岚序"), facts(fixture()));
        assertEquals("信息有限公司", result.get(0).text());
        assertTrue(result.get(0).sourceIds().contains("active-document"));
    }
    @Test void roleConflictProducesNoDeterministicName() {
        var facts = facts("甲方（采购方）：岚序信息有限公司\n甲方（采购方）：晓澜科技有限公司");
        assertTrue(profile.deterministic(context("采购方：岚"), facts).isEmpty());
        assertTrue(facts.stream().filter(f -> f.role().equals("采购方")).allMatch(f -> f.status().equals("CONFLICT")));
    }
    @Test void calculatesInstallmentFromExactTotalAndRatio() throws Exception {
        var result = profile.deterministic(context("第二期价款为人民币"), facts(fixture()));
        assertEquals(1, result.size());
        assertEquals("36,000元", result.get(0).text());
        assertEquals(2, result.get(0).factIds().size());
        assertTrue(result.get(0).explanation().contains("30%"));
    }
    @Test void conflictingTotalsBlockCalculatedAmount() {
        var facts = facts("合同总价为人民币120,000元。\n合同总价为人民币150,000元。\n第二期价款占合同总价30%。");
        assertTrue(profile.deterministic(context("第二期价款为人民币"), facts).isEmpty());
    }
    @Test void explicitInstallmentDisagreeingWithCalculationBlocksSuggestion() {
        var facts = facts("合同总价为人民币120,000元。\n第二期价款占合同总价30%。\n第二期价款为人民币40,000元。");
        assertTrue(profile.deterministic(context("第二期价款为人民币"), facts).isEmpty());
    }
    @Test void unknownTaxCurrencyOrConditionsDoNotProduceCalculatedFields() {
        var facts = facts("合同总价暂定为人民币120,000元。\n第二期价款占合同总价30%。");
        assertTrue(profile.deterministic(context("第二期价款为人民币"), facts).isEmpty());
        var foreign = facts("合同总价为美元120,000元。\n第二期价款占合同总价30%。");
        assertTrue(profile.deterministic(context("第二期价款为人民币"), foreign).isEmpty());
    }
    @Test void dateFieldNeedsMatchingLabelAndKeepsSuffixProtocol() throws Exception {
        var facts = facts(fixture());
        var results = profile.deterministic(context("交付日期为2027年3月"), facts);
        assertEquals("15日", results.get(0).text());
        assertTrue(profile.deterministic(context("签署日期为"), facts).isEmpty());
    }
    @Test void contractPromiseCannotBecomePerformedFact() throws Exception {
        var facts = facts(fixture());
        assertFalse(profile.validate(context(""), facts, generated("采购方已支付全部价款。", facts)).isEmpty());
        assertFalse(profile.validate(context("采购方已支"), facts, generated("付全部价款。", facts)).isEmpty());
        var deliveryFacts = facts.stream().filter(f -> f.quote().equals("服务方应向采购方交付本合同约定的交付物。")).toList();
        assertFalse(deliveryFacts.isEmpty());
        assertTrue(profile.validate(context(""), facts, generated("服务方应向采购方交付本合同约定的交付物。", deliveryFacts)).isEmpty());
    }
    @Test void counterpartyDraftCannotBePresentedAsConfirmedTerms() {
        Context ctx = new Context("contract", "技术服务合同（对方稿）", "第二条", "", "", "采购方", "");
        var facts = profile.facts(ctx, List.of(active("合同总价为人民币120,000元。")));
        assertTrue(facts.stream().allMatch(f -> f.status().equals("COUNTERPARTY_PROPOSAL")));
        assertFalse(profile.validate(ctx, facts, generated("双方已确认合同总价为人民币120,000元。", facts)).isEmpty());
        assertTrue(profile.deterministic(new Context("contract", ctx.title(), "第二条", "合同总价为人民币", "", "采购方", ""), facts).isEmpty());
    }
    @Test void unsupportedNumbersAndDeemedAcceptanceAreRejected() throws Exception {
        var facts = facts(fixture());
        assertFalse(profile.validate(context(""), facts, generated("合同总价为人民币999,000元。", facts)).isEmpty());
        assertFalse(profile.validate(context(""), facts, generated("逾期未答复即视为验收通过。", facts)).isEmpty());
    }
    @Test void unknownStanceDoesNotAuthorizeOurPartyClaim() throws Exception {
        Context ctx = new Context("contract", "合同", "", "", "", "", ""); var facts = facts(fixture());
        assertFalse(profile.validate(ctx, facts, generated("我方为采购方。", facts)).isEmpty());
        String prompt = profile.instructions(ctx, facts);
        assertTrue(prompt.contains("约定")); assertTrue(prompt.contains("履行")); assertTrue(prompt.contains("对方"));
    }
    @Test void duplicateIdenticalTermsDoNotBecomeConflict() {
        var facts = facts("合同总价为人民币120,000元。\n合同总价为人民币120,000元。");
        assertTrue(facts.stream().noneMatch(f -> f.status().equals("CONFLICT")));
        assertEquals("120,000元", profile.deterministic(context("合同总价为人民币"), facts).get(0).text());
    }
    @Test void retainsExactConditionQuoteAndAllowsSupportedDraftSentence() throws Exception {
        var facts = facts(fixture());
        assertTrue(facts.stream().anyMatch(f -> f.kind().equals("CONTRACT_CLAUSE") && f.quote().contains("10个工作日")));
        assertTrue(profile.validate(context(""), facts, generated("采购方收到交付物后10个工作日内提交书面验收意见。", facts)).isEmpty());
    }
    @Test void generatedRoleReversalIsRejected() throws Exception {
        var facts = facts(fixture());
        assertFalse(profile.validate(context(""), facts, generated("甲方：砚桥技术有限公司", facts)).isEmpty());
        var partyFacts = facts.stream().filter(f -> f.kind().equals("PARTY") && f.role().equals("甲方")).toList();
        assertFalse(partyFacts.isEmpty());
        assertTrue(profile.validate(context(""), facts, generated("甲方：岚序信息有限公司", partyFacts)).isEmpty());
    }
    @Test void validatesCompletedAmountAcrossTheTypedPrefixAndSuffix() {
        var facts = facts("合同总价为人民币120000元。");
        var ctx = context("合同总价为人民币12");
        var candidate = profile.deterministic(ctx, facts).get(0);
        assertEquals("0000元", candidate.text());
        assertTrue(profile.validate(ctx, facts, candidate).isEmpty());
        assertFalse(profile.validate(ctx, facts, generated("00000元", facts)).isEmpty(), "extra zero cannot be hidden in a suffix");
    }
    @Test void appendixNamespaceSurvivesItsOwnUnstyledArticleHeading() {
        String text = "第一条 价款\n合同总价为人民币120000元。\n附件一 专用合同\n第一条价款\n合同总价为人民币10000元。";
        var main = profile.facts(context(""), List.of(active(text)));
        assertEquals(List.of("120000元"), main.stream().filter(f -> f.kind().equals("AMOUNT")).map(Fact::value).toList());
        var ctx = new Context("contract", "合同", "附件一 / 第一条", "合同总价为人民币", "", "", "");
        var appendix = profile.facts(ctx, List.of(active(text)));
        assertEquals(List.of("10000元"), appendix.stream().filter(f -> f.kind().equals("AMOUNT")).map(Fact::value).toList());
        assertEquals("10000元", profile.deterministic(ctx, appendix).get(0).text());
        assertTrue(appendix.stream().allMatch(f -> text.contains(f.quote())));
    }
    @Test void unknownOrUnavailableAppendixScopeCannotDefaultToMainBody() {
        String text = "合同总价为人民币120000元。\n附件一 专用约定\n合同总价为人民币10000元。";
        var unknown = new Context("contract", "合同", "", "合同总价为人民币", "", "", "");
        assertTrue(profile.facts(unknown, List.of(active(text))).isEmpty());
        var missingAppendix = new Context("contract", "合同", "附件二 / 第一条", "合同总价为人民币", "", "", "");
        assertTrue(profile.facts(missingAppendix, List.of(active(text))).isEmpty());
    }
    @Test void emptyPartyFieldCanCompleteOneExplicitCurrentRole() {
        var facts = facts("甲方：岚序信息有限公司");
        assertEquals("岚序信息有限公司", profile.deterministic(context("甲方："), facts).get(0).text());
    }
    @Test void proseScopePreservesProposalPendingAndOriginalParagraphNumbers() {
        var ctx = new Context("contract", "合同（对方稿）", "正文", "", "", "", "");
        var statuses = ContractWritingProfile.narrativeScope(ctx, active("\n服务费用暂定由采购方在收到交付物后另行确认。\n服务方应在约定期限内完成交付。"));
        assertEquals("PENDING", statuses.get(2));
        assertEquals("COUNTERPARTY_PROPOSAL", statuses.get(3));
    }
    @Test void shadowedGlobalDefinitionCannotReturnViaProseFallback() {
        var ctx = new Context("contract", "合同", "第一条", "", "", "", "");
        var source = active("“交付物”是指操作手册。\n第一条\n“交付物”在本条中是指培训录屏。\n“交付物”是指操作手册。");
        var defs = profile.facts(ctx, List.of(source)).stream().filter(f -> f.kind().equals("DEFINITION")).toList();
        assertEquals(1, defs.size()); assertEquals("培训录屏", defs.get(0).value());
        var allowed = ContractWritingProfile.narrativeScope(ctx, source);
        assertFalse(allowed.containsKey(1)); assertFalse(allowed.containsKey(4)); assertTrue(allowed.containsKey(3));
    }
    @Test void twoDifferentLocalDefinitionsRemainAConflict() {
        var ctx = new Context("contract", "合同", "第一条", "", "", "", "");
        var defs = profile.facts(ctx, List.of(active("第一条\n“交付物”在本条中是指培训录屏。\n“交付物”在本条中是指测试数据。")));
        assertEquals(2, defs.stream().filter(f -> f.kind().equals("DEFINITION") && f.status().equals("CONFLICT")).count());
    }
    @Test void conditionalAmountKeepsCompleteQuoteAndCannotBecomeUnconditionalField() {
        String clause = "在选择加急方案时，合同总价为人民币120000元。";
        var facts = facts(clause);
        Fact amount = facts.stream().filter(f -> f.kind().equals("AMOUNT")).findFirst().orElseThrow();
        assertEquals(clause, amount.quote());
        assertEquals("PENDING", amount.status());
        assertTrue(profile.deterministic(context("合同总价为人民币"), facts).isEmpty());
        assertFalse(profile.validate(context(""), facts, generated("合同总价为人民币120000元。", facts)).isEmpty());
        assertTrue(profile.validate(context("在选择加急方案时，"), facts, generated("合同总价为人民币120000元。", facts)).isEmpty());
    }
    @Test void contingentAmountsRatiosAndDatesCannotSilentlyLoseTheirPrerequisites() {
        for (String clause : List.of("如选择分期，第二期价款占合同总价30%。", "若完成签署，生效日期为2027年3月15日。", "仅当验收通过，尾款为人民币10000元。", "交付前，预付款为人民币20000元。")) {
            var facts = facts(clause);
            assertFalse(facts.isEmpty(), clause);
            assertTrue(facts.stream().allMatch(f -> f.status().equals("PENDING") && f.quote().equals(clause)), clause);
        }
    }
    @Test void paymentAfterDeliveryRemainsConditionalPromiseNotProofOfPayment() {
        String clause = "交付后支付，尾款为人民币10000元。";
        var facts = facts(clause);
        assertTrue(profile.validate(context(""), facts, generated(clause, facts)).isEmpty());
        assertFalse(profile.validate(context(""), facts, generated("交付后已支付尾款人民币10000元。", facts)).isEmpty());
    }
    @Test void oversizedParagraphIsNotTruncatedIntoUnqualifiedStructuredFacts() {
        assertTrue(facts("如" + "待核事项".repeat(301) + "，合同总价为人民币120000元。").isEmpty());
        var facts = facts("合同总价为人民币120000元。");
        assertEquals("CONTRACT_TERM", facts.get(0).status());
        assertEquals("合同总价为人民币120000元。", facts.get(0).quote());
    }
}
