package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 新建文件必须落在用户指定的文件夹里（dev-board#465）。
 *
 * <p>病灶：doc_start_stream / sheet_create_file 把 parentId 写死成 null、物理路径手拼成
 * {@code projects/{pid}/{name}}，用户说「放进 08-尽调清单与工作底稿」，文件必然出现在项目根目录，
 * 而且没有任何报错——AI 还照常汇报「已创建」。
 *
 * <p>本测试断言修好后的行为：parentFolderId 真的传到 createFile、物理文件真的写进那个文件夹、
 * 非法 parentFolderId 明确报错（绝不静默落根目录、也不自动建文件夹），以及原有的路径穿越围栏还在。
 */
class DocumentEditToolsNewFileFolderTest {

    private static final long PROJECT_ID = 42L;
    private static final long FOLDER_ID = 7L;

    private record Harness(DocumentEditTools tools, ProjectFileService fileService,
                           ProjectFileRepository repo, Path projectRoot) {}

    /**
     * projectRoot = 临时目录；resolve("projects/42/xxx") = projectRoot/xxx，
     * 与 ProjectStorageResolver 真实行为同构（含越界围栏）。
     */
    private static Harness harness(Path root) {
        ProjectFileService fileService = Mockito.mock(ProjectFileService.class);
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        ProjectStorageResolver resolver = Mockito.mock(ProjectStorageResolver.class);

        when(resolver.projectRoot(anyLong())).thenReturn(root);
        when(resolver.resolve(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String prefix = "projects/" + PROJECT_ID + "/";
            assertTrue(key.startsWith(prefix), "存储键必须落在本项目命名空间: " + key);
            Path p = root.resolve(key.substring(prefix.length())).normalize();
            assertTrue(p.startsWith(root.normalize()), "越出项目根: " + key);
            return p;
        });

        when(bridge.getCurrentConversationId()).thenReturn("conv-1");
        when(bridge.executeEditorCommand(anyString(), any())).thenReturn("{\"success\":true}");

        // createFile：按父文件夹层级生成 filePath，与 ProjectFileService.buildPhysicalPath 同构
        when(fileService.createFile(anyLong(), any(), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), any())).thenAnswer(inv -> {
            Long parentId = inv.getArgument(1);
            String name = inv.getArgument(2);
            ProjectFile f = new ProjectFile();
            f.setId(999L);
            f.setProjectId(PROJECT_ID);
            f.setParentId(parentId);
            f.setName(name);
            f.setFileType(inv.getArgument(3));
            f.setWpsFileId(inv.getArgument(6));
            f.setFilePath(parentId == null
                    ? "projects/" + PROJECT_ID + "/" + name
                    : "projects/" + PROJECT_ID + "/08-尽调清单与工作底稿/" + name);
            return f;
        });
        when(repo.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectFile folder = new ProjectFile();
        folder.setId(FOLDER_ID);
        folder.setProjectId(PROJECT_ID);
        folder.setIsFolder(true);
        folder.setName("08-尽调清单与工作底稿");
        when(repo.findById(FOLDER_ID)).thenReturn(Optional.of(folder));

        DocumentEditTools tools = new DocumentEditTools(fileService, repo, bridge, resolver, null, null, null, null);
        return new Harness(tools, fileService, repo, root);
    }

    @Test
    @DisplayName("doc_start_stream：给了 parentFolderId 就必须建在那个文件夹下（本卡的症状）")
    void docStartStreamCreatesInsideTheNamedFolder(@TempDir Path dir) {
        Harness h = harness(dir);

        String out = h.tools().doc_start_stream(null, "核查备忘录-注册资本与出资.docx", PROJECT_ID, FOLDER_ID);
        assertTrue(out.startsWith("文档流式写入模式已激活"), "应当正常激活流式写入，实际：" + out);

        ArgumentCaptor<Long> parentId = ArgumentCaptor.forClass(Long.class);
        verify(h.fileService()).createFile(eq(PROJECT_ID), parentId.capture(), anyString(), eq("docx"),
                any(), any(), anyString(), anyLong(), any());
        assertEquals(FOLDER_ID, parentId.getValue(), "parentId 必须是用户指名的文件夹，不能是 null");

        Path expected = dir.resolve("08-尽调清单与工作底稿").resolve("核查备忘录-注册资本与出资.docx");
        assertTrue(Files.exists(expected), "空白 docx 必须写进文件夹里，实际不在: " + expected);
        assertTrue(!Files.exists(dir.resolve("核查备忘录-注册资本与出资.docx")), "不许同时落在项目根目录");
    }

    @Test
    @DisplayName("doc_start_stream：不给 parentFolderId 仍落项目根目录（老用法不变）")
    void docStartStreamWithoutFolderStillLandsInProjectRoot(@TempDir Path dir) {
        Harness h = harness(dir);

        h.tools().doc_start_stream(null, "法律意见书.docx", PROJECT_ID, null);

        verify(h.fileService()).createFile(eq(PROJECT_ID), eq(null), anyString(), eq("docx"),
                any(), any(), anyString(), anyLong(), any());
        assertTrue(Files.exists(dir.resolve("法律意见书.docx")));
    }

    @Test
    @DisplayName("parentFolderId 不存在：明确报错，绝不静默落根目录、也不自动建文件夹")
    void unknownFolderIsRejectedRatherThanFallingBackToRoot(@TempDir Path dir) {
        Harness h = harness(dir);
        when(h.repo().findById(12345L)).thenReturn(Optional.empty());

        String out = h.tools().doc_start_stream(null, "尽调报告.docx", PROJECT_ID, 12345L);

        assertTrue(out.startsWith("Error:"), "必须报错，实际：" + out);
        assertTrue(out.contains("list_project_folders"), "报错要告诉模型怎么拿正确的 id，实际：" + out);
        verify(h.fileService(), never()).createFile(anyLong(), any(), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), any());
        assertNull(dir.toFile().list().length == 0 ? null : "有文件被创建", "报错时不许在磁盘上留下任何文件");
    }

    @Test
    @DisplayName("parentFolderId 指向文件 / 别的项目的文件夹：一律拒绝")
    void nonFolderAndCrossProjectFolderAreRejected(@TempDir Path dir) {
        Harness h = harness(dir);

        ProjectFile notAFolder = new ProjectFile();
        notAFolder.setId(21L);
        notAFolder.setProjectId(PROJECT_ID);
        notAFolder.setIsFolder(false);
        notAFolder.setName("章程.docx");
        when(h.repo().findById(21L)).thenReturn(Optional.of(notAFolder));

        String out = h.tools().doc_start_stream(null, "尽调报告.docx", PROJECT_ID, 21L);
        assertTrue(out.startsWith("Error:") && out.contains("文件夹"), "实际：" + out);

        ProjectFile otherProject = new ProjectFile();
        otherProject.setId(22L);
        otherProject.setProjectId(PROJECT_ID + 1);
        otherProject.setIsFolder(true);
        otherProject.setName("别家项目的文件夹");
        when(h.repo().findById(22L)).thenReturn(Optional.of(otherProject));

        String out2 = h.tools().doc_start_stream(null, "尽调报告.docx", PROJECT_ID, 22L);
        assertTrue(out2.startsWith("Error:") && out2.contains("不属于"), "实际：" + out2);

        verify(h.fileService(), never()).createFile(anyLong(), any(), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("路径穿越围栏仍在：文件名里带 ../ 一律拒绝，不许落进别家项目目录")
    void pathTraversalFenceStillHolds(@TempDir Path dir) {
        Harness h = harness(dir);

        String out = h.tools().doc_start_stream(null, "../43/补充协议.docx", PROJECT_ID, null);

        assertTrue(out.startsWith("Error:") && out.contains("越出项目目录"), "实际：" + out);
        verify(h.fileService(), never()).createFile(anyLong(), any(), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("sheet_create_file 是同一条路：parentFolderId 同样生效")
    void sheetCreateFileHonoursTheFolderToo(@TempDir Path dir) {
        Harness h = harness(dir);

        String out = h.tools().sheet_create_file("费用明细表", PROJECT_ID, FOLDER_ID);
        assertTrue(out.startsWith("已创建空白表格文件"), "实际：" + out);

        ArgumentCaptor<Long> parentId = ArgumentCaptor.forClass(Long.class);
        verify(h.fileService()).createFile(eq(PROJECT_ID), parentId.capture(), eq("费用明细表.xlsx"), eq("xlsx"),
                any(), any(), anyString(), anyLong(), any());
        assertEquals(FOLDER_ID, parentId.getValue());
        assertTrue(Files.exists(dir.resolve("08-尽调清单与工作底稿").resolve("费用明细表.xlsx")));
    }

    @Test
    @DisplayName("同名不再覆盖：走 ConflictPolicy.RENAME（「已存在则加 (n)」策略保留）")
    void duplicateNamesGoThroughRenamePolicy(@TempDir Path dir) {
        Harness h = harness(dir);

        h.tools().doc_start_stream(null, "法律意见书.docx", PROJECT_ID, null);

        ArgumentCaptor<ProjectFileService.ConflictPolicy> policy =
                ArgumentCaptor.forClass(ProjectFileService.ConflictPolicy.class);
        verify(h.fileService()).createFile(anyLong(), any(), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), policy.capture());
        assertEquals(ProjectFileService.ConflictPolicy.RENAME, policy.getValue());
        // createOrUpdateFile 会就地更新同名行（等于覆盖用户已有的文档），新路径不许再走它
        verify(h.fileService(), never()).createOrUpdateFile(anyLong(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong());
    }
}
