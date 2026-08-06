package com.checkba.service.ai.tools;

import com.checkba.service.ai.OfficeBridgeService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Office 插件文档工具集（Word/Excel/PowerPoint 任务窗格，Phase C 工具桥）。
 *
 * 每个工具 = 参数校验 + 调 OfficeBridgeService 下发 office_command + 透传结果。
 * 命令由插件端 officeExecutor（Office.js）执行：Word 修改类命令走 Word 原生修订
 * （changeTrackingMode=TrackAll）；Excel/PowerPoint 没有修订机制，写入直接生效。
 *
 * 可见性：office_* 仅对 clientCapability=office 的会话可见
 * （ClientCapabilityService + ToolRegistry 过滤），且按宿主再细分——
 * office_excel_* 只见于 Excel 会话、office_ppt_* 只见于 PowerPoint 会话、
 * 其余 office_*（Word 面）只见于 Word 会话。LOWA 会话看不到任何 office_*，
 * 反之 office 会话看不到 doc_* 与 sheet_*——远端工具没有执行器就是 30 秒超时死路径。
 *
 * conversationId 参数由 ToolRegistry 从服务端上下文强制注入（SERVER_CONTEXT_PARAMS），
 * 模型传入值一律被忽略。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OfficeEditTools implements AgentToolComponent {

    private final OfficeBridgeService officeBridgeService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** Excel 区域地址（A1 表示法，不带工作表名）：A1 或 A1:B10 */
    private static final java.util.regex.Pattern RANGE_ADDRESS =
            java.util.regex.Pattern.compile("^[A-Za-z]{1,3}\\d{1,7}(:[A-Za-z]{1,3}\\d{1,7})?$");

    /** office_excel_set_values 单次写入的单元格上限（防一把写爆工作表与 SSE payload） */
    private static final int MAX_SET_CELLS = 2000;

    // ==================== 读取 ====================

    @Tool("读取当前 Word 文档的全文纯文本。超长文档会被截断（约 20 万字符）。")
    @ToolMeta(displayName = "读取文档", category = "office")
    public String office_get_text(
            @P("会话ID（系统自动注入）") String conversationId
    ) {
        log.info("Tool: office_get_text called");
        return officeBridgeService.executeOfficeCommand(conversationId, "get_text", Map.of());
    }

    @Tool("读取用户当前在 Word 中选中的文本内容。未选中时返回空文本。")
    @ToolMeta(displayName = "读取选区", category = "office")
    public String office_get_selection(
            @P("会话ID（系统自动注入）") String conversationId
    ) {
        log.info("Tool: office_get_selection called");
        return officeBridgeService.executeOfficeCommand(conversationId, "get_selection", Map.of());
    }

    @Tool("在当前 Word 文档中查找文本，返回命中数量与每处命中所在段落的上下文。查找串最长 255 字符。")
    @ToolMeta(displayName = "查找文本", category = "office")
    public String office_search(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("要查找的文本") String query
    ) {
        log.info("Tool: office_search called, query={}", query);
        if (query == null || query.isBlank()) {
            return "Error: 查找文本不能为空";
        }
        if (query.length() > 255) {
            return "Error: 查找文本过长（Word 查找上限 255 字符），请缩短后重试";
        }
        return officeBridgeService.executeOfficeCommand(conversationId, "search", Map.of("query", query));
    }

    // ==================== 修改（Word 原生修订） ====================

    @Tool("在当前 Word 文档中查找并替换文本，修改以 Word 原生修订（Track Changes）形式呈现。" +
          "searchText 必须与文档中的文本精确一致；replaceAll=false 时只替换第一处。")
    @ToolMeta(displayName = "替换文本（修订）", category = "office", fileEffect = "MODIFIED")
    public String office_replace_text(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("要查找的原文（须与文档精确一致）") String searchText,
            @P("替换后的新文本") String replaceText,
            @P("是否替换所有匹配（false=仅第一处）") Boolean replaceAll
    ) {
        log.info("Tool: office_replace_text called, search={}, replaceAll={}", searchText, replaceAll);
        if (searchText == null || searchText.isBlank()) {
            return "Error: 查找文本不能为空";
        }
        if (searchText.length() > 255) {
            return "Error: 查找文本过长（Word 查找上限 255 字符）。可分段替换，或用 office_insert_text 配合较短锚点";
        }
        if (replaceText == null) {
            return "Error: 替换文本不能为 null（删除文本请传空字符串）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("searchText", searchText);
        args.put("replaceText", replaceText);
        args.put("replaceAll", replaceAll != null && replaceAll);
        return officeBridgeService.executeOfficeCommand(conversationId, "replace_text", args);
    }

    @Tool("在当前 Word 文档中插入文本，插入以 Word 原生修订（Track Changes）形式呈现。" +
          "提供 anchorText 时在该锚点前/后插入（锚点须与文档精确一致）；不提供时在用户当前光标/选区处插入。")
    @ToolMeta(displayName = "插入文本（修订）", category = "office", fileEffect = "MODIFIED")
    public String office_insert_text(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("要插入的文本") String text,
            @P("定位锚点文本（可选；为空则在当前光标/选区处插入）") String anchorText,
            @P("相对锚点的位置：before 或 after（默认 after，仅提供锚点时有效）") String position
    ) {
        log.info("Tool: office_insert_text called, anchor={}, position={}", anchorText, position);
        if (text == null || text.isEmpty()) {
            return "Error: 插入文本不能为空";
        }
        if (anchorText != null && anchorText.length() > 255) {
            return "Error: 锚点文本过长（Word 查找上限 255 字符），请改用更短的唯一锚点";
        }
        String pos = position == null || position.isBlank() ? "after" : position.trim().toLowerCase();
        if (!"before".equals(pos) && !"after".equals(pos)) {
            return "Error: position 只能是 before 或 after";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("text", text);
        args.put("anchorText", anchorText == null ? "" : anchorText);
        args.put("position", pos);
        return officeBridgeService.executeOfficeCommand(conversationId, "insert_text", args);
    }

    @Tool("在当前 Word 文档中为指定文本添加批注（Word 原生批注）。anchorText 须与文档中的文本精确一致，批注挂在第一处匹配上。")
    @ToolMeta(displayName = "插入批注", category = "office", fileEffect = "MODIFIED")
    public String office_add_comment(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("要批注的目标文本（须与文档精确一致）") String anchorText,
            @P("批注内容") String comment
    ) {
        log.info("Tool: office_add_comment called, anchor={}", anchorText);
        if (anchorText == null || anchorText.isBlank()) {
            return "Error: 批注目标文本不能为空";
        }
        if (anchorText.length() > 255) {
            return "Error: 批注目标文本过长（Word 查找上限 255 字符），请截取其中一段唯一文本作为目标";
        }
        if (comment == null || comment.isBlank()) {
            return "Error: 批注内容不能为空";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("anchorText", anchorText);
        args.put("comment", comment);
        return officeBridgeService.executeOfficeCommand(conversationId, "add_comment", args);
    }

    // ==================== Excel（office_excel_*，仅 Excel 会话可见） ====================

    @Tool("读取当前 Excel 工作表的区域值。不指定 rangeAddress 时读取已用区域（used range）。" +
          "sheetName 缺省为当前活动工作表。返回二维数组与区域地址。")
    @ToolMeta(displayName = "读取区域", category = "office")
    public String office_excel_get_range(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("区域地址，A1 表示法如 A1:D20（可选；为空取已用区域）") String rangeAddress
    ) {
        log.info("Tool: office_excel_get_range called, sheet={}, range={}", sheetName, rangeAddress);
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if (!addr.isEmpty() && !RANGE_ADDRESS.matcher(addr).matches()) {
            return "Error: 区域地址格式非法（应为 A1 表示法，如 A1 或 A1:D20，不带工作表名——工作表用 sheetName 参数指定）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("rangeAddress", addr);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_get_range", args);
    }

    @Tool("向当前 Excel 工作表的区域写入值（直接生效，Excel 没有修订机制）。" +
          "valuesJson 是 JSON 二维数组（按行），如 [[\"名称\",\"金额\"],[\"甲\",100]]。" +
          "rangeAddress 为单元格（如 B2）时按 values 尺寸向右下展开写入；为区域时尺寸必须与 values 一致。")
    @ToolMeta(displayName = "写入区域", category = "office", fileEffect = "MODIFIED")
    public String office_excel_set_values(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("起始单元格或区域地址，A1 表示法如 B2 或 B2:C4") String rangeAddress,
            @P("要写入的值，JSON 二维数组（按行），元素为字符串/数字/布尔") String valuesJson
    ) {
        log.info("Tool: office_excel_set_values called, sheet={}, range={}", sheetName, rangeAddress);
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
            return "Error: 区域地址不能为空且须为 A1 表示法（如 B2 或 B2:C4，不带工作表名）";
        }
        java.util.List<java.util.List<Object>> values;
        try {
            values = objectMapper.readValue(valuesJson == null ? "" : valuesJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.List<Object>>>() {});
        } catch (Exception e) {
            return "Error: valuesJson 不是合法的 JSON 二维数组，示例：[[\"名称\",\"金额\"],[\"甲\",100]]";
        }
        if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isEmpty()) {
            return "Error: valuesJson 不能为空数组";
        }
        int cols = values.get(0).size();
        int cells = 0;
        for (java.util.List<Object> row : values) {
            if (row == null || row.size() != cols) {
                return "Error: valuesJson 必须是矩形二维数组（每行列数一致）";
            }
            for (Object cell : row) {
                if (cell != null && !(cell instanceof String) && !(cell instanceof Number) && !(cell instanceof Boolean)) {
                    return "Error: valuesJson 的元素只能是字符串、数字或布尔值";
                }
            }
            cells += row.size();
        }
        if (cells > MAX_SET_CELLS) {
            return "Error: 单次写入上限 " + MAX_SET_CELLS + " 个单元格，请分批写入";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("rangeAddress", addr);
        args.put("values", values);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_set_values", args);
    }

    @Tool("在当前 Excel 工作表的已用区域中查找文本（大小写不敏感的包含匹配），" +
          "返回命中单元格地址与内容。sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "查找单元格", category = "office")
    public String office_excel_search(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("要查找的文本") String query
    ) {
        log.info("Tool: office_excel_search called, sheet={}, query={}", sheetName, query);
        if (query == null || query.isBlank()) {
            return "Error: 查找文本不能为空";
        }
        if (query.length() > 255) {
            return "Error: 查找文本过长（上限 255 字符），请缩短后重试";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("query", query);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_search", args);
    }

    // ==================== PowerPoint（office_ppt_*，仅 PowerPoint 会话可见） ====================

    @Tool("读取当前 PowerPoint 演示文稿各页的文本清单（每页各形状的文字）。" +
          "需要 PowerPointApi 1.4（Microsoft 365 较新版本），旧版宿主会返回明确错误。")
    @ToolMeta(displayName = "读取幻灯片", category = "office")
    public String office_ppt_get_slides(
            @P("会话ID（系统自动注入）") String conversationId
    ) {
        log.info("Tool: office_ppt_get_slides called");
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_get_slides", Map.of());
    }

    @Tool("在当前 PowerPoint 演示文稿中跨页查找并替换文本（直接生效，PowerPoint 没有修订机制）。" +
          "searchText 必须与幻灯片中的文本精确一致（区分大小写的包含匹配）。" +
          "需要 PowerPointApi 1.4，旧版宿主会返回明确错误。")
    @ToolMeta(displayName = "替换幻灯片文本", category = "office", fileEffect = "MODIFIED")
    public String office_ppt_replace_text(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("要查找的原文（须与幻灯片文本精确一致）") String searchText,
            @P("替换后的新文本") String replaceText
    ) {
        log.info("Tool: office_ppt_replace_text called, search={}", searchText);
        if (searchText == null || searchText.isBlank()) {
            return "Error: 查找文本不能为空";
        }
        if (searchText.length() > 255) {
            return "Error: 查找文本过长（上限 255 字符），请缩短后重试";
        }
        if (replaceText == null) {
            return "Error: 替换文本不能为 null（删除文本请传空字符串）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("searchText", searchText);
        args.put("replaceText", replaceText);
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_replace_text", args);
    }
}
