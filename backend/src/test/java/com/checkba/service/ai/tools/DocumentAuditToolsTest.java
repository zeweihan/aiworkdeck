package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * doc_audit_structure 与编辑器桥的契约：分页读到 truncated=false 为止、
 * 桥的 {"error"} 原样转成 Error 回喂、修订清单读不到不掀翻整个报告。
 */
class DocumentAuditToolsTest {

    private static String page(int start, int count, boolean truncated) {
        StringBuilder sb = new StringBuilder("{\"success\":true,\"totalParagraphs\":700,\"paragraphs\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            int idx = start + i;
            String text = idx == 0 ? "第一條 定義" : idx == 1 ? "第三條 交割" : "正文段落 " + idx;
            sb.append("{\"index\":").append(idx).append(",\"text\":\"").append(text).append("\"}");
        }
        sb.append(']');
        if (truncated) sb.append(",\"truncated\":true,\"nextStartParagraph\":").append(start + count);
        return sb.append('}').toString();
    }

    @Test
    @DisplayName("分页读完全文：第一页 truncated 就接着读下一页，直到不再截断")
    @SuppressWarnings("unchecked")
    void pagesThroughWholeDocument() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        when(bridge.executeEditorCommand(eq("get_document_text"), any()))
                .thenReturn(page(0, 500, true), page(500, 200, false));
        when(bridge.executeEditorCommand(eq("list_revisions"), any()))
                .thenReturn("{\"success\":true,\"count\":0,\"revisions\":[]}");

        String report = new DocumentAuditTools(bridge).doc_audit_structure();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bridge, Mockito.times(2)).executeEditorCommand(eq("get_document_text"), captor.capture());
        assertEquals(List.of(0, 500), captor.getAllValues().stream().map(m -> m.get("startParagraph")).toList(),
                "第二页必须从 nextStartParagraph 接着读");
        assertTrue(report.contains("段落总数：700"), report);
        assertTrue(report.contains("第1条之后直接是第3条"), "跨页拼出来的全文要能审到编号缺号：" + report);
        assertTrue(report.contains("没有未处理的修订"), report);
    }

    @Test
    @DisplayName("编辑器桥返回 {\"error\"} 时原样转成 Error，不去读修订")
    void bridgeErrorIsSurfaced() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        when(bridge.executeEditorCommand(eq("get_document_text"), any()))
                .thenReturn("{\"error\": \"No active conversation. Please ensure a document is open.\"}");

        String out = new DocumentAuditTools(bridge).doc_audit_structure();

        assertTrue(out.startsWith("Error: No active conversation"), out);
        verify(bridge, never()).executeEditorCommand(eq("list_revisions"), any());
    }

    @Test
    @DisplayName("修订清单读失败只在第 7 节写明，正文各项照常审")
    void revisionFailureDoesNotSinkTheReport() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        when(bridge.executeEditorCommand(eq("get_document_text"), any())).thenReturn(page(0, 3, false));
        when(bridge.executeEditorCommand(eq("list_revisions"), any()))
                .thenReturn("{\"error\":\"not a text document\"}");

        String report = new DocumentAuditTools(bridge).doc_audit_structure();

        assertTrue(report.contains("修订清单读取失败"), report);
        assertTrue(report.contains("第1条之后直接是第3条"), report);
        assertFalse(report.startsWith("Error"), report);
    }
}
