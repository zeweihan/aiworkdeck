package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.evidence.webverify.WebVerifyImportService;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * web_verify_import 工具层：fileId 缺省定位（唯一 zip 自动用、零/多个报错列候选）、
 * 跨项目 fileId 拒绝、未挂链清单出现在摘要里、Service 错误原样透传。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebVerifyToolsTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 5L;
    private static final String PARTY = "某某科技有限公司";

    @Mock private WebVerifyImportService webVerifyImportService;
    @Mock private ProjectFileService projectFileService;
    @Mock private ProjectFileRepository projectFileRepository;

    private WebVerifyTools tools() {
        return new WebVerifyTools(webVerifyImportService, projectFileService, projectFileRepository);
    }

    private static ProjectFile zipFile(long id, String name) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(PROJECT_ID);
        f.setName(name);
        f.setFileType("zip");
        f.setIsFolder(false);
        f.setIsDeleted(false);
        return f;
    }

    private static WebVerifyImportService.ImportResult result(List<WebVerifyImportService.UnlinkedItem> unlinked) {
        return new WebVerifyImportService.ImportResult(PARTY, "manual", 100L, 1,
                List.of(new WebVerifyImportService.LandedItem(300L, "_网核/" + PARTY + "/裁判文书-2026-08-21.png",
                        "judgment_docs", "裁判文书", "2026-08-21T00:00:00", null, null,
                        unlinked.isEmpty() ? List.of("EVID_A") : List.of())),
                unlinked);
    }

    @Test
    @DisplayName("fileId 显式给出：读该文件字节并透传，createdByKind=ai")
    void explicitFileId() throws Exception {
        when(projectFileRepository.findById(7L)).thenReturn(Optional.of(zipFile(7L, "网核.zip")));
        when(projectFileService.getFileBytes(7L)).thenReturn(new byte[]{1, 2, 3});
        when(webVerifyImportService.importArchive(eq(USER_ID), eq(PROJECT_ID), eq(PARTY), any(), anyList(), any(),
                any(), eq("ai"))).thenReturn(result(List.of()));

        String out = tools().web_verify_import(PARTY, 7L, null, null, null, PROJECT_ID, USER_ID);

        assertTrue(out.contains("已导入 1 件"), out);
        assertTrue(out.contains("EVID_A"), out);
        verify(projectFileRepository, never()).findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(any());
    }

    @Test
    @DisplayName("fileId 缺省且项目里只有一个 zip：自动用它")
    void resolvesSoleZip() throws Exception {
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT_ID))
                .thenReturn(List.of(zipFile(7L, "网核.zip")));
        when(projectFileRepository.findById(7L)).thenReturn(Optional.of(zipFile(7L, "网核.zip")));
        when(projectFileService.getFileBytes(7L)).thenReturn(new byte[]{1, 2, 3});
        when(webVerifyImportService.importArchive(any(), any(), any(), any(), anyList(), any(), any(), anyString()))
                .thenReturn(result(List.of()));

        String out = tools().web_verify_import(PARTY, null, null, null, null, PROJECT_ID, USER_ID);
        assertTrue(out.contains("已导入 1 件"), out);
    }

    @Test
    @DisplayName("fileId 缺省且项目里没有 zip：报错并说清「本工具不联网」")
    void errorsWhenNoZip() {
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT_ID))
                .thenReturn(List.of());
        String out = tools().web_verify_import(PARTY, null, null, null, null, PROJECT_ID, USER_ID);
        assertTrue(out.startsWith("Error"), out);
        assertTrue(out.contains("不联网"), out);
    }

    @Test
    @DisplayName("fileId 缺省且项目里多个 zip：报错并列出候选，不擅自挑一个")
    void errorsWhenMultipleZips() {
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT_ID))
                .thenReturn(List.of(zipFile(7L, "网核A.zip"), zipFile(8L, "网核B.zip")));
        String out = tools().web_verify_import(PARTY, null, null, null, null, PROJECT_ID, USER_ID);
        assertTrue(out.startsWith("Error"), out);
        assertTrue(out.contains("网核A.zip") && out.contains("网核B.zip"), out);
        verify(webVerifyImportService, never()).importArchive(any(), any(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("fileId 指向别的项目：拒绝，不读字节")
    void rejectsCrossProjectFile() throws Exception {
        ProjectFile other = zipFile(7L, "网核.zip");
        other.setProjectId(9L);
        when(projectFileRepository.findById(7L)).thenReturn(Optional.of(other));

        String out = tools().web_verify_import(PARTY, 7L, null, null, null, PROJECT_ID, USER_ID);
        assertTrue(out.startsWith("Error"), out);
        verify(projectFileService, never()).getFileBytes(any());
    }

    @Test
    @DisplayName("未挂链清单进摘要——别让模型以为「导入成功 = 都挂上了」")
    void reportsUnlinkedItems() throws Exception {
        when(projectFileRepository.findById(7L)).thenReturn(Optional.of(zipFile(7L, "网核.zip")));
        when(projectFileService.getFileBytes(7L)).thenReturn(new byte[]{1, 2, 3});
        when(webVerifyImportService.importArchive(any(), any(), any(), any(), anyList(), any(), any(), anyString()))
                .thenReturn(result(List.of(new WebVerifyImportService.UnlinkedItem(300L,
                        "_网核/" + PARTY + "/裁判文书-2026-08-21.png", "judgment_docs", "报告里没有提到该主体的网络核查段落"))));

        String out = tools().web_verify_import(PARTY, 7L, null, null, null, PROJECT_ID, USER_ID);
        assertTrue(out.contains("未挂链 1 件"), out);
        assertTrue(out.contains("报告里没有提到该主体的网络核查段落"), out);
    }

    @Test
    @DisplayName("Service 抛出的错误原样透传给模型")
    void propagatesServiceError() throws Exception {
        when(projectFileRepository.findById(7L)).thenReturn(Optional.of(zipFile(7L, "网核.zip")));
        when(projectFileService.getFileBytes(7L)).thenReturn(new byte[]{1, 2, 3});
        when(webVerifyImportService.importArchive(any(), any(), any(), any(), anyList(), any(), any(), anyString()))
                .thenThrow(new IllegalArgumentException("网核压缩包含非法路径（疑似路径穿越）: ../x"));

        String out = tools().web_verify_import(PARTY, 7L, null, null, null, PROJECT_ID, USER_ID);
        assertEquals("Error: 网核压缩包含非法路径（疑似路径穿越）: ../x", out);
    }

    @Test
    @DisplayName("sites 逗号分隔（认全角逗号）")
    void splitsSites() {
        assertEquals(List.of(), WebVerifyTools.splitSites(null));
        assertEquals(List.of("a", "b"), WebVerifyTools.splitSites("a, b"));
        assertEquals(List.of("a", "b"), WebVerifyTools.splitSites("a，b"));
    }

    @Test
    @DisplayName("工具描述里列全了站点 code，且写明不联网（枚举加站点忘改描述会红）")
    void toolDescriptionListsAllSitesAndSaysOffline() throws Exception {
        Method m = WebVerifyTools.class.getMethod("web_verify_import", String.class, Long.class, String.class,
                String.class, Long.class, Long.class, Long.class);
        String desc = String.join(" ", m.getAnnotation(Tool.class).value());
        for (String code : WebVerifyTools.siteCodes()) {
            assertTrue(desc.contains(code), "工具描述漏了站点 " + code);
        }
        assertTrue(desc.contains("不联网"), desc);
    }
}
