package com.checkba.service;

import com.checkba.model.entity.EvidenceLink;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.StyleProfileResolver;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.service.evidence.EvidenceLinkViews.FileBrief;
import com.checkba.service.evidence.EvidenceLinkViews.LinkView;
import com.checkba.service.evidence.EvidenceLinkViews.TargetView;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.util.style.DocxProfileReader;
import com.checkba.util.style.StyleProfile;
import com.checkba.util.style.StyleProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * dd-exports 三份交付件（底稿目录/查验计划/缺口清单）：分组正确性（含空数据）、
 * docx 套用项目画像、xlsx 列头、同名覆盖不产生副本、跨项目访问被拒。
 */
@ExtendWith(MockitoExtension.class)
class DdExportServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long OTHER_PROJECT_ID = 9L;
    private static final Long USER_ID = 5L;
    private static final Long DOC_FILE_ID = 100L;
    private static final Long FOLDER_ID = 200L;

    @Mock private EvidenceLinkService evidenceLinkService;
    @Mock private EvidenceLinkRepository evidenceLinkRepository;
    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private ProjectFileService projectFileService;
    @Mock private StorageServiceFactory storageServiceFactory;
    @Mock private StorageService storageService;
    @Mock private DocumentTextService documentTextService;
    @Mock private StyleProfileResolver styleProfileResolver;
    @Mock private ProjectMemberService projectMemberService;

    private final ObjectMapper om = new ObjectMapper();

    private DdExportService service() {
        return new DdExportService(evidenceLinkService, evidenceLinkRepository, projectFileRepository,
                projectFileService, storageServiceFactory, documentTextService, styleProfileResolver,
                projectMemberService, om);
    }

    private static ProjectFile reportFile() {
        ProjectFile f = new ProjectFile();
        f.setId(DOC_FILE_ID);
        f.setProjectId(PROJECT_ID);
        f.setName("尽调报告.docx");
        f.setFileType("docx");
        f.setIsFolder(false);
        f.setFilePath("projects/1/尽调报告.docx");
        return f;
    }

    private static FileBrief brief(long id, String name, String type) {
        return new FileBrief(id, name, type, 3L, false);
    }

    private static TargetView target(long id, long fileId, FileBrief file, String method) {
        return new TargetView(id, fileId, file, null, "supports", method, null, null);
    }

    private static LinkView link(long id, String key, String sectionPath, String sectionTitle, String anchorText,
                                 String status, List<TargetView> targets) {
        return new LinkView(id, key, DOC_FILE_ID, anchorText, "h", sectionPath, sectionTitle, status, "human",
                LocalDateTime.of(2026, 8, 21, 10, 0), LocalDateTime.of(2026, 8, 21, 10, 0), targets);
    }

    // ------------------------------------------------------------ 分组正确性：底稿目录

    @Test
    @DisplayName("底稿目录：按 sectionPath 一级分组，同一链接的多个 target 各占一行")
    void groupDocketByChapter() {
        FileBrief license = brief(11L, "营业执照.pdf", "pdf");
        FileBrief lease = brief(12L, "租赁合同.pdf", "pdf");
        List<LinkView> links = List.of(
                link(1L, "EVID_A", "一/（一）", "主体资格", "根据《营业执照》记载", "active",
                        List.of(target(101L, 11L, license, "written_review"))),
                link(2L, "EVID_B", "一/（二）", "经营场所", "租赁合同显示场所合法", "unverified",
                        List.of(target(102L, 11L, license, "written_review"), target(103L, 12L, lease, "web_check"))),
                link(3L, "EVID_C", "二/（一）", "资产情况", "另一章的陈述", "active",
                        List.of(target(104L, 12L, lease, "third_party")))
        );

        List<DdExportService.DocketRow> rows = DdExportService.groupDocket(links);

        assertEquals(4, rows.size());
        // 章节一：先出现的两条链接（EVID_A 的执照 + EVID_B 的执照与租赁合同）都归入「一」
        assertEquals("一", rows.get(0).chapter());
        assertEquals(11L, rows.get(0).fileId());
        assertEquals("一", rows.get(1).chapter());
        assertEquals(11L, rows.get(1).fileId());
        assertEquals("一", rows.get(2).chapter());
        assertEquals(12L, rows.get(2).fileId());
        assertEquals("web_check", rows.get(2).method());
        assertEquals("unverified", rows.get(2).status());
        // 章节二
        assertEquals("二", rows.get(3).chapter());
        assertEquals(12L, rows.get(3).fileId());
        assertEquals("third_party", rows.get(3).method());
    }

    @Test
    @DisplayName("底稿目录：sectionPath 为空归入「未分类」；反向链接文字截断 80 字")
    void groupDocketUnclassifiedAndTruncate() {
        String longText = "字".repeat(120);
        List<LinkView> links = List.of(
                link(1L, "EVID_A", null, null, longText, "active",
                        List.of(target(101L, 11L, brief(11L, "文件.pdf", "pdf"), "written_review")))
        );
        List<DdExportService.DocketRow> rows = DdExportService.groupDocket(links);
        assertEquals(1, rows.size());
        assertEquals("未分类", rows.get(0).chapter());
        assertEquals(80, rows.get(0).anchorText().length());
    }

    @Test
    @DisplayName("底稿目录：空数据不抛异常，返回空列表")
    void groupDocketEmpty() {
        assertTrue(DdExportService.groupDocket(List.of()).isEmpty());
        assertTrue(DdExportService.groupDocket(null).isEmpty());
    }

    // ------------------------------------------------------------ 分组正确性：查验计划

    @Test
    @DisplayName("查验计划：按 method 枚举固定顺序归类，未注明的排最后")
    void groupVerifyPlanByMethod() {
        FileBrief f1 = brief(11L, "A.pdf", "pdf");
        FileBrief f2 = brief(12L, "B.pdf", "pdf");
        List<LinkView> links = List.of(
                link(1L, "EVID_A", "一", "标题一", "陈述一", "active",
                        List.of(target(101L, 11L, f1, "interview"))),
                link(2L, "EVID_B", "一", "标题一", "陈述二", "active",
                        List.of(target(102L, 12L, f2, "written_review"))),
                link(3L, "EVID_C", "二", "标题二", "陈述三", "active",
                        List.of(target(103L, 11L, f1, null)))
        );
        List<DdExportService.VerifyPlanRow> rows = DdExportService.groupVerifyPlan(links);
        assertEquals(3, rows.size());
        // written_review 排在 interview 之前（固定枚举顺序），method 为空的排最后
        assertEquals("written_review", rows.get(0).method());
        assertEquals(12L, rows.get(0).fileId());
        assertEquals("interview", rows.get(1).method());
        assertEquals(11L, rows.get(1).fileId());
        assertEquals("", rows.get(2).method());
        assertEquals(11L, rows.get(2).fileId());
    }

    @Test
    @DisplayName("查验计划：空数据返回空列表")
    void groupVerifyPlanEmpty() {
        assertTrue(DdExportService.groupVerifyPlan(List.of()).isEmpty());
        assertTrue(DdExportService.groupVerifyPlan(null).isEmpty());
    }

    // ------------------------------------------------------------ 分组正确性：缺口清单

    @Test
    @org.junit.jupiter.api.DisplayName("缺口清单：模型写成「【待补充证据清单】」也要收（严格正则会静默漏掉真实缺口）")
    void gapsCatchLooseWordings() {
        String body = String.join("\n",
                "第一段。【待补：收购人 的 营业执照（用于主体资格）】",
                "第二段。【待补充证据清单：一致行动人董监高选举决议】",
                "第三段。【待补 上市公司前十名股东名册】",
                "第四段。【待补充：履行到期债务证明】");
        List<DdExportService.GapRow> rows = DdExportService.buildGaps(body, List.of());
        assertEquals(4, rows.size(), "四种写法都要收: " + rows);
        assertTrue(rows.stream().anyMatch(r -> r.content().contains("营业执照")), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> r.content().contains("选举决议")), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> r.content().contains("股东名册")), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> r.content().contains("到期债务")), rows.toString());
    }

    @Test
    @DisplayName("缺口清单：正则抽取【待补：…】占位符 + 项目里全部 orphan 状态的 link")
    void buildGapsExtractsPlaceholdersAndOrphans() {
        String body = "公司设立情况如上所述。注册资本【待补：验资报告】，控股股东【待补：持股比例】情况见附表。";
        EvidenceLink orphan = new EvidenceLink();
        orphan.setId(9L);
        orphan.setLinkKey("EVID_X");
        orphan.setAnchorText("已失效的一段陈述");
        orphan.setSectionPath("三/（一）");
        orphan.setSectionTitle("历史沿革");
        orphan.setStatus(EvidenceLink.STATUS_ORPHAN);

        List<DdExportService.GapRow> rows = DdExportService.buildGaps(body, List.of(orphan));

        assertEquals(3, rows.size());
        assertEquals("占位符", rows.get(0).type());
        assertTrue(rows.get(0).content().contains("验资报告"), rows.get(0).content());
        assertEquals("占位符", rows.get(1).type());
        assertTrue(rows.get(1).content().contains("持股比例"), rows.get(1).content());
        assertEquals("孤儿关联", rows.get(2).type());
        assertEquals("已失效的一段陈述", rows.get(2).content());
        assertTrue(rows.get(2).location().contains("历史沿革"), rows.get(2).location());
        assertEquals("EVID_X", rows.get(2).note());
    }

    @Test
    @DisplayName("缺口清单：半角冒号变体也能命中；无占位符无 orphan 时返回空列表")
    void buildGapsHalfWidthColonAndEmpty() {
        List<DdExportService.GapRow> rows = DdExportService.buildGaps("金额【待补:审计确认】。", List.of());
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).content().contains("审计确认"));

        assertTrue(DdExportService.buildGaps("", List.of()).isEmpty());
        assertTrue(DdExportService.buildGaps(null, null).isEmpty());
    }

    // ------------------------------------------------------------ docx 套用项目画像

    @Test
    @DisplayName("导出 docx：调用 StyleProfileResolver.resolve 拿到的项目画像被真实套用（不是 house-default）")
    void docxUsesProjectStyleProfile() throws Exception {
        when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);
        when(projectFileRepository.findById(DOC_FILE_ID)).thenReturn(Optional.of(reportFile()));
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null)).thenReturn(List.of());

        StyleProfile custom = StyleProfiles.houseDefault().merge(StyleProfiles.parse(
                "{\"body\": {\"font\": {\"eastAsia\": \"SimSun\", \"western\": \"Times New Roman\"}}}"));
        when(styleProfileResolver.resolve(PROJECT_ID, null)).thenReturn(custom);

        ProjectFile folder = folder();
        when(projectFileService.ensureFolderPath(PROJECT_ID, USER_ID, List.of(DdExportService.DELIVERABLE_FOLDER)))
                .thenReturn(folder);
        when(projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(PROJECT_ID, FOLDER_ID, "底稿目录.docx"))
                .thenReturn(Optional.empty());
        ProjectFile created = new ProjectFile();
        created.setId(300L);
        created.setFilePath("projects/1/_交付件/底稿目录.docx");
        when(projectFileService.createFile(eq(PROJECT_ID), eq(FOLDER_ID), eq("底稿目录.docx"), eq("docx"),
                anyLong(), isNull(), isNull(), eq(USER_ID))).thenReturn(created);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);

        DdExportService.ExportResult result = service().export(USER_ID, PROJECT_ID, DOC_FILE_ID, "docket", "docx");

        assertEquals(300L, result.fileId());
        assertEquals("_交付件/底稿目录.docx", result.path());
        assertEquals(0, result.rows());

        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(storageService).save(eq(created.getFilePath()), captor.capture());
        byte[] bytes = captor.getValue().readAllBytes();
        StyleProfile back = DocxProfileReader.read(new ByteArrayInputStream(bytes));
        assertEquals("SimSun", back.body().font().eastAsia(), "应套用 StyleProfileResolver 给的项目画像，而不是 house-default（楷体_GB2312）");
    }

    // ------------------------------------------------------------ xlsx 列头

    @Test
    @DisplayName("导出 xlsx：列头与预期一致")
    void xlsxHeaders() throws Exception {
        when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);
        when(projectFileRepository.findById(DOC_FILE_ID)).thenReturn(Optional.of(reportFile()));
        FileBrief license = brief(11L, "营业执照.pdf", "pdf");
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null)).thenReturn(List.of(
                link(1L, "EVID_A", "一", "主体资格", "根据营业执照", "active",
                        List.of(target(101L, 11L, license, "written_review")))
        ));
        when(projectFileRepository.findAllById(any())).thenReturn(List.of());

        ProjectFile folder = folder();
        when(projectFileService.ensureFolderPath(PROJECT_ID, USER_ID, List.of(DdExportService.DELIVERABLE_FOLDER)))
                .thenReturn(folder);
        when(projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(PROJECT_ID, FOLDER_ID, "底稿目录.xlsx"))
                .thenReturn(Optional.empty());
        ProjectFile created = new ProjectFile();
        created.setId(301L);
        created.setFilePath("projects/1/_交付件/底稿目录.xlsx");
        when(projectFileService.createFile(eq(PROJECT_ID), eq(FOLDER_ID), eq("底稿目录.xlsx"), eq("xlsx"),
                anyLong(), isNull(), isNull(), eq(USER_ID))).thenReturn(created);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);

        DdExportService.ExportResult result = service().export(USER_ID, PROJECT_ID, DOC_FILE_ID, "docket", "xlsx");
        assertEquals(1, result.rows());

        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(storageService).save(eq(created.getFilePath()), captor.capture());
        try (XSSFWorkbook wb = new XSSFWorkbook(captor.getValue())) {
            Row header = wb.getSheetAt(0).getRow(0);
            List<String> headers = List.of("章节", "底稿文件", "编号", "类型", "引用段落路径", "引用段落标题", "引用文字", "查验方式", "状态");
            for (int i = 0; i < headers.size(); i++) {
                Cell c = header.getCell(i);
                assertEquals(headers.get(i), c.getStringCellValue(), "第 " + i + " 列列头");
            }
        }
    }

    // ------------------------------------------------------------ 同名覆盖不产生副本

    @Test
    @DisplayName("同名再次导出：就地覆盖同一个 fileId，不新建文件、不生成「(1)」副本")
    void overwritesInPlace() throws Exception {
        when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);
        when(projectFileRepository.findById(DOC_FILE_ID)).thenReturn(Optional.of(reportFile()));
        when(evidenceLinkService.listByDoc(USER_ID, PROJECT_ID, DOC_FILE_ID, null, null)).thenReturn(List.of());
        when(styleProfileResolver.resolve(PROJECT_ID, null)).thenReturn(StyleProfiles.houseDefault());

        ProjectFile folder = folder();
        when(projectFileService.ensureFolderPath(PROJECT_ID, USER_ID, List.of(DdExportService.DELIVERABLE_FOLDER)))
                .thenReturn(folder);
        ProjectFile existing = new ProjectFile();
        existing.setId(400L);
        existing.setFilePath("projects/1/_交付件/底稿目录.docx");
        when(projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(PROJECT_ID, FOLDER_ID, "底稿目录.docx"))
                .thenReturn(Optional.of(existing));
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);

        DdExportService svc = service();
        DdExportService.ExportResult r1 = svc.export(USER_ID, PROJECT_ID, DOC_FILE_ID, "docket", "docx");
        DdExportService.ExportResult r2 = svc.export(USER_ID, PROJECT_ID, DOC_FILE_ID, "docket", "docx");

        assertEquals(400L, r1.fileId());
        assertEquals(400L, r2.fileId());
        verify(projectFileService, never()).createFile(anyLong(), anyLong(), anyString(), anyString(),
                anyLong(), any(), any(), anyLong());
        verify(storageService, times(2)).save(eq(existing.getFilePath()), any(InputStream.class));
    }

    // ------------------------------------------------------------ 跨项目访问被拒

    @Test
    @DisplayName("docFileId 属于别的项目：拒绝，且不触碰证据链接与存储")
    void rejectsCrossProjectFile() {
        when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);
        ProjectFile other = reportFile();
        other.setProjectId(OTHER_PROJECT_ID);
        when(projectFileRepository.findById(DOC_FILE_ID)).thenReturn(Optional.of(other));

        assertThrows(IllegalArgumentException.class,
                () -> service().export(USER_ID, PROJECT_ID, DOC_FILE_ID, "docket", "docx"));

        verifyNoInteractions(evidenceLinkService, storageServiceFactory, documentTextService);
        verify(projectFileService, never()).ensureFolderPath(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("非项目成员（无写权限）：拒绝")
    void rejectsWithoutWritePermission() {
        when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> service().export(USER_ID, PROJECT_ID, DOC_FILE_ID, "docket", "docx"));

        verifyNoInteractions(evidenceLinkService, projectFileRepository, storageServiceFactory);
    }

    @Test
    @DisplayName("kind/format 非法参数直接拒绝")
    void rejectsIllegalKindOrFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> service().export(USER_ID, PROJECT_ID, DOC_FILE_ID, "not-a-kind", "docx"));
        assertThrows(IllegalArgumentException.class,
                () -> service().export(USER_ID, PROJECT_ID, DOC_FILE_ID, "docket", "pdf"));
    }

    private static ProjectFile folder() {
        ProjectFile f = new ProjectFile();
        f.setId(FOLDER_ID);
        f.setProjectId(PROJECT_ID);
        f.setName(DdExportService.DELIVERABLE_FOLDER);
        f.setIsFolder(true);
        f.setFileType("folder");
        return f;
    }
}
