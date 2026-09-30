// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.ai;

import com.checkba.service.ai.tools.DocumentEditTools;
import com.checkba.service.ai.tools.ToolContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real parser → registry → document tool; only the editor transport is mocked. */
class EmptyStringToolArgumentsTest {
    private final EditorBridgeService bridge = mock(EditorBridgeService.class);
    private final ToolRegistry registry = new ToolRegistry(List.of(
            new DocumentEditTools(null, null, bridge, null, null, null, null, null)),
            new PluginService(), new ClientCapabilityService());

    private String execute(String code) {
        registry.init();
        var calls = new XmlToolCallParser(registry).parse("<tool_code>" + code + "</tool_code>");
        assertEquals(1, calls.size());
        var call = calls.get(0);
        var result = registry.execute(call.toolName(), call.argsJson(), new ToolContext(5L, "empty-arg", 7L, null));
        assertTrue(result.found());
        return result.output();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "doc_find_replace(findText=\"（草稿）\",replaceText=\"\",replaceAll=false)",
            "doc_find_replace(findText='（草稿）',replaceText='',replaceAll=false)",
            "doc_find_replace(\"（草稿）\",\"\",false)",
            "doc_find_replace({\"findText\":\"（草稿）\",\"replaceText\":\"\",\"replaceAll\":false})"
    })
    void deletionReachesEditorWithEmptyReplacement(String code) {
        when(bridge.executeEditorCommand(eq("find_replace"), anyMap())).thenReturn("deleted one match");
        assertEquals("deleted one match", execute(code));
        verify(bridge).executeEditorCommand("find_replace",
                Map.of("findText", "（草稿）", "replaceText", "", "replaceAll", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "doc_find_replace(findText=\"（草稿）\",replaceAll=false)",
            "doc_find_replace({\"findText\":\"（草稿）\",\"replaceAll\":false})"
    })
    void missingReplacementIsRejectedBeforeEditor(String code) {
        assertTrue(execute(code).contains("缺少必填参数"));
        verifyNoInteractions(bridge);
    }
}
