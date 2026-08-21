package com.checkba.service.evidence;

import com.checkba.model.entity.DocFileLink;
import com.checkba.model.entity.EvidenceLink;
import com.checkba.model.entity.EvidenceLinkTarget;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.DocFileLinkRepository;
import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.repository.EvidenceLinkTargetRepository;
import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * doc_file_link → evidence_link 启动迁移（spec §1.5）：幂等、按 (projectId, wpsFileId) 反查报告文件、
 * 查不到的行跳过、每个 fileId 一条 target、status=unverified。
 */
class EvidenceLinkMigrationRunnerTest {

    DocFileLinkRepository old = mock(DocFileLinkRepository.class);
    EvidenceLinkRepository links = mock(EvidenceLinkRepository.class);
    EvidenceLinkTargetRepository targets = mock(EvidenceLinkTargetRepository.class);
    ProjectFileRepository files = mock(ProjectFileRepository.class);
    EvidenceLinkMigrationRunner runner;

    @BeforeEach
    void setUp() {
        runner = new EvidenceLinkMigrationRunner(old, links, targets, files, new ObjectMapper());
        when(links.save(any())).thenAnswer(inv -> {
            EvidenceLink l = inv.getArgument(0);
            if (l.getId() == null) l.setId(100L);
            return l;
        });
        when(targets.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    static DocFileLink row(long id, long pid, String wps, String key, String anchor, String fileIdsJson) {
        DocFileLink r = new DocFileLink();
        r.setId(id);
        r.setProjectId(pid);
        r.setUserId(9L);
        r.setDocWpsFileId(wps);
        r.setLinkKey(key);
        r.setAnchorText(anchor);
        r.setFileIdsJson(fileIdsJson);
        r.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        return r;
    }

    @Test
    @DisplayName("每行 doc_file_link 迁一次：反查得到的建链 + 每个 fileId 一条 target；反查不到的跳过")
    void migratesEachDocFileLinkRowOnce() throws Exception {
        ProjectFile doc = new ProjectFile();
        doc.setId(10L);
        doc.setProjectId(1L);
        when(files.findFirstByProjectIdAndWpsFileId(1L, "w1")).thenReturn(Optional.of(doc));
        when(files.findFirstByProjectIdAndWpsFileId(1L, "w-missing")).thenReturn(Optional.empty());
        when(old.findAll()).thenReturn(List.of(
                row(1, 1, "w1", "lk_x", "根据《营业执照》", "[11,12]"),
                row(2, 1, "w-missing", "lk_y", "x", "[13]")));

        runner.run(null);

        ArgumentCaptor<EvidenceLink> lc = ArgumentCaptor.forClass(EvidenceLink.class);
        verify(links, times(1)).save(lc.capture());
        EvidenceLink l = lc.getValue();
        assertEquals("lk_x", l.getLinkKey());
        assertEquals(10L, l.getDocFileId());
        assertEquals(1L, l.getProjectId());
        assertEquals("unverified", l.getStatus());
        assertEquals("human", l.getCreatedByKind());
        assertEquals(9L, l.getCreatedBy());
        assertEquals(AnchorHash.of("根据《营业执照》"), l.getAnchorHash());
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4), l.getCreatedAt());

        ArgumentCaptor<EvidenceLinkTarget> tc = ArgumentCaptor.forClass(EvidenceLinkTarget.class);
        verify(targets, times(2)).save(tc.capture());
        List<EvidenceLinkTarget> ts = tc.getAllValues();
        assertEquals(11L, ts.get(0).getFileId());
        assertEquals(12L, ts.get(1).getFileId());
        assertEquals(0, ts.get(0).getSortOrder());
        assertEquals(1, ts.get(1).getSortOrder());
        assertEquals("-", ts.get(0).getLocatorHash());
        assertEquals("supports", ts.get(0).getRelation());
        assertEquals("human", ts.get(0).getCreatedByKind());
        assertEquals(100L, ts.get(0).getLinkId());
        assertEquals(1, runner.lastSkipped());
        assertEquals(1, runner.lastMigrated());
    }

    @Test
    @DisplayName("幂等：evidence_link 非空时什么都不做")
    void secondRunIsNoop() throws Exception {
        when(links.count()).thenReturn(5L);
        runner.run(null);
        verify(old, never()).findAll();
        verify(links, never()).save(any());
    }

    @Test
    @DisplayName("doc_file_link 表不存在（findAll 抛异常）→ 跳过，不炸启动")
    void missingOldTableIsSkipped() throws Exception {
        when(old.findAll()).thenThrow(new RuntimeException("table not found"));
        runner.run(null);
        verify(links, never()).save(any());
    }

    @Test
    @DisplayName("fileIdsJson 坏掉或为空 → 建链但无 target；非法 JSON 不炸")
    void badFileIdsJsonYieldsNoTargets() throws Exception {
        ProjectFile doc = new ProjectFile();
        doc.setId(10L);
        doc.setProjectId(1L);
        when(files.findFirstByProjectIdAndWpsFileId(1L, "w1")).thenReturn(Optional.of(doc));
        when(old.findAll()).thenReturn(List.of(
                row(1, 1, "w1", "lk_a", "x", "not json"),
                row(2, 1, "w1", "lk_b", "x", null)));

        runner.run(null);

        verify(links, times(2)).save(any());
        verify(targets, never()).save(any());
    }
}
