package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * doc_replace_nth_match / doc_delete_match 的 matchIndex 基数契约。
 *
 * <p>模型面（工具描述、system prompt 第 7 节第 3 条、ToolRegistry 的 LEGACY_DEFAULTS、
 * 后端 {@code matchIndex < 1} 拦截）一律 <b>1 基</b>；编辑器 worker（office_thread.js 的
 * replace_nth_match / delete_match）与它的其它整数定位一样是 <b>0 基</b>
 * （{@code i = 0} 起数、{@code i === idx} 命中），而且 JAR/Web 插件经 PluginHostImpl.DOC_ACTIONS
 * 直接按 worker 契约调用。所以归一只能落在后端：下发前减 1。
 * 病灶：原先原样透传，模型说「第 1 处」改的是第 2 处，matchIndex 等于命中数时直接越界。
 */
class MatchIndexBaseTest {

    private static DocumentEditTools toolsWithBridge(EditorBridgeService bridge) {
        return new DocumentEditTools(null, null, bridge, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dispatchedParams(EditorBridgeService bridge, String action) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeEditorCommand(eq(action), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("doc_replace_nth_match：模型说「第 2 处」→ 下发给 worker 的是 0 基的 1")
    void replaceNthMatchDispatchesZeroBasedIndex() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        Mockito.when(bridge.executeEditorCommand(anyString(), any())).thenReturn("{\"success\":true}");
        DocumentEditTools tools = toolsWithBridge(bridge);

        tools.doc_replace_nth_match("甲方", "买方", 2);

        assertEquals(1, dispatchedParams(bridge, "replace_nth_match").get("matchIndex"),
                "worker 的 replace_nth_match 从 0 起数，模型面的第 2 处必须换算成 1");
    }

    @Test
    @DisplayName("doc_replace_nth_match：「第 1 处」→ 0（首个匹配）")
    void replaceFirstMatchDispatchesZero() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        Mockito.when(bridge.executeEditorCommand(anyString(), any())).thenReturn("{\"success\":true}");
        DocumentEditTools tools = toolsWithBridge(bridge);

        tools.doc_replace_nth_match("甲方", "买方", 1);

        assertEquals(0, dispatchedParams(bridge, "replace_nth_match").get("matchIndex"));
    }

    @Test
    @DisplayName("doc_delete_match：模型说「第 3 处」→ 下发 2")
    void deleteMatchDispatchesZeroBasedIndex() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        Mockito.when(bridge.executeEditorCommand(anyString(), any())).thenReturn("{\"success\":true}");
        DocumentEditTools tools = toolsWithBridge(bridge);

        tools.doc_delete_match("甲方", 3);

        assertEquals(2, dispatchedParams(bridge, "delete_match").get("matchIndex"));
    }

    @Test
    @DisplayName("matchIndex 缺失或 < 1 仍在后端拦下，不下发")
    void invalidMatchIndexIsRejectedBeforeDispatch() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        DocumentEditTools tools = toolsWithBridge(bridge);

        assertTrue(tools.doc_replace_nth_match("a", "b", 0).startsWith("Error"));
        assertTrue(tools.doc_replace_nth_match("a", "b", null).startsWith("Error"));
        assertTrue(tools.doc_delete_match("a", 0).startsWith("Error"));
        verify(bridge, never()).executeEditorCommand(anyString(), any());
    }
}
