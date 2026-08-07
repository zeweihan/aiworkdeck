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

    /** 文字颜色格式：#RRGGBB */
    private static final java.util.regex.Pattern HEX_COLOR = java.util.regex.Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /** 下划线线型白名单（插件端映射为 Word.UnderlineType） */
    private static final java.util.Set<String> UNDERLINE_VALUES =
            java.util.Set.of("none", "single", "double", "dotted", "wave");

    /** 段落对齐白名单（插件端映射为 Word.Alignment） */
    private static final java.util.Set<String> ALIGNMENT_VALUES =
            java.util.Set.of("left", "center", "right", "justify");

    /** 段落内置样式白名单（插件端映射为 Word.BuiltInStyleName，用于标题级别） */
    private static final java.util.Set<String> PARAGRAPH_STYLE_VALUES =
            java.util.Set.of("normal", "heading1", "heading2", "heading3", "heading4");

    /** 自动编号类型白名单（bullet/decimal 走 Word 原生列表，chinese 走手写编号，none 清除） */
    private static final java.util.Set<String> NUMBERING_KIND_VALUES =
            java.util.Set.of("bullet", "decimal", "chinese", "none");

    /** 一次套用自动编号的最大段数（防一把改爆整篇） */
    private static final int MAX_NUMBERING_PARAGRAPHS = 200;

    /** 表格边框范围白名单（插件端映射为 Word.BorderLocation） */
    private static final java.util.Set<String> TABLE_BORDER_VALUES =
            java.util.Set.of("all", "outside", "inside", "none");

    /** 表格整体对齐白名单（Word 表格只有左/中/右，没有两端对齐） */
    private static final java.util.Set<String> TABLE_ALIGNMENT_VALUES =
            java.util.Set.of("left", "center", "right");

    /** 标准格式套用范围白名单 */
    private static final java.util.Set<String> STANDARD_FORMAT_SCOPES =
            java.util.Set.of("document", "selection");

    /** Excel 单元格字体字号上限（Excel UI 硬上限 409 磅） */
    private static final double MAX_EXCEL_FONT_SIZE = 409;

    /** Excel 单元格格式对齐白名单 */
    private static final java.util.Set<String> EXCEL_H_ALIGN_VALUES = java.util.Set.of("left", "center", "right");
    private static final java.util.Set<String> EXCEL_V_ALIGN_VALUES = java.util.Set.of("top", "middle", "bottom");

    /** Excel 边框范围/线宽白名单 */
    private static final java.util.Set<String> EXCEL_BORDER_VALUES = java.util.Set.of("all", "outside", "inside", "none");
    private static final java.util.Set<String> EXCEL_BORDER_STYLE_VALUES = java.util.Set.of("thin", "medium", "thick");

    /** Excel 行列编辑动作白名单 */
    private static final java.util.Set<String> EXCEL_EDIT_ROWS_COLS_ACTIONS =
            java.util.Set.of("insert_rows", "delete_rows", "insert_cols", "delete_cols", "set_width", "set_height");
    /** 单次插入/删除/改行高列宽的行列数上限 */
    private static final int MAX_EXCEL_ROWS_COLS_COUNT = 100;

    /** Excel 合并/取消合并动作白名单 */
    private static final java.util.Set<String> EXCEL_MERGE_ACTIONS = java.util.Set.of("merge", "unmerge");

    /** Excel 工作表管理动作白名单 */
    private static final java.util.Set<String> EXCEL_SHEET_ACTIONS =
            java.util.Set.of("add", "rename", "delete", "move", "activate");

    /** Excel 冻结窗格动作白名单 */
    private static final java.util.Set<String> EXCEL_FREEZE_ACTIONS =
            java.util.Set.of("freeze_rows", "freeze_cols", "freeze_at", "unfreeze");

    /** Excel 自动筛选动作白名单 */
    private static final java.util.Set<String> EXCEL_AUTOFILTER_ACTIONS = java.util.Set.of("apply", "clear", "remove");

    /** Excel 条件格式规则类型/比较运算符/动作白名单（值统一小写，normalizeEnum 归一后不含大小写边界） */
    private static final java.util.Set<String> EXCEL_CF_RULE_TYPES = java.util.Set.of("cellvalue", "colorscale");
    private static final java.util.Set<String> EXCEL_CF_OPERATOR_VALUES =
            java.util.Set.of("greaterthan", "lessthan", "between", "equalto");
    private static final java.util.Set<String> EXCEL_CF_ACTIONS = java.util.Set.of("apply", "clearall");

    /**
     * 枚举参数归一化：null/空返回 null（表示不改），命中白名单返回小写值，
     * 非法值抛 IllegalArgumentException 由调用方转成 "Error:" 文案。
     * 枚举在后端先拦一道——比等 30 秒桥往返再报错便宜得多。
     */
    private static String normalizeEnum(String raw, java.util.Set<String> allowed, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(field + " 只能是 "
                    + String.join("/", new java.util.TreeSet<>(allowed)) + "（收到：" + raw + "）");
        }
        return value;
    }

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

    // ==================== 格式（Word 原生修订） ====================

    @Tool("在当前 Word 文档中设置指定文本的字符格式：字体、字号、加粗、斜体、下划线、删除线、颜色。" +
          "anchorText 须与文档中的文本精确一致，默认只对第一处匹配生效，applyToAll=true 对所有匹配生效。" +
          "格式参数至少要给一个，没传的保持原样。fontName 中西文字体名都可（如 宋体 / 楷体_GB2312 / Times New Roman）。" +
          "修改以 Word 原生修订（Track Changes）形式呈现。")
    @ToolMeta(displayName = "设置文字格式", category = "office", fileEffect = "MODIFIED")
    public String office_format_text(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("要设置格式的目标文本（须与文档精确一致）") String anchorText,
            @P("是否对所有匹配生效（false=仅第一处）") Boolean applyToAll,
            @P("字体名，如 宋体 / 楷体_GB2312 / Times New Roman（不改则不传）") String fontName,
            @P("字号（磅），如 12（不改则不传）") Double fontSize,
            @P("加粗 true/false（不改则不传）") Boolean bold,
            @P("斜体 true/false（不改则不传）") Boolean italic,
            @P("下划线线型：none/single/double/dotted/wave（wave=波浪线；不改则不传）") String underline,
            @P("删除线 true/false（不改则不传）") Boolean strikeThrough,
            @P("双删除线 true/false（不改则不传）") Boolean doubleStrikeThrough,
            @P("文字颜色 #RRGGBB，如 #C00000（不改则不传）") String color
    ) {
        log.info("Tool: office_format_text called, anchor={}, applyToAll={}", anchorText, applyToAll);
        if (anchorText == null || anchorText.isBlank()) {
            return "Error: 目标文本不能为空";
        }
        if (anchorText.length() > 255) {
            return "Error: 目标文本过长（Word 查找上限 255 字符），请截取其中一段唯一文本作为目标";
        }
        if (fontSize != null && (fontSize <= 0 || fontSize > 1638)) {
            return "Error: fontSize 须为大于 0 且不超过 1638 的磅值";
        }
        if (color != null && !color.isBlank() && !HEX_COLOR.matcher(color.trim()).matches()) {
            return "Error: color 须为 #RRGGBB 格式，如 #C00000";
        }
        String underlineValue;
        try {
            underlineValue = normalizeEnum(underline, UNDERLINE_VALUES, "underline");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        Map<String, Object> args = new HashMap<>();
        if (fontName != null && !fontName.isBlank()) args.put("fontName", fontName.trim());
        if (fontSize != null) args.put("fontSize", fontSize);
        if (bold != null) args.put("bold", bold);
        if (italic != null) args.put("italic", italic);
        if (underlineValue != null) args.put("underline", underlineValue);
        if (strikeThrough != null) args.put("strikeThrough", strikeThrough);
        if (doubleStrikeThrough != null) args.put("doubleStrikeThrough", doubleStrikeThrough);
        if (color != null && !color.isBlank()) args.put("color", color.trim());
        if (args.isEmpty()) {
            return "Error: 未给出任何格式参数（fontName/fontSize/bold/italic/underline/strikeThrough/"
                    + "doubleStrikeThrough/color 至少给一个）";
        }
        args.put("anchorText", anchorText);
        args.put("applyToAll", applyToAll != null && applyToAll);
        return officeBridgeService.executeOfficeCommand(conversationId, "format_text", args);
    }

    @Tool("在当前 Word 文档中设置段落格式：对齐、行距、段前段后间距、缩进、标题级别。" +
          "anchorText 须与文档中的文本精确一致，命中处所在的整个段落即目标段落；" +
          "默认只对第一处匹配所在段落生效，applyToAll=true 对所有匹配所在段落生效。" +
          "格式参数至少要给一个，没传的保持原样。" +
          "lineSpacing/spaceBefore/spaceAfter/firstLineIndent/leftIndent/rightIndent 单位都是磅：" +
          "行距按字号换算（12 磅字的 1.5 倍行距填 18，2 倍填 24）；" +
          "首行缩进按中文惯例 2 字符换算（12 磅字填 24）。" +
          "styleBuiltIn 用于标题级别（heading1~heading4，normal 恢复正文）。" +
          "修改以 Word 原生修订（Track Changes）形式呈现。")
    @ToolMeta(displayName = "设置段落格式", category = "office", fileEffect = "MODIFIED")
    public String office_set_paragraph_format(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标段落中的一段文本（须与文档精确一致）") String anchorText,
            @P("是否对所有匹配所在段落生效（false=仅第一处）") Boolean applyToAll,
            @P("对齐方式：left/center/right/justify（不改则不传）") String alignment,
            @P("行距（磅），如 12 磅字的 1.5 倍行距填 18（不改则不传）") Double lineSpacing,
            @P("段前间距（磅）（不改则不传）") Double spaceBefore,
            @P("段后间距（磅）（不改则不传）") Double spaceAfter,
            @P("首行缩进（磅），正值缩进；12 磅字缩进 2 字符填 24（不改则不传）") Double firstLineIndent,
            @P("左缩进（磅）（不改则不传）") Double leftIndent,
            @P("右缩进（磅）（不改则不传）") Double rightIndent,
            @P("段落样式（标题级别）：normal/heading1/heading2/heading3/heading4（不改则不传）") String styleBuiltIn
    ) {
        log.info("Tool: office_set_paragraph_format called, anchor={}, alignment={}, styleBuiltIn={}",
                anchorText, alignment, styleBuiltIn);
        if (anchorText == null || anchorText.isBlank()) {
            return "Error: 目标文本不能为空";
        }
        if (anchorText.length() > 255) {
            return "Error: 目标文本过长（Word 查找上限 255 字符），请截取其中一段唯一文本作为目标";
        }
        if (lineSpacing != null && lineSpacing <= 0) {
            return "Error: lineSpacing 须为大于 0 的磅值（12 磅字的 1.5 倍行距填 18）";
        }
        String alignmentValue;
        String styleValue;
        try {
            alignmentValue = normalizeEnum(alignment, ALIGNMENT_VALUES, "alignment");
            styleValue = normalizeEnum(styleBuiltIn, PARAGRAPH_STYLE_VALUES, "styleBuiltIn");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        Map<String, Object> args = new HashMap<>();
        if (alignmentValue != null) args.put("alignment", alignmentValue);
        if (lineSpacing != null) args.put("lineSpacing", lineSpacing);
        if (spaceBefore != null) args.put("spaceBefore", spaceBefore);
        if (spaceAfter != null) args.put("spaceAfter", spaceAfter);
        if (firstLineIndent != null) args.put("firstLineIndent", firstLineIndent);
        if (leftIndent != null) args.put("leftIndent", leftIndent);
        if (rightIndent != null) args.put("rightIndent", rightIndent);
        if (styleValue != null) args.put("styleBuiltIn", styleValue);
        if (args.isEmpty()) {
            return "Error: 未给出任何格式参数（alignment/lineSpacing/spaceBefore/spaceAfter/"
                    + "firstLineIndent/leftIndent/rightIndent/styleBuiltIn 至少给一个）";
        }
        args.put("anchorText", anchorText);
        args.put("applyToAll", applyToAll != null && applyToAll);
        return officeBridgeService.executeOfficeCommand(conversationId, "set_paragraph_format", args);
    }

    @Tool("读取当前 Word 文档中某处的现有格式（字符格式 + 所在段落的段落格式）。" +
          "提供 anchorText 时读第一处匹配（须与文档精确一致）；不提供时读用户当前选区，" +
          "选区为空则读光标所在段落。改格式前先用它看一眼现状。")
    @ToolMeta(displayName = "读取格式", category = "office")
    public String office_get_formatting(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标文本（可选；为空则读当前选区/光标处）") String anchorText
    ) {
        log.info("Tool: office_get_formatting called, anchor={}", anchorText);
        if (anchorText != null && anchorText.length() > 255) {
            return "Error: 目标文本过长（Word 查找上限 255 字符），请截取其中一段唯一文本作为目标";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("anchorText", anchorText == null ? "" : anchorText);
        return officeBridgeService.executeOfficeCommand(conversationId, "get_formatting", args);
    }

    @Tool("在当前 Word 文档中为一段连续段落设置自动编号或项目符号。" +
          "anchorText 定位起始段落（须与文档精确一致，命中处所在的整个段落就是第一段），" +
          "paragraphCount 是从该段起连续套用的段数（缺省 1，上限 200）。" +
          "kind：bullet=项目符号、decimal=阿拉伯数字（1. 2. 3.）、chinese=中文数字（一、二、三、）、none=清除编号。" +
          "bullet 与 decimal 用 Word 原生列表；chinese 是把「一、」这样的编号作为文字写入各段段首" +
          "（Word 原生编号没有中文数字这一档），返回值的 via 字段会标明实际走的是 listApi 还是 literalText。" +
          "修改以 Word 原生修订（Track Changes）形式呈现。")
    @ToolMeta(displayName = "设置自动编号", category = "office", fileEffect = "MODIFIED")
    public String office_set_numbering(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("起始段落中的一段文本（须与文档精确一致）") String anchorText,
            @P("从起始段起连续套用的段数（缺省 1，上限 200）") Integer paragraphCount,
            @P("编号类型：bullet/decimal/chinese/none") String kind
    ) {
        log.info("Tool: office_set_numbering called, anchor={}, count={}, kind={}", anchorText, paragraphCount, kind);
        if (anchorText == null || anchorText.isBlank()) {
            return "Error: 目标文本不能为空";
        }
        if (anchorText.length() > 255) {
            return "Error: 目标文本过长（Word 查找上限 255 字符），请截取其中一段唯一文本作为目标";
        }
        String kindValue;
        try {
            kindValue = normalizeEnum(kind, NUMBERING_KIND_VALUES, "kind");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (kindValue == null) {
            return "Error: kind 不能为空（bullet/decimal/chinese/none）";
        }
        int count = paragraphCount == null ? 1 : paragraphCount;
        if (count < 1 || count > MAX_NUMBERING_PARAGRAPHS) {
            return "Error: paragraphCount 须为 1~" + MAX_NUMBERING_PARAGRAPHS + " 的整数（缺省 1）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("anchorText", anchorText);
        args.put("paragraphCount", count);
        args.put("kind", kindValue);
        return officeBridgeService.executeOfficeCommand(conversationId, "set_numbering", args);
    }

    @Tool("在当前 Word 文档中设置某张表格的整体格式：边框、表格对齐、首行加粗、自动调整列宽、全表字号。" +
          "tableIndex 是文档中第几张表（0 起，缺省 0），越界时返回表格总数供重试。" +
          "borders：all=全部框线、outside=外框线、inside=内框线、none=去掉框线；" +
          "borderColor 缺省 #000000，borderWidth 缺省 1 磅。" +
          "alignment 是表格相对页面的水平对齐（left/center/right）。" +
          "格式参数（borders/alignment/headerBold/autoFit/fontSize）至少要给一个，没传的保持原样。" +
          "需要 WordApi 1.3；Word 对表格格式的修订记录能力有限，返回值的 tracked 字段说明实际情况。")
    @ToolMeta(displayName = "设置表格格式", category = "office", fileEffect = "MODIFIED")
    public String office_format_table(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("表格序号（0 起，缺省 0）") Integer tableIndex,
            @P("边框范围：all/outside/inside/none（不改则不传）") String borders,
            @P("边框颜色 #RRGGBB（缺省 #000000，仅 borders 非 none 时有效）") String borderColor,
            @P("边框粗细（磅，缺省 1，仅 borders 非 none 时有效）") Double borderWidth,
            @P("表格整体对齐：left/center/right（不改则不传）") String alignment,
            @P("首行是否加粗（不改则不传）") Boolean headerBold,
            @P("是否自动调整列宽到页面宽度（不改则不传）") Boolean autoFit,
            @P("全表字号（磅）（不改则不传）") Double fontSize
    ) {
        log.info("Tool: office_format_table called, index={}, borders={}, alignment={}", tableIndex, borders, alignment);
        int index = tableIndex == null ? 0 : tableIndex;
        if (index < 0) {
            return "Error: tableIndex 不能为负（文档中第一张表是 0）";
        }
        if (fontSize != null && (fontSize <= 0 || fontSize > 1638)) {
            return "Error: fontSize 须为大于 0 且不超过 1638 的磅值";
        }
        if (borderColor != null && !borderColor.isBlank() && !HEX_COLOR.matcher(borderColor.trim()).matches()) {
            return "Error: borderColor 须为 #RRGGBB 格式，如 #000000";
        }
        if (borderWidth != null && (borderWidth <= 0 || borderWidth > 6)) {
            return "Error: borderWidth 须为 0~6 之间的磅值（Word 框线上限 6 磅）";
        }
        String bordersValue;
        String alignmentValue;
        try {
            bordersValue = normalizeEnum(borders, TABLE_BORDER_VALUES, "borders");
            alignmentValue = normalizeEnum(alignment, TABLE_ALIGNMENT_VALUES, "alignment");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (bordersValue == null && alignmentValue == null && headerBold == null && autoFit == null && fontSize == null) {
            return "Error: 未给出任何格式参数（borders/alignment/headerBold/autoFit/fontSize 至少给一个；"
                    + "borderColor 与 borderWidth 只是 borders 的修饰参数）";
        }

        Map<String, Object> args = new HashMap<>();
        args.put("tableIndex", index);
        if (bordersValue != null) {
            args.put("borders", bordersValue);
            // 缺省值在后端定死，插件端不再猜（两处各有一套默认值最容易走偏）
            if (!"none".equals(bordersValue)) {
                args.put("borderColor", borderColor == null || borderColor.isBlank() ? "#000000" : borderColor.trim());
                args.put("borderWidth", borderWidth == null ? 1.0 : borderWidth);
            }
        }
        if (alignmentValue != null) args.put("alignment", alignmentValue);
        if (headerBold != null) args.put("headerBold", headerBold);
        if (autoFit != null) args.put("autoFit", autoFit);
        if (fontSize != null) args.put("fontSize", fontSize);
        return officeBridgeService.executeOfficeCommand(conversationId, "format_table", args);
    }

    @Tool("把当前 Word 文档整篇（或选区）套用律所标准格式：正文楷体_GB2312/Arial 12 磅两端对齐、" +
          "段后 18 磅、行距 16 磅、首行缩进 2 字符；主标题 16 磅加粗居中；" +
          "小标题（第X条、一、、（一）、1. 这类）与正文同款但加粗且不缩进；表格全表 10 磅。" +
          "scope：document=全文（缺省）、selection=用户当前选区。" +
          "超长文档只处理前 500 段并在返回值里标 truncated。" +
          "整篇套用会产生大量格式修订，用户可在 Word 审阅面板逐条接受或拒绝。")
    @ToolMeta(displayName = "套用标准格式", category = "office", fileEffect = "MODIFIED")
    public String office_apply_standard_format(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("套用范围：document（全文，缺省）或 selection（当前选区）") String scope
    ) {
        log.info("Tool: office_apply_standard_format called, scope={}", scope);
        String scopeValue;
        try {
            scopeValue = normalizeEnum(scope, STANDARD_FORMAT_SCOPES, "scope");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("scope", scopeValue == null ? "document" : scopeValue);
        return officeBridgeService.executeOfficeCommand(conversationId, "apply_standard_format", args);
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

    // ==================== Excel 格式/结构（批次6，office_excel_*，仅 Excel 会话可见） ====================

    @Tool("设置 Excel 区域的单元格格式：字体、字号、加粗、斜体、字体颜色、填充色、水平对齐、垂直对齐、数字格式、自动换行。" +
          "直接生效（Excel 没有修订机制）。sheetName 缺省为当前活动工作表。格式参数至少要给一个，没传的保持原样。")
    @ToolMeta(displayName = "设置单元格格式", category = "office", fileEffect = "MODIFIED")
    public String office_excel_format_cells(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("区域地址，A1 表示法如 A1 或 A1:D20") String rangeAddress,
            @P("字体名（不改则不传）") String fontName,
            @P("字号（磅，1~409）（不改则不传）") Double fontSize,
            @P("加粗 true/false（不改则不传）") Boolean bold,
            @P("斜体 true/false（不改则不传）") Boolean italic,
            @P("文字颜色 #RRGGBB（不改则不传）") String fontColor,
            @P("填充色 #RRGGBB（不改则不传）") String fillColor,
            @P("水平对齐：left/center/right（不改则不传）") String horizontalAlignment,
            @P("垂直对齐：top/middle/bottom（不改则不传）") String verticalAlignment,
            @P("数字格式码，如 0.00% 或 yyyy-mm-dd（不改则不传）") String numberFormat,
            @P("是否自动换行 true/false（不改则不传）") Boolean wrapText
    ) {
        log.info("Tool: office_excel_format_cells called, sheet={}, range={}", sheetName, rangeAddress);
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
            return "Error: 区域地址不能为空且须为 A1 表示法（如 A1 或 A1:D20，不带工作表名）";
        }
        if (fontSize != null && (fontSize <= 0 || fontSize > MAX_EXCEL_FONT_SIZE)) {
            return "Error: fontSize 须为大于 0 且不超过 " + (int) MAX_EXCEL_FONT_SIZE + " 的磅值";
        }
        if (fontColor != null && !fontColor.isBlank() && !HEX_COLOR.matcher(fontColor.trim()).matches()) {
            return "Error: fontColor 须为 #RRGGBB 格式，如 #C00000";
        }
        if (fillColor != null && !fillColor.isBlank() && !HEX_COLOR.matcher(fillColor.trim()).matches()) {
            return "Error: fillColor 须为 #RRGGBB 格式，如 #FFF2CC";
        }
        String hAlign;
        String vAlign;
        try {
            hAlign = normalizeEnum(horizontalAlignment, EXCEL_H_ALIGN_VALUES, "horizontalAlignment");
            vAlign = normalizeEnum(verticalAlignment, EXCEL_V_ALIGN_VALUES, "verticalAlignment");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        Map<String, Object> args = new HashMap<>();
        if (fontName != null && !fontName.isBlank()) args.put("fontName", fontName.trim());
        if (fontSize != null) args.put("fontSize", fontSize);
        if (bold != null) args.put("bold", bold);
        if (italic != null) args.put("italic", italic);
        if (fontColor != null && !fontColor.isBlank()) args.put("fontColor", fontColor.trim());
        if (fillColor != null && !fillColor.isBlank()) args.put("fillColor", fillColor.trim());
        if (hAlign != null) args.put("horizontalAlignment", hAlign);
        if (vAlign != null) args.put("verticalAlignment", vAlign);
        if (numberFormat != null && !numberFormat.isBlank()) args.put("numberFormat", numberFormat.trim());
        if (wrapText != null) args.put("wrapText", wrapText);
        if (args.isEmpty()) {
            return "Error: 未给出任何格式参数（fontName/fontSize/bold/italic/fontColor/fillColor/"
                    + "horizontalAlignment/verticalAlignment/numberFormat/wrapText 至少给一个）";
        }
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("rangeAddress", addr);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_format_cells", args);
    }

    @Tool("设置 Excel 区域的边框：范围（all/outside/inside/none）、线宽（thin/medium/thick）、颜色。" +
          "直接生效（Excel 没有修订机制）。sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "设置边框", category = "office", fileEffect = "MODIFIED")
    public String office_excel_set_borders(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("区域地址，A1 表示法如 A1:D20") String rangeAddress,
            @P("边框范围：all/outside/inside/none") String borders,
            @P("线宽：thin/medium/thick（缺省 thin，borders=none 时无意义）") String style,
            @P("边框颜色 #RRGGBB（缺省 #000000，borders=none 时无意义）") String color
    ) {
        log.info("Tool: office_excel_set_borders called, sheet={}, range={}, borders={}", sheetName, rangeAddress, borders);
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
            return "Error: 区域地址不能为空且须为 A1 表示法（如 A1:D20，不带工作表名）";
        }
        String bordersValue;
        String styleValue;
        try {
            bordersValue = normalizeEnum(borders, EXCEL_BORDER_VALUES, "borders");
            styleValue = normalizeEnum(style, EXCEL_BORDER_STYLE_VALUES, "style");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (bordersValue == null) {
            return "Error: borders 不能为空（all/outside/inside/none）";
        }
        if (color != null && !color.isBlank() && !HEX_COLOR.matcher(color.trim()).matches()) {
            return "Error: color 须为 #RRGGBB 格式，如 #000000";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("rangeAddress", addr);
        args.put("borders", bordersValue);
        if (!"none".equals(bordersValue)) {
            args.put("style", styleValue == null ? "thin" : styleValue);
            args.put("color", color == null || color.isBlank() ? "#000000" : color.trim());
        }
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_set_borders", args);
    }

    @Tool("在当前 Excel 工作表插入/删除整行整列，或设置行高/列宽。" +
          "index 是行/列序号（0 起），count 是本次影响的行列数（缺省 1，上限 100）。" +
          "insert_rows/delete_rows/insert_cols/delete_cols 直接生效（Excel 没有修订机制，" +
          "误操作靠 Ctrl+Z 或文档检查点，不是修订面板）；set_width（列宽，约合像素）/set_height（行高，磅）需额外传 size。" +
          "sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "编辑行列", category = "office", fileEffect = "MODIFIED")
    public String office_excel_edit_rows_cols(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("动作：insert_rows/delete_rows/insert_cols/delete_cols/set_width/set_height") String action,
            @P("起始行/列序号（0 起）") Integer index,
            @P("影响的行列数（缺省 1，上限 100）") Integer count,
            @P("set_width/set_height 专用：列宽（约合像素）或行高（磅）") Double size
    ) {
        log.info("Tool: office_excel_edit_rows_cols called, sheet={}, action={}, index={}", sheetName, action, index);
        String actionValue;
        try {
            actionValue = normalizeEnum(action, EXCEL_EDIT_ROWS_COLS_ACTIONS, "action");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (actionValue == null) {
            return "Error: action 不能为空（insert_rows/delete_rows/insert_cols/delete_cols/set_width/set_height）";
        }
        if (index == null || index < 0) {
            return "Error: index 不能为空且不能为负（第一行/列是 0）";
        }
        int cnt = count == null ? 1 : count;
        if (cnt < 1 || cnt > MAX_EXCEL_ROWS_COLS_COUNT) {
            return "Error: count 须为 1~" + MAX_EXCEL_ROWS_COLS_COUNT + " 的整数（缺省 1）";
        }
        boolean needsSize = "set_width".equals(actionValue) || "set_height".equals(actionValue);
        if (needsSize && (size == null || size <= 0)) {
            return "Error: " + actionValue + " 需要 size（正数）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("action", actionValue);
        args.put("index", index);
        args.put("count", cnt);
        if (needsSize) args.put("size", size);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_edit_rows_cols", args);
    }

    @Tool("合并或取消合并 Excel 区域的单元格。直接生效（Excel 没有修订机制）。sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "合并单元格", category = "office", fileEffect = "MODIFIED")
    public String office_excel_merge_cells(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("区域地址，A1 表示法如 A1:D1") String rangeAddress,
            @P("动作：merge/unmerge") String action
    ) {
        log.info("Tool: office_excel_merge_cells called, sheet={}, range={}, action={}", sheetName, rangeAddress, action);
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
            return "Error: 区域地址不能为空且须为 A1 表示法（如 A1:D1，不带工作表名）";
        }
        String actionValue;
        try {
            actionValue = normalizeEnum(action, EXCEL_MERGE_ACTIONS, "action");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (actionValue == null) {
            return "Error: action 不能为空（merge/unmerge）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("rangeAddress", addr);
        args.put("action", actionValue);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_merge_cells", args);
    }

    @Tool("对 Excel 区域按某一列排序。keyColumn 是区域内的列偏移（0 起，不是工作表绝对列号）。" +
          "直接生效（Excel 没有修订机制）。sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "排序", category = "office", fileEffect = "MODIFIED")
    public String office_excel_sort_range(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("区域地址，A1 表示法如 A1:D20") String rangeAddress,
            @P("排序依据列：区域内偏移，0 起（区域第一列是 0）") Integer keyColumn,
            @P("是否升序（缺省 true）") Boolean ascending,
            @P("区域是否带表头（表头行不参与排序，缺省 false）") Boolean hasHeader
    ) {
        log.info("Tool: office_excel_sort_range called, sheet={}, range={}, keyColumn={}", sheetName, rangeAddress, keyColumn);
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
            return "Error: 区域地址不能为空且须为 A1 表示法（如 A1:D20，不带工作表名）";
        }
        if (keyColumn == null || keyColumn < 0) {
            return "Error: keyColumn 不能为空且不能为负（区域第一列是 0）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("rangeAddress", addr);
        args.put("keyColumn", keyColumn);
        args.put("ascending", ascending == null || ascending);
        args.put("hasHeader", hasHeader != null && hasHeader);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_sort_range", args);
    }

    @Tool("管理 Excel 工作簿的工作表：新增、重命名、删除、移动位置、设为当前活动表。" +
          "add 时 sheetName 是可选的新表名（不传则由 Excel 自动命名）；" +
          "rename/delete/move/activate 都要求 sheetName 指定目标表，rename 额外要 newName，move 额外要 position（0 起）。" +
          "删除是不可逆动作且没有修订可撤——工作簿只剩一张表时会拒绝删除。直接生效（Excel 没有修订机制）。")
    @ToolMeta(displayName = "管理工作表", category = "office", fileEffect = "MODIFIED")
    public String office_excel_manage_sheets(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("动作：add/rename/delete/move/activate") String action,
            @P("目标工作表名（add 时可选新表名；其余动作必填）") String sheetName,
            @P("新名称（仅 rename 用）") String newName,
            @P("新位置，0 起（仅 move 用）") Integer position
    ) {
        log.info("Tool: office_excel_manage_sheets called, action={}, sheetName={}", action, sheetName);
        String actionValue;
        try {
            actionValue = normalizeEnum(action, EXCEL_SHEET_ACTIONS, "action");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (actionValue == null) {
            return "Error: action 不能为空（add/rename/delete/move/activate）";
        }
        String name = sheetName == null ? "" : sheetName.trim();
        if (!"add".equals(actionValue) && name.isEmpty()) {
            return "Error: sheetName 不能为空（" + actionValue + " 需要指定目标工作表）";
        }
        if ("rename".equals(actionValue) && (newName == null || newName.isBlank())) {
            return "Error: rename 需要 newName（新表名不能为空）";
        }
        if ("move".equals(actionValue) && (position == null || position < 0)) {
            return "Error: move 需要 position（0 起的整数，不能为负）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("action", actionValue);
        args.put("sheetName", name);
        if (newName != null && !newName.isBlank()) args.put("newName", newName.trim());
        if (position != null) args.put("position", position);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_manage_sheets", args);
    }

    @Tool("冻结或取消冻结 Excel 工作表的窗格。freeze_rows/freeze_cols 从表格左上角起冻结指定行数/列数（count），" +
          "freeze_at 冻结到指定单元格为止（cellAddress，该单元格左上方区域被冻结），unfreeze 取消冻结。" +
          "需要 ExcelApi 1.7（较新版本 Excel）。直接生效（Excel 没有修订机制）。sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "冻结窗格", category = "office", fileEffect = "MODIFIED")
    public String office_excel_freeze_panes(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("动作：freeze_rows/freeze_cols/freeze_at/unfreeze") String action,
            @P("冻结的行数或列数（freeze_rows/freeze_cols 用，缺省 1）") Integer count,
            @P("冻结基准单元格，A1 表示法（freeze_at 用）") String cellAddress
    ) {
        log.info("Tool: office_excel_freeze_panes called, sheet={}, action={}", sheetName, action);
        String actionValue;
        try {
            actionValue = normalizeEnum(action, EXCEL_FREEZE_ACTIONS, "action");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (actionValue == null) {
            return "Error: action 不能为空（freeze_rows/freeze_cols/freeze_at/unfreeze）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("action", actionValue);
        if ("freeze_rows".equals(actionValue) || "freeze_cols".equals(actionValue)) {
            int cnt = count == null ? 1 : count;
            if (cnt < 1) {
                return "Error: count 须为正整数（缺省 1）";
            }
            args.put("count", cnt);
        } else if ("freeze_at".equals(actionValue)) {
            String addr = cellAddress == null ? "" : cellAddress.trim();
            if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
                return "Error: freeze_at 需要 cellAddress（A1 表示法，如 C3）";
            }
            args.put("cellAddress", addr);
        }
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_freeze_panes", args);
    }

    @Tool("向 Excel 区域批量写入公式（直接生效，Excel 没有修订机制）。" +
          "formulasJson 是 JSON 二维数组（按行），每个元素须是以 = 开头的公式字符串，" +
          "如 [[\"=SUM(A1:A10)\"],[\"=B1*1.1\"]]。**公式必须用 Excel 原生文法**：参数用逗号分隔、" +
          "跨表引用写作 Sheet1!A1（与桌面端 LOWA 电子表格原语的分号/点号文法相反，不要混用）。" +
          "rangeAddress 为单元格时按 formulas 尺寸向右下展开写入；为区域时尺寸必须与 formulas 一致。" +
          "写入后自动读回结果，若某格算出 #REF!/#NAME? 等错误，会在返回值 formulaErrors 里列出供自纠。" +
          "sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "写入公式", category = "office", fileEffect = "MODIFIED")
    public String office_excel_set_formulas(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("起始单元格或区域地址，A1 表示法如 B2 或 B2:B10") String rangeAddress,
            @P("要写入的公式，JSON 二维数组（按行），元素为以 = 开头的公式字符串") String formulasJson
    ) {
        log.info("Tool: office_excel_set_formulas called, sheet={}, range={}", sheetName, rangeAddress);
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
            return "Error: 区域地址不能为空且须为 A1 表示法（如 B2 或 B2:B10，不带工作表名）";
        }
        java.util.List<java.util.List<Object>> formulas;
        try {
            formulas = objectMapper.readValue(formulasJson == null ? "" : formulasJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.List<Object>>>() {});
        } catch (Exception e) {
            return "Error: formulasJson 不是合法的 JSON 二维数组，示例：[[\"=SUM(A1:A10)\"]]";
        }
        if (formulas == null || formulas.isEmpty() || formulas.get(0) == null || formulas.get(0).isEmpty()) {
            return "Error: formulasJson 不能为空数组";
        }
        int cols = formulas.get(0).size();
        int cells = 0;
        for (java.util.List<Object> row : formulas) {
            if (row == null || row.size() != cols) {
                return "Error: formulasJson 必须是矩形二维数组（每行列数一致）";
            }
            for (Object cell : row) {
                if (!(cell instanceof String) || !((String) cell).trim().startsWith("=")) {
                    return "Error: formulasJson 的每个元素都必须是以 = 开头的公式字符串";
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
        args.put("formulas", formulas);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_set_formulas", args);
    }

    @Tool("读取当前 Excel 工作簿总览：所有工作表清单（名称、是否为当前活动表）+ 各表已用区域尺寸" +
          "（行数/列数/地址，空表为 null）。适合在动手改表前先建立全局认知，对标桌面端 sheet_get_overview。")
    @ToolMeta(displayName = "读取总览", category = "office")
    public String office_excel_get_overview(
            @P("会话ID（系统自动注入）") String conversationId
    ) {
        log.info("Tool: office_excel_get_overview called");
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_get_overview", Map.of());
    }

    @Tool("把用户在 Excel 中的视图定位到指定区域并选中（不修改数据，只是把焦点带过去，" +
          "常用于向用户展示「你看这里」）。sheetName 缺省为当前活动工作表；若指定了非活动表，会先切到该表。")
    @ToolMeta(displayName = "选中区域", category = "office")
    public String office_excel_select_range(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("区域地址，A1 表示法如 A1 或 A1:D20") String rangeAddress
    ) {
        log.info("Tool: office_excel_select_range called, sheet={}, range={}", sheetName, rangeAddress);
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
            return "Error: 区域地址不能为空且须为 A1 表示法（如 A1 或 A1:D20，不带工作表名）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("rangeAddress", addr);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_select_range", args);
    }

    @Tool("设置 Excel 工作表的自动筛选：apply 套上筛选（在指定区域顶行加下拉箭头，不预设筛选条件）、" +
          "clear 清除已生效的筛选条件（保留下拉箭头）、remove 彻底移除自动筛选。" +
          "**首版只做套上/清除筛选，不支持按具体条件筛值**（如只看某列等于某值），" +
          "按条件筛选请用户在 Excel 里手动点下拉箭头操作。需要 ExcelApi 1.9。直接生效（Excel 没有修订机制）。" +
          "sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "设置自动筛选", category = "office", fileEffect = "MODIFIED")
    public String office_excel_set_autofilter(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("区域地址，A1 表示法如 A1:D20（仅 apply 需要）") String rangeAddress,
            @P("动作：apply/clear/remove") String action
    ) {
        log.info("Tool: office_excel_set_autofilter called, sheet={}, action={}", sheetName, action);
        String actionValue;
        try {
            actionValue = normalizeEnum(action, EXCEL_AUTOFILTER_ACTIONS, "action");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (actionValue == null) {
            return "Error: action 不能为空（apply/clear/remove）";
        }
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if ("apply".equals(actionValue) && (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches())) {
            return "Error: apply 需要 rangeAddress（A1 表示法，如 A1:D20）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("action", actionValue);
        if ("apply".equals(actionValue)) args.put("rangeAddress", addr);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_set_autofilter", args);
    }

    @Tool("给 Excel 区域套用或清除条件格式。ruleType=cellValue 按单元格数值与阈值的比较关系" +
          "（operator: greaterThan/lessThan/between/equalTo，between 需要 value1 与 value2，其余只需 value1）" +
          "把命中单元格填色 fillColor（缺省 #FFC7CE，Excel 经典的浅红色高亮）；" +
          "ruleType=colorScale 套用红黄绿三色刻度（低到高，无需额外参数）。" +
          "action=clearAll 清除该区域已有的全部条件格式规则（此时 ruleType 等参数不需要）。" +
          "**首版只做这两类规则**，不支持图标集/数据条/公式自定义规则。每次调用会先清空该区域现有规则再套用新规则" +
          "（与桌面端 sheet_conditional_format 同口径，不是叠加）。" +
          "需要 ExcelApi 1.6。直接生效（Excel 没有修订机制）。sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "设置条件格式", category = "office", fileEffect = "MODIFIED")
    public String office_excel_conditional_format(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("区域地址，A1 表示法如 A1:D20") String rangeAddress,
            @P("规则类型：cellValue/colorScale（action=apply 时必填）") String ruleType,
            @P("比较运算符：greaterThan/lessThan/between/equalTo（ruleType=cellValue 时必填）") String operator,
            @P("比较值 1（ruleType=cellValue 时必填）") Double value1,
            @P("比较值 2（operator=between 时必填，用作区间上界）") Double value2,
            @P("命中单元格填充色 #RRGGBB（ruleType=cellValue 用，缺省 #FFC7CE）") String fillColor,
            @P("动作：apply（套用，缺省）/clearAll（清除该区域全部条件格式规则）") String action
    ) {
        log.info("Tool: office_excel_conditional_format called, sheet={}, range={}, action={}",
                sheetName, rangeAddress, action);
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
            return "Error: 区域地址不能为空且须为 A1 表示法（如 A1:D20，不带工作表名）";
        }
        String actionValue;
        try {
            actionValue = normalizeEnum(action, EXCEL_CF_ACTIONS, "action");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (actionValue == null) actionValue = "apply";

        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("rangeAddress", addr);
        args.put("action", actionValue);

        if ("apply".equals(actionValue)) {
            String ruleTypeValue;
            String operatorValue;
            try {
                ruleTypeValue = normalizeEnum(ruleType, EXCEL_CF_RULE_TYPES, "ruleType");
                operatorValue = normalizeEnum(operator, EXCEL_CF_OPERATOR_VALUES, "operator");
            } catch (IllegalArgumentException e) {
                return "Error: " + e.getMessage();
            }
            if (ruleTypeValue == null) {
                return "Error: ruleType 不能为空（apply 时必填：cellValue/colorScale）";
            }
            if (fillColor != null && !fillColor.isBlank() && !HEX_COLOR.matcher(fillColor.trim()).matches()) {
                return "Error: fillColor 须为 #RRGGBB 格式，如 #FFC7CE";
            }
            args.put("ruleType", ruleTypeValue);
            if ("cellvalue".equals(ruleTypeValue)) {
                if (operatorValue == null) {
                    return "Error: ruleType=cellValue 时 operator 不能为空（greaterThan/lessThan/between/equalTo）";
                }
                if (value1 == null) {
                    return "Error: ruleType=cellValue 时 value1 不能为空";
                }
                if ("between".equals(operatorValue) && value2 == null) {
                    return "Error: operator=between 时 value2 不能为空（区间上界）";
                }
                args.put("operator", operatorValue);
                args.put("value1", value1);
                if (value2 != null) args.put("value2", value2);
                args.put("fillColor", fillColor == null || fillColor.isBlank() ? "#FFC7CE" : fillColor.trim());
            }
        }
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_conditional_format", args);
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
