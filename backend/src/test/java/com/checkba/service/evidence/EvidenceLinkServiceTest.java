package com.checkba.service.evidence;

import com.checkba.model.entity.EvidenceLink;
import com.checkba.model.entity.EvidenceLinkTarget;
import com.checkba.model.entity.FileTag;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.repository.EvidenceLinkTargetRepository;
import com.checkba.repository.FileTagRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.evidence.EvidenceLinkViews.AnchorReport;
import com.checkba.service.evidence.EvidenceLinkViews.LinkView;
import com.checkba.service.evidence.EvidenceLinkViews.TargetInput;
import com.checkba.service.evidence.EvidenceLinkViews.TargetView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvidenceLinkService 契约（dev-board#102）：建链/追加/反查/状态机/权限/IDOR。
 * 全部 Repository 与 ProjectMemberService 为 mock，不起 Spring 上下文。
 */
class EvidenceLinkServiceTest {

    EvidenceLinkRepository links = mock(EvidenceLinkRepository.class);
    EvidenceLinkTargetRepository targets = mock(EvidenceLinkTargetRepository.class);
    ProjectFileRepository files = mock(ProjectFileRepository.class);
    FileTagRepository fileTags = mock(FileTagRepository.class);
    ProjectMemberService members = mock(ProjectMemberService.class);
    EvidenceLinkService svc;

    /** 所有 targets.save 过的对象，供 view() 回读。 */
    List<EvidenceLinkTarget> savedTargets = new ArrayList<>();
    long nextTargetId = 200L;

    @BeforeEach
    void setUp() {
        svc = new EvidenceLinkService(links, targets, files, fileTags, members, new ObjectMapper());
        when(members.hasReadPermission(1L, 9L)).thenReturn(true);
        when(members.hasWritePermission(1L, 9L)).thenReturn(true);
        when(links.save(any())).thenAnswer(inv -> {
            EvidenceLink l = inv.getArgument(0);
            if (l.getId() == null) l.setId(100L);
            return l;
        });
        when(targets.save(any())).thenAnswer(inv -> {
            EvidenceLinkTarget t = inv.getArgument(0);
            if (t.getId() == null) t.setId(nextTargetId++);
            savedTargets.add(t);
            return t;
        });
        when(targets.findByLinkIdInOrderBySortOrderAscIdAsc(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<?> ids = inv.getArgument(0);
            List<EvidenceLinkTarget> out = new ArrayList<>();
            for (EvidenceLinkTarget t : savedTargets) if (ids.contains(t.getLinkId())) out.add(t);
            return out;
        });
        ProjectFile doc = file(10L, 1L, "报告.docx");
        ProjectFile pdf = file(11L, 1L, "执照.pdf");
        ProjectFile pdf2 = file(12L, 1L, "章程.pdf");
        when(files.findById(10L)).thenReturn(Optional.of(doc));
        when(files.findById(11L)).thenReturn(Optional.of(pdf));
        when(files.findById(12L)).thenReturn(Optional.of(pdf2));
        when(files.findAllById(any())).thenAnswer(inv -> {
            List<ProjectFile> out = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) files.findById((Long) id).ifPresent(out::add);
            return out;
        });
    }

    static ProjectFile file(long id, long pid, String name) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(pid);
        f.setName(name);
        f.setFileType("pdf");
        f.setIsDeleted(false);
        return f;
    }

    static TargetInput target(Long fileId, String locator) {
        return new TargetInput(fileId, locator, null, "written_review", null, null);
    }

    EvidenceLink existing(String key, String status, String anchorText) {
        EvidenceLink l = new EvidenceLink();
        l.setId(100L);
        l.setProjectId(1L);
        l.setDocFileId(10L);
        l.setLinkKey(key);
        l.setStatus(status);
        l.setAnchorText(anchorText);
        l.setAnchorHash(AnchorHash.of(anchorText));
        l.setCreatedByKind("human");
        when(links.findByProjectIdAndLinkKey(1L, key)).thenReturn(Optional.of(l));
        return l;
    }

    // ---------- create ----------

    @Test
    @DisplayName("create：人工建链生成 EVID_ 书签名、active、anchorHash 按归一化算、relation 默认 supports")
    void createGeneratesEvidKeyAndActiveForHuman() {
        LinkView v = svc.create(9L, 1L, 10L, null, "根据《营业执照》", "一/（一）", "主体资格", "human",
                List.of(target(11L, "{\"type\":\"pdf\",\"page\":1}")));
        assertTrue(v.linkKey().startsWith("EVID_"));
        assertEquals(31, v.linkKey().length());
        assertEquals("active", v.status());
        assertEquals(AnchorHash.of("根据《营业执照》"), v.anchorHash());
        assertEquals("一/（一）", v.sectionPath());
        assertEquals(1, v.targets().size());
        TargetView t = v.targets().get(0);
        assertEquals("supports", t.relation());
        assertEquals("written_review", t.method());
        assertEquals(1, t.locator().get("page").asInt());
        assertEquals("执照.pdf", t.file().name());
        assertNotNull(v.createdAt());
    }

    @Test
    @DisplayName("create：AI 建链 status=unverified")
    void createByAiIsUnverified() {
        LinkView v = svc.create(9L, 1L, 10L, null, "x", null, null, "ai", List.of(target(11L, null)));
        assertEquals("unverified", v.status());
        assertEquals("ai", v.createdByKind());
    }

    @Test
    @DisplayName("create：显式 linkKey 原样使用；重复 → 拒绝")
    void createHonoursExplicitKeyAndRejectsDuplicate() {
        LinkView v = svc.create(9L, 1L, 10L, "lk_abc", "x", null, null, "human", List.of(target(11L, null)));
        assertEquals("lk_abc", v.linkKey());

        existing("EVID_DUP", "active", "x");
        assertThrows(IllegalArgumentException.class,
                () -> svc.create(9L, 1L, 10L, "EVID_DUP", "x", null, null, "human", List.of(target(11L, null))));
        assertThrows(IllegalArgumentException.class,
                () -> svc.create(9L, 1L, 10L, "bad key!", "x", null, null, "human", List.of(target(11L, null))));
    }

    @Test
    @DisplayName("create：target 文件属于别的项目 → IllegalArgumentException，不落库（IDOR）")
    void createRejectsForeignFile() {
        when(files.findById(11L)).thenReturn(Optional.of(file(11L, 2L, "别家.pdf")));
        assertThrows(IllegalArgumentException.class,
                () -> svc.create(9L, 1L, 10L, null, "x", null, null, "human", List.of(target(11L, null))));
        verify(links, never()).save(any());
        verify(targets, never()).save(any());
    }

    @Test
    @DisplayName("create：报告文件属于别的项目 → 拒绝")
    void createRejectsForeignDoc() {
        when(files.findById(10L)).thenReturn(Optional.of(file(10L, 2L, "别家.docx")));
        assertThrows(IllegalArgumentException.class,
                () -> svc.create(9L, 1L, 10L, null, "x", null, null, "human", List.of(target(11L, null))));
        verify(links, never()).save(any());
    }

    @Test
    @DisplayName("create：relation / method 不在白名单 → 拒绝")
    void createRejectsBadRelationOrMethod() {
        assertThrows(IllegalArgumentException.class, () -> svc.create(9L, 1L, 10L, null, "x", null, null, "human",
                List.of(new TargetInput(11L, null, "maybe", null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> svc.create(9L, 1L, 10L, null, "x", null, null, "human",
                List.of(new TargetInput(11L, null, null, "guess", null, null))));
        assertThrows(IllegalArgumentException.class, () -> svc.create(9L, 1L, 10L, null, "x", null, null, "human",
                List.of(new TargetInput(11L, null, null, null, (short) 101, null))));
        verify(links, never()).save(any());
    }

    @Test
    @DisplayName("create：targets 为空 → 拒绝（contradicts 必须有真实 target，查无此据不走这里）")
    void createRejectsWithoutTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.create(9L, 1L, 10L, null, "x", null, null, "human", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> svc.create(9L, 1L, 10L, null, "x", null, null, "human", null));
        verify(links, never()).save(any());
    }

    @Test
    @DisplayName("create：locatorJson 不是合法 JSON → 拒绝")
    void createRejectsMalformedLocator() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.create(9L, 1L, 10L, null, "x", null, null, "human", List.of(target(11L, "{nope"))));
        verify(links, never()).save(any());
    }

    @Test
    @DisplayName("create：非项目成员（无写权限）→ 拒绝")
    void createRejectsNonMember() {
        when(members.hasWritePermission(1L, 9L)).thenReturn(false);
        assertThrows(IllegalArgumentException.class,
                () -> svc.create(9L, 1L, 10L, null, "x", null, null, "human", List.of(target(11L, null))));
        assertThrows(IllegalArgumentException.class,
                () -> svc.create(null, 1L, 10L, null, "x", null, null, "human", List.of(target(11L, null))));
        verify(links, never()).save(any());
    }

    // ---------- targets ----------

    @Test
    @DisplayName("addTargets：同 (link,file,locatorHash) 已存在 → 不重复落库；locator 键序不同视为同一位置")
    void addTargetsDedupesByLocatorHash() {
        existing("EVID_A", "active", "x");
        String h = EvidenceLinkService.locatorHash("{\"page\":3,\"type\":\"pdf\"}");
        when(targets.existsByLinkIdAndFileIdAndLocatorHash(100L, 11L, h)).thenReturn(true);

        LinkView v = svc.addTargets(9L, 1L, "EVID_A", List.of(target(11L, "{\"type\":\"pdf\",\"page\":3}")), null);
        verify(targets, never()).save(any());
        assertEquals(0, v.targets().size());
    }

    @Test
    @DisplayName("addTargets：新位置落库并续排 sortOrder")
    void addTargetsAppendsWithSortOrder() {
        EvidenceLink l = existing("EVID_A", "active", "x");
        EvidenceLinkTarget t0 = new EvidenceLinkTarget();
        t0.setId(1L); t0.setLinkId(100L); t0.setFileId(11L); t0.setSortOrder(0);
        savedTargets.add(t0);
        when(targets.findByLinkIdOrderBySortOrderAscIdAsc(100L)).thenReturn(List.of(t0));

        LinkView v = svc.addTargets(9L, 1L, "EVID_A", List.of(target(12L, null)), null);
        assertEquals(2, v.targets().size());
        ArgumentCaptor<EvidenceLinkTarget> cap = ArgumentCaptor.forClass(EvidenceLinkTarget.class);
        verify(targets).save(cap.capture());
        assertEquals(1, cap.getValue().getSortOrder());
        assertEquals("-", cap.getValue().getLocatorHash());
        assertEquals("human", cap.getValue().getCreatedByKind(), "缺省 kind = human");
        assertNotNull(l.getUpdatedAt());
    }

    @Test
    @DisplayName("addTargets：createdByKind=plugin 记在 target 上；非法 kind 拒绝且不落库")
    void addTargetsRecordsCreatedByKind() {
        existing("EVID_A", "active", "x");
        svc.addTargets(9L, 1L, "EVID_A", List.of(target(12L, null)), "plugin");
        ArgumentCaptor<EvidenceLinkTarget> cap = ArgumentCaptor.forClass(EvidenceLinkTarget.class);
        verify(targets).save(cap.capture());
        assertEquals("plugin", cap.getValue().getCreatedByKind());

        assertThrows(IllegalArgumentException.class,
                () -> svc.addTargets(9L, 1L, "EVID_A", List.of(target(11L, null)), "robot"));
        verify(targets, times(1)).save(any());
    }

    @Test
    @DisplayName("updateTarget：null 字段不改；跨项目 target 拒绝")
    void updateTargetPatchesOnlyGivenFields() {
        EvidenceLink l = existing("EVID_A", "active", "x");
        EvidenceLinkTarget t = new EvidenceLinkTarget();
        t.setId(7L); t.setLinkId(100L); t.setFileId(11L); t.setMethod("written_review"); t.setNote("old");
        when(targets.findById(7L)).thenReturn(Optional.of(t));
        when(links.findById(100L)).thenReturn(Optional.of(l));

        TargetView v = svc.updateTarget(9L, 1L, 7L, new TargetInput(null, null, null, "interview", null, null));
        assertEquals("interview", v.method());
        assertEquals("old", v.note());

        assertThrows(IllegalArgumentException.class,
                () -> svc.updateTarget(9L, 1L, 7L, new TargetInput(null, null, "maybe", null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> svc.updateTarget(9L, 2L, 7L, new TargetInput(null, null, null, "interview", null, null)));
    }

    @Test
    @DisplayName("removeTarget：删掉最后一个 target 不自动改状态（只有彻底删文件才例外）")
    void removeTargetDeletesRow() {
        EvidenceLink l = existing("EVID_A", "active", "x");
        EvidenceLinkTarget t = new EvidenceLinkTarget();
        t.setId(7L); t.setLinkId(100L); t.setFileId(11L);
        when(targets.findById(7L)).thenReturn(Optional.of(t));
        when(links.findById(100L)).thenReturn(Optional.of(l));

        svc.removeTarget(9L, 1L, 7L);
        verify(targets).delete(t);
    }

    @Test
    @DisplayName("delete：级联删 targets 再删 link")
    void deleteCascades() {
        EvidenceLink l = existing("EVID_A", "active", "x");
        svc.delete(9L, 1L, "EVID_A");
        verify(targets).deleteByLinkId(100L);
        verify(links).delete(l);
    }

    // ---------- state machine ----------

    @Test
    @DisplayName("reportAnchors：active/unverified 文字变 → stale；stale 文字同 → 仍 stale；exists=false → orphan；active 文字同 → 不变且 checkedAt 更新")
    void reportAnchorsTransitions() {
        EvidenceLink a = new EvidenceLink(); a.setId(1L); a.setLinkKey("A"); a.setStatus("active"); a.setAnchorHash(AnchorHash.of("原文"));
        EvidenceLink b = new EvidenceLink(); b.setId(2L); b.setLinkKey("B"); b.setStatus("unverified"); b.setAnchorHash(AnchorHash.of("原文"));
        EvidenceLink c = new EvidenceLink(); c.setId(3L); c.setLinkKey("C"); c.setStatus("stale"); c.setAnchorHash(AnchorHash.of("原文"));
        EvidenceLink d = new EvidenceLink(); d.setId(4L); d.setLinkKey("D"); d.setStatus("active"); d.setAnchorHash(AnchorHash.of("原文"));
        EvidenceLink e = new EvidenceLink(); e.setId(5L); e.setLinkKey("E"); e.setStatus("active"); e.setAnchorHash(AnchorHash.of("原文"));
        EvidenceLink f = new EvidenceLink(); f.setId(6L); f.setLinkKey("F"); f.setStatus("unverified"); f.setAnchorHash(AnchorHash.of("原文"));
        for (EvidenceLink l : List.of(a, b, c, d, e, f)) { l.setProjectId(1L); l.setDocFileId(10L); }
        when(links.findByProjectIdAndDocFileIdOrderByIdAsc(1L, 10L)).thenReturn(List.of(a, b, c, d, e, f));

        List<String> changed = svc.reportAnchors(9L, 1L, 10L, List.of(
                new AnchorReport("A", true, "改了"),
                new AnchorReport("B", true, "改了"),
                new AnchorReport("C", true, "原 文"),
                new AnchorReport("D", false, null),
                new AnchorReport("E", true, "原　文。"),
                new AnchorReport("F", true, "原文"),
                new AnchorReport("ZZZ", true, "不存在的 key 忽略"))).changed();

        assertEquals("stale", a.getStatus());
        assertEquals("stale", b.getStatus());
        assertEquals("stale", c.getStatus());
        assertEquals("orphan", d.getStatus());
        assertEquals("stale", e.getStatus(), "句号是实质改动，归一化不吞标点");
        assertEquals("unverified", f.getStatus(), "文字没变不改状态");
        assertNotNull(f.getCheckedAt());
        assertEquals(List.of("A", "B", "D", "E"), changed);
    }

    @Test
    @DisplayName("reportAnchors：只读成员（有读无写）也能回写核对结果；非成员仍拒绝")
    void reportAnchorsNeedsReadOnly() {
        EvidenceLink a = new EvidenceLink(); a.setId(1L); a.setLinkKey("A"); a.setStatus("active"); a.setAnchorHash(AnchorHash.of("原文"));
        a.setProjectId(1L); a.setDocFileId(10L);
        when(links.findByProjectIdAndDocFileIdOrderByIdAsc(1L, 10L)).thenReturn(List.of(a));
        when(members.hasWritePermission(1L, 9L)).thenReturn(false);

        var res = svc.reportAnchors(9L, 1L, 10L, List.of(new AnchorReport("A", true, "改了")));
        assertEquals("stale", a.getStatus());
        assertEquals(List.of("A"), res.changed());

        when(members.hasReadPermission(1L, 9L)).thenReturn(false);
        assertThrows(IllegalArgumentException.class,
                () -> svc.reportAnchors(9L, 1L, 10L, List.of(new AnchorReport("A", true, "改了"))));
    }

    @Test
    @DisplayName("reportAnchors：orphan 再报 exists=true 不复活（复活只走 rebind）")
    void reportAnchorsDoesNotResurrectOrphan() {
        EvidenceLink o = new EvidenceLink(); o.setId(1L); o.setLinkKey("O"); o.setStatus("orphan"); o.setAnchorHash(AnchorHash.of("原文"));
        o.setProjectId(1L); o.setDocFileId(10L);
        when(links.findByProjectIdAndDocFileIdOrderByIdAsc(1L, 10L)).thenReturn(List.of(o));
        List<String> changed = svc.reportAnchors(9L, 1L, 10L, List.of(new AnchorReport("O", true, "原文"))).changed();
        assertEquals("orphan", o.getStatus());
        assertTrue(changed.isEmpty());
    }

    @Test
    @DisplayName("reportAnchors：exists 缺失（半截 payload）不改状态、不打 orphan，计入 ignored")
    void reportAnchorsIgnoresMissingExists() {
        EvidenceLink a = new EvidenceLink(); a.setId(1L); a.setLinkKey("A"); a.setStatus("active"); a.setAnchorHash(AnchorHash.of("原文"));
        EvidenceLink b = new EvidenceLink(); b.setId(2L); b.setLinkKey("B"); b.setStatus("active"); b.setAnchorHash(AnchorHash.of("原文"));
        for (EvidenceLink l : List.of(a, b)) { l.setProjectId(1L); l.setDocFileId(10L); }
        when(links.findByProjectIdAndDocFileIdOrderByIdAsc(1L, 10L)).thenReturn(List.of(a, b));

        var res = svc.reportAnchors(9L, 1L, 10L, List.of(
                new AnchorReport("A", null, "改了"),
                new AnchorReport("B", false, null)));

        assertEquals("active", a.getStatus(), "exists=null 不许当 false");
        assertNull(a.getCheckedAt());
        assertEquals("orphan", b.getStatus());
        assertEquals(List.of("B"), res.changed());
        assertEquals(1, res.ignored());
    }

    @Test
    @DisplayName("keepAnchor：stale → active，anchorText/anchorHash 刷成当前文字")
    void keepAnchorRefreshesHashAndActivates() {
        EvidenceLink l = existing("EVID_A", "stale", "原文");
        LinkView v = svc.keepAnchor(9L, 1L, "EVID_A", "改了");
        assertEquals("active", v.status());
        assertEquals("改了", v.anchorText());
        assertEquals(AnchorHash.of("改了"), v.anchorHash());
        assertNotNull(l.getUpdatedAt());
    }

    @Test
    @DisplayName("keepAnchor：orphan 不能保留（书签已不在），必须 rebind")
    void keepAnchorRejectsOrphan() {
        existing("EVID_A", "orphan", "原文");
        assertThrows(IllegalArgumentException.class, () -> svc.keepAnchor(9L, 1L, "EVID_A", "x"));
    }

    @Test
    @DisplayName("rebind：orphan → active，linkKey 换成新书签名；新 key 撞车 → 拒绝")
    void rebindReplacesKeyAndActivates() {
        EvidenceLink l = existing("EVID_OLD", "orphan", "原文");
        LinkView v = svc.rebind(9L, 1L, "EVID_OLD", "EVID_NEW", "新文字", "二/（一）", "财务");
        assertEquals("EVID_NEW", v.linkKey());
        assertEquals("active", v.status());
        assertEquals("新文字", v.anchorText());
        assertEquals(AnchorHash.of("新文字"), v.anchorHash());
        assertEquals("二/（一）", v.sectionPath());
        assertEquals("EVID_NEW", l.getLinkKey());

        existing("EVID_OLD2", "orphan", "原文");
        existing("EVID_TAKEN", "active", "y");
        assertThrows(IllegalArgumentException.class,
                () -> svc.rebind(9L, 1L, "EVID_OLD2", "EVID_TAKEN", "z", null, null));
    }

    @Test
    @DisplayName("onFilePurged：删该文件的 target；link 无 target 后 → orphan")
    void onFilePurgedCascades() {
        EvidenceLink l1 = new EvidenceLink(); l1.setId(100L); l1.setProjectId(1L); l1.setStatus("active");
        EvidenceLink l2 = new EvidenceLink(); l2.setId(101L); l2.setProjectId(1L); l2.setStatus("active");
        EvidenceLinkTarget t1 = new EvidenceLinkTarget(); t1.setId(1L); t1.setLinkId(100L); t1.setFileId(11L);
        EvidenceLinkTarget t2 = new EvidenceLinkTarget(); t2.setId(2L); t2.setLinkId(101L); t2.setFileId(11L);
        EvidenceLinkTarget t3 = new EvidenceLinkTarget(); t3.setId(3L); t3.setLinkId(101L); t3.setFileId(12L);
        when(targets.findByFileId(11L)).thenReturn(List.of(t1, t2));
        when(links.findByProjectIdAndIdIn(eq(1L), anyCollection())).thenReturn(List.of(l1, l2));
        when(targets.findByLinkIdOrderBySortOrderAscIdAsc(100L)).thenReturn(List.of());
        when(targets.findByLinkIdOrderBySortOrderAscIdAsc(101L)).thenReturn(List.of(t3));

        svc.onFilePurged(1L, 11L);

        verify(targets).deleteAll(List.of(t1, t2));
        assertEquals("orphan", l1.getStatus());
        assertEquals("active", l2.getStatus());
    }

    // ---------- queries ----------

    @Test
    @DisplayName("listByDoc：status / sectionPath 前缀两种过滤；读权限")
    void listByDocFilters() {
        EvidenceLink l = existing("EVID_A", "active", "x");
        when(links.findByProjectIdAndDocFileIdOrderByIdAsc(1L, 10L)).thenReturn(List.of(l));
        when(links.findByProjectIdAndDocFileIdAndStatusOrderByIdAsc(1L, 10L, "stale")).thenReturn(List.of());
        when(links.findByProjectIdAndDocFileIdAndSectionPathStartingWithOrderByIdAsc(1L, 10L, "一/")).thenReturn(List.of(l));

        assertEquals(1, svc.listByDoc(9L, 1L, 10L, null, null).size());
        assertEquals(0, svc.listByDoc(9L, 1L, 10L, "stale", null).size());
        assertEquals(1, svc.listByDoc(9L, 1L, 10L, null, "一/").size());

        when(members.hasReadPermission(1L, 9L)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> svc.listByDoc(9L, 1L, 10L, null, null));
    }

    @Test
    @DisplayName("listByFile：反查底稿被哪些锚点引用；别家项目的 link 不漏出")
    void listByFileReverseLookup() {
        EvidenceLink mine = new EvidenceLink(); mine.setId(100L); mine.setProjectId(1L); mine.setLinkKey("A"); mine.setStatus("active");
        EvidenceLinkTarget t = new EvidenceLinkTarget(); t.setId(1L); t.setLinkId(100L); t.setFileId(11L);
        EvidenceLinkTarget foreign = new EvidenceLinkTarget(); foreign.setId(2L); foreign.setLinkId(999L); foreign.setFileId(11L);
        savedTargets.add(t);
        when(targets.findByFileId(11L)).thenReturn(List.of(t, foreign));
        when(links.findByProjectIdAndIdIn(eq(1L), anyCollection())).thenReturn(List.of(mine));

        List<LinkView> out = svc.listByFile(9L, 1L, 11L);
        assertEquals(1, out.size());
        assertEquals("A", out.get(0).linkKey());
        assertEquals(11L, out.get(0).targets().get(0).fileId());
    }

    @Test
    @DisplayName("listByParty：只返回 targets.file 挂了该 PARTY 标签的 link")
    void listByPartyFiltersByTag() {
        EvidenceLink l1 = new EvidenceLink(); l1.setId(100L); l1.setProjectId(1L); l1.setDocFileId(10L); l1.setLinkKey("A"); l1.setStatus("active");
        EvidenceLink l2 = new EvidenceLink(); l2.setId(101L); l2.setProjectId(1L); l2.setDocFileId(10L); l2.setLinkKey("B"); l2.setStatus("active");
        EvidenceLinkTarget t1 = new EvidenceLinkTarget(); t1.setId(1L); t1.setLinkId(100L); t1.setFileId(11L);
        EvidenceLinkTarget t2 = new EvidenceLinkTarget(); t2.setId(2L); t2.setLinkId(101L); t2.setFileId(12L);
        savedTargets.add(t1); savedTargets.add(t2);
        when(links.findByProjectIdAndDocFileIdOrderByIdAsc(1L, 10L)).thenReturn(List.of(l1, l2));
        FileTag ft = new FileTag(); ft.setFileId(11L); ft.setTagId(5L);
        when(fileTags.findByTagId(5L)).thenReturn(List.of(ft));

        List<LinkView> out = svc.listByParty(9L, 1L, 10L, 5L);
        assertEquals(1, out.size());
        assertEquals("A", out.get(0).linkKey());
    }

    @Test
    @DisplayName("getByKey：不存在 → 拒绝；软删文件在 view 里 isDeleted=true")
    void getByKeyAndDeletedFileFlag() {
        assertThrows(IllegalArgumentException.class, () -> svc.getByKey(9L, 1L, "NOPE"));

        existing("EVID_A", "active", "x");
        EvidenceLinkTarget t = new EvidenceLinkTarget(); t.setId(1L); t.setLinkId(100L); t.setFileId(11L);
        savedTargets.add(t);
        ProjectFile gone = file(11L, 1L, "执照.pdf"); gone.setIsDeleted(true);
        when(files.findById(11L)).thenReturn(Optional.of(gone));

        LinkView v = svc.getByKey(9L, 1L, "EVID_A");
        assertTrue(v.targets().get(0).file().isDeleted());

        // 文件行已不存在（理论上彻底删除会级联，这里防御）：file=null，不抛
        when(files.findById(11L)).thenReturn(Optional.empty());
        assertNull(svc.getByKey(9L, 1L, "EVID_A").targets().get(0).file());
    }

    @Test
    @DisplayName("refCounts：{fileId,count} 行映射成 Map；空入参返回空 Map 不查库")
    void refCountsMapsRows() {
        when(targets.countByFileIds(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{11L, 3L}));
        Map<Long, Long> m = svc.refCounts(1L, List.of(11L, 12L));
        assertEquals(3L, m.get(11L));
        assertNull(m.get(12L));
        assertTrue(svc.refCounts(1L, List.of()).isEmpty());
        verify(targets, times(1)).countByFileIds(anyCollection());
    }

    @Test
    @DisplayName("refCounts：他项目的 fileId 不返回、也不带进计数查询；全是他项目的不查库")
    void refCountsFiltersByProject() {
        when(files.findById(13L)).thenReturn(Optional.of(file(13L, 2L, "别家.pdf")));
        when(targets.countByFileIds(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{11L, 3L}));

        Map<Long, Long> m = svc.refCounts(1L, List.of(11L, 13L));
        assertEquals(3L, m.get(11L));
        assertNull(m.get(13L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> cap = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(targets).countByFileIds(cap.capture());
        assertEquals(List.of(11L), new ArrayList<>(cap.getValue()));

        assertTrue(svc.refCounts(1L, List.of(13L)).isEmpty());
        verify(targets, times(1)).countByFileIds(anyCollection());
    }

    @Test
    @DisplayName("locatorHash：canonical（键排序）后再 hash；空/空白 = \"-\"")
    void locatorHashIsCanonical() {
        assertEquals("-", EvidenceLinkService.locatorHash(null));
        assertEquals("-", EvidenceLinkService.locatorHash("  "));
        assertEquals(EvidenceLinkService.locatorHash("{\"type\":\"pdf\",\"page\":3}"),
                EvidenceLinkService.locatorHash("{ \"page\": 3, \"type\": \"pdf\" }"));
        assertNotEquals(EvidenceLinkService.locatorHash("{\"page\":3}"), EvidenceLinkService.locatorHash("{\"page\":4}"));
        assertFalse(EvidenceLinkService.locatorHash("{\"a\":{\"z\":1,\"b\":[{\"y\":1,\"x\":2}]}}").isEmpty());
        assertEquals(EvidenceLinkService.locatorHash("{\"a\":{\"z\":1,\"b\":[{\"y\":1,\"x\":2}]}}"),
                EvidenceLinkService.locatorHash("{\"a\":{\"b\":[{\"x\":2,\"y\":1}],\"z\":1}}"));
        assertThrows(IllegalArgumentException.class, () -> EvidenceLinkService.locatorHash("{nope"));
    }

    @Test
    @DisplayName("locatorHash：数字归一（1 / 1.0 / 1.00 同 hash，嵌套也算）；根不是对象（裸数组/标量）拒绝")
    void locatorHashNormalisesNumbersAndRejectsNonObjectRoot() {
        assertEquals(EvidenceLinkService.locatorHash("{\"page\":1}"), EvidenceLinkService.locatorHash("{\"page\":1.0}"));
        assertEquals(EvidenceLinkService.locatorHash("{\"page\":1}"), EvidenceLinkService.locatorHash("{\"page\":1.00}"));
        assertEquals(EvidenceLinkService.locatorHash("{\"rect\":{\"x\":0.5,\"w\":100}}"),
                EvidenceLinkService.locatorHash("{\"rect\":{\"w\":100.0,\"x\":0.50}}"));
        assertNotEquals(EvidenceLinkService.locatorHash("{\"page\":1}"), EvidenceLinkService.locatorHash("{\"page\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> EvidenceLinkService.locatorHash("[1]"));
        assertThrows(IllegalArgumentException.class, () -> EvidenceLinkService.locatorHash("\"x\""));
        assertThrows(IllegalArgumentException.class, () -> EvidenceLinkService.locatorHash("42"));
        assertThrows(IllegalArgumentException.class, () -> EvidenceLinkService.locatorHash("null"));
    }
}
