package com.checkba.service.ai.tools;

import com.checkba.service.ai.OfficeBridgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * office_* 工具集：参数校验 + 桥调用透传。
 * 校验失败必须在后端拦下（不下发指令），错误以 "Error:" 前缀返回供模型自纠。
 */
class OfficeEditToolsTest {

    private OfficeBridgeService bridge;
    private OfficeEditTools tools;

    @BeforeEach
    void setUp() {
        bridge = mock(OfficeBridgeService.class);
        tools = new OfficeEditTools(bridge, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    @DisplayName("office_replace_text：参数齐备时下发 replace_text，结果透传")
    void replaceTextDispatches() {
        when(bridge.executeOfficeCommand(eq("conv-1"), eq("replace_text"), anyMap()))
                .thenReturn("{\"replaced\":1,\"tracked\":true}");

        String result = tools.office_replace_text("conv-1", "甲方", "买受人", false);

        assertEquals("{\"replaced\":1,\"tracked\":true}", result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("replace_text"), args.capture());
        assertEquals("甲方", args.getValue().get("searchText"));
        assertEquals("买受人", args.getValue().get("replaceText"));
        assertEquals(false, args.getValue().get("replaceAll"));
    }

    @Test
    @DisplayName("参数校验失败：返回 Error 前缀且不触碰桥（不产生 30 秒空等）")
    void validationFailuresDoNotTouchBridge() {
        assertTrue(tools.office_search("conv-1", " ").startsWith("Error"));
        assertTrue(tools.office_search("conv-1", "长".repeat(256)).startsWith("Error"));
        assertTrue(tools.office_replace_text("conv-1", "", "x", true).startsWith("Error"));
        assertTrue(tools.office_replace_text("conv-1", "找我", null, true).startsWith("Error"));
        assertTrue(tools.office_insert_text("conv-1", "", null, null).startsWith("Error"));
        assertTrue(tools.office_insert_text("conv-1", "文本", "锚点", "middle").startsWith("Error"));
        assertTrue(tools.office_add_comment("conv-1", "", "批注").startsWith("Error"));
        assertTrue(tools.office_add_comment("conv-1", "目标", " ").startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("office_insert_text：position 缺省归一为 after，锚点缺省传空串")
    void insertTextDefaults() {
        when(bridge.executeOfficeCommand(any(), eq("insert_text"), anyMap())).thenReturn("{}");

        tools.office_insert_text("conv-1", "新增条款", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("insert_text"), args.capture());
        assertEquals("after", args.getValue().get("position"));
        assertEquals("", args.getValue().get("anchorText"));
    }

    // ==================== 格式面（Word） ====================

    @Test
    @DisplayName("office_format_text：只下发给出的格式字段，枚举归一为小写短名")
    void formatTextDispatchesGivenFieldsOnly() {
        when(bridge.executeOfficeCommand(any(), eq("format_text"), anyMap()))
                .thenReturn("{\"formatted\":1,\"tracked\":true}");

        String result = tools.office_format_text("conv-1", "第一条", true, "楷体_GB2312", 12.0,
                true, null, "Wave", null, null, "#C00000");

        assertEquals("{\"formatted\":1,\"tracked\":true}", result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("format_text"), args.capture());
        Map<String, Object> sent = args.getValue();
        assertEquals("第一条", sent.get("anchorText"));
        assertEquals(true, sent.get("applyToAll"));
        assertEquals("楷体_GB2312", sent.get("fontName"));
        assertEquals(12.0, sent.get("fontSize"));
        assertEquals(true, sent.get("bold"));
        assertEquals("wave", sent.get("underline"));
        assertEquals("#C00000", sent.get("color"));
        assertFalse(sent.containsKey("italic"), "未给出的格式字段不应下发（避免把原格式改掉）");
        assertFalse(sent.containsKey("strikeThrough"));
    }

    @Test
    @DisplayName("office_set_paragraph_format：磅值与枚举透传，未给字段不下发")
    void setParagraphFormatDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("set_paragraph_format"), anyMap())).thenReturn("{}");

        tools.office_set_paragraph_format("conv-1", "第一条", null, "Justify", 18.0,
                null, 18.0, 24.0, null, null, "heading2");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("set_paragraph_format"), args.capture());
        Map<String, Object> sent = args.getValue();
        assertEquals("第一条", sent.get("anchorText"));
        assertEquals(false, sent.get("applyToAll"));
        assertEquals("justify", sent.get("alignment"));
        assertEquals(18.0, sent.get("lineSpacing"));
        assertEquals(18.0, sent.get("spaceAfter"));
        assertEquals(24.0, sent.get("firstLineIndent"));
        assertEquals("heading2", sent.get("styleBuiltIn"));
        assertFalse(sent.containsKey("spaceBefore"));
        assertFalse(sent.containsKey("leftIndent"));
    }

    @Test
    @DisplayName("office_get_formatting：锚点缺省传空串（插件端读当前选区/光标处）")
    void getFormattingDefaults() {
        when(bridge.executeOfficeCommand(any(), eq("get_formatting"), anyMap())).thenReturn("{}");

        tools.office_get_formatting("conv-1", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("get_formatting"), args.capture());
        assertEquals("", args.getValue().get("anchorText"));
    }

    @Test
    @DisplayName("格式工具参数校验失败：返回 Error 前缀且不触碰桥")
    void formatValidationFailuresDoNotTouchBridge() {
        // 空锚点 / 锚点超 255
        assertTrue(tools.office_format_text("conv-1", " ", null, "宋体", null,
                null, null, null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_format_text("conv-1", "长".repeat(256), null, "宋体", null,
                null, null, null, null, null, null).startsWith("Error"));
        // 一个格式参数都没给
        assertTrue(tools.office_format_text("conv-1", "第一条", null, null, null,
                null, null, null, null, null, null).startsWith("Error"));
        // 非法枚举 / 非法颜色 / 非法字号
        assertTrue(tools.office_format_text("conv-1", "第一条", null, null, null,
                null, null, "squiggly", null, null, null).startsWith("Error"));
        assertTrue(tools.office_format_text("conv-1", "第一条", null, null, null,
                null, null, null, null, null, "红色").startsWith("Error"));
        assertTrue(tools.office_format_text("conv-1", "第一条", null, null, 0.0,
                null, null, null, null, null, null).startsWith("Error"));
        // 段落面
        assertTrue(tools.office_set_paragraph_format("conv-1", "", null, "left", null,
                null, null, null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_set_paragraph_format("conv-1", "第一条", null, null, null,
                null, null, null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_set_paragraph_format("conv-1", "第一条", null, "middle", null,
                null, null, null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_set_paragraph_format("conv-1", "第一条", null, null, null,
                null, null, null, null, null, "heading9").startsWith("Error"));
        assertTrue(tools.office_set_paragraph_format("conv-1", "第一条", null, null, -1.0,
                null, null, null, null, null, null).startsWith("Error"));
        // 读取面
        assertTrue(tools.office_get_formatting("conv-1", "长".repeat(256)).startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    // ==================== Excel/PPT 面（宿主细分工具） ====================

    @Test
    @DisplayName("office_excel_get_range：地址与工作表名缺省传空串（插件端取活动表已用区域）")
    void excelGetRangeDefaults() {
        when(bridge.executeOfficeCommand(any(), eq("excel_get_range"), anyMap())).thenReturn("{}");

        tools.office_excel_get_range("conv-1", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_get_range"), args.capture());
        assertEquals("", args.getValue().get("sheetName"));
        assertEquals("", args.getValue().get("rangeAddress"));
    }

    @Test
    @DisplayName("office_excel_set_values：valuesJson 解析为二维数组下发，地址透传")
    void excelSetValuesDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("excel_set_values"), anyMap())).thenReturn("{}");

        tools.office_excel_set_values("conv-1", "Sheet1", "B2", "[[\"名称\",\"金额\"],[\"甲\",100]]");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_set_values"), args.capture());
        assertEquals("B2", args.getValue().get("rangeAddress"));
        @SuppressWarnings("unchecked")
        java.util.List<java.util.List<Object>> values =
                (java.util.List<java.util.List<Object>>) args.getValue().get("values");
        assertEquals(2, values.size());
        assertEquals("名称", values.get(0).get(0));
        assertEquals(100, values.get(1).get(1));
    }

    @Test
    @DisplayName("Excel/PPT 参数校验失败：返回 Error 前缀且不触碰桥")
    void excelPptValidationFailuresDoNotTouchBridge() {
        // 非法区域地址（带工作表名/乱写）
        assertTrue(tools.office_excel_get_range("conv-1", null, "Sheet1!A1").startsWith("Error"));
        assertTrue(tools.office_excel_set_values("conv-1", null, "", "[[1]]").startsWith("Error"));
        assertTrue(tools.office_excel_set_values("conv-1", null, "not-a-range", "[[1]]").startsWith("Error"));
        // valuesJson 非法/非矩形/元素类型非法/超上限
        assertTrue(tools.office_excel_set_values("conv-1", null, "A1", "not json").startsWith("Error"));
        assertTrue(tools.office_excel_set_values("conv-1", null, "A1", "[]").startsWith("Error"));
        assertTrue(tools.office_excel_set_values("conv-1", null, "A1", "[[1,2],[3]]").startsWith("Error"));
        assertTrue(tools.office_excel_set_values("conv-1", null, "A1", "[[{\"a\":1}]]").startsWith("Error"));
        String huge = "[[" + "1,".repeat(2000) + "1]]";
        assertTrue(tools.office_excel_set_values("conv-1", null, "A1", huge).startsWith("Error"));
        // 查找与替换
        assertTrue(tools.office_excel_search("conv-1", null, " ").startsWith("Error"));
        assertTrue(tools.office_excel_search("conv-1", null, "长".repeat(256)).startsWith("Error"));
        assertTrue(tools.office_ppt_replace_text("conv-1", "", "x").startsWith("Error"));
        assertTrue(tools.office_ppt_replace_text("conv-1", "找我", null).startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("office_ppt_get_slides / office_ppt_replace_text：下发对应命令并透传结果")
    void pptToolsDispatch() {
        when(bridge.executeOfficeCommand(eq("conv-1"), eq("ppt_get_slides"), anyMap()))
                .thenReturn("{\"slides\":[]}");
        assertEquals("{\"slides\":[]}", tools.office_ppt_get_slides("conv-1"));

        when(bridge.executeOfficeCommand(eq("conv-1"), eq("ppt_replace_text"), anyMap()))
                .thenReturn("{\"replaced\":2}");
        String result = tools.office_ppt_replace_text("conv-1", "旧标题", "新标题");
        assertEquals("{\"replaced\":2}", result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("ppt_replace_text"), args.capture());
        assertEquals("旧标题", args.getValue().get("searchText"));
        assertEquals("新标题", args.getValue().get("replaceText"));
    }
}
