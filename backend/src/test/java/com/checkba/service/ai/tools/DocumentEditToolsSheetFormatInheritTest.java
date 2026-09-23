// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import dev.langchain4j.agent.tool.P;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 表格新增行与既有行格式一致（dev-board#844）的后端契约：
 * {@code sheet_write_cells.inheritFormat}（缺省开，只有显式 false 才下发关闭）与
 * {@code sheet_read_range.withFormat}（缺省关，只有显式 true 才下发）。两个参数都在签名末尾、
 * 都是可选——旧会话按位置回放不受影响，模型不传时行为由 worker 缺省值决定。
 * 真引擎行为见 frontend/tests/lowa-e2e/sheet-format-inherit.mjs。
 */
class DocumentEditToolsSheetFormatInheritTest {

    private EditorBridgeService bridge;
    private DocumentEditTools tools;

    @BeforeEach
    void setUp() {
        bridge = mock(EditorBridgeService.class);
        tools = new DocumentEditTools(null, null, bridge, null, null, null, null, null);
        when(bridge.executeEditorCommand(anyString(), anyMap())).thenReturn("{\"success\":true}");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatched(String action) {
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeEditorCommand(eq(action), args.capture());
        return args.getValue();
    }

    @Test
    @DisplayName("sheet_write_cells 不传 inheritFormat：不下发该键，worker 按缺省沿用上一行格式")
    void writeCellsDefaultInherits() {
        tools.sheet_write_cells("A9", "[[6,\"保密协议\",880000]]", null, null);
        assertFalse(dispatched("sheet_write_cells").containsKey("inheritFormat"));
    }

    @Test
    @DisplayName("sheet_write_cells inheritFormat=false：下发 false 关闭沿用")
    void writeCellsCanOptOut() {
        tools.sheet_write_cells("A9", "[[6]]", "Sheet1", false);
        Map<String, Object> p = dispatched("sheet_write_cells");
        assertEquals(Boolean.FALSE, p.get("inheritFormat"));
        assertEquals("Sheet1", p.get("sheet"));
    }

    @Test
    @DisplayName("sheet_write_cells inheritFormat=true 与缺省同义，不下发多余的键")
    void writeCellsExplicitTrueIsDefault() {
        tools.sheet_write_cells("A9", "[[6]]", null, true);
        assertFalse(dispatched("sheet_write_cells").containsKey("inheritFormat"));
    }

    @Test
    @DisplayName("sheet_read_range 缺省不要格式；withFormat=true 才下发")
    void readRangeWithFormat() {
        tools.sheet_read_range("A3:E9", null, null);
        assertFalse(dispatched("sheet_read_range").containsKey("withFormat"));

        bridge = mock(EditorBridgeService.class);
        tools = new DocumentEditTools(null, null, bridge, null, null, null, null, null);
        when(bridge.executeEditorCommand(anyString(), anyMap())).thenReturn("{\"success\":true}");
        tools.sheet_read_range("A3:E9", null, true);
        assertEquals(Boolean.TRUE, dispatched("sheet_read_range").get("withFormat"));
    }

    @Test
    @DisplayName("两个新参数都在签名末尾且 @P(required=false)，描述写明沿用行为与 formatInherited")
    void newParamsAreOptionalAndLast() throws Exception {
        Method write = DocumentEditTools.class.getMethod("sheet_write_cells", String.class, String.class, String.class, Boolean.class);
        Parameter last = write.getParameters()[write.getParameterCount() - 1];
        assertFalse(last.getAnnotation(P.class).required(), "inheritFormat 必须可选");
        String writeDesc = String.join("", write.getAnnotation(dev.langchain4j.agent.tool.Tool.class).value());
        assertTrue(writeDesc.contains("formatInherited") && writeDesc.contains("inheritFormat=false"), writeDesc);

        Method read = DocumentEditTools.class.getMethod("sheet_read_range", String.class, String.class, Boolean.class);
        Parameter lastRead = read.getParameters()[read.getParameterCount() - 1];
        assertFalse(lastRead.getAnnotation(P.class).required(), "withFormat 必须可选");
        String readDesc = String.join("", read.getAnnotation(dev.langchain4j.agent.tool.Tool.class).value());
        assertTrue(readDesc.contains("withFormat=true"), readDesc);
    }
}
