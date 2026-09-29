// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * write_file 必须真的把文件登记进项目库。
 *
 * <p>病灶：工具描述写着 "Registers the file in the project database for editor access"、
 * 参数说明写着 "Project ID (Required for DB registration)"，方法体里却只有一段
 * 「Register in DB so Agent "owns" it」的**注释**，一行注册代码都没有。
 *
 * <p>后果：文件躺在项目目录里但没有 project_file 行——文件树看不见、编辑器打不开、
 * 后续工具拿不到 fileId，而模型已经照着返回值向用户报告「文件已创建」。
 * 用户看到的是「AI 说建好了，文件树里没有」。
 */
class WriteFileRegistrationTest {

    private static final long PROJECT_ID = 7L;

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    private record Harness(FileTools tools, ProjectFileService fileService,
                           EditorBridgeService bridge, Path projectRoot) {}

    private static Harness harness(Path root) {
        ProjectFileService fileService = Mockito.mock(ProjectFileService.class);
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        ProjectStorageResolver resolver = Mockito.mock(ProjectStorageResolver.class);
        when(resolver.projectRoot(anyLong())).thenReturn(root);

        ProjectFile saved = new ProjectFile();
        saved.setId(4242L);
        when(fileService.createOrUpdateFile(anyLong(), any(), anyString(), anyString(),
                anyLong(), anyString(), any(), anyLong())).thenReturn(saved);

        FileTools tools = new FileTools(fileService, null, bridge, null, resolver, null, null, null, null);
        ProjectContextHolder.setProjectId(String.valueOf(PROJECT_ID));
        return new Harness(tools, fileService, bridge, root);
    }

    @Test
    @DisplayName("根目录写文件：落盘之外必须落库，并把 db_id 交回模型")
    void writeFileRegistersInProjectDatabase(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);

        String out = h.tools().write_file("会议纪要.txt", "2026-09-01 开庭", PROJECT_ID, null);

        assertTrue(Files.exists(dir.resolve("会议纪要.txt")), "物理文件要写出来");
        assertEquals("2026-09-01 开庭",
                Files.readString(dir.resolve("会议纪要.txt"), StandardCharsets.UTF_8));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> storagePath = ArgumentCaptor.forClass(String.class);
        verify(h.fileService()).createOrUpdateFile(eq(PROJECT_ID), eq(null), name.capture(),
                anyString(), anyLong(), storagePath.capture(), any(), anyLong());
        assertEquals("会议纪要.txt", name.getValue());
        assertEquals("projects/7/会议纪要.txt", storagePath.getValue(),
                "存储 key 口径要与 write_docx 一致，否则读回时解析不到");

        assertTrue(out.contains("\"db_id\":4242"), "要把 db_id 交回模型，实际是：" + out);
        verify(h.bridge()).sendRefreshFilesAction();
    }

    @Test
    @DisplayName("名字里带路径直接拒绝并指向 parentFolderId——不再写一份文件树里看不见的孤儿文件（dev-board#1065）")
    void pathInFileNameIsRejectedWithTheFolderRoute(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);

        String out = h.tools().write_file("卷宗/证据清单.txt", "证据一", PROJECT_ID, null);

        assertTrue(out.startsWith("Error"), "实际是：" + out);
        assertTrue(out.contains("parentFolderId"), "要给模型下一步，实际是：" + out);
        assertFalse(out.contains("scan_files"), "scan_files 已不下发，不许再指向它：" + out);
        assertFalse(Files.exists(dir.resolve("卷宗/证据清单.txt")), "拒绝就不该落盘");
        verify(h.fileService(), never()).createOrUpdateFile(anyLong(), any(), anyString(), anyString(),
                anyLong(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("parentFolderId：登记进那个文件夹、字节经服务落盘，db_id 交回模型")
    void writeIntoFolderRegistersUnderThatFolder(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);
        ProjectFile folder = new ProjectFile();
        folder.setId(88L);
        folder.setProjectId(PROJECT_ID);
        folder.setIsFolder(true);
        folder.setName("卷宗");
        when(h.fileService().findFile(88L)).thenReturn(java.util.Optional.of(folder));
        ProjectFile created = new ProjectFile();
        created.setId(5151L);
        created.setFilePath("projects/7/卷宗/证据清单.txt");
        when(h.fileService().createOrUpdateFile(eq(PROJECT_ID), eq(88L), eq("证据清单.txt"), eq("txt"),
                anyLong(), eq(null), eq(null), anyLong())).thenReturn(created);
        when(h.fileService().overwriteTextContent(eq(PROJECT_ID), eq(5151L), eq("证据一"), anyLong()))
                .thenReturn(created);

        String out = h.tools().write_file("证据清单.txt", "证据一", PROJECT_ID, 88L);

        assertTrue(out.contains("\"db_id\":5151"), "实际是：" + out);
        verify(h.fileService()).overwriteTextContent(eq(PROJECT_ID), eq(5151L), eq("证据一"), anyLong());
        verify(h.bridge()).sendRefreshFilesAction();
        assertFalse(Files.exists(dir.resolve("证据清单.txt")), "不该顺手在项目根目录也写一份");
    }

    @Test
    @DisplayName("parentFolderId 指向别的项目 / 指向文件：拒绝，不建行")
    void writeIntoForeignOrNonFolderIsRejected(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);
        ProjectFile foreign = new ProjectFile();
        foreign.setId(90L);
        foreign.setProjectId(999L);
        foreign.setIsFolder(true);
        when(h.fileService().findFile(90L)).thenReturn(java.util.Optional.of(foreign));
        ProjectFile plain = new ProjectFile();
        plain.setId(91L);
        plain.setProjectId(PROJECT_ID);
        plain.setIsFolder(false);
        plain.setName("a.docx");
        when(h.fileService().findFile(91L)).thenReturn(java.util.Optional.of(plain));

        assertTrue(h.tools().write_file("x.txt", "x", PROJECT_ID, 90L).startsWith("Error"));
        assertTrue(h.tools().write_file("x.txt", "x", PROJECT_ID, 91L).startsWith("Error"));
        assertTrue(h.tools().write_file("x.txt", "x", PROJECT_ID, 92L).startsWith("Error"), "不存在的文件夹");
        verify(h.fileService(), never()).overwriteTextContent(anyLong(), anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("登记失败要如实报告，不许当成完全成功")
    void registrationFailureIsReported(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);
        when(h.fileService().createOrUpdateFile(anyLong(), any(), anyString(), anyString(),
                anyLong(), anyString(), any(), anyLong()))
                .thenThrow(new IllegalStateException("db down"));

        String out = h.tools().write_file("笔记.txt", "x", PROJECT_ID, null);

        assertTrue(Files.exists(dir.resolve("笔记.txt")));
        assertTrue(out.contains("DB registration failed"), "实际是：" + out);
        assertTrue(out.contains("write_file again"), "要给模型补救路径，实际是：" + out);
        assertFalse(out.contains("scan_files"), "scan_files 已不下发（dev-board#1065），不许再指向它：" + out);
        assertFalse(out.contains("\"status\":\"success\""), "登记失败不能报成完全成功，实际是：" + out);
    }

    @Test
    @DisplayName("缺文件名直接拒绝")
    void blankFileNameRejected(@TempDir Path dir) {
        Harness h = harness(dir);
        assertTrue(h.tools().write_file("  ", "x", PROJECT_ID, null).startsWith("Error"));
        assertTrue(h.tools().write_file(null, "x", PROJECT_ID, null).startsWith("Error"));
    }
}
