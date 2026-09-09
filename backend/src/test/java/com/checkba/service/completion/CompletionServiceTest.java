// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.completion;

import com.checkba.model.entity.*;
import com.checkba.repository.*;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.insight.DocInsightService;
import com.checkba.service.insight.DocInsightViews.EntityView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.checkba.service.completion.CompletionService.*;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CompletionServiceTest {
    private final CompletionEntryRepository entries = mock(CompletionEntryRepository.class);
    private final DocInsightEntityRepository insights = mock(DocInsightEntityRepository.class);
    private final ProjectVariableRepository projectVariables = mock(ProjectVariableRepository.class);
    private final UserVariableRepository userVariables = mock(UserVariableRepository.class);
    private final ProjectMemberService members = mock(ProjectMemberService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final DocInsightService insightService = mock(DocInsightService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CompletionService service = new CompletionService(entries, insights, projectVariables, userVariables, members, entityManager, insightService, objectMapper);

    @BeforeEach
    void permissions() {
        service.self = service;
        when(members.hasReadPermission(1L, 9L)).thenReturn(true);
        when(members.hasWritePermission(1L, 9L)).thenReturn(true);
        when(entityManager.find(Project.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(new Project());
        when(entityManager.find(User.class, 9L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(new User());
    }

    private static CompletionEntry entry(long id, String scope, String text) {
        CompletionEntry row = new CompletionEntry();
        row.setId(id); row.setScopeKey(scope); row.setText(text); row.setKind("PHRASE");
        row.setUses(3); row.setLastUsedAt(LocalDateTime.now());
        return row;
    }

    private static CompletionEntryRepository.Summary summary(CompletionEntry row) {
        return new CompletionEntryRepository.Summary() {
            public Long getId() { return row.getId(); }
            public String getText() { return row.getText(); }
            public String getKind() { return row.getKind(); }
            public long getUses() { return row.getUses(); }
            public boolean getHasDetail() { return row.getDetailJson() != null; }
        };
    }

    @Test
    void unauthorizedRequestsNeverReadOrWriteVocabulary() {
        assertThrows(IllegalArgumentException.class, () -> service.candidates(9L, 2L, null));
        assertThrows(IllegalArgumentException.class, () -> service.learn(9L, 2L, new LearnRequest(List.of(new LearnEntry("常用语", "WORD")), "user")));
        assertThrows(IllegalArgumentException.class, () -> service.delete(9L, 2L, "1"));
        assertThrows(IllegalArgumentException.class, () -> service.clear(9L, 2L, "user"));
        assertThrows(IllegalArgumentException.class, () -> service.candidates(null, 1L, null));
        verifyNoInteractions(entries, insights, projectVariables, userVariables, entityManager);
    }

    @Test
    void candidatesOnlyReadCurrentProjectAndCurrentUserAndKeepEntityDetailReference() {
        DocInsightEntity company = new DocInsightEntity();
        company.setId(31L); company.setKind("COMPANY"); company.setName("北京当红晴天律师事务所");
        company.setRetrievalJson("{\"Name\":\"北京当红晴天律师事务所\"}");
        DocInsightEntity duplicate = new DocInsightEntity();
        duplicate.setId(30L); duplicate.setKind("COMPANY"); duplicate.setName(company.getName());
        DocInsightEntity law = new DocInsightEntity();
        law.setId(29L); law.setKind("LAW"); law.setName("《公司法》第二十条");
        when(insights.findTop200ByProjectIdOrderByIdDesc(1L)).thenReturn(List.of(company, duplicate, law));
        CompletionEntry learned = entry(7, "p:1", company.getName()); learned.setKind("COMPANY");
        when(entries.findSummaries("p:1", PageRequest.of(0, 600))).thenReturn(List.of(summary(learned)));
        when(entries.findSummaries("u:9", PageRequest.of(0, 600))).thenReturn(List.of(summary(entry(8, "u:9", "诚实信用原则"))));
        var result = service.candidates(9L, 1L, null);
        assertEquals(3, result.items().size());
        Item first = result.items().get(0);
        assertEquals("learned:7", first.id()); assertEquals(31L, first.entityId()); assertEquals(3, first.uses());
        assertNull(first.detail(), "不在候选中传输工商全文");
        assertEquals("ARTICLE", result.items().get(result.items().size() - 1).kind());
        verify(insights).findTop200ByProjectIdOrderByIdDesc(1L);
        verify(projectVariables).findTop500ByProjectIdOrderByUpdatedAtDescIdDesc(1L);
        verify(userVariables).findTop500ByUserIdOrderByUpdatedAtDescIdDesc(9L);
        verify(entries).findSummaries("p:1", PageRequest.of(0, 600));
        verify(entries).findSummaries("u:9", PageRequest.of(0, 600));
        verifyNoMoreInteractions(entries, insights, projectVariables, userVariables);
        verifyNoInteractions(entityManager, insightService);
    }

    @Test
    void onlyPlainTextVariablesBecomeCandidatesAndPrefixIsLiteral() {
        ProjectVariable person = new ProjectVariable();
        person.setId(2L); person.setType("TEXT"); person.setName("法定代表人姓名"); person.setValue("张三丰");
        ProjectVariable template = new ProjectVariable();
        template.setId(3L); template.setType("TEMPLATE"); template.setValue("不应插入模板代码");
        ProjectVariable longText = new ProjectVariable();
        longText.setType("TEXT"); longText.setValue("字".repeat(161));
        when(projectVariables.findTop500ByProjectIdOrderByUpdatedAtDescIdDesc(1L)).thenReturn(List.of(person, template, longText));
        var result = service.candidates(9L, 1L, "张");
        assertEquals(1, result.items().size()); assertEquals("PERSON", result.items().get(0).kind());
        assertEquals("project-variable:2", result.items().get(0).id());
        assertEquals(0, service.candidates(9L, 1L, ".*").items().size());
        assertThrows(IllegalArgumentException.class, () -> service.candidates(9L, 1L, "x".repeat(161)));
    }

    @Test
    void totalCandidatesNeverExceedLimit() {
        List<CompletionEntry> rows = new ArrayList<>();
        for (int i = 0; i < 2000; i++) rows.add(entry(i + 1, "p:1", "项目表述" + i));
        when(entries.findSummaries("p:1", PageRequest.of(0, 600))).thenReturn(rows.stream().limit(600).map(CompletionServiceTest::summary).toList());
        when(entries.findSummaries("u:9", PageRequest.of(0, 600))).thenReturn(List.of(summary(entry(2001, "u:9", "个人表述"))));
        var candidates = service.candidates(9L, 1L, null).items();
        assertTrue(candidates.size() <= 2000);
        assertTrue(candidates.stream().anyMatch(i -> "user".equals(i.scope())), "项目学习满额仍保留个人候选");
    }

    @Test
    void learningUpsertsIncrementsOncePerBatchAndLocksActualOwner() {
        CompletionEntry row = entry(4, "u:9", "依法依约履行义务");
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        when(entries.findByScopeKeyAndText("u:9", row.getText())).thenReturn(Optional.of(row));
        var result = service.learn(9L, 1L, new LearnRequest(List.of(new LearnEntry("  依法依约履行义务  ", "PHRASE"), new LearnEntry(row.getText(), "PHRASE")), "user"));
        assertEquals(1, result.learned()); assertEquals(4, row.getUses()); assertTrue(row.getLastUsedAt().isAfter(before));
        verify(entityManager).find(User.class, 9L, LockModeType.PESSIMISTIC_WRITE);
        verify(entries, times(1)).save(row);
        verify(entries, never()).findByScopeKeyAndText(eq("p:1"), any());
    }

    @Test
    void allSevenKindsAndLengthBoundariesAreSupported() {
        var batch = List.of(new LearnEntry("公司", "COMPANY"), new LearnEntry("张三", "PERSON"), new LearnEntry("《公司法》", "LAW"),
                new LearnEntry("《公司法》第一条", "ARTICLE"), new LearnEntry("（2026）京01民终23号", "CASE"), new LearnEntry("词语", "WORD"), new LearnEntry("文".repeat(160), "PHRASE"));
        assertEquals(7, service.learn(9L, 1L, new LearnRequest(batch, "project")).learned());
        ArgumentCaptor<CompletionEntry> saved = ArgumentCaptor.forClass(CompletionEntry.class);
        verify(entries, times(7)).save(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(e -> "p:1".equals(e.getScopeKey()) && e.getUses() == 1));
        verify(entityManager).find(Project.class, 1L, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void invalidBatchHasNoPartialWritesAndCannotChooseAnotherOwner() {
        for (String text : List.of("一", "文".repeat(161), "第一行\n第二行", "两\t字")) {
            assertThrows(IllegalArgumentException.class, () -> service.learn(9L, 1L, new LearnRequest(List.of(new LearnEntry("合法词", "WORD"), new LearnEntry(text, "WORD")), "project")));
        }
        assertThrows(IllegalArgumentException.class, () -> service.learn(9L, 1L, new LearnRequest(List.of(new LearnEntry("合法词", "UNKNOWN")), "project")));
        assertThrows(IllegalArgumentException.class, () -> service.learn(9L, 1L, new LearnRequest(List.of(new LearnEntry("合法词", "WORD")), "u:10")));
        assertThrows(IllegalArgumentException.class, () -> service.learn(9L, 1L, new LearnRequest(List.of(), "user")));
        assertThrows(IllegalArgumentException.class, () -> service.learn(9L, 1L, new LearnRequest(java.util.Collections.nCopies(51, new LearnEntry("合法词", "WORD")), "user")));
        verifyNoInteractions(entries, entityManager);
    }

    @Test
    void learningPrunesOnlyOldestEntriesInSameScope() {
        when(entries.findIds("p:1", PageRequest.of(1, 2000))).thenReturn(List.of(2001L, 2002L));
        service.learn(9L, 1L, new LearnRequest(List.of(new LearnEntry("新增词语", "WORD")), "project"));
        verify(entries).deleteAllByIdInBatch(List.of(2001L, 2002L));
        verify(entries, never()).deleteByScopeKey(any());
    }

    @Test
    void deleteRejectsOtherUsersAndOtherProjectsEvenIfCurrentProjectIsWritable() {
        when(entries.findById(1L)).thenReturn(Optional.of(entry(1, "u:10", "他人词语")));
        when(entries.findById(2L)).thenReturn(Optional.of(entry(2, "p:2", "其他项目词语")));
        assertThrows(IllegalArgumentException.class, () -> service.delete(9L, 1L, "learned:1"));
        assertThrows(IllegalArgumentException.class, () -> service.delete(9L, 1L, "2"));
        assertThrows(IllegalArgumentException.class, () -> service.delete(9L, 1L, "insight:31"));
        verify(entries, never()).delete(any()); verifyNoInteractions(entityManager);
    }

    @Test
    void readOnlyMemberMayDeleteOwnWordsButCannotLearnOrDeleteProjectWords() {
        when(members.hasWritePermission(1L, 9L)).thenReturn(false);
        CompletionEntry own = entry(1, "u:9", "个人词语");
        when(entries.findById(1L)).thenReturn(Optional.of(own));
        when(entries.findById(2L)).thenReturn(Optional.of(entry(2, "p:1", "项目词语")));
        assertEquals(1, service.delete(9L, 1L, "learned:1").deleted());
        assertThrows(IllegalArgumentException.class, () -> service.delete(9L, 1L, "2"));
        assertThrows(IllegalArgumentException.class, () -> service.clear(9L, 1L, "project"));
        assertThrows(IllegalArgumentException.class, () -> service.learn(9L, 1L, new LearnRequest(List.of(new LearnEntry("词语", "WORD")), "user")));
        verify(entries).delete(own); verify(entries, never()).deleteByScopeKey("p:1");
    }

    @Test
    void clearingTargetsExactlyTheChosenAuthenticatedScope() {
        when(entries.deleteByScopeKey("u:9")).thenReturn(7L);
        when(entries.deleteByScopeKey("p:1")).thenReturn(4L);
        assertEquals(7, service.clear(9L, 1L, "user").deleted());
        assertEquals(4, service.clear(9L, 1L, "project").deleted());
        assertThrows(IllegalArgumentException.class, () -> service.clear(9L, 1L, "all"));
        verify(entries).deleteByScopeKey("u:9"); verify(entries).deleteByScopeKey("p:1");
        verifyNoMoreInteractions(entries);
    }
    private EntityView lookupView(String status) {
        return new EntityView(null, "COMPANY", "北京示例有限公司", "北京示例", status, "qichacha", null, null,
                "OK".equals(status), LocalDateTime.now(), List.of(), objectMapper.createObjectNode().put("Name", "北京示例有限公司"));
    }

    @Test
    void successfulExplicitLookupCachesProjectDetailsAndLocalReadsDoNotQueryAgain() throws Exception {
        EntityView view = lookupView("OK");
        when(insightService.lookupSelection(9L, 1L, "COMPANY", "北京示例有限公司")).thenReturn(view);
        assertSame(view, service.lookupSelection(9L, 1L, "COMPANY", "北京示例有限公司"));
        ArgumentCaptor<CompletionEntry> saved = ArgumentCaptor.forClass(CompletionEntry.class);
        verify(entries).saveAndFlush(saved.capture());
        CompletionEntry row = saved.getValue(); row.setId(77L);
        assertEquals("p:1", row.getScopeKey());
        assertEquals("北京示例有限公司", row.getText());
        when(entries.findById(77L)).thenReturn(Optional.of(row));
        when(entries.findSummaries("p:1", PageRequest.of(0, 600))).thenReturn(List.of(summary(row)));
        var candidates = service.candidates(9L, 1L, null);
        assertTrue(candidates.items().get(0).hasDetail());
        assertNull(candidates.items().get(0).detail());
        EntityView cached = service.detail(9L, 1L, "learned:77");
        assertEquals(view.name(), cached.name());
        assertEquals(view.detail(), cached.detail());
        assertEquals(view.fetchedAt(), cached.fetchedAt());
        verify(insightService, times(1)).lookupSelection(any(), any(), any(), any());
        verifyNoMoreInteractions(insightService);
    }

    @Test
    void failedLookupCannotOverwritePreviouslySuccessfulCache() {
        CompletionEntry row = entry(77L, "p:1", "北京示例有限公司"); row.setDetailJson("old-success");
        when(insightService.lookupSelection(9L, 1L, "COMPANY", row.getText())).thenReturn(lookupView("UNAVAILABLE"));
        assertEquals("UNAVAILABLE", service.lookupSelection(9L, 1L, "COMPANY", row.getText()).retrievalStatus());
        assertEquals("old-success", row.getDetailJson());
        verifyNoInteractions(entries, entityManager);
    }

    @Test
    void cachedDetailsRejectCrossProjectCrossUserAndUnauthenticatedAccess() throws Exception {
        CompletionEntry otherUser = entry(1L, "u:10", "他人词语");
        CompletionEntry otherProject = entry(2L, "p:2", "其他项目词语");
        CompletionEntry own = entry(3L, "u:9", "个人词语"); own.setDetailJson(objectMapper.writeValueAsString(lookupView("OK")));
        when(entries.findById(1L)).thenReturn(Optional.of(otherUser));
        when(entries.findById(2L)).thenReturn(Optional.of(otherProject));
        when(entries.findById(3L)).thenReturn(Optional.of(own));
        assertThrows(IllegalArgumentException.class, () -> service.detail(9L, 1L, "learned:1"));
        assertThrows(IllegalArgumentException.class, () -> service.detail(9L, 1L, "learned:2"));
        assertThrows(IllegalArgumentException.class, () -> service.detail(9L, 2L, "learned:2"));
        assertThrows(IllegalArgumentException.class, () -> service.detail(null, 1L, "learned:3"));
        assertEquals("OK", service.detail(9L, 1L, "learned:3").retrievalStatus());
        verifyNoInteractions(insightService);
    }

    @Test
    void lookupChecksPermissionBeforeCallingOnlineService() {
        assertThrows(IllegalArgumentException.class, () -> service.lookupSelection(9L, 2L, "COMPANY", "示例有限公司"));
        verifyNoInteractions(insightService, entries);
    }

    @Test
    void savedLookupDetailsTakePrecedenceOverAnOlderInsightWithTheSameName() throws Exception {
        EntityView fresh = lookupView("OK");
        CompletionEntry cached = entry(77L, "p:1", fresh.name());
        cached.setKind("COMPANY");
        cached.setDetailJson(objectMapper.writeValueAsString(fresh));
        DocInsightEntity old = new DocInsightEntity();
        old.setId(31L); old.setKind("COMPANY"); old.setName(fresh.name());
        old.setRetrievalStatus("UNAVAILABLE"); old.setRetrievalNote("旧查询失败");
        when(insights.findTop200ByProjectIdOrderByIdDesc(1L)).thenReturn(List.of(old));
        when(entries.findSummaries("p:1", PageRequest.of(0, 600))).thenReturn(List.of(summary(cached)));
        when(entries.findById(77L)).thenReturn(Optional.of(cached));

        List<Item> candidates = service.candidates(9L, 1L, null).items();
        assertEquals(1, candidates.size());
        Item candidate = candidates.get(0);
        assertTrue(candidate.hasDetail());
        assertEquals("learned:77", candidate.id());
        assertNull(candidate.entityId(), "the client must fetch the saved lookup, not the old insight");
        assertEquals(fresh, service.detail(9L, 1L, candidate.id()));
        verifyNoInteractions(insightService);
    }

    @Test
    void sameTextInBothScopesRemainsVisibleAndIndependentlyDeletable() {
        CompletionEntry project = entry(41L, "p:1", "依法依约履行义务");
        CompletionEntry personal = entry(42L, "u:9", project.getText());
        when(entries.findSummaries("p:1", PageRequest.of(0, 600))).thenReturn(List.of(summary(project)));
        when(entries.findSummaries("u:9", PageRequest.of(0, 600))).thenReturn(List.of(summary(personal)));
        when(entries.findById(42L)).thenReturn(Optional.of(personal));

        List<Item> candidates = service.candidates(9L, 1L, null).items();
        assertEquals(2, candidates.size(), "management needs both saved records even though suggestion UI deduplicates text");
        assertEquals(List.of("project", "user"), candidates.stream().map(Item::scope).toList());
        assertEquals(List.of("learned:41", "learned:42"), candidates.stream().map(Item::id).toList());
        assertEquals(1, service.delete(9L, 1L, candidates.get(1).id()).deleted());
        verify(entries).delete(personal);
        verify(entries, never()).delete(project);
    }

}
