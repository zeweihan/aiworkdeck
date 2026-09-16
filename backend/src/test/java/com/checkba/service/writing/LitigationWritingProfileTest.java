// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.writing;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.checkba.service.writing.WritingTypes.*;

class LitigationWritingProfileTest {
    final LitigationWritingProfile profile = new LitigationWritingProfile();
    Context context(String before) { return new Context("起诉状", "民事起诉状", "事实与理由", before, "", "原告", "2026-09-16"); }
    Source source(String id, String text) { return new Source(id, 1L, "合成材料", "v1", "第1页", text); }
    List<Fact> facts(String text) { return profile.facts(context(""), List.of(source("s1", text))); }
    Advice advice(String text, Fact fact) { return new Advice(text, "sentence", List.of(fact.id()), List.of(fact.sourceId()), ""); }
    @Test void recognizesBothPleadings() {
        assertTrue(profile.score(context(""), List.of()) > 0);
        assertTrue(profile.score(new Context("代理词", "", "", "", "", "被告", ""), List.of()) > 0);
        assertEquals(0, profile.score(new Context("合同", "设备采购合同", "", "", "", "", ""), List.of()));
    }
    @Test void syntheticFixtureKeepsExactProvenance() throws Exception {
        String text;
        try (var in = getClass().getResourceAsStream("/writing/litigation-loan.txt")) { text = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        var out = facts(text);
        assertTrue(out.size() >= 6);
        assertTrue(out.stream().allMatch(f -> text.contains(f.quote()) && "s1".equals(f.sourceId())));
        assertTrue(out.stream().anyMatch(f -> "AMOUNT".equals(f.kind()) && "人民币360000元".equals(f.value())));
    }
    @Test void completesOnlyDefendantSuffix() {
        var out = profile.deterministic(context("被告：顾"), facts("原告：沈望舒\n被告：顾青原"));
        assertEquals(1, out.size()); assertEquals("青原", out.get(0).text());
    }
    @Test void emptyRoleFieldUsesExplicitRole() {
        var out = profile.deterministic(context("原告："), facts("原告：沈望舒\n被告：顾青原"));
        assertEquals("沈望舒", out.get(0).text());
    }
    @Test void conflictingRolesAbstain() {
        assertTrue(profile.deterministic(context("被告：顾"), facts("被告：顾青原\n被告：顾青岑")).isEmpty());
    }
    @Test void missingRoleDoesNotGuessFromName() {
        assertTrue(profile.deterministic(context("被告：顾"), facts("顾青原曾向沈望舒出具借条。")).isEmpty());
    }
    @Test void duplicateIdenticalEvidenceDoesNotCreateConflict() {
        var fs = profile.facts(context(""), List.of(source("a", "被告：顾青原"), source("b", "被告：顾青原")));
        assertEquals(1, profile.deterministic(context("被告：顾"), fs).size());
    }
    @Test void claimsRemainAttributed() {
        var fs = facts("被告主张：已经以现金偿还50000元。");
        assertEquals("CLAIM", fs.get(0).status()); assertEquals("被告", fs.get(0).role());
        assertTrue(profile.validate(context(""), fs, advice("已经以现金偿还50000元。", fs.get(0))).size() > 0);
        assertTrue(profile.validate(context(""), fs, advice("被告主张：已经以现金偿还50000元。", fs.get(0))).isEmpty());
    }
    @Test void noPromotionToCourtFinding() {
        var fs = facts("原告主张：尚欠借款360000元。");
        assertFalse(profile.validate(context(""), fs, advice("法院认定被告尚欠借款360000元。", fs.get(0))).isEmpty());
    }
    @Test void noUnqualifiedLegalConclusion() {
        var fs = facts("原告主张：尚欠借款360000元。");
        assertFalse(profile.validate(context(""), fs, advice("足以认定原告的全部请求成立。", fs.get(0))).isEmpty());
    }
    @Test void refusesRoleSwap() {
        var fs = facts("原告：沈望舒\n被告：顾青原");
        assertFalse(profile.validate(context(""), fs, advice("原告顾青原", fs.get(0))).isEmpty());
    }
    @Test void amountFieldUsesExactSourceNotMentalCalculation() {
        var out = profile.deterministic(context("借款本金：人民币36"), facts("借款本金：人民币360000元"));
        assertEquals("0000元", out.get(0).text());
        assertTrue(profile.deterministic(context("尚欠本金："), facts("借款本金：人民币360000元\n已还款50000元")).isEmpty());
    }
    @Test void amountConflictAbstains() {
        assertTrue(profile.deterministic(context("借款本金："), facts("借款本金：360000元\n借款本金：310000元")).isEmpty());
    }
    @Test void dateFieldPreservesOriginalUnits() {
        var out = profile.deterministic(context("借款日期：2025年"), facts("借款日期：2025年4月8日"));
        assertEquals("4月8日", out.get(0).text());
    }
    @Test void staleFactsCannotBeAccepted() {
        Fact f = new Fact("old", "PARTY", "被告", "顾青原", "被告", "STALE", "s1", "被告：顾青原");
        assertTrue(profile.deterministic(context("被告：顾"), List.of(f)).isEmpty());
        assertFalse(profile.validate(context("被告：顾"), List.of(f), advice("青原", f)).isEmpty());
    }
    @Test void unknownFactReferenceRejected() {
        assertFalse(profile.validate(context(""), List.of(), new Advice("顾青原", "entity", List.of("missing"), List.of("missing"), "")).isEmpty());
    }
    @Test void partialMiddleTextDoesNotDuplicateExistingSuffix() {
        Context c = new Context("起诉状", "", "", "被告：顾", "青原", "原告", "");
        assertTrue(profile.deterministic(c, facts("被告：顾青原")).isEmpty());
    }
    @Test void instructionsPreserveAdvocacyAndUnknownFacts() {
        String s = profile.instructions(new Context("代理词", "", "争议焦点", "", "", "被告", ""), List.of());
        assertTrue(s.contains("被告")); assertTrue(s.contains("主张")); assertTrue(s.contains("认定"));
    }
    @Test void unsupportedRoleNarrativeIsNotParsedAsPartyField() {
        assertTrue(facts("原告认为：被告应当返还借款。").stream().noneMatch(f -> f.kind().equals("PARTY")));
    }
    @Test void activeDocumentRoleBeatsOldPleading() {
        var fs = profile.facts(context(""), List.of(source("archive", "被告：沈望舒"), source("active-document", "被告：顾青原")));
        assertEquals("顾青原", profile.deterministic(context("被告："), fs).get(0).text());
    }
    @Test void activeDocumentInternalConflictStillAbstains() {
        var fs = profile.facts(context(""), List.of(source("active-document", "被告：顾青原\n被告：沈望舒")));
        assertTrue(profile.deterministic(context("被告："), fs).isEmpty());
    }
    @Test void attributionInPreviousSentenceCannotLicenseNewAssertion() {
        var fs = facts("原告主张：尚欠借款360000元。");
        assertFalse(profile.validate(context("原告主张已交付借款。\n此外，"), fs, advice("尚欠借款360000元。", fs.get(0))).isEmpty());
    }
    @Test void claimAtCaretKeepsValidAttribution() {
        var fs = facts("原告主张：尚欠借款360000元。");
        assertTrue(profile.validate(context("原告主张："), fs, advice("尚欠借款360000元。", fs.get(0))).isEmpty());
    }
    @Test void splitCertaintyPhraseCannotBypassValidation() {
        var fs = facts("原告主张：尚欠借款360000元。");
        assertFalse(profile.validate(context("法院认"), fs, advice("定被告欠款。", fs.get(0))).isEmpty());
    }
    @Test void emptySourceIdentityCannotSupplyFacts() {
        assertTrue(profile.facts(context(""), List.of(source("", "被告：顾青原"))).isEmpty());
    }
    @Test void missingSourceReferenceRejectsOtherwiseValidSuggestion() {
        var fs = facts("被告：顾青原");
        assertFalse(profile.validate(context("被告：顾"), fs, new Advice("青原", "entity", List.of(fs.get(0).id()), List.of(), "")).isEmpty());
    }
    @Test void oneAttributedSentenceCannotLaunderAnUnattributedNextSentence() {
        var fs = facts("被告主张：已经以现金偿还50000元。");
        assertFalse(profile.validate(context(""), fs, advice("被告主张已经以现金偿还50000元。该款已经以现金偿还。", fs.get(0))).isEmpty());
        assertTrue(profile.validate(context(""), fs, advice("被告主张已经以现金偿还50000元。该主张尚待核实。", fs.get(0))).isEmpty());
    }
    @Test void aClaimBeforeSemicolonDoesNotAuthorizeAnUnqualifiedClaimAfterIt() {
        var fs = facts("被告主张：已经以现金偿还50000元。");
        assertFalse(profile.validate(context(""), fs, advice("被告主张已经以现金偿还50000元；该款已经偿还。", fs.get(0))).isEmpty());
    }
    @Test void denialAndStatementKeepExplicitAttributionWithoutRequiringOneVerb() {
        Fact fact = new Fact("claim", "SOURCE_TEXT", "被告陈述", "被告称并未收到原告所称借款，对借据的真实性仍有异议。", "被告", "CLAIM", "s1", "被告称并未收到原告所称借款，对借据的真实性仍有异议。");
        assertTrue(profile.validate(context("根据被告陈述，"), List.of(fact), advice("被告否认收到原告主张的借款，并对借据真实性提出异议。", fact)).isEmpty());
        assertFalse(profile.validate(context(""), List.of(fact), advice("被告称未收到借款。该借款未交付。", fact)).isEmpty());
    }
    @Test void completedCertaintyPrefixStillGovernsTheUnfinishedSentence() {
        var fs = facts("原告主张：尚欠本金360000元。");
        assertTrue(profile.validate(context("法院认定："), fs, advice("原告主张被告尚欠本金360000元", fs.get(0))).contains("UNVERIFIED_ADJUDICATION_OR_CERTAINTY"));
    }
    @Test void certaintyInFinishedEarlierSentenceDoesNotPoisonCurrentAttributedSentence() {
        var fs = facts("原告主张：尚欠本金360000元。");
        for (String punctuation : List.of("。", "！", "？", "；", ";", "\n")) {
            assertTrue(profile.validate(context("法院认定：该案已经审理" + punctuation), fs, advice("原告主张被告尚欠本金360000元。", fs.get(0))).isEmpty(), punctuation);
        }
    }
    @Test void laterAttributionCannotLaunderConflictingAmountAtSentenceStart() {
        var fs = facts("借款本金：人民币360000元\n借款本金：人民币400000元\n原告主张：被告尚欠本金360000元。");
        Fact claim = fs.stream().filter(f -> f.kind().equals("CLAIM")).findFirst().orElseThrow();
        assertTrue(profile.validate(context("借款本金："), fs,
                advice("人民币360000元，原告主张被告尚欠本金360000元。", claim)).contains("CLAIM_ATTRIBUTION_REQUIRED"));
        assertTrue(profile.validate(context("借款本金：人民币36"), fs,
                advice("0000元，原告主张被告尚欠本金360000元。", claim)).contains("CLAIM_ATTRIBUTION_REQUIRED"));
        assertTrue(profile.validate(context("借款本金："), fs,
                advice("原告主张被告尚欠本金360000元。", claim)).isEmpty());
    }
    @Test void laterAttributionCannotLaunderUnqualifiedNonNumericFact() {
        var fs = facts("被告主张：借款已经偿还。");
        assertTrue(profile.validate(context(""), fs,
                advice("借款已经偿还，被告主张该借款已经偿还。", fs.get(0))).contains("CLAIM_ATTRIBUTION_REQUIRED"));
    }

}
