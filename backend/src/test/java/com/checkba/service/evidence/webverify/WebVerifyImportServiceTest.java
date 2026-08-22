package com.checkba.service.evidence.webverify;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.service.evidence.EvidenceLinkViews.FileBrief;
import com.checkba.service.evidence.EvidenceLinkViews.LinkView;
import com.checkba.service.evidence.EvidenceLinkViews.TargetInput;
import com.checkba.service.evidence.EvidenceLinkViews.TargetView;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网核 zip 落盘 + 自动挂链：落盘路径与 metaJson、只挂 method=web_check 且提到该主体的段落、
 * 站点更精确时只挂该站点段落、没有匹配段落时如实返回未挂链清单、鉴权与跨项目归属。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebVerifyImportServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long OTHER_PROJECT_ID = 9L;
    private static final Long USER_ID = 5L;
    private static final Long DOC_FILE_ID = 100L;
    private static final Long FOLDER_ID = 200L;
    private static final String PARTY = "某某科技有限公司";

    @Mock private ProjectMemberService projectMemberService;
    @Mock private ProjectFileService projectFileService;
    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private StorageServiceFactory storageServiceFactory;
    @Mock private StorageService storageService;
    @Mock private EvidenceLinkService evidenceLinkService;
    @Mock private EvidenceLinkRepository evidenceLinkRepository;

    private final ObjectMapper om = new ObjectMapper();

    private WebVerifyImportService service() {
        return service(new ManualWebVerifyProvider(om));
    }

    private WebVerifyImportService service(WebVerifyProvider provider) {
        when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(projectFileService.ensureFolderPath(eq(PROJECT_ID), eq(USER_ID), anyList())).thenReturn(folder());
        when(projectFileService.createFile(eq(PROJECT_ID), eq(FOLDER_ID), anyString(), anyString(), anyLong(),
                any(), any(), eq(USER_ID), eq(ProjectFileService.ConflictPolicy.RENAME)))
                .thenAnswer(inv -> landed(inv.getArgument(2)));
        when(projectFileRepository.findById(DOC_FILE_ID)).thenReturn(Optional.of(reportFile(PROJECT_ID)));
        when(projectFileRepository.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));
        return new WebVerifyImportService(projectMemberService, projectFileService, projectFileRepository,
                storageServiceFactory, evidenceLinkService, evidenceLinkRepository, List.of(provider), om);
    }

    // ------------------------------------------------------------------ 夹具

    private static ProjectFile folder() {
        ProjectFile f = new ProjectFile();
        f.setId(FOLDER_ID);
        f.setProjectId(PROJECT_ID);
        f.setName("某某科技有限公司");
        f.setIsFolder(true);
        return f;
    }

    private static long nextId = 300L;

    private static ProjectFile landed(String name) {
        ProjectFile f = new ProjectFile();
        f.setId(nextId++);
        f.setProjectId(PROJECT_ID);
        f.setParentId(FOLDER_ID);
        f.setName(name);
        f.setIsFolder(false);
        f.setFilePath("projects/1/_网核/" + name);
        return f;
    }

    private static ProjectFile reportFile(Long projectId) {
        ProjectFile f = new ProjectFile();
        f.setId(DOC_FILE_ID);
        f.setProjectId(projectId);
        f.setName("尽调报告.docx");
        f.setFileType("docx");
        f.setIsFolder(false);
        return f;
    }

    private static TargetView target(long id, String method) {
        return new TargetView(id, 55L, new FileBrief(55L, "底稿.pdf", "pdf", 3L, false), null, "supports", method, null, null);
    }

    private static LinkView link(long id, String key, String anchorText, String sectionTitle, List<TargetView> targets) {
        return new LinkView(id, key, DOC_FILE_ID, anchorText, "h", "一/（一）", sectionTitle, "active", "human",
                LocalDateTime.of(2026, 8, 21, 10, 0), LocalDateTime.of(2026, 8, 21, 10, 0), targets);
    }

    private static byte[] zipOf(String... names) {
        java.util.LinkedHashMap<String, byte[]> m = new java.util.LinkedHashMap<>();
        for (String n : names) m.put(n, ("bytes-" + n).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
             org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zos =
                     new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(bos)) {
            for (var e : m.entrySet()) {
                var ze = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(e.getKey());
                ze.setSize(e.getValue().length);
                zos.putArchiveEntry(ze);
                zos.write(e.getValue());
                zos.closeArchiveEntry();
            }
            zos.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ 落盘

    @Test
    @DisplayName("落盘到 _网核/<主体>/<站点>-<日期>.<ext>，同名不覆盖而是加副本（网核件是证据）")
    void landsIntoWebVerifyFolder() {
        WebVerifyImportService svc = service();
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null)).thenReturn(List.of());

        var r = svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of(), DOC_FILE_ID,
                zipOf("裁判文书-2026-08-21.png"), "human");

        ArgumentCaptor<List<String>> segs = ArgumentCaptor.forClass(List.class);
        verify(projectFileService).ensureFolderPath(eq(PROJECT_ID), eq(USER_ID), segs.capture());
        assertEquals(List.of("_网核", PARTY), segs.getValue());
        verify(projectFileService).createFile(eq(PROJECT_ID), eq(FOLDER_ID), eq("裁判文书-2026-08-21.png"), eq("png"),
                anyLong(), any(), any(), eq(USER_ID), eq(ProjectFileService.ConflictPolicy.RENAME));
        verify(storageService).save(anyString(), any());
        assertEquals(1, r.landed());
        assertEquals("_网核/" + PARTY + "/裁判文书-2026-08-21.png", r.items().get(0).path());
    }

    @Test
    @DisplayName("metaJson 记 sourceUrl/capturedAt/provider/site（spec §1.2 的三件套 + 站点）")
    void writesMetaJson() {
        String manifest = """
                { "items": [ { "file": "shot.png", "site": "dishonest_executee",
                               "capturedAt": "2026-08-21T10:00:00+08:00",
                               "sourceUrl": "https://example.gov.cn/q" } ] }
                """;
        byte[] zip = zipWith("manifest.json", manifest, "shot.png", "bytes");
        WebVerifyImportService svc = service();
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null)).thenReturn(List.of());

        svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of(), DOC_FILE_ID, zip, "human");

        ArgumentCaptor<ProjectFile> saved = ArgumentCaptor.forClass(ProjectFile.class);
        verify(projectFileRepository).save(saved.capture());
        JsonNode meta = readJson(saved.getValue().getMetaJson());
        assertEquals("https://example.gov.cn/q", meta.get("sourceUrl").asText());
        assertEquals("manual", meta.get("provider").asText());
        assertEquals("dishonest_executee", meta.get("site").asText());
        assertTrue(meta.get("capturedAt").asText().startsWith("2026-08-21"), meta.toString());
    }

    // ------------------------------------------------------------------ 自动挂链

    @Test
    @DisplayName("只挂到 method=web_check 且提到该主体的段落；书面审查段落不挂")
    void linksOnlyWebCheckParagraphs() {
        WebVerifyImportService svc = service();
        LinkView webCheck = link(1L, "EVID_A", "经查询，" + PARTY + "不存在失信记录", "网络核查", List.of(target(11L, "web_check")));
        LinkView written = link(2L, "EVID_B", PARTY + "的营业执照载明注册资本 1000 万元", "主体资格",
                List.of(target(12L, "written_review")));
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null))
                .thenReturn(List.of(webCheck, written));

        var r = svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of(), DOC_FILE_ID,
                zipOf("失信被执行人-2026-08-21.png"), "human");

        verify(evidenceLinkService).addTargets(eq(USER_ID), eq(PROJECT_ID), eq("EVID_A"), anyList(), eq("human"));
        verify(evidenceLinkService, never()).addTargets(any(), any(), eq("EVID_B"), anyList(), any());
        assertEquals(List.of("EVID_A"), r.items().get(0).linkedKeys());
        assertTrue(r.unlinked().isEmpty());
    }

    @Test
    @DisplayName("段落没提这个主体就不挂——宁可不挂也不瞎挂")
    void doesNotLinkParagraphsOfOtherParties() {
        WebVerifyImportService svc = service();
        LinkView other = link(1L, "EVID_A", "经查询，另一家公司不存在失信记录", "网络核查", List.of(target(11L, "web_check")));
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null)).thenReturn(List.of(other));

        var r = svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of(), DOC_FILE_ID,
                zipOf("失信被执行人-2026-08-21.png"), "human");

        verify(evidenceLinkService, never()).addTargets(any(), any(), anyString(), anyList(), any());
        assertEquals(1, r.unlinked().size());
        assertTrue(r.unlinked().get(0).reason().contains("没有"), r.unlinked().get(0).reason());
    }

    @Test
    @DisplayName("多个候选里若有段落提到该站点，只挂到这些段落")
    void narrowsToParagraphsMentioningTheSite() {
        WebVerifyImportService svc = service();
        LinkView shixin = link(1L, "EVID_SX", PARTY + "不是失信被执行人", "网络核查", List.of(target(11L, "web_check")));
        LinkView wenshu = link(2L, "EVID_WS", PARTY + "无涉诉裁判文书", "网络核查", List.of(target(12L, "web_check")));
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null))
                .thenReturn(List.of(shixin, wenshu));

        var r = svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of(), DOC_FILE_ID,
                zipOf("失信被执行人-2026-08-21.png"), "human");

        verify(evidenceLinkService).addTargets(eq(USER_ID), eq(PROJECT_ID), eq("EVID_SX"), anyList(), eq("human"));
        verify(evidenceLinkService, never()).addTargets(any(), any(), eq("EVID_WS"), anyList(), any());
        assertEquals(List.of("EVID_SX"), r.items().get(0).linkedKeys());
    }

    @Test
    @DisplayName("没有段落提到站点时挂到全部候选段落")
    void linksAllCandidatesWhenNoSiteHint() {
        WebVerifyImportService svc = service();
        LinkView a = link(1L, "EVID_A", PARTY + "的网络核查情况如下", "网络核查", List.of(target(11L, "web_check")));
        LinkView b = link(2L, "EVID_B", "本所对" + PARTY + "进行了公开信息检索", "网络核查", List.of(target(12L, "web_check")));
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null)).thenReturn(List.of(a, b));

        var r = svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of(), DOC_FILE_ID,
                zipOf("失信被执行人-2026-08-21.png"), "human");

        assertEquals(List.of("EVID_A", "EVID_B"), r.items().get(0).linkedKeys());
    }

    @Test
    @DisplayName("新建的 target 是 web_check + web 型 locator（带 url 与 capturedAt）")
    void newTargetCarriesWebLocator() {
        String manifest = """
                { "items": [ { "file": "shot.png", "site": "dishonest_executee",
                               "capturedAt": "2026-08-21T10:00:00+08:00",
                               "sourceUrl": "https://example.gov.cn/q", "summary": "未见失信记录" } ] }
                """;
        WebVerifyImportService svc = service();
        LinkView a = link(1L, "EVID_A", PARTY + "不是失信被执行人", "网络核查", List.of(target(11L, "web_check")));
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null)).thenReturn(List.of(a));

        svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of(), DOC_FILE_ID,
                zipWith("manifest.json", manifest, "shot.png", "bytes"), "human");

        ArgumentCaptor<List<TargetInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(evidenceLinkService).addTargets(eq(USER_ID), eq(PROJECT_ID), eq("EVID_A"), cap.capture(), eq("human"));
        TargetInput t = cap.getValue().get(0);
        assertEquals("web_check", t.method());
        assertEquals("supports", t.relation());
        assertEquals("未见失信记录", t.note());
        JsonNode loc = readJson(t.locatorJson());
        assertEquals("web", loc.get("type").asText());
        assertEquals("https://example.gov.cn/q", loc.get("url").asText());
        assertNotNull(loc.get("capturedAt"));
    }

    @Test
    @DisplayName("挂链失败（如链接同期被删）只让那一件进未挂链清单，不影响其余落盘")
    void linkFailureIsReportedNotThrown() {
        WebVerifyImportService svc = service();
        LinkView a = link(1L, "EVID_A", PARTY + "不是失信被执行人", "网络核查", List.of(target(11L, "web_check")));
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null)).thenReturn(List.of(a));
        when(evidenceLinkService.addTargets(any(), any(), anyString(), anyList(), any()))
                .thenThrow(new IllegalArgumentException("链接不存在"));

        var r = svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of(), DOC_FILE_ID,
                zipOf("失信被执行人-2026-08-21.png"), "human");

        assertEquals(1, r.landed());
        assertEquals(1, r.unlinked().size());
        assertTrue(r.unlinked().get(0).reason().contains("链接不存在"), r.unlinked().get(0).reason());
    }

    // ------------------------------------------------------------------ docFileId 定位

    @Test
    @DisplayName("docFileId 缺省且项目里只有一份带证据关联的文档：自动用它")
    void resolvesSoleDocFile() {
        WebVerifyImportService svc = service();
        when(evidenceLinkRepository.findDistinctDocFileIdsByProjectId(PROJECT_ID)).thenReturn(List.of(DOC_FILE_ID));
        LinkView a = link(1L, "EVID_A", PARTY + "不是失信被执行人", "网络核查", List.of(target(11L, "web_check")));
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null)).thenReturn(List.of(a));

        var r = svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of(), null,
                zipOf("失信被执行人-2026-08-21.png"), "human");

        assertEquals(DOC_FILE_ID, r.docFileId());
        assertEquals(List.of("EVID_A"), r.items().get(0).linkedKeys());
    }

    @Test
    @DisplayName("docFileId 缺省且候选不唯一：只落盘，未挂链清单里说清楚原因，不报错也不猜")
    void skipsLinkingWhenDocFileAmbiguous() {
        WebVerifyImportService svc = service();
        when(evidenceLinkRepository.findDistinctDocFileIdsByProjectId(PROJECT_ID)).thenReturn(List.of(10L, 20L));

        var r = svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of(), null,
                zipOf("失信被执行人-2026-08-21.png"), "human");

        assertEquals(1, r.landed());
        assertEquals(1, r.unlinked().size());
        assertTrue(r.unlinked().get(0).reason().contains("docFileId"), r.unlinked().get(0).reason());
        verify(evidenceLinkService, never()).addTargets(any(), any(), anyString(), anyList(), any());
    }

    // ------------------------------------------------------------------ 鉴权与入参

    @Test
    @DisplayName("无写权限直接拒，一个文件都不落")
    void requiresWritePermission() {
        when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(false);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        WebVerifyImportService svc = new WebVerifyImportService(projectMemberService, projectFileService,
                projectFileRepository, storageServiceFactory, evidenceLinkService, evidenceLinkRepository,
                List.of(new ManualWebVerifyProvider(om)), om);

        assertThrows(IllegalArgumentException.class, () -> svc.importArchive(USER_ID, PROJECT_ID, PARTY, null,
                List.of(), DOC_FILE_ID, zipOf("失信被执行人-2026-08-21.png"), "human"));
        verify(projectFileService, never()).ensureFolderPath(any(), any(), anyList());
    }

    @Test
    @DisplayName("docFileId 属于别的项目：拒，不落盘")
    void rejectsCrossProjectDocFile() {
        WebVerifyImportService svc = service();
        when(projectFileRepository.findById(DOC_FILE_ID)).thenReturn(Optional.of(reportFile(OTHER_PROJECT_ID)));

        assertThrows(IllegalArgumentException.class, () -> svc.importArchive(USER_ID, PROJECT_ID, PARTY, null,
                List.of(), DOC_FILE_ID, zipOf("失信被执行人-2026-08-21.png"), "human"));
        verify(projectFileService, never()).createFile(any(), any(), anyString(), anyString(), anyLong(), any(), any(),
                any(), any());
    }

    @Test
    @DisplayName("sites 里写了不认识的 code：报错并列出可用取值，不悄悄当成「其他」")
    void rejectsUnknownSiteCode() {
        WebVerifyImportService svc = service();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of("no_such_site"), DOC_FILE_ID,
                        zipOf("失信被执行人-2026-08-21.png"), "human"));
        assertTrue(e.getMessage().contains("credit_publicity"), e.getMessage());
    }

    @Test
    @DisplayName("包里没有可导入的网核件：明确报错，不返回一个「成功但零件」的空结果")
    void rejectsEmptyArchive() {
        WebVerifyImportService svc = service();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.importArchive(USER_ID, PROJECT_ID, PARTY, null, List.of(WebVerifySite.ENV_PENALTY.code()),
                        DOC_FILE_ID, zipOf("失信被执行人-2026-08-21.png"), "human"));
        assertTrue(e.getMessage().contains("没有"), e.getMessage());
    }

    @Test
    @DisplayName("主体名含路径分隔符：清洗后落盘，不许穿出 _网核 目录")
    void sanitizesPartyNameForFolder() {
        WebVerifyImportService svc = service();
        when(evidenceLinkService.listByDoc(any(), any(), any(), any(), any())).thenReturn(List.of());

        svc.importArchive(USER_ID, PROJECT_ID, "../某某/公司", null, List.of(), DOC_FILE_ID,
                zipOf("失信被执行人-2026-08-21.png"), "human");

        ArgumentCaptor<List<String>> segs = ArgumentCaptor.forClass(List.class);
        verify(projectFileService).ensureFolderPath(eq(PROJECT_ID), eq(USER_ID), segs.capture());
        assertEquals(2, segs.getValue().size());
        assertTrue(!segs.getValue().get(1).contains("/") && !segs.getValue().get(1).contains(".."),
                segs.getValue().toString());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode readJson(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] zipWith(String... nameThenContent) {
        java.util.LinkedHashMap<String, byte[]> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < nameThenContent.length; i += 2) {
            m.put(nameThenContent[i], nameThenContent[i + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
             org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zos =
                     new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(bos)) {
            for (var e : m.entrySet()) {
                var ze = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(e.getKey());
                ze.setSize(e.getValue().length);
                zos.putArchiveEntry(ze);
                zos.write(e.getValue());
                zos.closeArchiveEntry();
            }
            zos.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
