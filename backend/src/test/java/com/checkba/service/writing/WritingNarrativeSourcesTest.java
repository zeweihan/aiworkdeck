// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.writing;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.checkba.service.writing.WritingTypes.*;
class WritingNarrativeSourcesTest {
    private Source source(String id,String text) {return new Source(id,1L,"合成文书","v1","段落",text);}
    private Context contract(String section) {return new Context("contract","合成服务合同",section,"","","甲方","");}
    @Test void preservesEntireClaimAndNegationWithoutCertifyingIt() {
        String text="被告称并未收到原告所称借款，对借据的真实性仍有异议。";
        Fact f=WritingNarrativeSources.append("litigation",List.of(source("active-document",text)),List.of()).get(0);
        assertEquals(text,f.quote()); assertEquals(text,f.value()); assertEquals("CLAIM",f.status()); assertEquals("被告",f.role());
    }
    @Test void ordinaryTermsRemainAttributedSourceProse() {
        String text="委托方负责提供测试账户，受托方负责制作操作手册。";
        var facts=WritingNarrativeSources.append("contract",contract("正文"),List.of(source("active-document",text)),List.of());
        assertEquals(1,facts.size()); assertEquals("SOURCE_TEXT",facts.get(0).kind()); assertEquals("DOCUMENT_STATES",facts.get(0).status());
    }
    @Test void oldContractCannotInjectDefinitionsIntoCurrentScope() {
        assertTrue(WritingNarrativeSources.append("contract",List.of(source("file:2","委托方负责提供测试账户，受托方负责制作操作手册。")),List.of()).isEmpty());
    }
    @Test void noAlternativeCitationAroundStructuredConflict() {
        String text="同一时点的期末余额记录为人民币300000元。";
        Fact conflict=new Fact("f1","AMOUNT","期末余额","300000元","","CONFLICT","active-document","期末余额记录为人民币300000元");
        assertEquals(List.of(conflict),WritingNarrativeSources.append("diligence",List.of(source("active-document",text)),List.of(conflict)));
    }
    @Test void missingSourceStaysMissingAndNotNegativeConclusion() {
        String text="资料清单显示，环境检测报告尚未提供，项目组将继续补充核查。";
        assertEquals("MISSING",WritingNarrativeSources.append("diligence",List.of(source("active-document",text)),List.of()).get(0).status());
    }
    @Test void longParagraphIsOmittedWholeRatherThanCuttingConditionsOff() {
        assertTrue(WritingNarrativeSources.append("litigation",List.of(source("active-document","条件".repeat(205))),List.of()).isEmpty());
    }
    @Test void totalFactBudgetIsBounded() {
        String body=String.join("\n",java.util.Collections.nCopies(250,"原告主张被告尚未交付案涉设备，具体责任有待进一步核查。"));
        assertEquals(200,WritingNarrativeSources.append("litigation",List.of(source("active-document",body)),List.of()).size());
    }
    @Test void appendixProseCannotReenterBodyThroughSourceText() {
        String body="第一条 服务内容\n双方按照合同约定提供培训服务，并保留相关记录。\n附件一 术语表\n本附件所称工作日包含星期六及星期日，仅适用于本附件。";
        var facts=WritingNarrativeSources.append("contract",contract("第一条 服务内容"),List.of(source("active-document",body)),List.of());
        assertTrue(facts.stream().anyMatch(f->f.value().contains("培训服务")));
        assertTrue(facts.stream().noneMatch(f->f.value().contains("星期六")));
    }
    @Test void onlySelectedAppendixNamespaceCanSupplyLocalNarrative() {
        String body="附件一 培训安排\n培训服务仅针对甲方指定人员开展，其他人员不在本附件范围。\n附件二 运输安排\n运输服务仅针对乙方指定地址提供，不涉及其他地区。";
        var facts=WritingNarrativeSources.append("contract",contract("附件一 培训安排"),List.of(source("active-document",body)),List.of());
        assertTrue(facts.stream().anyMatch(f->f.value().contains("培训服务")));
        assertTrue(facts.stream().noneMatch(f->f.value().contains("运输服务")));
    }
    @Test void localArticleProseCannotLeakToAnotherArticle() {
        String body="第一条 服务内容\n本条所称培训人员仅限甲方正式职员，不包括其他人员。\n第二条 运输范围\n本条约定的运输仅限乙方指定园区，不包括其他园区。";
        var facts=WritingNarrativeSources.append("contract",contract("第二条 运输范围"),List.of(source("active-document",body)),List.of());
        assertTrue(facts.stream().noneMatch(f->f.value().contains("培训人员")));
        assertTrue(facts.stream().anyMatch(f->f.value().contains("运输仅限")));
    }
    @Test void unknownSectionNeverAssumesDocumentStart() {
        String body="委托方负责提供测试账户，受托方负责制作操作手册。\n附件一 术语\n本附件约定培训对象包含外部人员，其他范围另行说明。";
        assertTrue(WritingNarrativeSources.append("contract",contract(""),List.of(source("active-document",body)),List.of()).isEmpty());
        assertTrue(WritingNarrativeSources.append("contract",List.of(source("active-document",body)),List.of()).isEmpty());
    }
    @Test void missingSelectedAppendixDoesNotFallBackToMainDocument() {
        String body="双方按照合同约定提供培训服务，并保留相关记录。\n附件一 培训安排\n培训对象限于甲方正式职员，不包括关联公司职员。";
        assertTrue(WritingNarrativeSources.append("contract",contract("附件二"),List.of(source("active-document",body)),List.of()).isEmpty());
    }
    @Test void sourceProseRetainsPendingAndCounterpartyStatus() {
        String pending="双方暂定由甲方安排运输，具体交付日仍待协商。";
        var a=WritingNarrativeSources.append("contract",contract("正文"),List.of(source("active-document",pending)),List.of());
        assertEquals("PENDING",a.get(0).status());
        Context counterparty=new Context("contract","对方稿服务合同","正文","","","甲方","");
        String text="委托方负责提供测试账户，受托方负责制作操作手册。";
        var b=WritingNarrativeSources.append("contract",counterparty,List.of(source("active-document",text)),List.of());
        assertEquals("COUNTERPARTY_PROPOSAL",b.get(0).status());
    }
    @Test void historicalClauseCannotBecomeCurrentContractFact() {
        String text="历史版本中运输费用由委托方承担，具体安排曾经另有说明。";
        assertTrue(WritingNarrativeSources.append("contract",contract("正文"),List.of(source("active-document",text)),List.of()).isEmpty());
    }
    @Test void bodyLabelCannotResolveAnUnlocatedLocalDefinition() {
        for(String text:List.of("本条所称培训人员仅指外部人员，范围与其他条款不同。","本附件中的运输费用包含其他地区，具体安排另有说明。")) {
            assertTrue(WritingNarrativeSources.append("contract",contract("正文"),List.of(source("active-document",text)),List.of()).isEmpty());
        }
    }
    @Test void shadowedGlobalDefinitionCannotReappearAsNarrativeEvidence() {
        String body="“交付物”是指委托方日常使用的操作手册。\n第一条 培训安排\n“交付物”在本条中指培训活动完整录屏文件。";
        Source source=source("active-document",body); Context context=contract("第一条 培训安排");
        var structured=new ContractWritingProfile().facts(context,List.of(source));
        assertTrue(structured.stream().noneMatch(f->f.value().contains("操作手册")));
        var all=WritingNarrativeSources.append("contract",context,List.of(source),structured);
        assertTrue(all.stream().anyMatch(f->f.value().contains("录屏")));
        assertTrue(all.stream().noneMatch(f->f.quote().contains("操作手册")));
    }
}
