// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code sheet_find_replace}（dev-board#804 / 审查 A16·B-04）的后端契约。
 *
 * <p>缺口：表格是三族里唯一「能找不能改」的面——Writer 有 doc_find_replace、Impress 有
 * slide_replace_text，Calc 只有只读的 sheet_search。模型只能 sheet_search 找坐标再
 * sheet_write_cells 回写，而后者是<b>按矩形区域写</b>的：散点命中要么拆成 N 次调用撞
 * MAX_LOOP_DEPTH，要么整块回写——把区域内不该动的格子一起覆盖掉（静默数据错误）。
 *
 * <p>本用例只盯后端这一层：参数校验必须在下发前拦住（不动编辑器），上限必须明示。
 */
class DocumentEditToolsSheetReplaceTest {

    private EditorBridgeService bridge;
    private DocumentEditTools tools;

    @BeforeEach
    void setUp() {
        bridge = mock(EditorBridgeService.class);
        tools = new DocumentEditTools(null, null, bridge, null, null, null, null, null);
    }

    @Test
    @DisplayName("参数齐备：下发 sheet_find_replace，可选参数按给了才透传")
    void dispatchesWithParams() {
        when(bridge.executeEditorCommand(eq("sheet_find_replace"), anyMap()))
                .thenReturn("{\"success\":true,\"replaced\":3}");

        String out = tools.sheet_find_replace("甲方", "买受人", "A1:D100", true, false, 100, "Sheet1");

        assertEquals("{\"success\":true,\"replaced\":3}", out);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeEditorCommand(eq("sheet_find_replace"), args.capture());
        Map<String, Object> p = args.getValue();
        assertEquals("甲方", p.get("find"));
        assertEquals("买受人", p.get("replace"));
        assertEquals("A1:D100", p.get("range"));
        assertEquals(Boolean.TRUE, p.get("matchCase"));
        assertEquals(Boolean.FALSE, p.get("wholeCell"));
        assertEquals(100, p.get("maxReplacements"));
        assertEquals("Sheet1", p.get("sheet"));
    }

    @Test
    @DisplayName("可选参数全空：只下发 find/replace，不塞空串（空 range 会被 worker 当无效区域）")
    void omitsBlankOptionals() {
        when(bridge.executeEditorCommand(eq("sheet_find_replace"), anyMap())).thenReturn("{\"success\":true}");

        tools.sheet_find_replace("甲方", "买受人", "  ", null, null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeEditorCommand(eq("sheet_find_replace"), args.capture());
        assertEquals(Map.of("find", "甲方", "replace", "买受人"), args.getValue());
    }

    @Test
    @DisplayName("replace 允许空串（= 删除命中文本），但不允许省略")
    void emptyReplaceMeansDelete() {
        when(bridge.executeEditorCommand(eq("sheet_find_replace"), anyMap())).thenReturn("{\"success\":true}");

        tools.sheet_find_replace("（草稿）", "", null, null, null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeEditorCommand(eq("sheet_find_replace"), args.capture());
        assertEquals("", args.getValue().get("replace"));

        assertTrue(tools.sheet_find_replace("甲方", null, null, null, null, null, null).startsWith("Error"),
                "省略 replace 不能当成「替换成空」——那是静默删数据");
    }

    @Test
    @DisplayName("参数不成立时一个编辑器命令都不发")
    void validationHappensBeforeDispatch() {
        assertTrue(tools.sheet_find_replace(null, "x", null, null, null, null, null).startsWith("Error"));
        assertTrue(tools.sheet_find_replace("  ", "x", null, null, null, null, null).startsWith("Error"));
        assertTrue(tools.sheet_find_replace("甲方", "甲方", null, null, null, null, null).startsWith("Error"),
                "find 与 replace 相同是空操作，拦下来省一轮");
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("maxReplacements 非法/超硬顶：报错点明范围，不静默夹逼")
    void maxReplacementsBounded() {
        String tooSmall = tools.sheet_find_replace("甲方", "买受人", null, null, null, 0, null);
        assertTrue(tooSmall.startsWith("Error"), tooSmall);
        assertTrue(tooSmall.contains(String.valueOf(DocumentEditTools.MAX_SHEET_REPLACEMENTS)), tooSmall);

        String tooBig = tools.sheet_find_replace("甲方", "买受人", null, null, null,
                DocumentEditTools.MAX_SHEET_REPLACEMENTS + 1, null);
        assertTrue(tooBig.startsWith("Error"), tooBig);
        assertTrue(tooBig.contains(String.valueOf(DocumentEditTools.MAX_SHEET_REPLACEMENTS)), tooBig);
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("工具描述：写入即生效 + doc_undo 兜底 + 与 sheet_search 互相指路")
    void descriptionsCrossReference() throws Exception {
        String replaceDesc = toolDescription("sheet_find_replace");
        assertTrue(replaceDesc.contains("doc_undo"),
                "Calc 没有修订机制，唯一的后悔药必须写进描述：" + replaceDesc);
        assertTrue(replaceDesc.contains("sheet_search"), replaceDesc);

        String searchDesc = toolDescription("sheet_search");
        assertTrue(searchDesc.contains("sheet_find_replace"),
                "查找工具要指向替换工具，否则模型仍会走 sheet_write_cells 整块回写：" + searchDesc);
    }

    @Test
    @DisplayName("@ToolMeta 标 MODIFIED：编排器据此在本轮首个写入前建文档检查点")
    void markedAsModifying() throws Exception {
        ToolMeta meta = DocumentEditTools.class
                .getMethod("sheet_find_replace", String.class, String.class, String.class,
                        Boolean.class, Boolean.class, Integer.class, String.class)
                .getAnnotation(ToolMeta.class);
        assertNotNull(meta, "缺 @ToolMeta");
        assertEquals("MODIFIED", meta.fileEffect());
    }

    private static String toolDescription(String name) {
        for (java.lang.reflect.Method m : DocumentEditTools.class.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            dev.langchain4j.agent.tool.Tool t = m.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
            if (t != null) return String.join(" ", t.value());
        }
        throw new AssertionError("找不到工具方法 " + name);
    }
}
