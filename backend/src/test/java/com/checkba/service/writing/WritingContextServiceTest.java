// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.writing;

import com.checkba.model.entity.*;
import com.checkba.repository.*;
import com.checkba.service.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.checkba.service.writing.WritingContextService.*;

class WritingContextServiceTest {
    ProjectFileRepository files = mock(ProjectFileRepository.class);
    ProjectVariableRepository variables = mock(ProjectVariableRepository.class);
    DocumentTextService texts = mock(DocumentTextService.class);
    ProjectMemberService members = mock(ProjectMemberService.class);
    WritingContextService service;
    @BeforeEach void setup() {
        when(members.hasReadPermission(10L, 1L)).thenReturn(true);
        when(members.hasWritePermission(10L, 1L)).thenReturn(true);
        when(files.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(10L)).thenReturn(List.of());
        when(variables.findByProjectIdAndName(anyLong(), anyString())).thenReturn(Optional.empty());
        file(100L, 10L); file(101L, 10L);
        service = new WritingContextService(files, variables, texts, members, List.of(new LitigationWritingProfile()));
    }
    ProjectFile file(Long id, Long pid) {
        ProjectFile f = new ProjectFile(); f.setId(id); f.setProjectId(pid); f.setName("合成材料"); f.setFileSize(100L); f.setIsDeleted(false); f.setIsFolder(false);
        when(files.findById(id)).thenReturn(Optional.of(f)); return f;
    }
    Request request(List<Long> refs) { return new Request(100L,"r1","原告：沈望舒\n被告：顾青原","被告：顾","","","民事起诉状","当事人","litigation",refs); }
    @Test void projectAuthorizationPrecedesAnyFileReads() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.capture(2L,10L,request(List.of(101L))));
        verify(texts,never()).extractText(any()); verify(files,never()).findById(any());
    }
    @Test void crossProjectDocumentRejected() throws Exception {
        file(100L,20L);
        assertThrows(IllegalArgumentException.class, () -> service.capture(1L,10L,request(List.of())));
    }
    @Test void validatesAllReferencePermissionsBeforeExtractingFirst() throws Exception {
        file(102L,20L);
        assertThrows(IllegalArgumentException.class, () -> service.capture(1L,10L,request(List.of(101L,102L))));
        verify(texts,never()).extractText(any());
    }
    @Test void rejectsDeletedFolderAndOversizedMaterials() throws Exception {
        ProjectFile f = file(101L,10L); f.setIsDeleted(true);
        assertThrows(IllegalArgumentException.class, () -> service.capture(1L,10L,request(List.of(101L))));
        f.setIsDeleted(false); f.setIsFolder(true);
        assertThrows(IllegalArgumentException.class, () -> service.capture(1L,10L,request(List.of(101L))));
        f.setIsFolder(false); f.setFileSize(9L*1024*1024);
        assertThrows(IllegalArgumentException.class, () -> service.capture(1L,10L,request(List.of(101L))));
    }
    @Test void liveUnsavedBodyIsUsedAndDuplicateReferenceExtractedOnce() throws Exception {
        ProjectFile ref = file(101L,10L); when(texts.extractText(ref)).thenReturn("借款本金：人民币360000元");
        Snapshot s = service.capture(1L,10L,request(List.of(101L,101L,100L)));
        assertEquals(2,s.sources().size()); assertEquals("active-document",s.sources().get(0).id());
        assertTrue(s.facts().stream().anyMatch(f -> f.value().equals("顾青原")));
        verify(texts,times(1)).extractText(ref);
    }
    @Test void changedSourceContentWithoutTimestampChangeInvalidates() throws Exception {
        ProjectFile ref = file(101L,10L); when(texts.extractText(ref)).thenReturn("借款本金：360000元");
        Snapshot s=service.capture(1L,10L,request(List.of(101L)));
        when(texts.extractText(ref)).thenReturn("借款本金：310000元");
        assertThrows(IllegalArgumentException.class, () -> service.revalidate(s,"r1"));
    }
    @Test void originallyEmptySourceBecomingAvailableAlsoInvalidates() throws Exception {
        ProjectFile ref=file(101L,10L); when(texts.extractText(ref)).thenReturn("");
        Snapshot s=service.capture(1L,10L,request(List.of(101L)));
        when(texts.extractText(ref)).thenReturn("原告：沈望舒");
        assertThrows(IllegalArgumentException.class, () -> service.revalidate(s,"r1"));
    }
    @Test void changesBeyondVisibleTruncationInvalidate() throws Exception {
        ProjectFile ref=file(101L,10L); String prefix="甲".repeat(6000);
        when(texts.extractText(ref)).thenReturn(prefix+"原版");
        Snapshot s=service.capture(1L,10L,request(List.of(101L)));
        assertTrue(s.warnings().stream().anyMatch(x -> x.contains("6000")));
        assertEquals(6000,s.sources().get(1).text().length());
        when(texts.extractText(ref)).thenReturn(prefix+"修改版");
        assertThrows(IllegalArgumentException.class, () -> service.revalidate(s,"r1"));
    }
    @Test void revokedPermissionAndDeletedSourceCannotBeAccepted() throws Exception {
        ProjectFile ref=file(101L,10L); when(texts.extractText(ref)).thenReturn("借款本金：360000元");
        Snapshot s=service.capture(1L,10L,request(List.of(101L)));
        when(members.hasWritePermission(10L,1L)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.revalidate(s,"r1"));
        when(members.hasWritePermission(10L,1L)).thenReturn(true); ref.setIsDeleted(true);
        assertThrows(IllegalArgumentException.class, () -> service.revalidate(s,"r1"));
    }
    @Test void revisionAndSettingsChangesInvalidate() throws Exception {
        Snapshot s=service.capture(1L,10L,request(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.revalidate(s,"r2"));
        ProjectVariable v=new ProjectVariable(); v.setValue("被告");
        when(variables.findByProjectIdAndName(10L,"写作立场")).thenReturn(Optional.of(v));
        assertThrows(IllegalArgumentException.class, () -> service.revalidate(s,"r1"));
    }
    @Test void invalidDateDoesNotPartiallyWriteSettings() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.saveSettings(1L,10L,new SettingsInput("原告","2026-02-31")));
        verify(variables,never()).save(any());
    }
    @Test void referenceCountAndSnapshotSizeAreBounded() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.capture(1L,10L,request(List.of(101L,102L,103L,104L,105L))));
        Request r=request(List.of());
        Request huge=new Request(r.fileId(),r.revision(),"字".repeat(16001),r.before(),r.after(),r.selection(),r.title(),r.section(),r.documentType(),List.of());
        assertThrows(IllegalArgumentException.class, () -> service.capture(1L,10L,huge));
    }
    @Test void narrativeParagraphIsAvailableWithoutPretendingItIsVerified() throws Exception {
        Request r=request(List.of()); String text="原告主张已于约定时间交付借款，被告对此提出异议。";
        Request narrative=new Request(r.fileId(),r.revision(),text,"原告主张",r.after(),r.selection(),r.title(),r.section(),r.documentType(),List.of());
        Snapshot s=service.capture(1L,10L,narrative);
        assertTrue(s.facts().stream().anyMatch(f -> f.quote().equals(text) && f.sourceId().equals("active-document") && !f.status().equals("VERIFIED")));
    }
    @Test void contractContextPassesLiveSectionToNarrativeScope() {
        service=new WritingContextService(files,variables,texts,members,List.of(new ContractWritingProfile()));
        String body="委托方负责提供测试账户，受托方负责制作操作手册。\n附件一 培训范围\n本附件培训对象包含外部人员，其他范围另行说明。";
        Request bodyRequest=new Request(100L,"r1",body,"双方约定","","","合成服务合同","正文","contract",List.of());
        Snapshot s=service.capture(1L,10L,bodyRequest);
        assertTrue(s.facts().stream().anyMatch(f->f.value().contains("操作手册")));
        assertTrue(s.facts().stream().noneMatch(f->f.value().contains("外部人员")));
        Request unknown=new Request(100L,"r1",body,"双方约定","","","合成服务合同","","contract",List.of());
        assertTrue(service.capture(1L,10L,unknown).facts().isEmpty());
    }
}
