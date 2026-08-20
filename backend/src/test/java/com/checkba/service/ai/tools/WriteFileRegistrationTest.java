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

        FileTools tools = new FileTools(fileService, null, bridge, null, resolver, null, null);
        ProjectContextHolder.setProjectId(String.valueOf(PROJECT_ID));
        return new Harness(tools, fileService, bridge, root);
    }

    @Test
    @DisplayName("根目录写文件：落盘之外必须落库，并把 db_id 交回模型")
    void writeFileRegistersInProjectDatabase(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);

        String out = h.tools().write_file("会议纪要.txt", "2026-09-01 开庭", PROJECT_ID);

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
    @DisplayName("子目录里的文件如实说明没登记，并指向 scan_files——不许假装已登记")
    void nestedFileIsHonestAboutNotBeingRegistered(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);

        String out = h.tools().write_file("卷宗/证据清单.txt", "证据一", PROJECT_ID);

        assertTrue(Files.exists(dir.resolve("卷宗/证据清单.txt")), "物理文件仍要写出来");
        verify(h.fileService(), never()).createOrUpdateFile(anyLong(), any(), anyString(), anyString(),
                anyLong(), anyString(), any(), anyLong());
        assertTrue(out.contains("NOT registered"), "要明说没登记，实际是：" + out);
        assertTrue(out.contains("scan_files"), "要给模型下一步，实际是：" + out);
    }

    @Test
    @DisplayName("登记失败要如实报告，不许当成完全成功")
    void registrationFailureIsReported(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);
        when(h.fileService().createOrUpdateFile(anyLong(), any(), anyString(), anyString(),
                anyLong(), anyString(), any(), anyLong()))
                .thenThrow(new IllegalStateException("db down"));

        String out = h.tools().write_file("笔记.txt", "x", PROJECT_ID);

        assertTrue(Files.exists(dir.resolve("笔记.txt")));
        assertTrue(out.contains("DB registration failed"), "实际是：" + out);
        assertTrue(out.contains("scan_files"), "要给模型补救路径，实际是：" + out);
        assertFalse(out.contains("\"status\":\"success\""), "登记失败不能报成完全成功，实际是：" + out);
    }

    @Test
    @DisplayName("缺文件名直接拒绝")
    void blankFileNameRejected(@TempDir Path dir) {
        Harness h = harness(dir);
        assertTrue(h.tools().write_file("  ", "x", PROJECT_ID).startsWith("Error"));
        assertTrue(h.tools().write_file(null, "x", PROJECT_ID).startsWith("Error"));
    }
}
