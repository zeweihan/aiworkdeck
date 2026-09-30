// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.ai.skill;

import com.checkba.service.AppLanguageService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.telemetry.TelemetryService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegalOpinionSkillRoutingTest {
    private SkillRouter router(boolean english) {
        SkillProperties properties = new SkillProperties();
        properties.setDir("skills");
        AppLanguageService language = mock(AppLanguageService.class);
        when(language.isEnglish()).thenReturn(english);
        when(language.language()).thenReturn(english ? AppLanguageService.EN_US : AppLanguageService.ZH_CN);
        SkillRegistry registry = new SkillRegistry(properties, null, new PluginService(), language);
        registry.init();
        return new SkillRouter(registry, properties, mock(TelemetryService.class), language);
    }

    @ParameterizedTest
    @CsvSource({"false,修订法律意见书", "true,Revise this legal opinion"})
    void opinionWorkflowSeparatesRequirementsFactsAndAdvice(boolean english, String request) {
        SkillRouter router = router(english);
        router.activateForTurn("opinion", "opinion-run", request, null);
        String prompt = router.promptInjectionFor(router.activeSkill("opinion-run").orElseThrow());
        assertAll(
                () -> assertTrue(prompt.contains(english ? "legal requirements, contractual terms, established facts and risk-control advice"
                        : "法律要求、合同约定、已证实事实与风险控制建议")),
                () -> assertTrue(prompt.contains(english ? "non-performance does not itself create a new precondition"
                        : "未履行事实本身不生成新的先决条件")),
                () -> assertTrue(prompt.contains(english ? "identities, subject matter or premises for applying the law"
                        : "主体身份、标的或法律适用前提")),
                () -> assertTrue(prompt.contains(english ? "conditional analysis or mark them unverified"
                        : "条件化分析或待核实")),
                () -> assertTrue(prompt.contains(english ? "current status" : "目前")),
                () -> assertTrue(prompt.contains(english ? "requirements, facts and advice have not been conflated"
                        : "要求、事实与建议未混淆")));
    }

    @ParameterizedTest
    @CsvSource({
        "false,帮我修订一下这个法律意见书",
        "false,根据项目材料审查法律意见书",
        "false,只审查法律意见书第三段的事实依据",
        "false,逐条修订法律意见书全文中有关付款的事实",
        "false,全面修订这份法律意见书，也检查错别字和格式",
        "false,审查法律意见书的事实依据，同时检查排版",
        "false,全面审查法律意见书，正文仅修错字",
        "false,修订法律意见书，格式不动",
        "false,不要只改错字，要修订整个法律意见书",
        "false,全面审查法律意见书，正文仅限把“甲”改成“乙”",
        "false,全面修订法律意见书，也把“甲”改成“乙”",
        "false,修订法律意见书，不要仅限把“甲”改成“乙”",
        "false,修订法律意见书，仅限把付款要求改为交割后结算",
        "false,請審閱這份法律意見書",
        "true,Please revise this legal opinion",
        "true,Review only paragraph three of this legal opinion against the evidence",
        "true,Review this legal opinion and fix typos and formatting"
    })
    void substantiveReviewActivatesEvidenceWorkflowWithoutContractRole(boolean english, String request) {
        SkillRouter router = router(english);
        router.activateForTurn("opinion", "opinion-run", request, null);
        var skill = router.activeSkill("opinion-run").orElseThrow();
        assertEquals("legal-opinion-review", skill.getId());
        String injection = router.promptInjectionFor(skill);
        assertTrue(injection.contains(english ? "source" : "来源"));
        assertTrue(injection.contains(english ? "as-of" : "截止日"));
        assertTrue(injection.contains(english ? "unverified" : "待核实"));
        assertTrue(injection.contains("todo_write"));
        assertTrue(injection.contains("doc_get_document_text"));
        assertFalse(injection.contains("六遍"));
        assertFalse(injection.contains("为指定一方审合同"));
    }

    @ParameterizedTest
    @CsvSource({
        "false,法律意见书是什么",
        "false,帮我整理法律意见书所在文件夹",
        "false,修改法律意见书中的错别字",
        "false,修订法律意见书的字体和字号",
        "false,法律意见书只改这一处：将甲替换为乙",
        "false,全文修订法律意见书中的错别字",
        "false,只修订法律意见书的格式",
        "false,修订这份法律意见书，仅限错别字和标点，其他不要动",
        "false,不要修改法律意见书，只检查错别字",
        "false,不要审查法律意见书，只改错别字",
        "false,修订一下这个法律意见书，仅限把“该项木”改成“该项目”，其余内容和格式不要动。",
        "false,修订法律意见书，仅限将“甲”改为“乙”，其余不动",
        "false,修訂法律意見書，僅限將「甲」改為「乙」",
        "false,修订法律意见书，仅限把“审查”改成“核对”",
        "false,修订法律意见书，仅限将\"甲\"改为\"乙\"",
        "true,Revise this legal opinion; only fix typos and punctuation",
        "true,Fix typos in this legal opinion",
        "true,Revise the formatting of the legal opinion",
        "true,Replace A with B in this legal opinion"
    })
    void narrowEditsAndNonReviewRequestsDoNotActivateSubstantiveWorkflow(boolean english, String request) {
        assertTrue(router(english).match(request).isEmpty());
    }
}
