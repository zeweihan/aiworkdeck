// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * doc_format_table 的列宽死路（dev-board#807，审计 B-07）。
 *
 * <p>本引擎的 WASM 桥没注册 TableColumnSeparator，worker 侧读回再设回同样抛
 * unregistered UNO type（doc-editor.md e2e 组 29 实锤）。改动前两层问题：
 * <ol>
 *   <li>@Tool 描述里挂着 columnWidthsPercent / columnWidthsCm，模型看到参数就会用——
 *       而表格排版里「把第一列调窄」是最自然不过的需求，于是反复往死路上撞、白烧步数预算；</li>
 *   <li>更实际的伤害：worker 先设边框/字号/首行加粗/垂直对齐（已经落到文档上了），
 *       最后才处理列宽；列宽抛错时直接 {@code return {success:false}}，<b>那条分支里没有 applied</b>，
 *       模型被告知整条命令失败，于是重试或改用别的手段，同一张表被格式化两三遍。</li>
 * </ol>
 */
class FormatTableColumnWidthTest {

    private static DocumentEditTools toolsWithBridge(EditorBridgeService bridge) {
        return new DocumentEditTools(null, null, bridge, null, null, null, null, null);
    }

    private static EditorBridgeService bridgeReturning(String json) {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        Mockito.when(bridge.executeEditorCommand(anyString(), any())).thenReturn(json);
        return bridge;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dispatchedParams(EditorBridgeService bridge) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeEditorCommand(eq("format_table"), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("列宽参数一个字节都不下发给编辑器，其余格式照常执行")
    void columnWidthsAreRejectedInTheBackendWhileTheRestStillRuns() {
        EditorBridgeService bridge = bridgeReturning("{\"success\":true,\"applied\":{\"borderWidthPt\":1.5}}");
        DocumentEditTools tools = toolsWithBridge(bridge);

        String result = tools.doc_format_table(true, 1.5, 10.0, true, "center",
                "20,50,30", null, null, 0, null, null, null, null, null, null, "3,5,4");

        Map<String, Object> params = dispatchedParams(bridge);
        assertFalse(params.containsKey("columnWidthsPercent"),
                "列宽百分比不该下发——worker 那条分支是死路，撞一次白烧一轮：" + params);
        assertFalse(params.containsKey("columnWidthsCm"),
                "列宽厘米同理：" + params);
        assertTrue(params.containsKey("borderWidthPt") && params.containsKey("fontSizePt"),
                "其余格式项必须照常下发，不能因为列宽做不到就整条不做：" + params);
        assertTrue(result.contains("不支持按列设宽"),
                "必须明确告诉模型这一项没生效，否则它会当成已完成转述给用户：" + result);
        assertTrue(result.contains("Word"),
                "要给出用户自己能走的出路（Word 里手工调）：" + result);
    }

    @Test
    @DisplayName("只要求改列宽时就地拒绝，连一次桥调用都不发")
    void aColumnWidthOnlyRequestNeverReachesTheEditor() {
        EditorBridgeService bridge = bridgeReturning("{\"success\":true}");
        DocumentEditTools tools = toolsWithBridge(bridge);

        String result = tools.doc_format_table(null, null, null, null, null,
                "20,50,30", null, null, null, null, null, null, null, null, null, null);

        verify(bridge, never()).executeEditorCommand(anyString(), any());
        assertTrue(result.startsWith("Error"),
                "做不到的事要按失败返回，才能进连续失败纠正回路：" + result);
        assertTrue(result.contains("不支持按列设宽"), result);
    }

    @Test
    @DisplayName("worker 报失败时，不把已经生效的边框/字号一起报成失败——禁止整条重发")
    void aPartialFailureDoesNotClaimEverythingFailed() {
        EditorBridgeService bridge = bridgeReturning("{\"success\":false,\"message\":\"行高设置失败: boom\"}");
        DocumentEditTools tools = toolsWithBridge(bridge);

        String result = tools.doc_format_table(null, 1.5, 10.0, null, null,
                null, 18.0, null, null, null, null, null, null, null, null, null);

        assertTrue(result.contains("doc_get_formatting"),
                "要让模型先读回核对，而不是凭一句 success:false 就重来：" + result);
        assertTrue(result.contains("不要整条命令重发"),
                "整条重发会把已生效的项再做一遍（重复格式化），这是这条审计里真实发生过的事：" + result);
        assertTrue(result.contains("borderWidthPt"),
                "要把本次请求了哪些项列出来，模型才知道该核对什么：" + result);
    }

    @Test
    @DisplayName("工具描述里不再出现列宽参数（挂着做不到的能力 = 让模型反复撞死路）")
    void theToolDescriptionNoLongerAdvertisesColumnWidths() throws Exception {
        Method m = null;
        for (Method candidate : DocumentEditTools.class.getDeclaredMethods()) {
            if (candidate.getName().equals("doc_format_table")) {
                m = candidate;
                break;
            }
        }
        assertNotNull(m, "doc_format_table 必须还在");
        String description = String.join(" ", m.getAnnotation(Tool.class).value());
        assertFalse(description.contains("columnWidthsPercent"), description);
        assertFalse(description.contains("columnWidthsCm"), description);
        assertTrue(description.contains("不支持按列设宽"),
                "光删掉参数不够——要明写做不到，否则模型会去找别的工具绕：" + description);
    }
}
