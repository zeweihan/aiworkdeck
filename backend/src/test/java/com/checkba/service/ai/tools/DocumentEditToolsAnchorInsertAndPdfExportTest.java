// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * dev-board#1065 T-16 / T-25 两个新能力的桥调用契约（mock 桥，逐条钉下发的 action 与参数）。
 *
 * <p>T-16：{@code doc_insert_at_cursor(text, anchorId, position)} 在服务端把
 * 「选中锚点 → 收起到开头/结尾 → 光标处插入」串起来，三条命令必须与
 * {@code doc_select_anchor} / {@code doc_collapse_cursor} / 旧的 {@code doc_insert_at_cursor}
 * 各自下发的逐字相同——否则 worker 那边走的是另一条没人验过的路。
 *
 * <p>T-25：{@code doc_export_pdf} 下发 {@code export_pdf}，把前端回传的 base64 解码后存进项目，
 * 落在原文档所在的文件夹、同名走 RENAME、绝不覆盖。
 */
class DocumentEditToolsAnchorInsertAndPdfExportTest {

    private static final String OK = "{\"success\":true}";

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    private static DocumentEditTools toolsWithBridge(EditorBridgeService bridge) {
        return new DocumentEditTools(null, null, bridge, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> paramsInOrder(EditorBridgeService bridge, List<String> actions) {
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(bridge, times(actions.size())).executeEditorCommand(action.capture(), params.capture());
        assertEquals(actions, action.getAllValues(), "下发的 action 顺序不对");
        return params.getAllValues();
    }

    // ==================== T-16：锚点插入 ====================

    @Test
    @DisplayName("anchorId + before：set_selection(anchor) → collapse_selection(start) → insert_at_cursor(text)")
    void insertBeforeAnchorComposesTheThreeExistingCommands() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        when(bridge.executeEditorCommand(anyString(), any())).thenReturn(OK);

        String out = toolsWithBridge(bridge).doc_insert_at_cursor("新增一句。", "__ai_anchor_7", "before");

        assertEquals(OK, out, "三步都成功时返回插入那一步的回执");
        List<Map<String, Object>> params = paramsInOrder(bridge,
                List.of("set_selection", "collapse_selection", "insert_at_cursor"));
        assertEquals(Map.of("anchor", "__ai_anchor_7"), params.get(0), "与 doc_select_anchor 下发的逐字相同");
        assertEquals(Map.of("to", "start"), params.get(1), "before 收到锚点开头");
        assertEquals(Map.of("text", "新增一句。"), params.get(2), "与不带锚点的插入下发的逐字相同");
    }

    @Test
    @DisplayName("anchorId + after（或缺省 position）：collapse 到 end")
    void insertAfterAnchorCollapsesToTheEnd() {
        for (String position : new String[]{"after", null, " AFTER "}) {
            EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
            when(bridge.executeEditorCommand(anyString(), any())).thenReturn(OK);

            toolsWithBridge(bridge).doc_insert_at_cursor("补充条款", "__ai_anchor_3", position);

            List<Map<String, Object>> params = paramsInOrder(bridge,
                    List.of("set_selection", "collapse_selection", "insert_at_cursor"));
            assertEquals(Map.of("anchor", "__ai_anchor_3"), params.get(0));
            assertEquals(Map.of("to", "end"), params.get(1), "position=" + position + " 应当收到结尾");
            assertEquals(Map.of("text", "补充条款"), params.get(2));
        }
    }

    @Test
    @DisplayName("不给 anchorId：行为与改动前一字不差，只发一条 insert_at_cursor")
    void withoutAnchorOnlyInsertsAtTheCursor() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        when(bridge.executeEditorCommand(anyString(), any())).thenReturn(OK);

        toolsWithBridge(bridge).doc_insert_at_cursor("原样插入", null, "before");
        toolsWithBridge(bridge).doc_insert_at_cursor("原样插入", "  ", null);

        verify(bridge, times(2)).executeEditorCommand(eq("insert_at_cursor"), eq(Map.of("text", "原样插入")));
        verify(bridge, never()).executeEditorCommand(eq("set_selection"), any());
        verify(bridge, never()).executeEditorCommand(eq("collapse_selection"), any());
    }

    @Test
    @DisplayName("第 1 步失败（锚点找不到）：Error 写明第几步，后两步一条都不发")
    void anchorSelectionFailureStopsBeforeTouchingTheDocument() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        when(bridge.executeEditorCommand(eq("set_selection"), any()))
                .thenReturn("{\"error\": \"anchor not found: __ai_anchor_9\"}");

        String out = toolsWithBridge(bridge).doc_insert_at_cursor("x", "__ai_anchor_9", "after");

        assertTrue(out.startsWith("Error: 第 1 步"), out);
        assertTrue(out.contains("set_selection") && out.contains("anchor not found") && out.contains("没有插入任何内容"), out);
        verify(bridge, never()).executeEditorCommand(eq("collapse_selection"), any());
        verify(bridge, never()).executeEditorCommand(eq("insert_at_cursor"), any());
    }

    @Test
    @DisplayName("第 2 步 / 第 3 步失败各报各的步骤；worker 的 success:false 也算失败")
    void laterStepFailuresNameTheirStep() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        when(bridge.executeEditorCommand(eq("set_selection"), any())).thenReturn(OK);
        when(bridge.executeEditorCommand(eq("collapse_selection"), any()))
                .thenReturn("{\"success\":false,\"message\":\"no view cursor\"}");
        String out = toolsWithBridge(bridge).doc_insert_at_cursor("x", "a1", "before");
        assertTrue(out.startsWith("Error: 第 2 步") && out.contains("collapse_selection") && out.contains("no view cursor"), out);
        verify(bridge, never()).executeEditorCommand(eq("insert_at_cursor"), any());

        EditorBridgeService bridge3 = Mockito.mock(EditorBridgeService.class);
        when(bridge3.executeEditorCommand(eq("set_selection"), any())).thenReturn(OK);
        when(bridge3.executeEditorCommand(eq("collapse_selection"), any())).thenReturn(OK);
        when(bridge3.executeEditorCommand(eq("insert_at_cursor"), any()))
                .thenReturn("{\"error\": \"操作超时：编辑器可能仍在执行该命令\", \"outcomeUnknown\": true}");
        String out3 = toolsWithBridge(bridge3).doc_insert_at_cursor("x", "a1", "after");
        assertTrue(out3.startsWith("Error: 第 3 步") && out3.contains("insert_at_cursor") && out3.contains("操作超时"), out3);
        assertFalse(out3.contains("没有插入任何内容"), "第 3 步超时的结局未知，不能说没插进去：" + out3);
    }

    @Test
    @DisplayName("编辑器还在启动（EDITOR_BOOTING）：原样透传，不包 Error 前缀（编排器靠那个码不计失败）")
    void editorBootingPassesThroughUntouched() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        String booting = "{\"error\": \"编辑器仍在启动\", \"code\": \"EDITOR_BOOTING\", \"retryable\": true}";
        when(bridge.executeEditorCommand(eq("set_selection"), any())).thenReturn(booting);

        assertEquals(booting, toolsWithBridge(bridge).doc_insert_at_cursor("x", "a1", "after"));
        verify(bridge, never()).executeEditorCommand(eq("insert_at_cursor"), any());
    }

    @Test
    @DisplayName("position 只认 before / after，其它值一条命令都不发")
    void unknownPositionIsRejectedBeforeDispatch() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        String out = toolsWithBridge(bridge).doc_insert_at_cursor("x", "a1", "middle");
        assertTrue(out.startsWith("Error:") && out.contains("before 或 after"), out);
        verify(bridge, never()).executeEditorCommand(anyString(), any());
    }

    // ==================== T-25：导出 PDF ====================

    private static ProjectFile file(long id, long projectId, Long parentId, String name) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(projectId);
        f.setParentId(parentId);
        f.setName(name);
        return f;
    }

    @Test
    @DisplayName("doc_export_pdf：下发 export_pdf，解码 base64 存进原文档所在文件夹（RENAME），返回新文件名与 id")
    void exportPdfSavesTheBytesNextToTheSourceDocument(@TempDir Path root) throws Exception {
        ProjectContextHolder.setProjectId("5");
        byte[] pdf = "%PDF-1.7 fake body".getBytes();
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        when(bridge.executeEditorCommand(eq("export_pdf"), any())).thenReturn(
                "{\"success\":true,\"size\":" + pdf.length + ",\"base64\":\""
                        + Base64.getEncoder().encodeToString(pdf) + "\",\"sourceFileId\":42}");

        ProjectFileService files = Mockito.mock(ProjectFileService.class);
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        ProjectStorageResolver storage = Mockito.mock(ProjectStorageResolver.class);
        when(files.findFile(42L)).thenReturn(Optional.of(file(42, 5, 9L, "股权转让协议.docx")));
        ProjectFile created = file(77, 5, 9L, "股权转让协议 (1).pdf");
        created.setFilePath("p5/股权转让协议 (1).pdf");
        when(files.createFile(eq(5L), eq(9L), anyString(), eq("pdf"), anyLong(), any(), anyString(),
                anyLong(), eq(ProjectFileService.ConflictPolicy.RENAME))).thenReturn(created);
        when(storage.projectRoot(5L)).thenReturn(root);
        when(storage.resolve("p5/股权转让协议 (1).pdf")).thenReturn(root.resolve("out/股权转让协议 (1).pdf"));

        DocumentEditTools tools = new DocumentEditTools(files, repo, bridge, storage, null, null, null, null);
        String out = tools.doc_export_pdf(null);

        verify(bridge).executeEditorCommand(eq("export_pdf"), eq(Map.of()));
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(files).createFile(eq(5L), eq(9L), name.capture(), eq("pdf"), eq((long) pdf.length), any(),
                anyString(), anyLong(), eq(ProjectFileService.ConflictPolicy.RENAME));
        assertEquals("股权转让协议.pdf", name.getValue(), "默认与原文档同名，扩展名换成 .pdf");
        assertArrayEquals(pdf, Files.readAllBytes(root.resolve("out/股权转让协议 (1).pdf")), "落盘的字节就是解码出来的 PDF");
        verify(repo).save(created);
        assertTrue(out.contains("股权转让协议 (1).pdf") && out.contains("77") && out.contains("原文档没有改动"), out);
        assertFalse(out.startsWith("Error"), out);
        try (var s = Files.list(root)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith(".export-pdf-")), "临时文件必须搬走或清掉");
        }
    }

    @Test
    @DisplayName("doc_export_pdf：失败面——编辑器报错、没有 base64、源文件不在本项目，各自返回 Error 且不建文件")
    void exportPdfFailuresNeverCreateAFile() {
        ProjectFileService files = Mockito.mock(ProjectFileService.class);
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        DocumentEditTools tools = new DocumentEditTools(files, null, bridge, null, null, null, null, null);

        when(bridge.executeEditorCommand(eq("export_pdf"), any())).thenReturn("{\"error\": \"当前文档类型不支持导出 PDF\"}");
        String err = tools.doc_export_pdf(null);
        assertTrue(err.startsWith("Error: 导出 PDF 失败") && err.contains("不支持导出 PDF"), err);

        when(bridge.executeEditorCommand(eq("export_pdf"), any())).thenReturn("{\"success\":true,\"size\":0}");
        assertTrue(tools.doc_export_pdf(null).startsWith("Error:"));

        ProjectContextHolder.setProjectId("5");
        when(bridge.executeEditorCommand(eq("export_pdf"), any())).thenReturn(
                "{\"success\":true,\"base64\":\"" + Base64.getEncoder().encodeToString("%PDF".getBytes())
                        + "\",\"sourceFileId\":43}");
        when(files.findFile(43L)).thenReturn(Optional.of(file(43, 6, null, "别人的.docx")));
        String outside = tools.doc_export_pdf(null);
        assertTrue(outside.startsWith("Error:") && outside.contains("does not belong"), outside);

        verify(files, never()).createFile(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("导出文件名：给了就用（抹路径分隔符、补 .pdf），没给取原文档名换扩展名")
    void exportPdfNaming() {
        assertEquals("合同.pdf", DocumentEditTools.exportPdfName(null, "合同.docx"));
        assertEquals("合同终稿.pdf", DocumentEditTools.exportPdfName("合同终稿", "合同.docx"));
        assertEquals("a_b.pdf", DocumentEditTools.exportPdfName("a/b.pdf", "合同.docx"));
        assertEquals("document.pdf", DocumentEditTools.exportPdfName(" ", null));
    }

    @Test
    @DisplayName("编辑器还在启动时 doc_export_pdf 原样透传 EDITOR_BOOTING")
    void exportPdfPassesThroughEditorBooting() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        String booting = "{\"error\": \"编辑器仍在启动\", \"code\": \"EDITOR_BOOTING\", \"retryable\": true}";
        when(bridge.executeEditorCommand(eq("export_pdf"), any())).thenReturn(booting);
        assertEquals(booting, toolsWithBridge(bridge).doc_export_pdf(null));
        InOrder order = inOrder(bridge);
        order.verify(bridge).executeEditorCommand(eq("export_pdf"), any());
    }
}
