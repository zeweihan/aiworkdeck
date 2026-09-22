// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.ai.tools.DocumentEditTools;
import com.checkba.service.ai.tools.ToolContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 注册表不得代工具填必填参数（dev-board 审计 A4）。
 *
 * <p>病灶：{@code LEGACY_DEFAULTS} 给 {@code doc_get_paragraph.paragraphIndex} 与
 * {@code doc_modify_paragraph.paragraphIndex} 都设了缺省值 1，而
 * {@code bindArguments} 的顺序是**先补缺省再转换**，于是 null 永远到不了
 * {@code DocumentEditTools.rejectBadParagraphIndex} 那个守卫——守卫代码写了但永远不执行。
 *
 * <p>后果：doc_modify_paragraph 是带修订的写入工具。模型漏传 paragraphIndex 时，
 * 本该收到「paragraphIndex is required」并自纠，实际却对文档的**第 2 段**
 * （0 基 index=1）做一次它从未主张过的整段替换，用户还很可能直接接受这条修订。
 *
 * <p>所以这个用例必须走<b>整条分发链</b>（ToolRegistry.execute → bindArguments → 方法），
 * 直接调方法的 {@code ParagraphIndexBaseTest} 绕过了 bindArguments，证明不了这件事。
 */
class ToolRegistryLegacyDefaultsTest {

    private EditorBridgeService bridge;
    private ToolRegistry registry;
    private final ToolContext ctx = new ToolContext(5L, "conv-1", 7L, null);

    @BeforeEach
    void setUp() {
        bridge = Mockito.mock(EditorBridgeService.class);
        Mockito.when(bridge.executeEditorCommand(anyString(), any())).thenReturn("{\"success\":true}");
        DocumentEditTools tools = new DocumentEditTools(null, null, bridge, null, null, null, null, null);
        registry = new ToolRegistry(List.of(tools), new PluginService(), new ClientCapabilityService());
        registry.init();
    }

    @Test
    @DisplayName("漏传 paragraphIndex 的 doc_modify_paragraph：返回必填错误，一个编辑器命令都不发")
    void modifyParagraphWithoutIndexIsRejectedThroughTheWholeDispatchChain() {
        ToolRegistry.ToolResult r = registry.execute(
                "doc_modify_paragraph", "{\"newText\":\"第一条 变更后的条款\"}", ctx);

        assertTrue(r.output().startsWith("Error"),
                "注册表代填缺省值会让守卫失效，模型会以为改成了、实际改的是第 2 段。实际返回：" + r.output());
        assertTrue(r.output().contains("paragraphIndex"),
                "错误必须点名缺的是哪个参数，模型下一轮才能自纠。实际返回：" + r.output());
        verify(bridge, never()).executeEditorCommand(anyString(), any());
    }

    @Test
    @DisplayName("漏传 paragraphIndex 的 doc_get_paragraph：同样拦下（读错段落会带偏后续推理）")
    void getParagraphWithoutIndexIsRejectedThroughTheWholeDispatchChain() {
        ToolRegistry.ToolResult r = registry.execute("doc_get_paragraph", "{}", ctx);

        assertTrue(r.output().startsWith("Error"), "实际返回：" + r.output());
        assertTrue(r.output().contains("paragraphIndex"), "实际返回：" + r.output());
        verify(bridge, never()).executeEditorCommand(anyString(), any());
    }

    @Test
    @DisplayName("0 是合法段落号：删掉缺省值不能把首段连带拦掉")
    void zeroStillPassesThroughTheDispatchChain() {
        ToolRegistry.ToolResult r = registry.execute(
                "doc_modify_paragraph", "{\"paragraphIndex\":0,\"newText\":\"首段\"}", ctx);

        assertTrue(r.success(), "实际返回：" + r.output());
        ArgumentCaptor<Map<String, Object>> params = paramsCaptor();
        verify(bridge).executeEditorCommand(Mockito.eq("modify_paragraph"), params.capture());
        assertEquals(0, params.getValue().get("index"));
    }

    @Test
    @DisplayName("doc_find_replace 的 replaceAll 缺省由工具自己兜（缺省=替换全部），注册表不再代填")
    void findReplaceDefaultsToReplaceAllInsideTheToolItself() {
        ToolRegistry.ToolResult r = registry.execute(
                "doc_find_replace", "{\"findText\":\"甲方\",\"replaceText\":\"买方\"}", ctx);

        assertTrue(r.success(), "实际返回：" + r.output());
        ArgumentCaptor<Map<String, Object>> params = paramsCaptor();
        verify(bridge).executeEditorCommand(Mockito.eq("find_replace"), params.capture());
        assertEquals(Boolean.TRUE, params.getValue().get("replaceAll"),
                "工具描述写的是「不传即替换全部」，缺省口径必须与描述一致");
    }

    @Test
    @DisplayName("显式 replaceAll=false 照样只替第一处（缺省值不得覆盖模型的明示）")
    void explicitFalseIsHonoured() {
        registry.execute("doc_find_replace",
                "{\"findText\":\"甲方\",\"replaceText\":\"买方\",\"replaceAll\":false}", ctx);

        ArgumentCaptor<Map<String, Object>> params = paramsCaptor();
        verify(bridge).executeEditorCommand(Mockito.eq("find_replace"), params.capture());
        assertEquals(Boolean.FALSE, params.getValue().get("replaceAll"));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> paramsCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }
}
