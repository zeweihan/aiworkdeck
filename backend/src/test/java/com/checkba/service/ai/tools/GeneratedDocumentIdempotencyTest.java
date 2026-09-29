// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.AiDocxExportService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.SseEmitterService;
import com.checkba.storage.ProjectStorageResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成类工具的同轮幂等与失败清理（dev-board#1017）。
 *
 * <p>病灶：编辑器还没就绪，模型拿到失败后改用 write_docx / doc_start_stream 另建一份；
 * 新建走 ConflictPolicy.RENAME 自动加「 (n)」，doc_start_stream 同步打开失败时还把刚建的空文件留在项目里——
 * 一轮下来项目里四份同名文档。
 */
class GeneratedDocumentIdempotencyTest {

    private static final long PROJECT_ID = 42L;

    // ==================== doc_start_stream ====================

    private record StreamHarness(DocumentEditTools tools, ProjectFileService files, EditorBridgeService bridge) {}

    private static StreamHarness streamHarness(Path root, String openResult) {
        ProjectFileService files = Mockito.mock(ProjectFileService.class);
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        ProjectStorageResolver resolver = Mockito.mock(ProjectStorageResolver.class);
        when(resolver.projectRoot(anyLong())).thenReturn(root);
        when(resolver.resolve(anyString())).thenAnswer(inv ->
                root.resolve(inv.getArgument(0, String.class).substring(("projects/" + PROJECT_ID + "/").length())));
        when(bridge.getCurrentConversationId()).thenReturn("conv-1");
        when(bridge.executeEditorCommand(anyString(), any())).thenReturn(openResult);
        when(files.createFile(anyLong(), any(), anyString(), anyString(), any(), any(), anyString(), anyLong(), any()))
                .thenAnswer(inv -> {
                    ProjectFile f = new ProjectFile();
                    f.setId(999L);
                    f.setProjectId(PROJECT_ID);
                    f.setName(inv.getArgument(2));
                    f.setFileType("docx");
                    f.setFilePath("projects/" + PROJECT_ID + "/" + inv.getArgument(2));
                    return f;
                });
        when(repo.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));
        return new StreamHarness(new DocumentEditTools(files, repo, bridge, resolver, null, null, null, null),
                files, bridge);
    }

    @Test
    @DisplayName("doc_start_stream 新建后同步打开失败：删掉刚建的空文件，不留在项目里")
    void openFailureDiscardsTheEmptyFile(@TempDir Path dir) {
        StreamHarness h = streamHarness(dir, "{\"error\": \"编辑器未就绪\"}");

        String out = h.tools().doc_start_stream(null, "精简版", PROJECT_ID, null);

        assertTrue(out.startsWith("Error opening file"), out);
        verify(h.files()).permDelete(999L, 10001L);
        verify(h.bridge(), never()).noteGenerated(anyString(), any());
        verify(h.bridge(), never()).setStreamingMode(anyString(), Mockito.anyBoolean());
    }

    @Test
    @DisplayName("doc_start_stream 打开成功：登记本轮生成物，不删文件")
    void openSuccessRegistersTheFile(@TempDir Path dir) {
        StreamHarness h = streamHarness(dir, "{\"success\":true}");

        h.tools().doc_start_stream(null, "精简版", PROJECT_ID, null);

        verify(h.files(), never()).permDelete(anyLong(), anyLong());
        verify(h.bridge()).noteGenerated(EditorBridgeService.newDocxKey(null, "精简版.docx"), 999L);
    }

    @Test
    @DisplayName("doc_start_stream 同一轮对同一目标再新建：复用第一次那份，不再 createFile")
    void sameRunSecondStreamReusesTheFirst(@TempDir Path dir) {
        StreamHarness h = streamHarness(dir, "{\"success\":true}");
        when(h.bridge().generatedInRun(EditorBridgeService.newDocxKey(null, "精简版.docx"))).thenReturn(999L);
        ProjectFile first = new ProjectFile();
        first.setId(999L);
        first.setName("精简版.docx");
        when(h.files().findFile(999L)).thenReturn(Optional.of(first));

        String out = h.tools().doc_start_stream(null, "精简版.docx", PROJECT_ID, null);

        assertTrue(out.contains("本轮已生成过"), out);
        verify(h.files(), never()).createFile(anyLong(), any(), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), any());
    }

    // ==================== write_docx（真实登记簿） ====================

    @Test
    @DisplayName("write_docx 同一轮同名同文件夹第二次：直接复用，不再 RENAME 出「 (1)」；新一轮照常新建")
    void writeDocxSameRunReusesAcrossCalls() throws Exception {
        ProjectFileService files = mock(ProjectFileService.class);
        AiDocxExportService export = mock(AiDocxExportService.class);
        ProjectFile made = new ProjectFile();
        made.setId(77L);
        made.setName("精简版.docx");
        made.setFilePath("projects/42/资料/精简版.docx");
        when(export.exportMarkdownToDocx(eq(PROJECT_ID), eq(5L), anyLong(), eq("精简版.docx"), anyString(), any()))
                .thenReturn(made);
        when(files.findFile(77L)).thenReturn(Optional.of(made));
        EditorBridgeService bridge = new EditorBridgeService(mock(SseEmitterService.class), new ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));
        bridge.setCurrentConversationId("conv-w");
        FileTools tools = new FileTools(files, null, bridge, null, null, null, export, null, null);

        String first = tools.write_docx("精简版.docx", "# 正文", PROJECT_ID, 5L, null);
        String second = tools.write_docx("精简版", "# 正文（重来）", PROJECT_ID, 5L, null);

        assertTrue(first.contains("\"db_id\":77"), first);
        assertTrue(second.contains("本轮已生成过"), second);
        assertTrue(second.contains("77"), second);
        verify(export, times(1)).exportMarkdownToDocx(anyLong(), any(), anyLong(), anyString(), anyString(), any());

        bridge.clearForNewRun("conv-w");
        tools.write_docx("精简版.docx", "# 正文", PROJECT_ID, 5L, null);
        verify(export, times(2)).exportMarkdownToDocx(anyLong(), any(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("登记的那份这一轮里被删了：撤掉登记，照常新建")
    void deletedRegisteredFileIsRecreated() {
        ProjectFileService files = mock(ProjectFileService.class);
        AiDocxExportService export = mock(AiDocxExportService.class);
        ProjectFile made = new ProjectFile();
        made.setId(78L);
        made.setName("a.docx");
        made.setFilePath("p/a.docx");
        when(export.exportMarkdownToDocx(anyLong(), any(), anyLong(), anyString(), anyString(), any())).thenReturn(made);
        ProjectFile gone = new ProjectFile();
        gone.setId(78L);
        gone.setIsDeleted(true);
        when(files.findFile(78L)).thenReturn(Optional.of(gone));
        EditorBridgeService bridge = new EditorBridgeService(mock(SseEmitterService.class), new ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));
        bridge.setCurrentConversationId("conv-d");
        FileTools tools = new FileTools(files, null, bridge, null, null, null, export, null, null);

        tools.write_docx("a.docx", "x", PROJECT_ID, 5L, null);
        String again = tools.write_docx("a.docx", "x", PROJECT_ID, 5L, null);

        assertTrue(again.contains("\"db_id\":78"), again);
        verify(export, times(2)).exportMarkdownToDocx(anyLong(), any(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("successDbId 只认成功回执")
    void successDbIdParsesOnlySuccess() {
        assertEquals(12L, FileTools.successDbId("{\"status\":\"success\", \"db_id\":12, \"file_path\":\"x\"}"));
        assertEquals(null, FileTools.successDbId("Error creating DOCX in folder 5: boom"));
        assertEquals(null, FileTools.successDbId(null));
    }
}
