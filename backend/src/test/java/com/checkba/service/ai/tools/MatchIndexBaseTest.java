package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import dev.langchain4j.agent.tool.Tool;
import org.mockito.Mockito;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    // ---- doc_find_text 返回给模型的 matchIndex 也要归一成 1 基（dev-board#369）----
    // worker 的 find_text_locations 返回 matchIndex: i（0 起）。后端只在下发时减一、不在返回时加一，
    // 就留下第二条差一路：模型从 doc_find_text 看到「第二个匹配 matchIndex=1」，照着传给
    // doc_replace_nth_match，减一后改的是第一个。归一必须两个方向都做。

    private static String toolDescription(String name) {
        for (Method m : DocumentEditTools.class.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Tool t = m.getAnnotation(Tool.class);
            assertNotNull(t, name + " 应当是 @Tool");
            return String.join("", t.value());
        }
        throw new AssertionError("DocumentEditTools 里没有工具 " + name);
    }

    @Test
    @DisplayName("doc_find_text：worker 的 0 基 matchIndex 回到模型前加 1，与 doc_replace_nth_match 同口径")
    void findTextRemapsMatchIndexToOneBased() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        Mockito.when(bridge.executeEditorCommand(eq("find_text_locations"), any())).thenReturn(
                "{\"success\":true,\"count\":2,\"matches\":["
                        + "{\"matchIndex\":0,\"anchorId\":\"__ai_anchor_1\",\"text\":\"甲方\"},"
                        + "{\"matchIndex\":1,\"anchorId\":\"__ai_anchor_2\",\"text\":\"甲方\"}]}");
        DocumentEditTools tools = toolsWithBridge(bridge);

        String out = tools.doc_find_text("甲方", false);

        assertTrue(out.contains("\"matchIndex\":1") && out.contains("\"matchIndex\":2"),
                "matches 里的 matchIndex 应当是 1、2，实际是：" + out);
        assertTrue(!out.contains("\"matchIndex\":0"), "不许再出现 0 基序号，实际是：" + out);
        assertTrue(out.contains("__ai_anchor_2") && out.contains("\"count\":2"), "其余字段原样保留，实际是：" + out);
    }

    @Test
    @DisplayName("doc_find_text：错误 / 非 JSON 结果原样透传，不因归一而吞掉")
    void findTextPassesThroughErrorsUntouched() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        Mockito.when(bridge.executeEditorCommand(eq("find_text_locations"), any()))
                .thenReturn("{\"error\": \"No active conversation. Please ensure a document is open.\"}")
                .thenReturn("not json at all");
        DocumentEditTools tools = toolsWithBridge(bridge);

        assertEquals("{\"error\": \"No active conversation. Please ensure a document is open.\"}", tools.doc_find_text("x", false));
        assertEquals("not json at all", tools.doc_find_text("x", false));
    }

    @Test
    @DisplayName("doc_find_text 的描述点明返回的 matchIndex 从 1 开始、可直接喂 doc_replace_nth_match")
    void findTextDescriptionStatesReturnedMatchIndexIsOneBased() {
        String d = toolDescription("doc_find_text");
        assertTrue(d.contains("matchIndex") && d.contains("从 1 开始") && d.contains("doc_replace_nth_match"),
                "doc_find_text 描述要说明 matchIndex 1 基且可直接喂 doc_replace_nth_match，实际是：" + d);
    }

    @Test
    @DisplayName("system prompt（中/英）工具表的 doc_find_text 一行同样点明 matchIndex 1 基")
    void systemPromptFindTextRowStatesOneBasedMatchIndex() throws Exception {
        String zh = readResource("prompts/system_prompt.md");
        String en = readResource("prompts/system_prompt.en.md");
        assertTrue(zh.contains("matchIndex（序号，从 1 开始"), "system_prompt.md 的 doc_find_text 一行缺 matchIndex 1 基说明");
        assertTrue(en.contains("matchIndex (1-based"), "system_prompt.en.md doc_find_text row lacks the 1-based matchIndex note");
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = MatchIndexBaseTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "classpath 上找不到 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
