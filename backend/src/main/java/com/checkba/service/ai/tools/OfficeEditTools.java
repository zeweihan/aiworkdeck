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
