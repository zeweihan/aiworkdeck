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

    private com.checkba.repository.ProjectFileRepository projectFileRepository;
    private com.checkba.storage.StorageServiceFactory storageServiceFactory;

    @BeforeEach
    void setUp() {
        bridge = mock(OfficeBridgeService.class);
        projectFileRepository = mock(com.checkba.repository.ProjectFileRepository.class);
        storageServiceFactory = mock(com.checkba.storage.StorageServiceFactory.class);
        tools = new OfficeEditTools(bridge, new com.fasterxml.jackson.databind.ObjectMapper(),
                projectFileRepository, storageServiceFactory);
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

    // ==================== 编号 / 表格 / 标准格式（Word 面批次 4B） ====================

    @Test
    @DisplayName("office_set_numbering：段数缺省为 1，枚举归一为小写短名")
    void setNumberingDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("set_numbering"), anyMap()))
                .thenReturn("{\"paragraphs\":1,\"via\":\"listApi\"}");

        String result = tools.office_set_numbering("conv-1", "第一条", null, "Decimal");

        assertEquals("{\"paragraphs\":1,\"via\":\"listApi\"}", result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("set_numbering"), args.capture());
        assertEquals("第一条", args.getValue().get("anchorText"));
        assertEquals(1, args.getValue().get("paragraphCount"));
        assertEquals("decimal", args.getValue().get("kind"));
    }

    @Test
    @DisplayName("office_format_table：序号缺省 0，borders 带出颜色与粗细默认值")
    void formatTableDispatchesWithBorderDefaults() {
        when(bridge.executeOfficeCommand(any(), eq("format_table"), anyMap())).thenReturn("{}");

        tools.office_format_table("conv-1", null, "Outside", null, null, "center", true, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("format_table"), args.capture());
        Map<String, Object> sent = args.getValue();
        assertEquals(0, sent.get("tableIndex"));
        assertEquals("outside", sent.get("borders"));
        assertEquals("#000000", sent.get("borderColor"));
        assertEquals(1.0, sent.get("borderWidth"));
        assertEquals("center", sent.get("alignment"));
        assertEquals(true, sent.get("headerBold"));
        assertFalse(sent.containsKey("autoFit"), "未给出的格式字段不应下发");
        assertFalse(sent.containsKey("fontSize"));
    }

    @Test
    @DisplayName("office_format_table：borders=none 不带颜色与粗细（去线时这两个参数没有意义）")
    void formatTableNoneBordersOmitsModifiers() {
        when(bridge.executeOfficeCommand(any(), eq("format_table"), anyMap())).thenReturn("{}");

        tools.office_format_table("conv-1", 2, "none", "#C00000", 2.0, null, null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("format_table"), args.capture());
        Map<String, Object> sent = args.getValue();
        assertEquals(2, sent.get("tableIndex"));
        assertEquals("none", sent.get("borders"));
        assertFalse(sent.containsKey("borderColor"));
        assertFalse(sent.containsKey("borderWidth"));
    }

    @Test
    @DisplayName("office_apply_standard_format：scope 缺省为 document")
    void applyStandardFormatDefaults() {
        when(bridge.executeOfficeCommand(any(), eq("apply_standard_format"), anyMap())).thenReturn("{}");

        tools.office_apply_standard_format("conv-1", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("apply_standard_format"), args.capture());
        assertEquals("document", args.getValue().get("scope"));
    }

    @Test
    @DisplayName("编号/表格/标准格式的参数校验失败：返回 Error 前缀且不触碰桥")
    void batch4bValidationFailuresDoNotTouchBridge() {
        // 编号：空锚点 / 锚点超长 / kind 缺省或非法 / 段数越界
        assertTrue(tools.office_set_numbering("conv-1", " ", 1, "decimal").startsWith("Error"));
        assertTrue(tools.office_set_numbering("conv-1", "长".repeat(256), 1, "decimal").startsWith("Error"));
        assertTrue(tools.office_set_numbering("conv-1", "第一条", 1, null).startsWith("Error"));
        assertTrue(tools.office_set_numbering("conv-1", "第一条", 1, "roman").startsWith("Error"));
        assertTrue(tools.office_set_numbering("conv-1", "第一条", 0, "decimal").startsWith("Error"));
        assertTrue(tools.office_set_numbering("conv-1", "第一条", 201, "decimal").startsWith("Error"));
        // 表格：负序号 / 非法枚举 / 非法颜色与粗细 / 一个格式参数都没给
        assertTrue(tools.office_format_table("conv-1", -1, "all", null, null, null, null, null, null)
                .startsWith("Error"));
        assertTrue(tools.office_format_table("conv-1", 0, "diagonal", null, null, null, null, null, null)
                .startsWith("Error"));
        // 表格对齐没有 justify（Word 表格只有左/中/右）
        assertTrue(tools.office_format_table("conv-1", 0, null, null, null, "justify", null, null, null)
                .startsWith("Error"));
        assertTrue(tools.office_format_table("conv-1", 0, "all", "红色", null, null, null, null, null)
                .startsWith("Error"));
        assertTrue(tools.office_format_table("conv-1", 0, "all", null, 9.0, null, null, null, null)
                .startsWith("Error"));
        assertTrue(tools.office_format_table("conv-1", 0, null, null, null, null, null, null, 0.0)
                .startsWith("Error"));
        assertTrue(tools.office_format_table("conv-1", 0, null, "#000000", 1.0, null, null, null, null)
                .startsWith("Error"), "只给边框修饰参数不算给了格式参数");
        // 标准格式：非法 scope
        assertTrue(tools.office_apply_standard_format("conv-1", "page").startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    // ==================== 表格 / 结构 / 批注（批次 8） ====================

    @Test
    @DisplayName("office_insert_table：rowsJson 解析为二维数组下发，position 缺省为 after")
    void insertTableDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("insert_table"), anyMap())).thenReturn("{}");

        tools.office_insert_table("conv-1", "[[\"项目\",\"金额\"],[\"咨询费\",\"10000\"]]", true, "第一条", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("insert_table"), args.capture());
        Map<String, Object> sent = args.getValue();
        assertEquals(true, sent.get("headerBold"));
        assertEquals("第一条", sent.get("anchorText"));
        assertEquals("after", sent.get("position"));
        @SuppressWarnings("unchecked")
        java.util.List<java.util.List<String>> rows = (java.util.List<java.util.List<String>>) sent.get("rows");
        assertEquals("项目", rows.get(0).get(0));
        assertEquals("10000", rows.get(1).get(1));
    }

    @Test
    @DisplayName("office_table_set_cell：cell 坐标透传，tableIndex 缺省 0")
    void tableSetCellDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("table_set_cell"), anyMap())).thenReturn("{}");

        tools.office_table_set_cell("conv-1", null, "B2", "新内容");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("table_set_cell"), args.capture());
        assertEquals(0, args.getValue().get("tableIndex"));
        assertEquals("B2", args.getValue().get("cell"));
        assertEquals("新内容", args.getValue().get("text"));
    }

    @Test
    @DisplayName("office_table_add_row：rowIndex 缺省下发 -1（追加到表尾），count 缺省 1")
    void tableAddRowDefaults() {
        when(bridge.executeOfficeCommand(any(), eq("table_add_row"), anyMap())).thenReturn("{}");

        tools.office_table_add_row("conv-1", null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("table_add_row"), args.capture());
        assertEquals(0, args.getValue().get("tableIndex"));
        assertEquals(-1, args.getValue().get("position"));
        assertEquals(1, args.getValue().get("count"));
    }

    @Test
    @DisplayName("office_table_delete_row：rowIndex 必填，缺失时报错且不触碰桥")
    void tableDeleteRowRequiresRowIndex() {
        assertTrue(tools.office_table_delete_row("conv-1", 0, null, 1).startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("office_table_add_col / office_table_delete_col：colIndex 透传为 position")
    void tableColDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("table_add_col"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("table_delete_col"), anyMap())).thenReturn("{}");

        tools.office_table_add_col("conv-1", 1, 0, 2);
        tools.office_table_delete_col("conv-1", 1, 2, 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> addArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("table_add_col"), addArgs.capture());
        assertEquals(1, addArgs.getValue().get("tableIndex"));
        assertEquals(0, addArgs.getValue().get("position"));
        assertEquals(2, addArgs.getValue().get("count"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> delArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("table_delete_col"), delArgs.capture());
        assertEquals(2, delArgs.getValue().get("position"));
    }

    @Test
    @DisplayName("表格工具参数校验失败：返回 Error 前缀且不触碰桥")
    void tableValidationFailuresDoNotTouchBridge() {
        assertTrue(tools.office_insert_table("conv-1", null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_insert_table("conv-1", "not json", null, null, null).startsWith("Error"));
        assertTrue(tools.office_insert_table("conv-1", "[]", null, null, null).startsWith("Error"));
        assertTrue(tools.office_insert_table("conv-1", "[[\"a\",\"b\"],[\"c\"]]", null, null, null).startsWith("Error"));
        assertTrue(tools.office_insert_table("conv-1", "[[\"a\"]]", null, null, "middle").startsWith("Error"));
        assertTrue(tools.office_table_read("conv-1", -1).startsWith("Error"));
        assertTrue(tools.office_table_set_cell("conv-1", -1, "B2", "x").startsWith("Error"));
        assertTrue(tools.office_table_set_cell("conv-1", 0, "", "x").startsWith("Error"));
        assertTrue(tools.office_table_set_cell("conv-1", 0, "2B", "x").startsWith("Error"));
        assertTrue(tools.office_table_add_row("conv-1", -1, null, null).startsWith("Error"));
        assertTrue(tools.office_table_add_row("conv-1", 0, null, 0).startsWith("Error"));
        assertTrue(tools.office_table_add_row("conv-1", 0, null, 51).startsWith("Error"));
        assertTrue(tools.office_table_delete_col("conv-1", 0, null, 1).startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("office_insert_break：breakType/position 缺省，anchorText 缺省传空串")
    void insertBreakDefaults() {
        when(bridge.executeOfficeCommand(any(), eq("insert_break"), anyMap())).thenReturn("{}");

        tools.office_insert_break("conv-1", null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("insert_break"), args.capture());
        assertEquals("page", args.getValue().get("breakType"));
        assertEquals("", args.getValue().get("anchorText"));
        assertEquals("after", args.getValue().get("position"));
    }

    @Test
    @DisplayName("office_set_hyperlink：url 协议白名单校验")
    void setHyperlinkValidatesUrlProtocol() {
        when(bridge.executeOfficeCommand(any(), eq("set_hyperlink"), anyMap())).thenReturn("{}");

        tools.office_set_hyperlink("conv-1", "本所官网", "https://example.com");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("set_hyperlink"), args.capture());
        assertEquals("https://example.com", args.getValue().get("url"));

        assertTrue(tools.office_set_hyperlink("conv-1", "本所官网", "javascript:alert(1)").startsWith("Error"));
        assertTrue(tools.office_set_hyperlink("conv-1", "本所官网", "").startsWith("Error"));
        assertTrue(tools.office_set_hyperlink("conv-1", "", "https://example.com").startsWith("Error"));
    }

    @Test
    @DisplayName("office_edit_header_footer：part 白名单校验，text 缺省传空串")
    void editHeaderFooterValidatesPart() {
        when(bridge.executeOfficeCommand(any(), eq("edit_header_footer"), anyMap())).thenReturn("{}");

        tools.office_edit_header_footer("conv-1", "footer", null, "center");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("edit_header_footer"), args.capture());
        assertEquals("footer", args.getValue().get("part"));
        assertEquals("", args.getValue().get("text"));
        assertEquals("center", args.getValue().get("alignment"));

        assertTrue(tools.office_edit_header_footer("conv-1", "sidebar", "text", null).startsWith("Error"));
    }

    @Test
    @DisplayName("office_get_comments / office_reply_comment / office_resolve_comment：定位参数与透传")
    void commentToolsDispatch() {
        when(bridge.executeOfficeCommand(eq("conv-1"), eq("get_comments"), anyMap()))
                .thenReturn("{\"comments\":[]}");
        assertEquals("{\"comments\":[]}", tools.office_get_comments("conv-1"));

        when(bridge.executeOfficeCommand(any(), eq("reply_comment"), anyMap())).thenReturn("{}");
        tools.office_reply_comment("conv-1", null, 0, "已核实");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> replyArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("reply_comment"), replyArgs.capture());
        assertEquals(0, replyArgs.getValue().get("commentIndex"));
        assertEquals("已核实", replyArgs.getValue().get("reply"));

        when(bridge.executeOfficeCommand(any(), eq("resolve_comment"), anyMap())).thenReturn("{}");
        tools.office_resolve_comment("conv-1", "cmt-123", null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> resolveArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("resolve_comment"), resolveArgs.capture());
        assertEquals("cmt-123", resolveArgs.getValue().get("commentId"));
        assertEquals(true, resolveArgs.getValue().get("resolved"));

        assertTrue(tools.office_reply_comment("conv-1", null, null, "回复").startsWith("Error"));
        assertTrue(tools.office_reply_comment("conv-1", null, 0, " ").startsWith("Error"));
        assertTrue(tools.office_resolve_comment("conv-1", null, null, null).startsWith("Error"));
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

    // ==================== Excel 格式/结构（批次6） ====================

    @Test
    @DisplayName("office_excel_format_cells：只下发给出的格式字段，枚举归一为小写短名")
    void excelFormatCellsDispatchesGivenFieldsOnly() {
        when(bridge.executeOfficeCommand(any(), eq("excel_format_cells"), anyMap())).thenReturn("{}");

        tools.office_excel_format_cells("conv-1", "Sheet1", "A1:B2", "Arial", 12.0,
                true, null, "#C00000", null, "Center", "Top", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_format_cells"), args.capture());
        Map<String, Object> sent = args.getValue();
        assertEquals("Sheet1", sent.get("sheetName"));
        assertEquals("A1:B2", sent.get("rangeAddress"));
        assertEquals("Arial", sent.get("fontName"));
        assertEquals(12.0, sent.get("fontSize"));
        assertEquals(true, sent.get("bold"));
        assertEquals("#C00000", sent.get("fontColor"));
        assertEquals("center", sent.get("horizontalAlignment"));
        assertEquals("top", sent.get("verticalAlignment"));
        assertFalse(sent.containsKey("italic"));
        assertFalse(sent.containsKey("fillColor"));
        assertFalse(sent.containsKey("numberFormat"));
        assertFalse(sent.containsKey("wrapText"));
    }

    @Test
    @DisplayName("office_excel_set_borders：非 none 时带出 style/color 默认值，none 时不带修饰参数")
    void excelSetBordersDefaultsAndNone() {
        when(bridge.executeOfficeCommand(any(), eq("excel_set_borders"), anyMap())).thenReturn("{}");

        tools.office_excel_set_borders("conv-1", null, "A1:D10", "outside", null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args1 = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_set_borders"), args1.capture());
        assertEquals("outside", args1.getValue().get("borders"));
        assertEquals("thin", args1.getValue().get("style"));
        assertEquals("#000000", args1.getValue().get("color"));

        tools.office_excel_set_borders("conv-1", null, "A1:D10", "none", "thick", "#C00000");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args2 = ArgumentCaptor.forClass(Map.class);
        verify(bridge, org.mockito.Mockito.times(2))
                .executeOfficeCommand(eq("conv-1"), eq("excel_set_borders"), args2.capture());
        Map<String, Object> lastSent = args2.getAllValues().get(1);
        assertEquals("none", lastSent.get("borders"));
        assertFalse(lastSent.containsKey("style"));
        assertFalse(lastSent.containsKey("color"));
    }

    @Test
    @DisplayName("office_excel_edit_rows_cols：count 缺省为 1，set_width 需要 size")
    void excelEditRowsColsDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("excel_edit_rows_cols"), anyMap())).thenReturn("{}");

        tools.office_excel_edit_rows_cols("conv-1", "Sheet1", "insert_rows", 2, null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_edit_rows_cols"), args.capture());
        assertEquals("insert_rows", args.getValue().get("action"));
        assertEquals(2, args.getValue().get("index"));
        assertEquals(1, args.getValue().get("count"));
        assertFalse(args.getValue().containsKey("size"));

        tools.office_excel_edit_rows_cols("conv-1", "Sheet1", "set_width", 0, 3, 80.0);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args2 = ArgumentCaptor.forClass(Map.class);
        verify(bridge, org.mockito.Mockito.times(2))
                .executeOfficeCommand(eq("conv-1"), eq("excel_edit_rows_cols"), args2.capture());
        Map<String, Object> sent = args2.getAllValues().get(1);
        assertEquals("set_width", sent.get("action"));
        assertEquals(80.0, sent.get("size"));
    }

    @Test
    @DisplayName("office_excel_merge_cells / office_excel_sort_range：下发对应动作与默认值")
    void excelMergeAndSortDispatch() {
        when(bridge.executeOfficeCommand(any(), eq("excel_merge_cells"), anyMap())).thenReturn("{}");
        tools.office_excel_merge_cells("conv-1", null, "A1:D1", "merge");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> mergeArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_merge_cells"), mergeArgs.capture());
        assertEquals("merge", mergeArgs.getValue().get("action"));

        when(bridge.executeOfficeCommand(any(), eq("excel_sort_range"), anyMap())).thenReturn("{}");
        tools.office_excel_sort_range("conv-1", null, "A1:D20", 0, null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> sortArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_sort_range"), sortArgs.capture());
        assertEquals(0, sortArgs.getValue().get("keyColumn"));
        assertEquals(true, sortArgs.getValue().get("ascending"));
        assertEquals(false, sortArgs.getValue().get("hasHeader"));
    }

    @Test
    @DisplayName("office_excel_manage_sheets：按 action 下发对应必填字段")
    void excelManageSheetsDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("excel_manage_sheets"), anyMap())).thenReturn("{}");

        tools.office_excel_manage_sheets("conv-1", "rename", "Sheet1", "汇总表", null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_manage_sheets"), args.capture());
        assertEquals("rename", args.getValue().get("action"));
        assertEquals("Sheet1", args.getValue().get("sheetName"));
        assertEquals("汇总表", args.getValue().get("newName"));

        tools.office_excel_manage_sheets("conv-1", "add", null, null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args2 = ArgumentCaptor.forClass(Map.class);
        verify(bridge, org.mockito.Mockito.times(2))
                .executeOfficeCommand(eq("conv-1"), eq("excel_manage_sheets"), args2.capture());
        Map<String, Object> addSent = args2.getAllValues().get(1);
        assertEquals("add", addSent.get("action"));
        assertEquals("", addSent.get("sheetName"));
    }

    @Test
    @DisplayName("office_excel_freeze_panes / office_excel_set_formulas：下发对应字段")
    void excelFreezePanesAndSetFormulasDispatch() {
        when(bridge.executeOfficeCommand(any(), eq("excel_freeze_panes"), anyMap())).thenReturn("{}");
        tools.office_excel_freeze_panes("conv-1", null, "freeze_rows", null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> freezeArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_freeze_panes"), freezeArgs.capture());
        assertEquals("freeze_rows", freezeArgs.getValue().get("action"));
        assertEquals(1, freezeArgs.getValue().get("count"));

        when(bridge.executeOfficeCommand(any(), eq("excel_set_formulas"), anyMap())).thenReturn("{}");
        tools.office_excel_set_formulas("conv-1", "Sheet1", "B2", "[[\"=SUM(A1:A10)\"]]");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> formulaArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_set_formulas"), formulaArgs.capture());
        assertEquals("B2", formulaArgs.getValue().get("rangeAddress"));
        @SuppressWarnings("unchecked")
        java.util.List<java.util.List<Object>> formulas =
                (java.util.List<java.util.List<Object>>) formulaArgs.getValue().get("formulas");
        assertEquals("=SUM(A1:A10)", formulas.get(0).get(0));
    }

    @Test
    @DisplayName("office_excel_get_overview / office_excel_select_range：只读工具下发对应命令")
    void excelOverviewAndSelectRangeDispatch() {
        when(bridge.executeOfficeCommand(eq("conv-1"), eq("excel_get_overview"), anyMap()))
                .thenReturn("{\"sheetCount\":1}");
        assertEquals("{\"sheetCount\":1}", tools.office_excel_get_overview("conv-1"));

        when(bridge.executeOfficeCommand(any(), eq("excel_select_range"), anyMap())).thenReturn("{}");
        tools.office_excel_select_range("conv-1", "Sheet1", "B2:C4");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_select_range"), args.capture());
        assertEquals("Sheet1", args.getValue().get("sheetName"));
        assertEquals("B2:C4", args.getValue().get("rangeAddress"));
    }

    @Test
    @DisplayName("office_excel_set_autofilter：apply 带出 rangeAddress，clear/remove 不带")
    void excelSetAutofilterDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("excel_set_autofilter"), anyMap())).thenReturn("{}");

        tools.office_excel_set_autofilter("conv-1", null, "A1:D20", "apply");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> applyArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_set_autofilter"), applyArgs.capture());
        assertEquals("apply", applyArgs.getValue().get("action"));
        assertEquals("A1:D20", applyArgs.getValue().get("rangeAddress"));

        tools.office_excel_set_autofilter("conv-1", null, null, "remove");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> removeArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge, org.mockito.Mockito.times(2))
                .executeOfficeCommand(eq("conv-1"), eq("excel_set_autofilter"), removeArgs.capture());
        Map<String, Object> lastSent = removeArgs.getAllValues().get(1);
        assertEquals("remove", lastSent.get("action"));
        assertFalse(lastSent.containsKey("rangeAddress"));
    }

    @Test
    @DisplayName("office_excel_conditional_format：cellValue/colorScale/clearAll 三种下发路径")
    void excelConditionalFormatDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("excel_conditional_format"), anyMap())).thenReturn("{}");

        tools.office_excel_conditional_format("conv-1", null, "A1:A10", "cellValue", "greaterThan",
                100.0, null, null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cellValueArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_conditional_format"), cellValueArgs.capture());
        Map<String, Object> sent1 = cellValueArgs.getValue();
        assertEquals("apply", sent1.get("action"));
        assertEquals("cellvalue", sent1.get("ruleType"));
        assertEquals("greaterthan", sent1.get("operator"));
        assertEquals(100.0, sent1.get("value1"));
        assertEquals("#FFC7CE", sent1.get("fillColor"));
        assertFalse(sent1.containsKey("value2"));

        tools.office_excel_conditional_format("conv-1", null, "A1:A10", "colorScale", null,
                null, null, null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> colorScaleArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge, org.mockito.Mockito.times(2))
                .executeOfficeCommand(eq("conv-1"), eq("excel_conditional_format"), colorScaleArgs.capture());
        Map<String, Object> sent2 = colorScaleArgs.getAllValues().get(1);
        assertEquals("colorscale", sent2.get("ruleType"));
        assertFalse(sent2.containsKey("operator"));

        tools.office_excel_conditional_format("conv-1", null, "A1:A10", null, null, null, null, null, "clearAll");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> clearArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge, org.mockito.Mockito.times(3))
                .executeOfficeCommand(eq("conv-1"), eq("excel_conditional_format"), clearArgs.capture());
        Map<String, Object> sent3 = clearArgs.getAllValues().get(2);
        assertEquals("clearall", sent3.get("action"));
        assertFalse(sent3.containsKey("ruleType"));
    }

    @Test
    @DisplayName("Excel 只读/筛选/条件格式工具参数校验失败：返回 Error 前缀且不触碰桥")
    void batch6ExtraValidationFailuresDoNotTouchBridge() {
        // select_range：地址为空/非法
        assertTrue(tools.office_excel_select_range("conv-1", null, "").startsWith("Error"));
        assertTrue(tools.office_excel_select_range("conv-1", null, "Sheet1!A1").startsWith("Error"));

        // set_autofilter：非法 action / apply 缺地址
        assertTrue(tools.office_excel_set_autofilter("conv-1", null, "A1:D20", "toggle").startsWith("Error"));
        assertTrue(tools.office_excel_set_autofilter("conv-1", null, null, "apply").startsWith("Error"));

        // conditional_format：地址为空 / apply 缺 ruleType / cellValue 缺 operator 或 value1 /
        // between 缺 value2 / 非法枚举 / 非法颜色
        assertTrue(tools.office_excel_conditional_format("conv-1", null, "", "cellValue", "greaterThan",
                1.0, null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_conditional_format("conv-1", null, "A1:A10", null, null,
                null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_conditional_format("conv-1", null, "A1:A10", "cellValue", null,
                1.0, null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_conditional_format("conv-1", null, "A1:A10", "cellValue", "greaterThan",
                null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_conditional_format("conv-1", null, "A1:A10", "cellValue", "between",
                1.0, null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_conditional_format("conv-1", null, "A1:A10", "heatmap", "greaterThan",
                1.0, null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_conditional_format("conv-1", null, "A1:A10", "cellValue", "greaterThan",
                1.0, null, "红色", null).startsWith("Error"));

        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("Excel 格式/结构工具参数校验失败：返回 Error 前缀且不触碰桥")
    void batch6ValidationFailuresDoNotTouchBridge() {
        // format_cells：地址非法 / 一个格式参数都没给 / 非法枚举 / 非法颜色 / 非法字号
        assertTrue(tools.office_excel_format_cells("conv-1", null, "Sheet1!A1", null, null,
                null, null, null, null, null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_format_cells("conv-1", null, "A1", null, null,
                null, null, null, null, null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_format_cells("conv-1", null, "A1", null, null,
                null, null, null, null, "diagonal", null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_format_cells("conv-1", null, "A1", null, null,
                null, null, "红色", null, null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_format_cells("conv-1", null, "A1", null, 0.0,
                null, null, null, null, null, null, null, null).startsWith("Error"));

        // set_borders：地址为空 / borders 缺省或非法 / 非法颜色
        assertTrue(tools.office_excel_set_borders("conv-1", null, "", "all", null, null).startsWith("Error"));
        assertTrue(tools.office_excel_set_borders("conv-1", null, "A1", null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_set_borders("conv-1", null, "A1", "diagonal", null, null).startsWith("Error"));
        assertTrue(tools.office_excel_set_borders("conv-1", null, "A1", "all", null, "红色").startsWith("Error"));

        // edit_rows_cols：非法 action / index 缺失或为负 / count 越界 / set_height 缺 size
        assertTrue(tools.office_excel_edit_rows_cols("conv-1", null, "shuffle", 0, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_edit_rows_cols("conv-1", null, "insert_rows", null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_edit_rows_cols("conv-1", null, "insert_rows", -1, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_edit_rows_cols("conv-1", null, "insert_rows", 0, 101, null).startsWith("Error"));
        assertTrue(tools.office_excel_edit_rows_cols("conv-1", null, "set_height", 0, null, null).startsWith("Error"));

        // merge_cells：地址为空 / 非法 action
        assertTrue(tools.office_excel_merge_cells("conv-1", null, "", "merge").startsWith("Error"));
        assertTrue(tools.office_excel_merge_cells("conv-1", null, "A1:B2", "combine").startsWith("Error"));

        // sort_range：地址为空 / keyColumn 缺失或为负
        assertTrue(tools.office_excel_sort_range("conv-1", null, "", 0, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_sort_range("conv-1", null, "A1:D20", null, null, null).startsWith("Error"));
        assertTrue(tools.office_excel_sort_range("conv-1", null, "A1:D20", -1, null, null).startsWith("Error"));

        // manage_sheets：非法 action / 非 add 时 sheetName 为空 / rename 缺 newName / move 缺 position
        assertTrue(tools.office_excel_manage_sheets("conv-1", "duplicate", "Sheet1", null, null).startsWith("Error"));
        assertTrue(tools.office_excel_manage_sheets("conv-1", "rename", null, "新名", null).startsWith("Error"));
        assertTrue(tools.office_excel_manage_sheets("conv-1", "rename", "Sheet1", null, null).startsWith("Error"));
        assertTrue(tools.office_excel_manage_sheets("conv-1", "move", "Sheet1", null, null).startsWith("Error"));

        // freeze_panes：非法 action / freeze_at 缺 cellAddress
        assertTrue(tools.office_excel_freeze_panes("conv-1", null, "pin", null, null).startsWith("Error"));
        assertTrue(tools.office_excel_freeze_panes("conv-1", null, "freeze_at", null, null).startsWith("Error"));

        // set_formulas：地址为空 / JSON 非法 / 空数组 / 非矩形 / 元素不以 = 开头 / 超上限
        assertTrue(tools.office_excel_set_formulas("conv-1", null, "", "[[\"=A1\"]]").startsWith("Error"));
        assertTrue(tools.office_excel_set_formulas("conv-1", null, "A1", "not json").startsWith("Error"));
        assertTrue(tools.office_excel_set_formulas("conv-1", null, "A1", "[]").startsWith("Error"));
        assertTrue(tools.office_excel_set_formulas("conv-1", null, "A1", "[[\"=A1\"],[\"=A2\",\"=A3\"]]").startsWith("Error"));
        assertTrue(tools.office_excel_set_formulas("conv-1", null, "A1", "[[\"B1+1\"]]").startsWith("Error"));
        String hugeFormulas = "[[" + "\"=A1\",".repeat(2000) + "\"=A1\"]]";
        assertTrue(tools.office_excel_set_formulas("conv-1", null, "A1", hugeFormulas).startsWith("Error"));
    }

    // ==================== PowerPoint 能力对齐（批次7） ====================

    @Test
    @DisplayName("office_ppt_format_text：只下发给出的格式字段，枚举归一为小写短名")
    void pptFormatTextDispatchesGivenFieldsOnly() {
        when(bridge.executeOfficeCommand(any(), eq("ppt_format_text"), anyMap()))
                .thenReturn("{\"formatted\":1}");

        String result = tools.office_ppt_format_text("conv-1", "项目介绍", true, "微软雅黑", 24.0,
                true, null, "Wave", "#C00000");

        assertEquals("{\"formatted\":1}", result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("ppt_format_text"), args.capture());
        Map<String, Object> sent = args.getValue();
        assertEquals("项目介绍", sent.get("searchText"));
        assertEquals(true, sent.get("applyToAll"));
        assertEquals("微软雅黑", sent.get("fontName"));
        assertEquals(24.0, sent.get("fontSize"));
        assertEquals(true, sent.get("bold"));
        assertEquals("wave", sent.get("underline"));
        assertEquals("#C00000", sent.get("color"));
        assertFalse(sent.containsKey("italic"), "未给出的格式字段不应下发");
    }

    @Test
    @DisplayName("office_ppt_add_slide：position 缺省不下发，仅给出的字段透传")
    void pptAddSlideDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("ppt_add_slide"), anyMap())).thenReturn("{}");

        tools.office_ppt_add_slide("conv-1", 2, "标题", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("ppt_add_slide"), args.capture());
        Map<String, Object> sent = args.getValue();
        assertEquals(2, sent.get("position"));
        assertEquals("标题", sent.get("title"));
        assertFalse(sent.containsKey("body"));

        tools.office_ppt_add_slide("conv-1", null, null, null);
        verify(bridge, org.mockito.Mockito.times(2))
                .executeOfficeCommand(eq("conv-1"), eq("ppt_add_slide"), args.capture());
        assertFalse(args.getValue().containsKey("position"));
    }

    @Test
    @DisplayName("office_ppt_delete_slide / office_ppt_move_slide / office_ppt_get_slide_details：页码透传")
    void pptSlideStructureDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("ppt_delete_slide"), anyMap())).thenReturn("{}");
        tools.office_ppt_delete_slide("conv-1", 3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> deleteArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("ppt_delete_slide"), deleteArgs.capture());
        assertEquals(3, deleteArgs.getValue().get("slideNumber"));

        when(bridge.executeOfficeCommand(any(), eq("ppt_move_slide"), anyMap())).thenReturn("{}");
        tools.office_ppt_move_slide("conv-1", 1, 4);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> moveArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("ppt_move_slide"), moveArgs.capture());
        assertEquals(1, moveArgs.getValue().get("slideNumber"));
        assertEquals(4, moveArgs.getValue().get("toPosition"));

        when(bridge.executeOfficeCommand(any(), eq("ppt_get_slide_details"), anyMap())).thenReturn("{}");
        tools.office_ppt_get_slide_details("conv-1", 2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("ppt_get_slide_details"), detailArgs.capture());
        assertEquals(2, detailArgs.getValue().get("slideNumber"));
    }

    @Test
    @DisplayName("office_ppt_add_text_box / office_ppt_add_shape：位置尺寸未给不下发，给了就透传")
    void pptAddTextBoxAndShapeDispatch() {
        when(bridge.executeOfficeCommand(any(), eq("ppt_add_text_box"), anyMap())).thenReturn("{}");
        tools.office_ppt_add_text_box("conv-1", 1, "正文", null, null, null, null, 18.0, true, "#000000");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> boxArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("ppt_add_text_box"), boxArgs.capture());
        Map<String, Object> sentBox = boxArgs.getValue();
        assertEquals(1, sentBox.get("slideNumber"));
        assertEquals("正文", sentBox.get("text"));
        assertEquals(18.0, sentBox.get("fontSize"));
        assertFalse(sentBox.containsKey("left"), "未给出的位置字段不应下发（插件端用默认值）");

        when(bridge.executeOfficeCommand(any(), eq("ppt_add_shape"), anyMap())).thenReturn("{}");
        tools.office_ppt_add_shape("conv-1", 1, "Ellipse", 10.0, 20.0, null, null, "#4472C4");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> shapeArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("ppt_add_shape"), shapeArgs.capture());
        Map<String, Object> sentShape = shapeArgs.getValue();
        assertEquals("ellipse", sentShape.get("shapeType"));
        assertEquals(10.0, sentShape.get("left"));
        assertEquals("#4472C4", sentShape.get("fillColor"));
        assertFalse(sentShape.containsKey("width"));
    }

    @Test
    @DisplayName("office_ppt_delete_shape：shapeId 与 textMatch 二选一透传")
    void pptDeleteShapeDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("ppt_delete_shape"), anyMap())).thenReturn("{}");

        tools.office_ppt_delete_shape("conv-1", 1, "shape-42", null);
        tools.office_ppt_delete_shape("conv-1", 1, null, "旧标题");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge, org.mockito.Mockito.times(2))
                .executeOfficeCommand(eq("conv-1"), eq("ppt_delete_shape"), args.capture());
        java.util.List<Map<String, Object>> sent = args.getAllValues();
        assertEquals("shape-42", sent.get(0).get("shapeId"));
        assertFalse(sent.get(0).containsKey("textMatch"));
        assertEquals("旧标题", sent.get(1).get("textMatch"));
        assertFalse(sent.get(1).containsKey("shapeId"));
    }

    @Test
    @DisplayName("PowerPoint 能力对齐工具的参数校验失败：返回 Error 前缀且不触碰桥")
    void pptCapabilityParityValidationFailuresDoNotTouchBridge() {
        // format_text：空查找 / 超长 / 非法枚举 / 非法颜色 / 未给任何格式参数
        assertTrue(tools.office_ppt_format_text("conv-1", " ", null, "宋体", null,
                null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_ppt_format_text("conv-1", "长".repeat(256), null, "宋体", null,
                null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_ppt_format_text("conv-1", "标题", null, null, null,
                null, null, "squiggly", null).startsWith("Error"));
        assertTrue(tools.office_ppt_format_text("conv-1", "标题", null, null, null,
                null, null, null, "红色").startsWith("Error"));
        assertTrue(tools.office_ppt_format_text("conv-1", "标题", null, null, null,
                null, null, null, null).startsWith("Error"));
        // add_slide：position 越界
        assertTrue(tools.office_ppt_add_slide("conv-1", 0, null, null).startsWith("Error"));
        // delete_slide / move_slide / get_slide_details：页码非法
        assertTrue(tools.office_ppt_delete_slide("conv-1", null).startsWith("Error"));
        assertTrue(tools.office_ppt_delete_slide("conv-1", 0).startsWith("Error"));
        assertTrue(tools.office_ppt_move_slide("conv-1", 1, null).startsWith("Error"));
        assertTrue(tools.office_ppt_move_slide("conv-1", 0, 1).startsWith("Error"));
        assertTrue(tools.office_ppt_get_slide_details("conv-1", null).startsWith("Error"));
        // add_text_box：空内容 / 非法尺寸
        assertTrue(tools.office_ppt_add_text_box("conv-1", 1, "", null, null, null, null,
                null, null, null).startsWith("Error"));
        assertTrue(tools.office_ppt_add_text_box("conv-1", 1, "正文", null, null, 0.0, null,
                null, null, null).startsWith("Error"));
        // add_shape：非法形状类型 / 非法颜色
        assertTrue(tools.office_ppt_add_shape("conv-1", 1, "circle", null, null, null, null, null)
                .startsWith("Error"));
        assertTrue(tools.office_ppt_add_shape("conv-1", 1, "rectangle", null, null, null, null, "红色")
                .startsWith("Error"));
        // delete_shape：shapeId 与 textMatch 都不给
        assertTrue(tools.office_ppt_delete_shape("conv-1", 1, null, null).startsWith("Error"));
        assertTrue(tools.office_ppt_delete_shape("conv-1", 1, " ", " ").startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    // ==================== 批次 9：Excel 批注 ====================

    @Test
    @DisplayName("Excel 批注四件套 + 添加：正常下发透传")
    void excelCommentGroupDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("excel_add_comment"), anyMap())).thenReturn("{\"added\":true}");
        when(bridge.executeOfficeCommand(any(), eq("excel_get_comments"), anyMap())).thenReturn("{\"count\":0}");
        when(bridge.executeOfficeCommand(any(), eq("excel_reply_comment"), anyMap())).thenReturn("{\"replied\":true}");
        when(bridge.executeOfficeCommand(any(), eq("excel_resolve_comment"), anyMap())).thenReturn("{\"resolved\":true}");
        when(bridge.executeOfficeCommand(any(), eq("excel_delete_comment"), anyMap())).thenReturn("{\"deleted\":true}");

        assertEquals("{\"added\":true}", tools.office_excel_add_comment("conv-1", "Sheet1", "B2", "核对金额"));
        assertEquals("{\"count\":0}", tools.office_excel_get_comments("conv-1", "Sheet1", null));
        assertEquals("{\"replied\":true}", tools.office_excel_reply_comment("conv-1", "Sheet1", "B2", "已核对"));
        assertEquals("{\"resolved\":true}", tools.office_excel_resolve_comment("conv-1", "Sheet1", "B2", null));
        assertEquals("{\"deleted\":true}", tools.office_excel_delete_comment("conv-1", "Sheet1", "B2"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> addArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_add_comment"), addArgs.capture());
        assertEquals("B2", addArgs.getValue().get("cellAddress"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> getArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_get_comments"), getArgs.capture());
        assertEquals("sheet", getArgs.getValue().get("scope"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> resolveArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_resolve_comment"), resolveArgs.capture());
        assertEquals(true, resolveArgs.getValue().get("resolved"));
    }

    @Test
    @DisplayName("Excel 批注参数校验失败：单格地址非法则拦下")
    void excelCommentValidationFailuresDoNotTouchBridge() {
        assertTrue(tools.office_excel_add_comment("conv-1", null, "B2:C3", "x").startsWith("Error"));
        assertTrue(tools.office_excel_add_comment("conv-1", null, "B2", " ").startsWith("Error"));
        assertTrue(tools.office_excel_get_comments("conv-1", null, "invalid").startsWith("Error"));
        assertTrue(tools.office_excel_reply_comment("conv-1", null, "B2", "").startsWith("Error"));
        assertTrue(tools.office_excel_resolve_comment("conv-1", null, "", null).startsWith("Error"));
        assertTrue(tools.office_excel_delete_comment("conv-1", null, "B2:C3").startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    // ==================== 批次 9：Excel 数据验证/图表/命名区域/保护/分组/透视表 ====================

    @Test
    @DisplayName("office_excel_set_data_validation：list 与 wholeNumber 两种规则正常下发")
    void excelDataValidationDispatches() {
        when(bridge.executeOfficeCommand(any(), eq("excel_set_data_validation"), anyMap())).thenReturn("{}");

        tools.office_excel_set_data_validation("conv-1", null, "B2:B10", null, "list", null, null, null, "是,否,待定");
        tools.office_excel_set_data_validation("conv-1", null, "C2:C10", null, "wholeNumber", "between", "0", "100", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(bridge, org.mockito.Mockito.times(2))
                .executeOfficeCommand(eq("conv-1"), eq("excel_set_data_validation"), args.capture());
        java.util.List<Map<String, Object>> sent = args.getAllValues();
        assertEquals("list", sent.get(0).get("type"));
        assertEquals("是,否,待定", sent.get(0).get("listSource"));
        assertEquals("wholenumber", sent.get(1).get("type"));
        assertEquals("between", sent.get(1).get("operator"));
        assertEquals("100", sent.get(1).get("value2"));
    }

    @Test
    @DisplayName("office_excel_add_chart / define_name / protect_sheet / group_rows_cols / add_pivot_table：正常下发")
    void excelMiscToolsDispatch() {
        when(bridge.executeOfficeCommand(any(), eq("excel_add_chart"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("excel_define_name"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("excel_protect_sheet"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("excel_group_rows_cols"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("excel_add_pivot_table"), anyMap())).thenReturn("{}");

        assertEquals("{}", tools.office_excel_add_chart("conv-1", null, "A1:C5", "column", "季度收入"));
        assertEquals("{}", tools.office_excel_define_name("conv-1", null, "add", "ExpensesHeader", "A1:D1"));
        assertEquals("{}", tools.office_excel_protect_sheet("conv-1", null, "protect", "s3cr3t"));
        assertEquals("{}", tools.office_excel_group_rows_cols("conv-1", null, "4:9", "group", "rows"));
        assertEquals("{}", tools.office_excel_add_pivot_table("conv-1", null, "A1:D50", "F1",
                "[\"部门\"]", "[\"金额\"]", "透视表1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> pivotArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("excel_add_pivot_table"), pivotArgs.capture());
        assertEquals(java.util.List.of("部门"), pivotArgs.getValue().get("rowFields"));
        assertEquals(java.util.List.of("金额"), pivotArgs.getValue().get("valueFields"));
    }

    @Test
    @DisplayName("Excel 批次9 misc 工具参数校验失败：拦下不触碰桥")
    void excelMiscValidationFailuresDoNotTouchBridge() {
        assertTrue(tools.office_excel_add_chart("conv-1", null, "A1:C5", "circle", null).startsWith("Error"));
        assertTrue(tools.office_excel_define_name("conv-1", null, "add", "1bad", "A1").startsWith("Error"));
        assertTrue(tools.office_excel_define_name("conv-1", null, "add", "Good", null).startsWith("Error"));
        assertTrue(tools.office_excel_protect_sheet("conv-1", null, "toggle", null).startsWith("Error"));
        assertTrue(tools.office_excel_group_rows_cols("conv-1", null, "A1:C5", "group", "rows").startsWith("Error"));
        assertTrue(tools.office_excel_group_rows_cols("conv-1", null, "4:9", "group", "cols").startsWith("Error"));
        assertTrue(tools.office_excel_add_pivot_table("conv-1", null, "", "F1", "[\"部门\"]", "[\"金额\"]", null)
                .startsWith("Error"));
        assertTrue(tools.office_excel_add_pivot_table("conv-1", null, "A1:D50", "F1", "[]", "[\"金额\"]", null)
                .startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    // ==================== 批次 9：Word 修订接受/拒绝 ====================

    @Test
    @DisplayName("office_get_revisions/accept_revision/reject_revision：正常下发")
    void wordRevisionToolsDispatch() {
        when(bridge.executeOfficeCommand(any(), eq("get_revisions"), anyMap())).thenReturn("{\"count\":0}");
        when(bridge.executeOfficeCommand(any(), eq("accept_revision"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("reject_revision"), anyMap())).thenReturn("{}");

        assertEquals("{\"count\":0}", tools.office_get_revisions("conv-1"));
        tools.office_accept_revision("conv-1", 2, null);
        tools.office_reject_revision("conv-1", null, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> acceptArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("accept_revision"), acceptArgs.capture());
        assertEquals(false, acceptArgs.getValue().get("all"));
        assertEquals(2, acceptArgs.getValue().get("revisionIndex"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> rejectArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("reject_revision"), rejectArgs.capture());
        assertEquals(true, rejectArgs.getValue().get("all"));
        assertFalse(rejectArgs.getValue().containsKey("revisionIndex"));
    }

    @Test
    @DisplayName("修订接受/拒绝：既没有 index 也没有 all 时拦下")
    void wordRevisionValidationFailuresDoNotTouchBridge() {
        assertTrue(tools.office_accept_revision("conv-1", null, null).startsWith("Error"));
        assertTrue(tools.office_reject_revision("conv-1", null, false).startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    // ==================== 批次 9：Word 脚注/尾注/样式/内容控件/文档属性 ====================

    @Test
    @DisplayName("office_insert_footnote/endnote/apply_style/manage_content_control/set_document_properties：正常下发")
    void wordMiscBatch9ToolsDispatch() {
        when(bridge.executeOfficeCommand(any(), eq("insert_footnote"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("insert_endnote"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("apply_style"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("manage_content_control"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("set_document_properties"), anyMap())).thenReturn("{}");

        assertEquals("{}", tools.office_insert_footnote("conv-1", "第一条", "参见附件一"));
        assertEquals("{}", tools.office_insert_endnote("conv-1", "第一条", "参见附件一"));
        assertEquals("{}", tools.office_apply_style("conv-1", "标题", true, "标题 1"));
        assertEquals("{}", tools.office_manage_content_control("conv-1", "insert", "填空处", "field-1", "客户名称", null, null));
        assertEquals("{}", tools.office_set_document_properties("conv-1", "尽调报告", null, "AI WorkDeck", null, null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ccArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("manage_content_control"), ccArgs.capture());
        assertEquals("insert", ccArgs.getValue().get("action"));
        assertEquals("field-1", ccArgs.getValue().get("tag"));
        assertEquals("客户名称", ccArgs.getValue().get("title"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> propsArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("set_document_properties"), propsArgs.capture());
        assertEquals("尽调报告", propsArgs.getValue().get("title"));
        assertFalse(propsArgs.getValue().containsKey("subject"));
    }

    @Test
    @DisplayName("批次9 Word 杂项工具参数校验失败：拦下不触碰桥")
    void wordMiscBatch9ValidationFailuresDoNotTouchBridge() {
        assertTrue(tools.office_insert_footnote("conv-1", "", "内容").startsWith("Error"));
        assertTrue(tools.office_insert_endnote("conv-1", "锚点", "").startsWith("Error"));
        assertTrue(tools.office_apply_style("conv-1", "", true, "标题 1").startsWith("Error"));
        assertTrue(tools.office_apply_style("conv-1", "标题", true, "").startsWith("Error"));
        assertTrue(tools.office_manage_content_control("conv-1", "insert", "", "tag1", null, null, null).startsWith("Error"));
        assertTrue(tools.office_manage_content_control("conv-1", "read", null, null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_manage_content_control("conv-1", "set_text", null, "tag1", null, null, null).startsWith("Error"));
        assertTrue(tools.office_set_document_properties("conv-1", null, null, null, null, null, null).startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("office_insert_image：文件不存在时拦下且不触碰桥")
    void insertImageMissingFileDoesNotTouchBridge() {
        when(projectFileRepository.findById(999L)).thenReturn(java.util.Optional.empty());
        assertTrue(tools.office_insert_image("conv-1", 999L, null, null, null).startsWith("Error"));
        assertTrue(tools.office_insert_image("conv-1", null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_insert_image("conv-1", 1L, "长".repeat(256), null, null).startsWith("Error"));
        assertTrue(tools.office_insert_image("conv-1", 1L, null, "middle", null).startsWith("Error"));
        assertTrue(tools.office_insert_image("conv-1", 1L, null, null, 0.0).startsWith("Error"));
        verifyNoInteractions(bridge);
    }

    // ==================== 批次 9：PPT 表格/超链接 ====================

    @Test
    @DisplayName("office_ppt_add_table/table_read/table_set_cell/set_hyperlink：正常下发")
    void pptBatch9ToolsDispatch() {
        when(bridge.executeOfficeCommand(any(), eq("ppt_add_table"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("ppt_table_read"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("ppt_table_set_cell"), anyMap())).thenReturn("{}");
        when(bridge.executeOfficeCommand(any(), eq("ppt_set_hyperlink"), anyMap())).thenReturn("{}");

        assertEquals("{}", tools.office_ppt_add_table("conv-1", 1, "[[\"项目\",\"金额\"],[\"咨询费\",\"10000\"]]",
                null, null, null, null, null, null));
        assertEquals("{}", tools.office_ppt_table_read("conv-1", 1, null));
        assertEquals("{}", tools.office_ppt_table_set_cell("conv-1", 1, null, 0, 1, "20000"));
        assertEquals("{}", tools.office_ppt_set_hyperlink("conv-1", 1, "点击查看", "https://example.com"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> tableArgs = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeOfficeCommand(eq("conv-1"), eq("ppt_add_table"), tableArgs.capture());
        assertEquals(2, ((java.util.List<?>) tableArgs.getValue().get("rows")).size());
    }

    @Test
    @DisplayName("PPT 批次9工具参数校验失败：拦下不触碰桥")
    void pptBatch9ValidationFailuresDoNotTouchBridge() {
        assertTrue(tools.office_ppt_add_table("conv-1", 0, null, 2, 2, null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_ppt_add_table("conv-1", 1, null, null, null, null, null, null, null).startsWith("Error"));
        assertTrue(tools.office_ppt_table_read("conv-1", 0, null).startsWith("Error"));
        assertTrue(tools.office_ppt_table_set_cell("conv-1", 1, null, null, 0, "x").startsWith("Error"));
        assertTrue(tools.office_ppt_table_set_cell("conv-1", 1, null, 0, -1, "x").startsWith("Error"));
        assertTrue(tools.office_ppt_set_hyperlink("conv-1", 1, "", "https://example.com").startsWith("Error"));
        assertTrue(tools.office_ppt_set_hyperlink("conv-1", 1, "文本", "ftp://example.com").startsWith("Error"));
        verifyNoInteractions(bridge);
    }
}
