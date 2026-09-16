// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.writing;

import com.checkba.service.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.*;
import okhttp3.Call;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.checkba.service.writing.WritingTypes.*;
import static com.checkba.service.writing.WritingContextService.*;
import static com.checkba.service.writing.WritingSuggestionService.*;

class WritingSuggestionServiceTest {
    WritingContextService contexts=mock(WritingContextService.class);
    ChatModelFactory models=mock(ChatModelFactory.class);
    TokenUsageService usage=mock(TokenUsageService.class);
    OpenRouterStreamingChatModel model=mock(OpenRouterStreamingChatModel.class);
    Call call=mock(Call.class);
    ObjectMapper json=new ObjectMapper();
    WritingSuggestionService service;
    AtomicReference<StreamingResponseHandler<AiMessage>> handler=new AtomicReference<>();
    Snapshot snapshot;
    @BeforeEach void setup() {
        service=new WritingSuggestionService(contexts,models,usage,json);
        snapshot=snapshot(new LitigationWritingProfile(),"原告：沈望舒\n被告：顾青原","被告：顾","");
        when(contexts.capture(eq(1L),eq(10L),any())).thenAnswer(i -> snapshot);
        when(models.getWritingModel(any())).thenReturn(model); when(model.modelName()).thenReturn("synthetic-model");
        when(model.generateCancellable(anyList(),anyInt(),any())).thenAnswer(i -> {handler.set(i.getArgument(2)); return call;});
    }
    @AfterEach void close() { service.close(); }
    Snapshot snapshot(WritingProfile profile,String body,String before,String selection) {
        String section=profile.id().equals("contract")?"正文":"事实与理由";
        Context c=new Context(profile.id(),profile.label(),section,before,"","原告","");
        Source source=new Source("active-document",100L,"合成文书","v1","当前文档",body);
        Request r=new Request(100L,"r1",body,before,"",selection,profile.label(),section,profile.id(),List.of());
        return new Snapshot(10L,1L,r,new Settings("原告","",List.of()),c,profile,List.of(source),profile.facts(c,List.of(source)),List.of());
    }
    View start(String mode) { return service.start(1L,10L,new Input(mode,snapshot.request())); }
    Fact defendant() { return snapshot.facts().stream().filter(f -> f.role().equals("被告")).findFirst().orElseThrow(); }
    String result(String text,String kind,Fact f) throws Exception {
        return json.writeValueAsString(Map.of("suggestions",List.of(new Advice(text,kind,List.of(f.id()),List.of(f.sourceId()),"材料所载"))));
    }
    void complete(String raw) { handler.get().onComplete(Response.from(AiMessage.from(raw),new TokenUsage(20,10))); }
    @Test void localCompletionNeverRequestsModelOrRecordsTokens() {
        View v=start("local"); assertEquals("READY",v.status()); assertEquals("青原",v.advice().get(0).text());
        verifyNoInteractions(models,usage);
    }
    @Test void contractAndDiligenceActualAdviceKindsRemainUsable() {
        Snapshot contract=snapshot(new ContractWritingProfile(),"甲方：北京澄屿设备有限公司\n乙方：杭州禾序商贸有限公司","甲方：北京","");
        var a=contract.profile().deterministic(contract.context(),contract.facts()); assertFalse(a.isEmpty());
        assertTrue(valid(contract,a.get(0),2000));
        Snapshot diligence=snapshot(new DiligenceWritingProfile(),"标的公司：北京澄屿设备有限公司","标的公司：北京","");
        var b=diligence.profile().deterministic(diligence.context(),diligence.facts()); assertFalse(b.isEmpty());
        assertTrue(valid(diligence,b.get(0),2000));
    }
    @Test void numericSuffixMustBeCheckedTogetherWithTypedPrefix() {
        Snapshot s=snapshot(new LitigationWritingProfile(),"借款本金：人民币360000元","借款本金：人民币36","");
        Advice a=s.profile().deterministic(s.context(),s.facts()).get(0);
        assertEquals("0000元",a.text()); assertTrue(valid(s,a,2000));
    }
    @Test void moneyUnitCannotInflateSupportedNumber() {
        Snapshot s=snapshot(new LitigationWritingProfile(),"借款本金：人民币360000元","",""); Fact f=s.facts().get(0);
        Advice a=new Advice("借款本金为人民币360000万元。","sentence",List.of(f.id()),List.of(f.sourceId()),"");
        assertFalse(valid(s,a,2000));
    }
    @Test void moneyUnitOnlySuffixCannotInflateTypedNumber() {
        Snapshot s=snapshot(new LitigationWritingProfile(),"借款本金：人民币360000元","借款本金：人民币360000",""); Fact f=s.facts().get(0);
        assertFalse(valid(s,new Advice("万元","fact",List.of(f.id()),List.of(f.sourceId()),""),2000));
        assertTrue(valid(s,new Advice("元","fact",List.of(f.id()),List.of(f.sourceId()),""),2000));
    }
    @Test void numberCannotSilentlyChangeCurrencySignOrDateUnit() {
        Snapshot s=snapshot(new LitigationWritingProfile(),"借款本金：人民币360000元\n借款日期：2025年4月8日","","");
        Fact money=s.facts().get(0), date=s.facts().get(1);
        for(String text:List.of("360000美元","-360000元","三十六万元")) assertFalse(valid(s,new Advice(text,"fact",List.of(money.id()),List.of(money.sourceId()),""),2000));
        assertFalse(valid(s,new Advice("2025年8月4日","fact",List.of(date.id()),List.of(date.sourceId()),""),2000));
    }
    @Test void existingButUnrelatedSourceCannotBeUsedAsDecorativeCitation() {
        Fact f=defendant(); var sources=new ArrayList<>(snapshot.sources()); sources.add(new Source("unrelated",101L,"其他材料","v1","第1页","无关材料"));
        Snapshot s=new Snapshot(snapshot.projectId(),snapshot.userId(),snapshot.request(),snapshot.settings(),snapshot.context(),snapshot.profile(),sources,snapshot.facts(),List.of());
        assertFalse(valid(s,new Advice("青原","entity",List.of(f.id()),List.of(f.sourceId(),"unrelated"),""),2000));
    }
    @Test void unknownFactsUnsupportedNumbersAndMissingSourcesAreRejected() throws Exception {
        Fact f=defendant();
        assertTrue(service.parse(snapshot,result("顾青原支付999元","sentence",f),"sentence").isEmpty());
        assertTrue(service.parse(snapshot,"{\"suggestions\":[{\"text\":\"顾青原\",\"kind\":\"sentence\",\"factIds\":[\"invented\"],\"sourceIds\":[\"active-document\"]}]}","sentence").isEmpty());
        assertFalse(valid(snapshot,new Advice("顾青原","entity",List.of(f.id()),List.of(),""),2000));
    }
    @Test void malformedAndTooManySuggestionsAreNotDisplayed() throws Exception {
        assertTrue(service.parse(snapshot,"{\"suggestions\":{}}","sentence").isEmpty());
        assertTrue(service.parse(snapshot,"{\"suggestions\":[{},{},{}]}","sentence").isEmpty());
        assertThrows(Exception.class, () -> service.parse(snapshot,"not-json","sentence"));
    }
    @Test void cancelledJobCannotResurrectWhenLateCompletionArrives() throws Exception {
        View v=start("sentence"); service.cancel(1L,10L,v.id());
        complete(result("青原","sentence",defendant()));
        View after=service.get(1L,10L,v.id()); assertEquals("CANCELLED",after.status()); assertTrue(after.advice().isEmpty());
        verify(call).cancel(); verify(usage).recordUsage(eq(10L),eq(1L),eq("synthetic-model"),any(),isNull());
    }
    @Test void requestAndCallbackUseCorrectUserScopeAndRestoreOuterScope() throws Exception {
        when(models.getWritingModel(any())).thenAnswer(i -> { assertEquals(1L,PlatformAiUserScope.current()); return model; });
        doAnswer(i -> {assertEquals(1L,PlatformAiUserScope.current()); return null;}).when(usage).recordUsage(any(),any(),any(),any(),any());
        PlatformAiUserScope.run(7L,() -> { start("sentence"); assertEquals(7L,PlatformAiUserScope.current()); });
        String raw=result("青原","sentence",defendant());
        CompletableFuture.runAsync(() -> { assertNull(PlatformAiUserScope.current()); complete(raw); assertNull(PlatformAiUserScope.current()); }).get(3,TimeUnit.SECONDS);
    }
    @Test void crossUserAndCrossProjectGetCancelAcceptAreForbidden() {
        View v=start("local");
        assertThrows(IllegalArgumentException.class,()->service.get(2L,10L,v.id()));
        assertThrows(IllegalArgumentException.class,()->service.cancel(1L,20L,v.id()));
        assertThrows(IllegalArgumentException.class,()->service.accept(2L,10L,v.id(),new Accept(0,"r1")));
    }
    @Test void revokedAccessOrStaleContextCannotAuthorizeInsertion() {
        View v=start("local"); doThrow(new IllegalArgumentException("stale")).when(contexts).revalidate(snapshot,"r2");
        assertThrows(IllegalArgumentException.class,()->service.accept(1L,10L,v.id(),new Accept(0,"r2")));
        assertEquals("READY",service.get(1L,10L,v.id()).status());
        doThrow(new IllegalArgumentException("revoked")).when(contexts).require(1L,10L,false);
        assertThrows(IllegalArgumentException.class,()->service.get(1L,10L,v.id()));
    }
    @Test void acceptanceIsSingleUseAndReturnsOriginalSnapshotRevision() {
        View v=start("local"); Accepted accepted=service.accept(1L,10L,v.id(),new Accept(0,"r1"));
        assertEquals("青原",accepted.text()); assertEquals("r1",accepted.revision()); assertEquals("",accepted.selection());
        assertThrows(IllegalArgumentException.class,()->service.accept(1L,10L,v.id(),new Accept(0,"r1")));
    }
    @Test void selectedTextRequiresRewriteModeBeforeModelAdmission() {
        snapshot=snapshot(new LitigationWritingProfile(),"被告：顾青原","被告：","顾青原");
        assertThrows(IllegalArgumentException.class,()->start("sentence")); verifyNoInteractions(models);
        snapshot=snapshot(new LitigationWritingProfile(),"被告：顾青原","被告：","");
        assertThrows(IllegalArgumentException.class,()->start("rewrite")); verifyNoInteractions(models);
    }
    @Test void rewriteAcceptanceReturnsExactOriginalSelection() throws Exception {
        snapshot=snapshot(new LitigationWritingProfile(),"被告：顾青原","被告：","顾青原");
        View v=start("rewrite"); complete(result("顾青原","rewrite",defendant()));
        assertEquals("顾青原",service.accept(1L,10L,v.id(),new Accept(0,"r1")).selection());
    }
    @Test void perMinuteQuotaStopsThirteenthModelRequest() {
        for(int i=0;i<12;i++) start("sentence");
        assertThrows(IllegalArgumentException.class,()->start("sentence")); verify(models,times(12)).getWritingModel(any());
        assertEquals("READY",start("local").status());
    }
    @Test void cancellationBeforeCallHandleAssignmentStillCancelsTransportWithoutDeadlock() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(model.generateCancellable(anyList(),anyInt(),any())).thenAnswer(i -> { entered.countDown(); assertTrue(release.await(3,TimeUnit.SECONDS)); return call; });
        var pending=CompletableFuture.supplyAsync(()->start("sentence")); assertTrue(entered.await(2,TimeUnit.SECONDS));
        View local=start("local"); assertEquals("READY",local.status()); release.countDown();
        assertEquals("CANCELLED",pending.get(3,TimeUnit.SECONDS).status()); verify(call).cancel();
    }
    @Test void oversizedStreamFailsAndCancelsEvenBeforeCallHandleAssigned() {
        when(model.generateCancellable(anyList(),anyInt(),any())).thenAnswer(i -> { StreamingResponseHandler<AiMessage> h=i.getArgument(2); h.onNext("x".repeat(16001)); return call; });
        assertEquals("FAILED",start("sentence").status()); verify(call).cancel();
    }
    @Test void usageStorageFailureSettlesJobInsteadOfLeavingItRunning() throws Exception {
        View v=start("sentence");
        doThrow(new IllegalStateException("synthetic database failure")).when(usage).recordUsage(any(),any(),any(),any(),any());
        String raw=result("青原","sentence",defendant()); assertDoesNotThrow(()->complete(raw));
        View done=service.get(1L,10L,v.id()); assertEquals("FAILED",done.status()); assertEquals("reporting_failed",done.usageStatus());
        assertFalse(done.error().contains("database"));
    }
    @Test void oneUserRetainsEightRecentJobsAndCanKeepUsingLocalCompletion() {
        List<View> views=new ArrayList<>(); for(int i=0;i<140;i++) views.add(start("local"));
        for(int i=0;i<132;i++) { String id=views.get(i).id(); assertThrows(IllegalArgumentException.class,()->service.get(1L,10L,id)); }
        for(int i=132;i<139;i++) assertEquals("CANCELLED",service.get(1L,10L,views.get(i).id()).status());
        assertEquals("READY",service.get(1L,10L,views.get(139).id()).status());
        assertThrows(IllegalArgumentException.class,()->service.accept(1L,10L,views.get(138).id(),new Accept(0,"r1")));
    }
    Snapshot forUser(long uid) {
        return new Snapshot(snapshot.projectId(),uid,snapshot.request(),snapshot.settings(),snapshot.context(),snapshot.profile(),snapshot.sources(),snapshot.facts(),snapshot.warnings());
    }
    @Test void fullHistoryEvictsOldestFinishedJobInsteadOfBlockingAllUsers() {
        when(contexts.capture(anyLong(),eq(10L),any())).thenAnswer(i -> forUser(i.getArgument(0)));
        List<View> views=new ArrayList<>();
        for(long uid=1;uid<=128;uid++) views.add(service.start(uid,10L,new Input("local",snapshot.request())));
        View next=service.start(129L,10L,new Input("local",snapshot.request())); assertEquals("READY",next.status());
        assertThrows(IllegalArgumentException.class,()->service.get(1L,10L,views.get(0).id()));
        assertEquals("READY",service.get(2L,10L,views.get(1).id()).status());
    }
    @Test void sixteenActuallyRunningUsersBlockNewUserButAllowOwnReplacement() {
        when(contexts.capture(anyLong(),eq(10L),any())).thenAnswer(i -> forUser(i.getArgument(0)));
        for(long uid=1;uid<=16;uid++) service.start(uid,10L,new Input("sentence",snapshot.request()));
        assertThrows(IllegalArgumentException.class,()->service.start(17L,10L,new Input("sentence",snapshot.request())));
        assertEquals("RUNNING",service.start(1L,10L,new Input("sentence",snapshot.request())).status());
        verify(models,times(17)).getWritingModel(any());
    }
    @Test void cancellationDuringFactorySetupDoesNotStartPaidHttpCall() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(models.getWritingModel(any())).thenAnswer(i -> { entered.countDown(); assertTrue(release.await(3,TimeUnit.SECONDS)); return model; });
        var pending=CompletableFuture.supplyAsync(()->start("sentence")); assertTrue(entered.await(2,TimeUnit.SECONDS));
        assertEquals("READY",start("local").status()); release.countDown();
        assertEquals("CANCELLED",pending.get(3,TimeUnit.SECONDS).status());
        verify(model,never()).generateCancellable(anyList(),anyInt(),any());
    }
    @Test void nullProviderCompletionCannotLeaveRequestRunning() {
        View v=start("sentence"); assertDoesNotThrow(()->handler.get().onComplete(null));
        assertEquals("FAILED",service.get(1L,10L,v.id()).status());
        assertEquals("pending_or_unreported",service.get(1L,10L,v.id()).usageStatus());
        verifyNoInteractions(usage);
    }
    @Test void emptyResponseStillRecordsKnownUsageUnderCorrectUser() {
        View v=start("sentence");
        doAnswer(i -> {assertEquals(1L,PlatformAiUserScope.current()); return null;}).when(usage).recordUsage(any(),any(),any(),any(),any());
        handler.get().onError(new OpenRouterStreamingChatModel.EmptyResponseException(new TokenUsage(10,256)));
        View failed=service.get(1L,10L,v.id()); assertEquals("FAILED",failed.status());
        assertEquals("reported",failed.usageStatus()); assertEquals(256,failed.outputTokens());
    }
    @Test void explicitEmptyPartyFieldUsesKnownBindingWithoutPaidGeneration() {
        snapshot=snapshot(new LitigationWritingProfile(),"原告：沈望舒\n被告：顾青原","原告：","");
        View v=start("sentence"); assertEquals("READY",v.status()); assertEquals("沈望舒",v.advice().get(0).text());
        assertEquals("not_requested",v.usageStatus()); verifyNoInteractions(models,usage);
    }
    @Test void singleJsonFenceIsParsedButSentenceCannotAddAnotherPartyLine() throws Exception {
        Fact f=defendant();
        assertEquals(1,service.parse(snapshot,"```json\n"+result("青原","sentence",f)+"\n```","sentence").size());
        assertTrue(service.parse(snapshot,result("青原\n原告：沈望舒","sentence",f),"sentence").isEmpty());
    }
    @Test void paragraphCannotBeJustANameFragment() throws Exception {
        assertTrue(service.parse(snapshot,result("顾青原","paragraph",defendant()),"paragraph").isEmpty());
    }

    @Test void parserCannotSilentlyIgnoreSecondJsonObjectOrFence() throws Exception {
        String valid=result("青原","sentence",defendant());
        assertThrows(Exception.class, () -> service.parse(snapshot,valid+" {}","sentence"));
        assertThrows(Exception.class, () -> service.parse(snapshot,"```json\n"+valid+"\n```\n```json\n{}\n```","sentence"));
    }
    @Test void companyNameWithFullStopIsStillNotAParagraph() throws Exception {
        snapshot=snapshot(new LitigationWritingProfile(),"被告：北京澄屿设备股份有限公司","","");
        assertTrue(service.parse(snapshot,result("北京澄屿设备股份有限公司。","paragraph",defendant()),"paragraph").isEmpty());
    }

    @Test void shortPromptReferencesResolveToRealFactsAndUnknownAliasesStayRejected() throws Exception {
        AtomicReference<String> prompt=new AtomicReference<>();
        when(model.generateCancellable(anyList(),anyInt(),any())).thenAnswer(invocation -> {
            List<ChatMessage> messages=invocation.getArgument(0); prompt.set(((UserMessage)messages.get(1)).singleText()); return call;
        });
        start("sentence");
        var facts=json.readTree(prompt.get()).path("facts");
        String alias="";
        for(var fact:facts) if(fact.path("role").asText().equals("被告")) alias=fact.path("id").asText();
        assertTrue(alias.matches("F[0-9]+")); assertFalse(prompt.get().contains(defendant().id()));
        String raw=json.writeValueAsString(Map.of("suggestions",List.of(new Advice("青原","sentence",List.of(alias),List.of("active-document"),"材料所载"))));
        assertEquals(List.of(defendant().id()),service.parse(snapshot,raw,"sentence").get(0).factIds());
        assertTrue(service.parse(snapshot,raw.replace("\""+alias+"\"","\"F9999\""),"sentence").isEmpty());
    }

    @Test void excludedRawSourceParagraphCannotReenterModelPrompt() {
        Snapshot original=snapshot;
        Source source=new Source("active-document",100L,"合成文书","v1","正文",original.sources().get(0).text()+"\n不应进入模型的旧附件定义：测试代号旧蓝图。");
        snapshot=new Snapshot(original.projectId(),original.userId(),original.request(),original.settings(),original.context(),original.profile(),List.of(source),original.facts(),List.of());
        AtomicReference<String> prompt=new AtomicReference<>();
        when(model.generateCancellable(anyList(),anyInt(),any())).thenAnswer(invocation -> {
            List<ChatMessage> messages=invocation.getArgument(0); prompt.set(((UserMessage)messages.get(1)).singleText()); return call;
        });
        start("sentence");
        assertTrue(prompt.get().contains("原告：沈望舒")); assertFalse(prompt.get().contains("旧蓝图"));
    }

}
