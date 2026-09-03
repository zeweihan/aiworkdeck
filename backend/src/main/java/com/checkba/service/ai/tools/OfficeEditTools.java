package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.OfficeBridgeService;
import com.checkba.storage.StorageServiceFactory;
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
    /** office_insert_image 读取项目文件字节用（批次 9） */
    private final ProjectFileRepository projectFileRepository;
    private final StorageServiceFactory storageServiceFactory;

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
    /** PPT 字符格式下划线线型白名单（与 Word 面对齐；插件端 wave 映射为 PowerPoint 的 Wavy 枚举） */
    private static final java.util.Set<String> PPT_UNDERLINE_VALUES =
            java.util.Set.of("none", "single", "double", "dotted", "wave");

    /** PPT 几何形状类型白名单（v1 起步三种；GeometricShapeType 全集远不止这些，按需再扩） */
    private static final java.util.Set<String> PPT_SHAPE_TYPES =
            java.util.Set.of("rectangle", "ellipse", "triangle");

    /** PPT 结构操作（增删移动幻灯片/形状）页码与序号的防呆上限 */
    private static final int MAX_PPT_SLIDE_NUMBER = 2000;
    /** 表格单元格坐标：列字母（1~3 位）+ 行号（1 开始），如 B2（批次 8） */
    private static final java.util.regex.Pattern TABLE_CELL_REF =
            java.util.regex.Pattern.compile("^[A-Za-z]{1,3}\\d{1,6}$");

    /** 表格行/列结构操作（增删）单次的最大数量 */
    private static final int MAX_TABLE_STRUCTURE_COUNT = 50;

    /** 分页符类型白名单（起步只开页面分隔与节分隔两种，其余 Word.BreakType 成员暂不暴露给模型） */
    private static final java.util.Set<String> BREAK_TYPE_VALUES =
            java.util.Set.of("page", "sectionNext");

    /** 页眉/页脚部位白名单 */
    private static final java.util.Set<String> HEADER_FOOTER_PART_VALUES =
            java.util.Set.of("header", "footer");

    /** 超链接协议白名单：只认 http/https，防止 javascript: 等协议注入 */
    private static final java.util.regex.Pattern HTTP_URL =
            java.util.regex.Pattern.compile("^https?://.+", java.util.regex.Pattern.CASE_INSENSITIVE);

    // ==================== 批次 9 新增常量（Excel 批注/校验/图表/命名区域/保护/分组/透视表，
    // Word 修订/脚注尾注/图片/样式/内容控件/文档属性，PPT 表格/超链接） ====================

    /** Excel 单元格地址：A1 表示法，不带工作表名，须为单格（用于批注定位） */
    private static final java.util.regex.Pattern SINGLE_CELL_ADDRESS =
            java.util.regex.Pattern.compile("^[A-Za-z]{1,3}\\d{1,7}$");

    /** Excel 批注读取范围白名单 */
    private static final java.util.Set<String> EXCEL_COMMENT_SCOPES = java.util.Set.of("sheet", "workbook");

    /** Excel 数据验证白名单 */
    private static final java.util.Set<String> EXCEL_DV_ACTIONS = java.util.Set.of("apply", "clear");
    private static final java.util.Set<String> EXCEL_DV_TYPES = java.util.Set.of("wholenumber", "list", "date");
    private static final java.util.Set<String> EXCEL_DV_OPERATORS =
            java.util.Set.of("between", "greaterthan", "lessthan", "equalto");

    /** Excel 图表类型白名单（v1 起步四种） */
    private static final java.util.Set<String> EXCEL_CHART_TYPES = java.util.Set.of("column", "line", "pie", "bar");

    /** Excel 命名区域动作白名单 + 名称合法性（须以字母/下划线开头，只含字母数字下划线点号） */
    private static final java.util.Set<String> EXCEL_NAME_ACTIONS = java.util.Set.of("add", "remove");
    private static final java.util.regex.Pattern EXCEL_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]{0,254}$");

    /** Excel 工作表保护动作白名单 */
    private static final java.util.Set<String> EXCEL_PROTECT_ACTIONS = java.util.Set.of("protect", "unprotect");

    /** Excel 行列分组动作/方向白名单；rangeAddress 须是整行区域（如 4:9）或整列区域（如 C:E） */
    private static final java.util.Set<String> EXCEL_GROUP_ACTIONS = java.util.Set.of("group", "ungroup");
    private static final java.util.Set<String> EXCEL_GROUP_BY = java.util.Set.of("rows", "cols");
    private static final java.util.regex.Pattern EXCEL_ROWCOL_RANGE =
            java.util.regex.Pattern.compile("^([A-Za-z]{1,3}:[A-Za-z]{1,3}|\\d{1,7}:\\d{1,7})$");

    /** Excel 透视表行/值字段数上限 */
    private static final int MAX_PIVOT_FIELDS = 10;

    /** Word 内容控件动作白名单 */
    private static final java.util.Set<String> CONTENT_CONTROL_ACTIONS =
            java.util.Set.of("insert", "read", "set_text", "delete");

    /** office_insert_image 单张图片字节上限（2MB；经桥下发 base64 后体积再膨胀约 1/3） */
    private static final long MAX_INSERT_IMAGE_BYTES = 2L * 1024 * 1024;

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
          "searchText 必须与文档中的文本精确一致；replaceAll=false 时只替换第一处。" +
          "replaceText 必须是纯文本，不要携带 Markdown 记号（---、**、# 等）——它们只会成为文档里的字面字符，排版请改用格式化工具。")
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

    /** 一次 office_replace_batch 最多改多少处（与插件端 batchEdits.MAX_BATCH_ITEMS 同值） */
    private static final int MAX_BATCH_EDITS = 50;

    @Tool("在当前 Word 文档中一次完成多处查找替换，全部以 Word 原生修订（Track Changes）形式呈现。" +
          "editsJson 是 JSON 数组，每个元素形如 {\"searchText\":\"原文\",\"replaceText\":\"新文\"}，一批最多 " + MAX_BATCH_EDITS + " 处。" +
          "【整篇校对、整篇润色、批量改称谓这类要改很多处的任务，必须用本工具成批提交，不要逐处调用 office_replace_text】" +
          "——逐处调用每处都要占一整个执行步（单轮上限 30 步），一份合同改不完就会中途暂停。" +
          "每条的 searchText 须与文档精确一致、不得跨段落、不超 255 字；各条之间不得重复、也不得互相包含（会把同一段文字改两遍）；" +
          "replaceText 必须是纯文本，不要携带 Markdown 记号。" +
          "返回值里 replaced 是成功处数，failed 逐条列出没定位到的条目——只需针对 failed 里的条目换锚点重试，不要整批重发（会写入两遍）。")
    @ToolMeta(displayName = "批量替换（修订）", category = "office", fileEffect = "MODIFIED")
    public String office_replace_batch(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("修改清单，JSON 数组：[{\"searchText\":\"原文\",\"replaceText\":\"新文\"}, ...]") String editsJson
    ) {
        log.info("Tool: office_replace_batch called");
        if (editsJson == null || editsJson.isBlank()) {
            return "Error: editsJson 不能为空，示例：[{\"searchText\":\"违约责仁\",\"replaceText\":\"违约责任\"}]";
        }
        java.util.List<java.util.Map<String, Object>> raw;
        try {
            raw = objectMapper.readValue(editsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {});
        } catch (Exception e) {
            return "Error: editsJson 不是合法的 JSON 数组，示例：[{\"searchText\":\"违约责仁\",\"replaceText\":\"违约责任\"}]";
        }
        if (raw == null || raw.isEmpty()) {
            return "Error: editsJson 至少要有一条修改";
        }
        if (raw.size() > MAX_BATCH_EDITS) {
            return "Error: 一批最多 " + MAX_BATCH_EDITS + " 处，本次给了 " + raw.size() + " 处，请拆成多批分次提交";
        }
        // 校验全部前置：任何一条不合法都不下发，避免一次 30 秒起步的空等过桥
        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            int index = i + 1;
            java.util.Map<String, Object> item = raw.get(i);
            Object search = item == null ? null : item.get("searchText");
            Object replace = item == null ? null : item.get("replaceText");
            String searchText = search == null ? "" : String.valueOf(search);
            if (searchText.isBlank()) {
                return "Error: 第 " + index + " 条的 searchText 为空";
            }
            if (replace == null) {
                return "Error: 第 " + index + " 条缺少 replaceText（删除请传空字符串）";
            }
            if (searchText.length() > 255) {
                return "Error: 第 " + index + " 条的 searchText 过长（Word 查找上限 255 字符），请缩短";
            }
            if (searchText.indexOf('\n') >= 0 || searchText.indexOf('\r') >= 0) {
                return "Error: 第 " + index + " 条的 searchText 跨段落（含换行），Word 的查找不支持跨段匹配，请拆成同一段内的多条";
            }
            if (!seen.add(searchText)) {
                return "Error: 第 " + index + " 条的 searchText 与前面某条重复（同一处会被改两遍），请合并成一条";
            }
            java.util.Map<String, Object> normalized = new HashMap<>();
            normalized.put("searchText", searchText);
            normalized.put("replaceText", String.valueOf(replace));
            items.add(normalized);
        }
        // 一条 searchText 是另一条的子串时两处命中必然重叠，各自落笔＝同一段文字被改两遍
        // （后一笔盖在前一笔的产物上），产物是乱码而不是报错。校对场景里模型很容易同时给出
        // 「违约责仁」和「承担违约责仁。」这样一短一长的两条。
        for (int a = 0; a < items.size(); a++) {
            for (int b = 0; b < items.size(); b++) {
                if (a == b) continue;
                String outer = String.valueOf(items.get(a).get("searchText"));
                String inner = String.valueOf(items.get(b).get("searchText"));
                if (outer.contains(inner)) {
                    return "Error: 第 " + (b + 1) + " 条的 searchText 是第 " + (a + 1)
                            + " 条的一部分，两处会重叠、同一段文字被改两遍。请合并成一条，或把两条都换成互不包含的原文";
                }
            }
        }
        return officeBridgeService.executeOfficeCommand(conversationId, "replace_batch",
                Map.of("items", items));
    }

    @Tool("在当前 Word 文档中插入文本，插入以 Word 原生修订（Track Changes）形式呈现。" +
          "提供 anchorText 时在该锚点前/后插入（锚点须与文档精确一致）；不提供时在用户当前光标/选区处插入。" +
          "text 必须是纯文本，不要携带 Markdown 记号（---、**、# 等）——它们只会成为文档里的字面字符，排版请改用格式化工具。")
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
        // borderColor/borderWidth 是 borders 的修饰参数，不是独立参数——此前只在"一个格式参数都
        // 没给"时才会报这个道理（下面那条 all-empty 检查）；只要 headerBold/alignment 等任一其它
        // 参数也一起给了，这条检查就不触发，调用照常派发成功，borderColor/borderWidth 被悄悄丢弃，
        // 响应里没有任何字样提示——模型和用户都以为边框颜色生效了（审计条目）。必须单独判一次。
        if ((borderColor != null || borderWidth != null) && bordersValue == null) {
            return "Error: borderColor/borderWidth 只在同时传入 borders（且不为 none）时才会生效，"
                    + "本次没有传 borders，这两个参数不会被应用。若要设置边框请同时传 borders；"
                    + "若只是想改其它参数（如 headerBold），请去掉 borderColor/borderWidth 后重试。";
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

    // ==================== 表格（Word 原生修订，批次 8） ====================

    @Tool("在当前 Word 文档中插入一张表格。rowsJson 是 JSON 二维字符串数组（矩形，每行列数一致），如 " +
          "[[\"项目\",\"金额\"],[\"咨询费\",\"10000\"]]，第一行默认为表头。" +
          "提供 anchorText 时插在该锚点前/后（锚点须与文档精确一致）；不提供时插在当前光标/选区处。" +
          "headerBold=true 时首行加粗。需要 WordApi 1.3；插入以 Word 原生修订（Track Changes）形式呈现。")
    @ToolMeta(displayName = "插入表格", category = "office", fileEffect = "MODIFIED")
    public String office_insert_table(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("表格内容，JSON 二维字符串数组（矩形），第一行为表头") String rowsJson,
            @P("首行是否加粗（不改则不传）") Boolean headerBold,
            @P("定位锚点文本（可选；为空则在当前光标/选区处插入）") String anchorText,
            @P("相对锚点的位置：before 或 after（默认 after，仅提供锚点时有效）") String position
    ) {
        log.info("Tool: office_insert_table called, json length={}, anchor={}",
                rowsJson != null ? rowsJson.length() : 0, anchorText);
        if (rowsJson == null || rowsJson.isBlank()) {
            return "Error: 缺少 rowsJson 参数（JSON 二维数组）";
        }
        if (anchorText != null && anchorText.length() > 255) {
            return "Error: 锚点文本过长（Word 查找上限 255 字符），请改用更短的唯一锚点";
        }
        String pos = position == null || position.isBlank() ? "after" : position.trim().toLowerCase();
        if (!"before".equals(pos) && !"after".equals(pos)) {
            return "Error: position 只能是 before 或 after";
        }
        java.util.List<java.util.List<String>> rows;
        try {
            rows = objectMapper.readValue(rowsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.List<String>>>() {});
        } catch (Exception e) {
            return "Error: rowsJson 不是合法的 JSON 二维数组，示例：[[\"项目\",\"金额\"],[\"咨询费\",\"10000\"]]";
        }
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isEmpty()) {
            return "Error: rowsJson 不能为空表";
        }
        int cols = rows.get(0).size();
        for (java.util.List<String> row : rows) {
            if (row == null || row.size() != cols) {
                return "Error: rowsJson 必须是矩形二维数组（每行列数一致）";
            }
        }
        Map<String, Object> args = new HashMap<>();
        args.put("rows", rows);
        if (headerBold != null) args.put("headerBold", headerBold);
        args.put("anchorText", anchorText == null ? "" : anchorText);
        args.put("position", pos);
        return officeBridgeService.executeOfficeCommand(conversationId, "insert_table", args);
    }

    @Tool("把当前 Word 文档里的一张表读成二维数组（行列数 + 每格文本），改表格前必须先用它看清现状。" +
          "tableIndex 是文档中第几张表（0 开始，缺省 0），越界时返回表格总数供重试。" +
          "返回的 cells 按行给出，office_table_set_cell 的 cell 参数用列字母+行号（如 B2）坐标。" +
          "需要 WordApi 1.3。")
    @ToolMeta(displayName = "读取表格", category = "office")
    public String office_table_read(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("表格序号（0 开始，缺省 0）") Integer tableIndex
    ) {
        log.info("Tool: office_table_read called, index={}", tableIndex);
        int index = tableIndex == null ? 0 : tableIndex;
        if (index < 0) {
            return "Error: tableIndex 不能为负（文档中第一张表是 0）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("tableIndex", index);
        return officeBridgeService.executeOfficeCommand(conversationId, "table_read", args);
    }

    @Tool("改当前 Word 文档里一张表格中一个单元格的文本（整格替换）。先用 office_table_read 看清表格坐标再改。" +
          "cell 用列字母+行号，如 B2 = 第 2 列第 2 行。修订模式下只有真正变动的字符会落成修订，不是整格删了重打。" +
          "tableIndex 是文档中第几张表（0 开始，缺省 0）。需要 WordApi 1.3；修改以 Word 原生修订形式呈现。")
    @ToolMeta(displayName = "修改单元格", category = "office", fileEffect = "MODIFIED")
    public String office_table_set_cell(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("表格序号（0 开始，缺省 0）") Integer tableIndex,
            @P("单元格坐标，如 B2（列字母 + 行号，行号 1 开始）") String cell,
            @P("该单元格的新文本") String text
    ) {
        log.info("Tool: office_table_set_cell called, index={}, cell={}", tableIndex, cell);
        int index = tableIndex == null ? 0 : tableIndex;
        if (index < 0) {
            return "Error: tableIndex 不能为负（文档中第一张表是 0）";
        }
        if (cell == null || cell.isBlank() || !TABLE_CELL_REF.matcher(cell.trim()).matches()) {
            return "Error: cell 格式非法，须为列字母+行号（如 B2）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("tableIndex", index);
        args.put("cell", cell.trim());
        args.put("text", text == null ? "" : text);
        return officeBridgeService.executeOfficeCommand(conversationId, "table_set_cell", args);
    }

    @Tool("给当前 Word 文档里的一张表格插入空白行。rowIndex 是新行插入位置（0 开始，插在该行之前）；" +
          "不传或传 -1 则追加到表尾。count 一次插几行（默认 1，上限 " + MAX_TABLE_STRUCTURE_COUNT + "）。" +
          "插完用 office_table_set_cell 逐格填内容。tableIndex 是文档中第几张表（0 开始，缺省 0）。" +
          "需要 WordApi 1.3；插入以 Word 原生修订形式呈现。")
    @ToolMeta(displayName = "插入表格行", category = "office", fileEffect = "MODIFIED")
    public String office_table_add_row(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("表格序号（0 开始，缺省 0）") Integer tableIndex,
            @P("插入位置行号（0 开始，新行插在该行之前），不传或 -1 则追加到表尾") Integer rowIndex,
            @P("插入几行，默认 1") Integer count
    ) {
        log.info("Tool: office_table_add_row called, tableIndex={}, rowIndex={}, count={}", tableIndex, rowIndex, count);
        return dispatchTableStructureCommand(conversationId, "table_add_row", tableIndex, rowIndex, count, -1);
    }

    @Tool("删除当前 Word 文档里一张表格的整行。rowIndex 是要删的行号（0 开始，必填），" +
          "count 连删几行（默认 1，上限 " + MAX_TABLE_STRUCTURE_COUNT + "）。" +
          "注意：删行是**直接删除、不留修订痕迹**（Word 对表格结构变化的修订记录能力有限），" +
          "删错只能靠 Ctrl+Z 或文档检查点回退，所以删之前务必先用 office_table_read 看清要删的是哪一行。" +
          "表格至少要留一行。tableIndex 是文档中第几张表（0 开始，缺省 0）。需要 WordApi 1.3。")
    @ToolMeta(displayName = "删除表格行", category = "office", fileEffect = "MODIFIED")
    public String office_table_delete_row(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("表格序号（0 开始，缺省 0）") Integer tableIndex,
            @P("要删的行号（0 开始，必填）") Integer rowIndex,
            @P("连删几行，默认 1") Integer count
    ) {
        log.info("Tool: office_table_delete_row called, tableIndex={}, rowIndex={}, count={}", tableIndex, rowIndex, count);
        if (rowIndex == null) {
            return "Error: 缺少 rowIndex 参数（要删的行号，0 开始）";
        }
        return dispatchTableStructureCommand(conversationId, "table_delete_row", tableIndex, rowIndex, count, null);
    }

    @Tool("给当前 Word 文档里的一张表格插入空白列。colIndex 是新列插入位置（0 开始，插在该列之前）；" +
          "不传或传 -1 则追加到最右。count 一次插几列（默认 1，上限 " + MAX_TABLE_STRUCTURE_COUNT + "）。" +
          "colIndex 为 0 或 -1（表头/表尾）时任何 Word 版本都可用；插在表格中间某一列前** " +
          "需要较新的桌面版 Word（WordApiDesktop 1.3，Word 网页版不支持）**，不支持时会明确报错，" +
          "可改用 0 或 -1。tableIndex 是文档中第几张表（0 开始，缺省 0）。修改以 Word 原生修订形式呈现。")
    @ToolMeta(displayName = "插入表格列", category = "office", fileEffect = "MODIFIED")
    public String office_table_add_col(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("表格序号（0 开始，缺省 0）") Integer tableIndex,
            @P("插入位置列号（0 开始，新列插在该列之前），不传或 -1 则追加到最右") Integer colIndex,
            @P("插入几列，默认 1") Integer count
    ) {
        log.info("Tool: office_table_add_col called, tableIndex={}, colIndex={}, count={}", tableIndex, colIndex, count);
        return dispatchTableStructureCommand(conversationId, "table_add_col", tableIndex, colIndex, count, -1);
    }

    @Tool("删除当前 Word 文档里一张表格的整列。colIndex 是要删的列号（0 开始，必填），" +
          "count 连删几列（默认 1，上限 " + MAX_TABLE_STRUCTURE_COUNT + "）。" +
          "与删行一样是**直接删除、不留修订痕迹**，删前先用 office_table_read 看清。表格至少要留一列。" +
          "tableIndex 是文档中第几张表（0 开始，缺省 0）。需要 WordApi 1.3。")
    @ToolMeta(displayName = "删除表格列", category = "office", fileEffect = "MODIFIED")
    public String office_table_delete_col(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("表格序号（0 开始，缺省 0）") Integer tableIndex,
            @P("要删的列号（0 开始，必填）") Integer colIndex,
            @P("连删几列，默认 1") Integer count
    ) {
        log.info("Tool: office_table_delete_col called, tableIndex={}, colIndex={}, count={}", tableIndex, colIndex, count);
        if (colIndex == null) {
            return "Error: 缺少 colIndex 参数（要删的列号，0 开始）";
        }
        return dispatchTableStructureCommand(conversationId, "table_delete_col", tableIndex, colIndex, count, null);
    }

    /**
     * 表格行/列增删四个原语的共同下发路径：tableIndex 定位 + position（行/列号）+ count 校验。
     * defaultPositionWhenNull 非 null 时表示 position 缺省值（add 系列缺省 -1=追加到末尾）；
     * 为 null 时表示 position 是必填参数（delete 系列，调用方已在此之前校验过非空）。
     */
    private String dispatchTableStructureCommand(String conversationId, String command, Integer tableIndex,
                                                   Integer position, Integer count, Integer defaultPositionWhenNull) {
        int index = tableIndex == null ? 0 : tableIndex;
        if (index < 0) {
            return "Error: tableIndex 不能为负（文档中第一张表是 0）";
        }
        int pos = position == null
                ? (defaultPositionWhenNull == null ? Integer.MIN_VALUE : defaultPositionWhenNull)
                : position;
        if (pos < -1) {
            return "Error: 位置参数不能小于 -1（-1 表示末尾）";
        }
        int cnt = count == null ? 1 : count;
        if (cnt < 1 || cnt > MAX_TABLE_STRUCTURE_COUNT) {
            return "Error: count 须为 1~" + MAX_TABLE_STRUCTURE_COUNT + " 的整数（缺省 1）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("tableIndex", index);
        args.put("position", pos);
        args.put("count", cnt);
        return officeBridgeService.executeOfficeCommand(conversationId, command, args);
    }

    // ==================== 结构（分页符/超链接/页眉页脚，批次 8） ====================

    @Tool("在当前 Word 文档中插入分页符或分节符。breakType：page=分页符（缺省）、sectionNext=下一页分节符。" +
          "提供 anchorText 时在该锚点前/后插入（锚点须与文档精确一致）；不提供时在当前光标/选区处插入。" +
          "插入以 Word 原生修订（Track Changes）形式呈现。")
    @ToolMeta(displayName = "插入分页符", category = "office", fileEffect = "MODIFIED")
    public String office_insert_break(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("分页类型：page（分页符，缺省）或 sectionNext（下一页分节符）") String breakType,
            @P("定位锚点文本（可选；为空则在当前光标/选区处插入）") String anchorText,
            @P("相对锚点的位置：before 或 after（默认 after，仅提供锚点时有效）") String position
    ) {
        log.info("Tool: office_insert_break called, breakType={}, anchor={}", breakType, anchorText);
        String typeValue;
        try {
            typeValue = normalizeEnum(breakType, BREAK_TYPE_VALUES, "breakType");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (anchorText != null && anchorText.length() > 255) {
            return "Error: 锚点文本过长（Word 查找上限 255 字符），请改用更短的唯一锚点";
        }
        String pos = position == null || position.isBlank() ? "after" : position.trim().toLowerCase();
        if (!"before".equals(pos) && !"after".equals(pos)) {
            return "Error: position 只能是 before 或 after";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("breakType", typeValue == null ? "page" : typeValue);
        args.put("anchorText", anchorText == null ? "" : anchorText);
        args.put("position", pos);
        return officeBridgeService.executeOfficeCommand(conversationId, "insert_break", args);
    }

    @Tool("在当前 Word 文档中为指定文本设置超链接。anchorText 须与文档中的文本精确一致，" +
          "命中第一处并把该文本变成指向 url 的链接（原有格式基本保留）。url 只认 http/https 协议。" +
          "修改以 Word 原生修订（Track Changes）形式呈现，需要 WordApi 1.3。")
    @ToolMeta(displayName = "设置超链接", category = "office", fileEffect = "MODIFIED")
    public String office_set_hyperlink(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("要设置超链接的目标文本（须与文档精确一致）") String anchorText,
            @P("链接地址，须以 http:// 或 https:// 开头") String url
    ) {
        log.info("Tool: office_set_hyperlink called, anchor={}, url={}", anchorText, url);
        if (anchorText == null || anchorText.isBlank()) {
            return "Error: 目标文本不能为空";
        }
        if (anchorText.length() > 255) {
            return "Error: 目标文本过长（Word 查找上限 255 字符），请截取其中一段唯一文本作为目标";
        }
        if (url == null || url.isBlank() || !HTTP_URL.matcher(url.trim()).matches()) {
            return "Error: url 须以 http:// 或 https:// 开头";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("anchorText", anchorText);
        args.put("url", url.trim());
        return officeBridgeService.executeOfficeCommand(conversationId, "set_hyperlink", args);
    }

    @Tool("编辑当前 Word 文档首节的页眉或页脚文字（覆盖原有内容）。part：header=页眉、footer=页脚。" +
          "text 为空字符串等于清空该页眉/页脚。alignment 可选设置对齐方式。" +
          "仅作用于文档首节——多节文档（如分节设置了不同页眉页脚）的其余节不受影响，" +
          "如需处理请让用户确认要改的是哪一节。页眉页脚编辑是否记入 Word 修订面板取决于 Word 版本。")
    @ToolMeta(displayName = "编辑页眉页脚", category = "office", fileEffect = "MODIFIED")
    public String office_edit_header_footer(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("header（页眉）或 footer（页脚）") String part,
            @P("要写入的文本，覆盖原有内容（空字符串等于清空）") String text,
            @P("对齐方式：left/center/right/justify（不改则不传）") String alignment
    ) {
        log.info("Tool: office_edit_header_footer called, part={}", part);
        String partValue;
        String alignmentValue;
        try {
            partValue = normalizeEnum(part, HEADER_FOOTER_PART_VALUES, "part");
            alignmentValue = normalizeEnum(alignment, ALIGNMENT_VALUES, "alignment");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (partValue == null) {
            return "Error: part 不能为空（header 或 footer）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("part", partValue);
        args.put("text", text == null ? "" : text);
        if (alignmentValue != null) args.put("alignment", alignmentValue);
        return officeBridgeService.executeOfficeCommand(conversationId, "edit_header_footer", args);
    }

    // ==================== 批注（读取/回复/解决，批次 8） ====================

    @Tool("读取当前 Word 文档中的全部批注：作者、创建时间、内容、锚点文本摘要、是否已解决、序号（index）。" +
          "回复或解决某条批注时，用这里返回的 index 定位。需要 WordApi 1.4。")
    @ToolMeta(displayName = "读取批注", category = "office")
    public String office_get_comments(
            @P("会话ID（系统自动注入）") String conversationId
    ) {
        log.info("Tool: office_get_comments called");
        return officeBridgeService.executeOfficeCommand(conversationId, "get_comments", Map.of());
    }

    @Tool("回复当前 Word 文档中的一条批注。用 office_get_comments 返回的 id 或 index（0 开始）定位目标批注，" +
          "两者给一个即可（id 优先）；先调用 office_get_comments 拿到定位信息再回复。")
    @ToolMeta(displayName = "回复批注", category = "office", fileEffect = "MODIFIED")
    public String office_reply_comment(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标批注的 id（来自 office_get_comments 的返回值，与 commentIndex 二选一，id 优先）") String commentId,
            @P("目标批注的序号（0 开始，来自 office_get_comments 的返回值，与 commentId 二选一）") Integer commentIndex,
            @P("回复内容") String reply
    ) {
        log.info("Tool: office_reply_comment called, id={}, index={}", commentId, commentIndex);
        if ((commentId == null || commentId.isBlank()) && (commentIndex == null || commentIndex < 0)) {
            return "Error: 缺少批注定位参数（commentId 或 commentIndex，先调用 office_get_comments 拿到）";
        }
        if (reply == null || reply.isBlank()) {
            return "Error: 回复内容不能为空";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("commentId", commentId == null ? "" : commentId.trim());
        if (commentIndex != null) args.put("commentIndex", commentIndex);
        args.put("reply", reply);
        return officeBridgeService.executeOfficeCommand(conversationId, "reply_comment", args);
    }

    @Tool("标记当前 Word 文档中的一条批注为已解决（或重新打开）。用 office_get_comments 返回的 id 或 " +
          "index（0 开始）定位目标批注，两者给一个即可（id 优先）；" +
          "resolved 缺省 true（标记已解决），传 false 可重新打开一条已解决的批注。")
    @ToolMeta(displayName = "解决批注", category = "office", fileEffect = "MODIFIED")
    public String office_resolve_comment(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标批注的 id（来自 office_get_comments 的返回值，与 commentIndex 二选一，id 优先）") String commentId,
            @P("目标批注的序号（0 开始，来自 office_get_comments 的返回值，与 commentId 二选一）") Integer commentIndex,
            @P("标记为已解决（true，缺省）还是重新打开（false）") Boolean resolved
    ) {
        log.info("Tool: office_resolve_comment called, id={}, index={}, resolved={}", commentId, commentIndex, resolved);
        if ((commentId == null || commentId.isBlank()) && (commentIndex == null || commentIndex < 0)) {
            return "Error: 缺少批注定位参数（commentId 或 commentIndex，先调用 office_get_comments 拿到）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("commentId", commentId == null ? "" : commentId.trim());
        if (commentIndex != null) args.put("commentIndex", commentIndex);
        args.put("resolved", resolved == null || resolved);
        return officeBridgeService.executeOfficeCommand(conversationId, "resolve_comment", args);
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

    @Tool("在当前 PowerPoint 演示文稿中查找文本并设置其字符格式：字体、字号、加粗、斜体、下划线、颜色。" +
          "searchText 须与幻灯片文本精确一致（区分大小写），默认只对第一处匹配生效，applyToAll=true 对所有匹配生效。" +
          "格式参数至少要给一个，没传的保持原样。直接生效（PowerPoint 没有修订机制）。" +
          "需要 PowerPointApi 1.4，旧版宿主会返回明确错误。")
    @ToolMeta(displayName = "设置幻灯片文字格式", category = "office", fileEffect = "MODIFIED")
    public String office_ppt_format_text(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("要查找的文本（须与幻灯片文本精确一致）") String searchText,
            @P("是否对所有匹配生效（false=仅第一处）") Boolean applyToAll,
            @P("字体名（不改则不传）") String fontName,
            @P("字号（磅）（不改则不传）") Double fontSize,
            @P("加粗 true/false（不改则不传）") Boolean bold,
            @P("斜体 true/false（不改则不传）") Boolean italic,
            @P("下划线线型：none/single/double/dotted/wave（不改则不传）") String underline,
            @P("文字颜色 #RRGGBB，如 #C00000（不改则不传）") String color
    ) {
        log.info("Tool: office_ppt_format_text called, search={}, applyToAll={}", searchText, applyToAll);
        if (searchText == null || searchText.isBlank()) {
            return "Error: 查找文本不能为空";
        }
        if (searchText.length() > 255) {
            return "Error: 查找文本过长（上限 255 字符），请缩短后重试";
        }
        if (fontSize != null && (fontSize <= 0 || fontSize > 1638)) {
            return "Error: fontSize 须为大于 0 且不超过 1638 的磅值";
        }
        if (color != null && !color.isBlank() && !HEX_COLOR.matcher(color.trim()).matches()) {
            return "Error: color 须为 #RRGGBB 格式，如 #C00000";
        }
        String underlineValue;
        try {
            underlineValue = normalizeEnum(underline, PPT_UNDERLINE_VALUES, "underline");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        Map<String, Object> args = new HashMap<>();
        if (fontName != null && !fontName.isBlank()) args.put("fontName", fontName.trim());
        if (fontSize != null) args.put("fontSize", fontSize);
        if (bold != null) args.put("bold", bold);
        if (italic != null) args.put("italic", italic);
        if (underlineValue != null) args.put("underline", underlineValue);
        if (color != null && !color.isBlank()) args.put("color", color.trim());
        if (args.isEmpty()) {
            return "Error: 未给出任何格式参数（fontName/fontSize/bold/italic/underline/color 至少给一个）";
        }
        args.put("searchText", searchText);
        args.put("applyToAll", applyToAll != null && applyToAll);
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_format_text", args);
    }

    @Tool("在当前 PowerPoint 演示文稿中新增一页幻灯片，可选写入标题与正文文本框。" +
          "position 指定新页插入后成为第几页（1 起，即插在原第 position 页之前；不传则追加到末尾）——PowerPoint JS API 只能把新页加到末尾" +
          "再挪动位置，挪动需要 PowerPointApi 1.8（较新 Microsoft 365），旧版宿主上会追加到末尾但不挪动位置，" +
          "返回值 moved/note 字段说明实际情况。title/body 用文本框承载（需要 PowerPointApi 1.4），位置尺寸用固定默认值。")
    @ToolMeta(displayName = "新增幻灯片", category = "office", fileEffect = "MODIFIED")
    public String office_ppt_add_slide(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("新页插入后成为第几页（1 起，即插在原第 position 页之前；不传则追加到末尾）") Integer position,
            @P("标题文本（可选）") String title,
            @P("正文文本（可选）") String body
    ) {
        log.info("Tool: office_ppt_add_slide called, position={}", position);
        if (position != null && (position < 1 || position > MAX_PPT_SLIDE_NUMBER)) {
            return "Error: position 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        Map<String, Object> args = new HashMap<>();
        if (position != null) args.put("position", position);
        if (title != null && !title.isBlank()) args.put("title", title);
        if (body != null && !body.isBlank()) args.put("body", body);
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_add_slide", args);
    }

    @Tool("删除当前 PowerPoint 演示文稿中的指定幻灯片。slideNumber 从 1 起。" +
          "演示文稿只剩一页时拒绝删除（PowerPoint 不允许空演示文稿）。直接生效，无法通过审阅面板撤销——" +
          "误删的安全网是 office_ppt_add_slide 补建或用户在 Word/PowerPoint 里 Ctrl+Z。")
    @ToolMeta(displayName = "删除幻灯片", category = "office", fileEffect = "MODIFIED")
    public String office_ppt_delete_slide(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("要删除的幻灯片页码（1 起）") Integer slideNumber
    ) {
        log.info("Tool: office_ppt_delete_slide called, slideNumber={}", slideNumber);
        if (slideNumber == null || slideNumber < 1 || slideNumber > MAX_PPT_SLIDE_NUMBER) {
            return "Error: slideNumber 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("slideNumber", slideNumber);
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_delete_slide", args);
    }

    @Tool("在当前 PowerPoint 演示文稿的指定幻灯片上插入一个文本框。slideNumber 从 1 起，text 必填。" +
          "left/top/width/height 单位磅，不传则用默认位置尺寸（left=50/top=50/width=400/height=100）。" +
          "fontSize/bold/color 可选设置文本框内文字格式。直接生效（PowerPoint 没有修订机制）。" +
          "需要 PowerPointApi 1.4，旧版宿主会返回明确错误。")
    @ToolMeta(displayName = "插入文本框", category = "office", fileEffect = "MODIFIED")
    public String office_ppt_add_text_box(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标幻灯片页码（1 起）") Integer slideNumber,
            @P("文本框内容") String text,
            @P("左边距（磅）（不传则用默认值 50）") Double left,
            @P("上边距（磅）（不传则用默认值 50）") Double top,
            @P("宽度（磅）（不传则用默认值 400）") Double width,
            @P("高度（磅）（不传则用默认值 100）") Double height,
            @P("字号（磅）（不改则不传）") Double fontSize,
            @P("加粗 true/false（不改则不传）") Boolean bold,
            @P("文字颜色 #RRGGBB（不改则不传）") String color
    ) {
        log.info("Tool: office_ppt_add_text_box called, slideNumber={}", slideNumber);
        if (slideNumber == null || slideNumber < 1 || slideNumber > MAX_PPT_SLIDE_NUMBER) {
            return "Error: slideNumber 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        if (text == null || text.isEmpty()) {
            return "Error: 文本框内容不能为空";
        }
        if (width != null && width <= 0) {
            return "Error: width 须为大于 0 的磅值";
        }
        if (height != null && height <= 0) {
            return "Error: height 须为大于 0 的磅值";
        }
        if (fontSize != null && (fontSize <= 0 || fontSize > 1638)) {
            return "Error: fontSize 须为大于 0 且不超过 1638 的磅值";
        }
        if (color != null && !color.isBlank() && !HEX_COLOR.matcher(color.trim()).matches()) {
            return "Error: color 须为 #RRGGBB 格式，如 #C00000";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("slideNumber", slideNumber);
        args.put("text", text);
        if (left != null) args.put("left", left);
        if (top != null) args.put("top", top);
        if (width != null) args.put("width", width);
        if (height != null) args.put("height", height);
        if (fontSize != null) args.put("fontSize", fontSize);
        if (bold != null) args.put("bold", bold);
        if (color != null && !color.isBlank()) args.put("color", color.trim());
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_add_text_box", args);
    }

    @Tool("把当前 PowerPoint 演示文稿中的一页幻灯片移动到新位置。slideNumber/toPosition 均从 1 起。" +
          "需要 PowerPointApi 1.8（较新 Microsoft 365 才有，比其余 PPT 工具的 1.4 门槛更高），" +
          "旧版宿主会返回明确错误而不是静默不生效。")
    @ToolMeta(displayName = "移动幻灯片", category = "office", fileEffect = "MODIFIED")
    public String office_ppt_move_slide(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("要移动的幻灯片页码（1 起）") Integer slideNumber,
            @P("移动到的目标页码（1 起）") Integer toPosition
    ) {
        log.info("Tool: office_ppt_move_slide called, slideNumber={}, toPosition={}", slideNumber, toPosition);
        if (slideNumber == null || slideNumber < 1 || slideNumber > MAX_PPT_SLIDE_NUMBER) {
            return "Error: slideNumber 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        if (toPosition == null || toPosition < 1 || toPosition > MAX_PPT_SLIDE_NUMBER) {
            return "Error: toPosition 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("slideNumber", slideNumber);
        args.put("toPosition", toPosition);
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_move_slide", args);
    }

    @Tool("在当前 PowerPoint 演示文稿的指定幻灯片上插入一个几何形状：矩形/椭圆/三角形。slideNumber 从 1 起。" +
          "left/top/width/height 单位磅，不传则用默认位置尺寸（left=50/top=50/width=200/height=150）。" +
          "fillColor 可选设置填充色 #RRGGBB，不传则用形状默认填充。直接生效（PowerPoint 没有修订机制）。" +
          "需要 PowerPointApi 1.4，旧版宿主会返回明确错误。")
    @ToolMeta(displayName = "插入形状", category = "office", fileEffect = "MODIFIED")
    public String office_ppt_add_shape(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标幻灯片页码（1 起）") Integer slideNumber,
            @P("形状类型：rectangle/ellipse/triangle") String shapeType,
            @P("左边距（磅）（不传则用默认值 50）") Double left,
            @P("上边距（磅）（不传则用默认值 50）") Double top,
            @P("宽度（磅）（不传则用默认值 200）") Double width,
            @P("高度（磅）（不传则用默认值 150）") Double height,
            @P("填充色 #RRGGBB（不传则用形状默认填充）") String fillColor
    ) {
        log.info("Tool: office_ppt_add_shape called, slideNumber={}, shapeType={}", slideNumber, shapeType);
        if (slideNumber == null || slideNumber < 1 || slideNumber > MAX_PPT_SLIDE_NUMBER) {
            return "Error: slideNumber 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        String shapeTypeValue;
        try {
            shapeTypeValue = normalizeEnum(shapeType, PPT_SHAPE_TYPES, "shapeType");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (shapeTypeValue == null) {
            return "Error: shapeType 不能为空（rectangle/ellipse/triangle）";
        }
        if (width != null && width <= 0) {
            return "Error: width 须为大于 0 的磅值";
        }
        if (height != null && height <= 0) {
            return "Error: height 须为大于 0 的磅值";
        }
        if (fillColor != null && !fillColor.isBlank() && !HEX_COLOR.matcher(fillColor.trim()).matches()) {
            return "Error: fillColor 须为 #RRGGBB 格式，如 #4472C4";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("slideNumber", slideNumber);
        args.put("shapeType", shapeTypeValue);
        if (left != null) args.put("left", left);
        if (top != null) args.put("top", top);
        if (width != null) args.put("width", width);
        if (height != null) args.put("height", height);
        if (fillColor != null && !fillColor.isBlank()) args.put("fillColor", fillColor.trim());
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_add_shape", args);
    }

    @Tool("读取当前 PowerPoint 演示文稿中指定一页幻灯片的形状明细：每个形状的 id、类型、位置尺寸（磅）、" +
          "文字内容（若有）。比 office_ppt_get_slides 细一级，供精确定位形状（如后续用 office_ppt_delete_shape）前先看一眼。" +
          "需要 PowerPointApi 1.4，旧版宿主会返回明确错误。")
    @ToolMeta(displayName = "读取幻灯片明细", category = "office")
    public String office_ppt_get_slide_details(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标幻灯片页码（1 起）") Integer slideNumber
    ) {
        log.info("Tool: office_ppt_get_slide_details called, slideNumber={}", slideNumber);
        if (slideNumber == null || slideNumber < 1 || slideNumber > MAX_PPT_SLIDE_NUMBER) {
            return "Error: slideNumber 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("slideNumber", slideNumber);
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_get_slide_details", args);
    }

    @Tool("删除当前 PowerPoint 演示文稿中指定幻灯片上的一个形状。slideNumber 从 1 起；" +
          "shapeId 是 office_ppt_get_slide_details 返回的形状 id（精确定位，推荐）；" +
          "不传 shapeId 时可传 textMatch，按形状文字内容精确匹配删除第一个命中的形状。" +
          "shapeId 与 textMatch 至少给一个。直接生效，无法通过审阅面板撤销。")
    @ToolMeta(displayName = "删除形状", category = "office", fileEffect = "MODIFIED")
    public String office_ppt_delete_shape(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标幻灯片页码（1 起）") Integer slideNumber,
            @P("形状 id（office_ppt_get_slide_details 返回，可选）") String shapeId,
            @P("按形状文字内容精确匹配（可选，shapeId 未给时使用）") String textMatch
    ) {
        log.info("Tool: office_ppt_delete_shape called, slideNumber={}, shapeId={}", slideNumber, shapeId);
        if (slideNumber == null || slideNumber < 1 || slideNumber > MAX_PPT_SLIDE_NUMBER) {
            return "Error: slideNumber 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        boolean hasId = shapeId != null && !shapeId.isBlank();
        boolean hasText = textMatch != null && !textMatch.isBlank();
        if (!hasId && !hasText) {
            return "Error: shapeId 与 textMatch 须至少给一个（先用 office_ppt_get_slide_details 看形状 id）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("slideNumber", slideNumber);
        if (hasId) args.put("shapeId", shapeId.trim());
        if (hasText) args.put("textMatch", textMatch.trim());
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_delete_shape", args);
    }

    // ==================== Excel 单元格批注（office_excel_*，批次 9，ExcelApi 1.10） ====================

    @Tool("在 Excel 单元格上添加批注（线程式评论，非旧版批注/Note）。cellAddress 必须是单个单元格（如 B2，不能是区域）。" +
          "需要 ExcelApi 1.10。sheetName 缺省为当前活动工作表。直接生效（Excel 没有修订机制）。")
    @ToolMeta(displayName = "添加批注", category = "office", fileEffect = "MODIFIED")
    public String office_excel_add_comment(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("单元格地址，A1 表示法，须为单格如 B2") String cellAddress,
            @P("批注内容") String comment
    ) {
        log.info("Tool: office_excel_add_comment called, sheet={}, cell={}", sheetName, cellAddress);
        String addr = cellAddress == null ? "" : cellAddress.trim();
        if (addr.isEmpty() || !SINGLE_CELL_ADDRESS.matcher(addr).matches()) {
            return "Error: cellAddress 必须是单个单元格地址（如 B2），不能是区域";
        }
        if (comment == null || comment.isBlank()) {
            return "Error: 批注内容不能为空";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("cellAddress", addr);
        args.put("comment", comment);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_add_comment", args);
    }

    @Tool("读取当前 Excel 工作表（或整个工作簿）的全部批注线程：单元格地址、楼主内容、作者、是否已解决、回复列表。" +
          "scope=sheet（缺省，当前工作表）或 workbook（整个工作簿）。回复/解决/删除批注时用返回的 cellAddress 定位。")
    @ToolMeta(displayName = "读取批注", category = "office")
    public String office_excel_get_comments(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表，仅 scope=sheet 时有效）") String sheetName,
            @P("范围：sheet（缺省，当前工作表）或 workbook（整个工作簿）") String scope
    ) {
        log.info("Tool: office_excel_get_comments called, sheet={}, scope={}", sheetName, scope);
        String scopeValue;
        try {
            scopeValue = normalizeEnum(scope, EXCEL_COMMENT_SCOPES, "scope");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("scope", scopeValue == null ? "sheet" : scopeValue);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_get_comments", args);
    }

    @Tool("回复 Excel 中某单元格的批注线程。cellAddress 定位目标单元格（须已有批注，先用 office_excel_get_comments 核对）。" +
          "需要 ExcelApi 1.10。")
    @ToolMeta(displayName = "回复批注", category = "office", fileEffect = "MODIFIED")
    public String office_excel_reply_comment(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("目标单元格地址，A1 表示法（须已有批注）") String cellAddress,
            @P("回复内容") String reply
    ) {
        log.info("Tool: office_excel_reply_comment called, cell={}", cellAddress);
        String addr = cellAddress == null ? "" : cellAddress.trim();
        if (addr.isEmpty() || !SINGLE_CELL_ADDRESS.matcher(addr).matches()) {
            return "Error: cellAddress 必须是单个单元格地址（如 B2）";
        }
        if (reply == null || reply.isBlank()) {
            return "Error: 回复内容不能为空";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("cellAddress", addr);
        args.put("reply", reply);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_reply_comment", args);
    }

    @Tool("标记 Excel 中某单元格的批注线程为已解决（或重新打开）。cellAddress 定位目标单元格。" +
          "resolved 缺省 true。需要 ExcelApi 1.10。")
    @ToolMeta(displayName = "解决批注", category = "office", fileEffect = "MODIFIED")
    public String office_excel_resolve_comment(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("目标单元格地址，A1 表示法（须已有批注）") String cellAddress,
            @P("标记为已解决（true，缺省）还是重新打开（false）") Boolean resolved
    ) {
        log.info("Tool: office_excel_resolve_comment called, cell={}", cellAddress);
        String addr = cellAddress == null ? "" : cellAddress.trim();
        if (addr.isEmpty() || !SINGLE_CELL_ADDRESS.matcher(addr).matches()) {
            return "Error: cellAddress 必须是单个单元格地址（如 B2）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("cellAddress", addr);
        args.put("resolved", resolved == null || resolved);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_resolve_comment", args);
    }

    @Tool("删除 Excel 中某单元格的整条批注线程（含全部回复）。cellAddress 定位目标单元格。不可撤销（无修订机制），" +
          "误删靠 Ctrl+Z 或文档检查点。")
    @ToolMeta(displayName = "删除批注", category = "office", fileEffect = "MODIFIED")
    public String office_excel_delete_comment(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("目标单元格地址，A1 表示法（须已有批注）") String cellAddress
    ) {
        log.info("Tool: office_excel_delete_comment called, cell={}", cellAddress);
        String addr = cellAddress == null ? "" : cellAddress.trim();
        if (addr.isEmpty() || !SINGLE_CELL_ADDRESS.matcher(addr).matches()) {
            return "Error: cellAddress 必须是单个单元格地址（如 B2）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("cellAddress", addr);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_delete_comment", args);
    }

    // ==================== Excel 数据验证（office_excel_set_data_validation，批次 9，ExcelApi 1.8） ====================

    @Tool("给 Excel 区域设置或清除数据验证规则。action=apply 时 type 必填：" +
          "wholeNumber=整数（operator+value1[+value2]）、list=下拉列表（listSource 逗号分隔的候选值）、" +
          "date=日期（operator+value1[+value2]，日期用 yyyy-mm-dd）。operator=between 时 value2 是区间上界，" +
          "其余 operator 只需 value1。action=clear 清除该区域已有的验证规则（此时其余参数不需要）。" +
          "需要 ExcelApi 1.8。直接生效（Excel 没有修订机制）。sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "设置数据验证", category = "office", fileEffect = "MODIFIED")
    public String office_excel_set_data_validation(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("区域地址，A1 表示法如 B2:B20") String rangeAddress,
            @P("动作：apply（套用，缺省）/clear（清除）") String action,
            @P("验证类型：wholeNumber/list/date（action=apply 时必填）") String type,
            @P("比较运算符：between/greaterThan/lessThan/equalTo（wholeNumber/date 用，between 时必填 value2）") String operator,
            @P("比较值 1（wholeNumber 用数字字符串，date 用 yyyy-mm-dd）") String value1,
            @P("比较值 2（operator=between 时必填，用作区间上界）") String value2,
            @P("下拉候选值，逗号分隔（type=list 时必填，如 是,否,待定）") String listSource
    ) {
        log.info("Tool: office_excel_set_data_validation called, sheet={}, range={}, action={}", sheetName, rangeAddress, action);
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
            return "Error: 区域地址不能为空且须为 A1 表示法（如 B2:B20，不带工作表名）";
        }
        String actionValue;
        String typeValue;
        String operatorValue;
        try {
            actionValue = normalizeEnum(action, EXCEL_DV_ACTIONS, "action");
            typeValue = normalizeEnum(type, EXCEL_DV_TYPES, "type");
            operatorValue = normalizeEnum(operator, EXCEL_DV_OPERATORS, "operator");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (actionValue == null) actionValue = "apply";

        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("rangeAddress", addr);
        args.put("action", actionValue);
        if ("apply".equals(actionValue)) {
            if (typeValue == null) {
                return "Error: type 不能为空（apply 时必填：wholeNumber/list/date）";
            }
            args.put("type", typeValue);
            if ("list".equals(typeValue)) {
                if (listSource == null || listSource.isBlank()) {
                    return "Error: type=list 时 listSource 不能为空（逗号分隔的候选值）";
                }
                args.put("listSource", listSource.trim());
            } else {
                if (operatorValue == null) {
                    return "Error: type=" + typeValue + " 时 operator 不能为空（between/greaterThan/lessThan/equalTo）";
                }
                if (value1 == null || value1.isBlank()) {
                    return "Error: value1 不能为空";
                }
                if ("between".equals(operatorValue) && (value2 == null || value2.isBlank())) {
                    return "Error: operator=between 时 value2 不能为空（区间上界）";
                }
                args.put("operator", operatorValue);
                args.put("value1", value1.trim());
                if (value2 != null && !value2.isBlank()) args.put("value2", value2.trim());
            }
        }
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_set_data_validation", args);
    }

    // ==================== Excel 图表（office_excel_add_chart，批次 9，ExcelApi 1.1） ====================

    @Tool("在 Excel 工作表插入图表：柱状图/折线图/饼图/条形图。dataRangeAddress 是数据源区域（含表头，第一行/列" +
          "作为系列名或分类名）。title 可选设置图表标题。需要 ExcelApi 1.1。直接生效（Excel 没有修订机制）。" +
          "sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "插入图表", category = "office", fileEffect = "MODIFIED")
    public String office_excel_add_chart(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("数据源区域，A1 表示法如 A1:C5") String dataRangeAddress,
            @P("图表类型：column（柱状图）/line（折线图）/pie（饼图）/bar（条形图）") String chartType,
            @P("图表标题（可选）") String title
    ) {
        log.info("Tool: office_excel_add_chart called, sheet={}, range={}, type={}", sheetName, dataRangeAddress, chartType);
        String addr = dataRangeAddress == null ? "" : dataRangeAddress.trim();
        if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
            return "Error: 数据源区域不能为空且须为 A1 表示法（如 A1:C5，不带工作表名）";
        }
        String typeValue;
        try {
            typeValue = normalizeEnum(chartType, EXCEL_CHART_TYPES, "chartType");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (typeValue == null) {
            return "Error: chartType 不能为空（column/line/pie/bar）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("dataRangeAddress", addr);
        args.put("chartType", typeValue);
        if (title != null && !title.isBlank()) args.put("title", title.trim());
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_add_chart", args);
    }

    // ==================== Excel 命名区域（office_excel_define_name，批次 9，ExcelApi 1.1） ====================

    @Tool("新增或删除 Excel 工作簿级命名区域（Named Range）。add 时需 rangeAddress；remove 只需 name。" +
          "name 须以字母或下划线开头，只能含字母数字下划线点号。需要 ExcelApi 1.1。sheetName 缺省为当前活动工作表" +
          "（仅用于解析 rangeAddress，命名区域本身是工作簿级）。")
    @ToolMeta(displayName = "管理命名区域", category = "office", fileEffect = "MODIFIED")
    public String office_excel_define_name(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表，用于解析 rangeAddress）") String sheetName,
            @P("动作：add/remove") String action,
            @P("命名区域名称") String name,
            @P("区域地址，A1 表示法如 A1:D1（action=add 时必填）") String rangeAddress
    ) {
        log.info("Tool: office_excel_define_name called, action={}, name={}", action, name);
        String actionValue;
        try {
            actionValue = normalizeEnum(action, EXCEL_NAME_ACTIONS, "action");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (actionValue == null) {
            return "Error: action 不能为空（add/remove）";
        }
        if (name == null || name.isBlank() || !EXCEL_NAME_PATTERN.matcher(name.trim()).matches()) {
            return "Error: name 非法，须以字母或下划线开头，只能含字母数字下划线点号（如 ExpensesHeader）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("action", actionValue);
        args.put("name", name.trim());
        if ("add".equals(actionValue)) {
            String addr = rangeAddress == null ? "" : rangeAddress.trim();
            if (addr.isEmpty() || !RANGE_ADDRESS.matcher(addr).matches()) {
                return "Error: add 需要 rangeAddress（A1 表示法，如 A1:D1，不带工作表名）";
            }
            args.put("rangeAddress", addr);
        }
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_define_name", args);
    }

    // ==================== Excel 工作表保护（office_excel_protect_sheet，批次 9，ExcelApi 1.2/1.7） ====================

    @Tool("保护或解除保护 Excel 工作表（阻止/允许用户编辑单元格）。password 可选（保护时设置解除密码，" +
          "解除时如工作表设了密码则必须提供）。**password 参数内容不落日志**。需要 ExcelApi 1.2（密码参数需 1.7）。" +
          "sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "保护工作表", category = "office", fileEffect = "MODIFIED")
    public String office_excel_protect_sheet(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("动作：protect/unprotect") String action,
            @P("密码（可选，不落日志）") String password
    ) {
        log.info("Tool: office_excel_protect_sheet called, sheet={}, action={}", sheetName, action);
        String actionValue;
        try {
            actionValue = normalizeEnum(action, EXCEL_PROTECT_ACTIONS, "action");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (actionValue == null) {
            return "Error: action 不能为空（protect/unprotect）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("action", actionValue);
        if (password != null && !password.isBlank()) args.put("password", password);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_protect_sheet", args);
    }

    // ==================== Excel 行列分组（office_excel_group_rows_cols，批次 9，ExcelApi 1.10） ====================

    @Tool("对 Excel 行或列区域分组/取消分组，形成可折叠大纲。rangeAddress 须是整行区域（如 4:9）或整列区域（如 C:E），" +
          "不能是普通单元格区域。by 指定分组方向：rows/cols，须与 rangeAddress 的行/列性质一致。需要 ExcelApi 1.10。" +
          "直接生效（Excel 没有修订机制）。sheetName 缺省为当前活动工作表。")
    @ToolMeta(displayName = "分组行列", category = "office", fileEffect = "MODIFIED")
    public String office_excel_group_rows_cols(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表）") String sheetName,
            @P("整行区域（如 4:9）或整列区域（如 C:E）") String rangeAddress,
            @P("动作：group/ungroup") String action,
            @P("分组方向：rows/cols，须与 rangeAddress 一致") String by
    ) {
        log.info("Tool: office_excel_group_rows_cols called, sheet={}, range={}, action={}", sheetName, rangeAddress, action);
        String addr = rangeAddress == null ? "" : rangeAddress.trim();
        if (addr.isEmpty() || !EXCEL_ROWCOL_RANGE.matcher(addr).matches()) {
            return "Error: rangeAddress 须是整行区域（如 4:9）或整列区域（如 C:E），不能是普通单元格区域";
        }
        String actionValue;
        String byValue;
        try {
            actionValue = normalizeEnum(action, EXCEL_GROUP_ACTIONS, "action");
            byValue = normalizeEnum(by, EXCEL_GROUP_BY, "by");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (actionValue == null) {
            return "Error: action 不能为空（group/ungroup）";
        }
        if (byValue == null) {
            return "Error: by 不能为空（rows/cols），须与 rangeAddress 一致";
        }
        boolean isRowRange = addr.matches("\\d{1,7}:\\d{1,7}");
        if (isRowRange != "rows".equals(byValue)) {
            return "Error: by 与 rangeAddress 不一致（" + (isRowRange ? "rangeAddress 是整行区域，by 应为 rows"
                    : "rangeAddress 是整列区域，by 应为 cols") + "）";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("rangeAddress", addr);
        args.put("action", actionValue);
        args.put("by", byValue);
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_group_rows_cols", args);
    }

    // ==================== Excel 透视表（office_excel_add_pivot_table，批次 9，ExcelApi 1.8） ====================

    @Tool("在 Excel 中创建一张基础透视表：源区域数据按行字段分组，值字段求和汇总。sourceRangeAddress 含表头。" +
          "destinationCellAddress 是透视表放置的左上角单元格（可以是另一张工作表的空白处）。" +
          "rowFieldsJson 是行分组字段名的 JSON 字符串数组（如 [\"部门\"]，最多 " + MAX_PIVOT_FIELDS + " 个）；" +
          "valueFieldsJson 同样是字段名数组，按数值求和汇总（如 [\"金额\"]）。" +
          "**只做基础形态**（行分组 + 求和汇总），不支持列字段/筛选字段/自定义汇总函数/复杂布局。" +
          "需要 ExcelApi 1.8。sheetName 缺省为当前活动工作表（源区域所在表）。")
    @ToolMeta(displayName = "创建透视表", category = "office", fileEffect = "MODIFIED")
    public String office_excel_add_pivot_table(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("工作表名（可选；为空取当前活动工作表，源区域所在表）") String sheetName,
            @P("源数据区域，A1 表示法，含表头，如 A1:D50") String sourceRangeAddress,
            @P("透视表放置的左上角单元格，A1 表示法，如 F1") String destinationCellAddress,
            @P("行分组字段名，JSON 字符串数组，如 [\"部门\"]") String rowFieldsJson,
            @P("求和汇总字段名，JSON 字符串数组，如 [\"金额\"]") String valueFieldsJson,
            @P("透视表名称（可选）") String pivotName
    ) {
        log.info("Tool: office_excel_add_pivot_table called, sheet={}, source={}, dest={}",
                sheetName, sourceRangeAddress, destinationCellAddress);
        String srcAddr = sourceRangeAddress == null ? "" : sourceRangeAddress.trim();
        if (srcAddr.isEmpty() || !RANGE_ADDRESS.matcher(srcAddr).matches()) {
            return "Error: sourceRangeAddress 不能为空且须为 A1 表示法（如 A1:D50，不带工作表名）";
        }
        String destAddr = destinationCellAddress == null ? "" : destinationCellAddress.trim();
        if (destAddr.isEmpty() || !RANGE_ADDRESS.matcher(destAddr).matches()) {
            return "Error: destinationCellAddress 不能为空且须为 A1 表示法（如 F1）";
        }
        java.util.List<String> rowFields;
        java.util.List<String> valueFields;
        try {
            rowFields = objectMapper.readValue(rowFieldsJson == null ? "" : rowFieldsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
            valueFields = objectMapper.readValue(valueFieldsJson == null ? "" : valueFieldsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
        } catch (Exception e) {
            return "Error: rowFieldsJson/valueFieldsJson 不是合法的 JSON 字符串数组，示例：[\"部门\"]";
        }
        if (rowFields.isEmpty()) {
            return "Error: rowFieldsJson 不能为空（至少一个行分组字段）";
        }
        if (valueFields.isEmpty()) {
            return "Error: valueFieldsJson 不能为空（至少一个求和汇总字段）";
        }
        if (rowFields.size() > MAX_PIVOT_FIELDS || valueFields.size() > MAX_PIVOT_FIELDS) {
            return "Error: 行分组字段与求和字段各自上限 " + MAX_PIVOT_FIELDS + " 个";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("sheetName", sheetName == null ? "" : sheetName.trim());
        args.put("sourceRangeAddress", srcAddr);
        args.put("destinationCellAddress", destAddr);
        args.put("rowFields", rowFields);
        args.put("valueFields", valueFields);
        if (pivotName != null && !pivotName.isBlank()) args.put("pivotName", pivotName.trim());
        return officeBridgeService.executeOfficeCommand(conversationId, "excel_add_pivot_table", args);
    }

    // ==================== Word 修订接受/拒绝（office_*，批次 9，WordApi 1.6） ====================

    @Tool("读取当前 Word 文档中的全部修订（Track Changes）记录：序号（index）、作者、时间、类型（插入/删除/格式）、文本摘要。" +
          "接受/拒绝某条修订时用这里返回的 index 定位。需要 WordApi 1.6。")
    @ToolMeta(displayName = "读取修订", category = "office")
    public String office_get_revisions(
            @P("会话ID（系统自动注入）") String conversationId
    ) {
        log.info("Tool: office_get_revisions called");
        return officeBridgeService.executeOfficeCommand(conversationId, "get_revisions", Map.of());
    }

    @Tool("接受当前 Word 文档中的修订。revisionIndex（来自 office_get_revisions 的 index，0 开始）定位单条修订；" +
          "acceptAll=true 时接受全部修订（此时忽略 revisionIndex）。两者至少给一个。需要 WordApi 1.6。")
    @ToolMeta(displayName = "接受修订", category = "office", fileEffect = "MODIFIED")
    public String office_accept_revision(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标修订的序号（0 开始，来自 office_get_revisions；acceptAll=true 时可不传）") Integer revisionIndex,
            @P("是否接受全部修订（true 时忽略 revisionIndex）") Boolean acceptAll
    ) {
        log.info("Tool: office_accept_revision called, index={}, all={}", revisionIndex, acceptAll);
        boolean all = acceptAll != null && acceptAll;
        if (!all && (revisionIndex == null || revisionIndex < 0)) {
            return "Error: 缺少 revisionIndex（0 开始，来自 office_get_revisions），或传 acceptAll=true 接受全部";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("all", all);
        if (!all) args.put("revisionIndex", revisionIndex);
        return officeBridgeService.executeOfficeCommand(conversationId, "accept_revision", args);
    }

    @Tool("拒绝当前 Word 文档中的修订。revisionIndex（来自 office_get_revisions 的 index，0 开始）定位单条修订；" +
          "rejectAll=true 时拒绝全部修订（此时忽略 revisionIndex）。两者至少给一个。需要 WordApi 1.6。")
    @ToolMeta(displayName = "拒绝修订", category = "office", fileEffect = "MODIFIED")
    public String office_reject_revision(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标修订的序号（0 开始，来自 office_get_revisions；rejectAll=true 时可不传）") Integer revisionIndex,
            @P("是否拒绝全部修订（true 时忽略 revisionIndex）") Boolean rejectAll
    ) {
        log.info("Tool: office_reject_revision called, index={}, all={}", revisionIndex, rejectAll);
        boolean all = rejectAll != null && rejectAll;
        if (!all && (revisionIndex == null || revisionIndex < 0)) {
            return "Error: 缺少 revisionIndex（0 开始，来自 office_get_revisions），或传 rejectAll=true 拒绝全部";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("all", all);
        if (!all) args.put("revisionIndex", revisionIndex);
        return officeBridgeService.executeOfficeCommand(conversationId, "reject_revision", args);
    }

    // ==================== Word 脚注/尾注（office_*，批次 9，WordApi 1.5） ====================

    @Tool("在当前 Word 文档中为指定文本插入脚注。anchorText 须与文档中的文本精确一致，脚注标记插在该文本之后，" +
          "text 是脚注正文内容。需要 WordApi 1.5；插入以 Word 原生修订（Track Changes）形式呈现。")
    @ToolMeta(displayName = "插入脚注", category = "office", fileEffect = "MODIFIED")
    public String office_insert_footnote(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("插入脚注标记的目标文本（须与文档精确一致）") String anchorText,
            @P("脚注正文内容") String text
    ) {
        log.info("Tool: office_insert_footnote called, anchor={}", anchorText);
        if (anchorText == null || anchorText.isBlank()) {
            return "Error: 目标文本不能为空";
        }
        if (anchorText.length() > 255) {
            return "Error: 目标文本过长（Word 查找上限 255 字符），请截取其中一段唯一文本作为目标";
        }
        if (text == null || text.isBlank()) {
            return "Error: 脚注正文内容不能为空";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("anchorText", anchorText);
        args.put("text", text);
        return officeBridgeService.executeOfficeCommand(conversationId, "insert_footnote", args);
    }

    @Tool("在当前 Word 文档中为指定文本插入尾注。用法与 office_insert_footnote 相同，" +
          "区别是尾注排在文档末尾而非当页页脚。需要 WordApi 1.5；插入以 Word 原生修订形式呈现。")
    @ToolMeta(displayName = "插入尾注", category = "office", fileEffect = "MODIFIED")
    public String office_insert_endnote(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("插入尾注标记的目标文本（须与文档精确一致）") String anchorText,
            @P("尾注正文内容") String text
    ) {
        log.info("Tool: office_insert_endnote called, anchor={}", anchorText);
        if (anchorText == null || anchorText.isBlank()) {
            return "Error: 目标文本不能为空";
        }
        if (anchorText.length() > 255) {
            return "Error: 目标文本过长（Word 查找上限 255 字符），请截取其中一段唯一文本作为目标";
        }
        if (text == null || text.isBlank()) {
            return "Error: 尾注正文内容不能为空";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("anchorText", anchorText);
        args.put("text", text);
        return officeBridgeService.executeOfficeCommand(conversationId, "insert_endnote", args);
    }

    // ==================== Word 图片插入（office_insert_image，批次 9，WordApi 1.2） ====================

    @Tool("把项目里的一张图片文件插入到当前 Word 文档中（内联图片）。fileId 是项目文件的数据库 ID" +
          "（doc_list_project_files 或素材列表可查到）。图片大小上限 2MB，超限会报错。" +
          "提供 anchorText 时在该锚点前/后插入（锚点须与文档精确一致）；不提供时在当前光标/选区处插入。" +
          "width 可选设置图片显示宽度（磅），不传则用图片原始尺寸。插入以 Word 原生修订形式呈现，需要 WordApi 1.2。")
    @ToolMeta(displayName = "插入图片", category = "office", fileEffect = "MODIFIED")
    public String office_insert_image(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("项目文件的数据库 ID（图片文件）") Long fileId,
            @P("定位锚点文本（可选；为空则在当前光标/选区处插入）") String anchorText,
            @P("相对锚点的位置：before 或 after（默认 after，仅提供锚点时有效）") String position,
            @P("图片显示宽度（磅）（不改则不传，用原始尺寸）") Double width
    ) {
        log.info("Tool: office_insert_image called, fileId={}, anchor={}", fileId, anchorText);
        if (fileId == null) {
            return "Error: fileId 不能为空（项目文件的数据库 ID）";
        }
        if (anchorText != null && anchorText.length() > 255) {
            return "Error: 锚点文本过长（Word 查找上限 255 字符），请改用更短的唯一锚点";
        }
        String pos = position == null || position.isBlank() ? "after" : position.trim().toLowerCase();
        if (!"before".equals(pos) && !"after".equals(pos)) {
            return "Error: position 只能是 before 或 after";
        }
        if (width != null && width <= 0) {
            return "Error: width 须为大于 0 的磅值";
        }
        java.util.Optional<ProjectFile> fileOpt = projectFileRepository.findById(fileId);
        if (fileOpt.isEmpty()) {
            return "Error: 文件不存在（fileId=" + fileId + "）";
        }
        ProjectFile pf = fileOpt.get();
        String denied = ToolFileGuard.rejectIfOutsideProject(pf);
        if (denied != null) {
            return denied;
        }
        String filePath = pf.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            filePath = pf.getWpsFileId();
        }
        if (filePath == null || filePath.isBlank()) {
            return "Error: 文件路径为空（fileId=" + fileId + "）";
        }
        byte[] bytes;
        try {
            org.springframework.core.io.Resource resource = storageServiceFactory.getStorageService().load(filePath);
            try (java.io.InputStream is = resource.getInputStream()) {
                bytes = is.readAllBytes();
            }
        } catch (Exception e) {
            log.warn("office_insert_image: failed to load file, fileId={}", fileId, e);
            return "Error: 读取文件失败：" + e.getMessage();
        }
        if (bytes.length > MAX_INSERT_IMAGE_BYTES) {
            return "Error: 图片过大（" + (bytes.length / 1024) + "KB），上限 2MB（2048KB），请压缩后重试";
        }
        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
        Map<String, Object> args = new HashMap<>();
        args.put("imageBase64", base64);
        args.put("anchorText", anchorText == null ? "" : anchorText);
        args.put("position", pos);
        if (width != null) args.put("width", width);
        return officeBridgeService.executeOfficeCommand(conversationId, "insert_image", args);
    }

    // ==================== Word 样式应用（office_apply_style，批次 9，WordApi 1.1） ====================

    @Tool("给当前 Word 文档中的段落套用一个已命名的样式（内置或自定义，如「标题 1」「正文」「引用」等，" +
          "样式名须与文档中实际存在的样式名一致，中文文档通常是中文样式名）。anchorText 定位段落" +
          "（命中处所在的整个段落就是目标段落），默认只对第一处匹配所在段落生效，applyToAll=true 对所有匹配所在段落生效。" +
          "套样式会重置段落的直接格式为该样式自带的格式。需要 WordApi 1.1；修改以 Word 原生修订形式呈现。")
    @ToolMeta(displayName = "应用样式", category = "office", fileEffect = "MODIFIED")
    public String office_apply_style(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标段落中的一段文本（须与文档精确一致）") String anchorText,
            @P("是否对所有匹配所在段落生效（false=仅第一处）") Boolean applyToAll,
            @P("样式名，须与文档中实际存在的样式名一致（如 标题 1、正文、引用）") String styleName
    ) {
        log.info("Tool: office_apply_style called, anchor={}, styleName={}", anchorText, styleName);
        if (anchorText == null || anchorText.isBlank()) {
            return "Error: 目标文本不能为空";
        }
        if (anchorText.length() > 255) {
            return "Error: 目标文本过长（Word 查找上限 255 字符），请截取其中一段唯一文本作为目标";
        }
        if (styleName == null || styleName.isBlank()) {
            return "Error: 样式名不能为空";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("anchorText", anchorText);
        args.put("applyToAll", applyToAll != null && applyToAll);
        args.put("styleName", styleName.trim());
        return officeBridgeService.executeOfficeCommand(conversationId, "apply_style", args);
    }

    // ==================== Word 内容控件（office_manage_content_control，批次 9，WordApi 1.1） ====================

    @Tool("管理当前 Word 文档中的内容控件（富文本类型，用于绑定/标记文档中的特定区域，如模板填空场景）。" +
          "insert：用 anchorText 定位一段文本，该文本所在的**整个段落**会被包进新内容控件（不是仅那段文本），" +
          "tag 是该控件的标识（后续用它定位，必填），" +
          "title 是显示给用户的可选标签。read：用 tag 找到控件并返回其文本内容。set_text：用 tag 找到控件并整体替换其文本。" +
          "delete：用 tag 找到控件并删除（keepContent=true 时只删控件外壳保留文字内容，缺省 false 连内容一起删）。" +
          "tag 在同一文档内应保持唯一，重复 tag 时各操作只命中第一个。需要 WordApi 1.1；insert/set_text/delete 以 Word 原生修订形式呈现。")
    @ToolMeta(displayName = "管理内容控件", category = "office", fileEffect = "MODIFIED")
    public String office_manage_content_control(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("动作：insert/read/set_text/delete") String action,
            @P("定位锚点文本（insert 时必填，须与文档精确一致）") String anchorText,
            @P("内容控件标识（insert 时必填用于新建；read/set_text/delete 时必填用于定位）") String tag,
            @P("控件显示标签（可选，仅 insert 用）") String title,
            @P("新文本内容（set_text 时必填）") String text,
            @P("delete 时是否保留内容只删控件外壳（缺省 false）") Boolean keepContent
    ) {
        log.info("Tool: office_manage_content_control called, action={}, tag={}", action, tag);
        String actionValue;
        try {
            actionValue = normalizeEnum(action, CONTENT_CONTROL_ACTIONS, "action");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (actionValue == null) {
            return "Error: action 不能为空（insert/read/set_text/delete）";
        }
        if ("insert".equals(actionValue)) {
            if (anchorText == null || anchorText.isBlank()) {
                return "Error: insert 需要 anchorText（须与文档精确一致）";
            }
            if (anchorText.length() > 255) {
                return "Error: anchorText 过长（Word 查找上限 255 字符），请截取其中一段唯一文本作为目标";
            }
            if (tag == null || tag.isBlank()) {
                return "Error: insert 需要 tag（内容控件标识）";
            }
        } else {
            if (tag == null || tag.isBlank()) {
                return "Error: " + actionValue + " 需要 tag（定位目标内容控件）";
            }
            if ("set_text".equals(actionValue) && text == null) {
                return "Error: set_text 需要 text（新文本内容，删除文本请传空字符串）";
            }
        }
        Map<String, Object> args = new HashMap<>();
        args.put("action", actionValue);
        args.put("tag", tag.trim());
        if ("insert".equals(actionValue)) {
            args.put("anchorText", anchorText);
            if (title != null && !title.isBlank()) args.put("title", title.trim());
        } else if ("set_text".equals(actionValue)) {
            args.put("text", text);
        } else if ("delete".equals(actionValue)) {
            args.put("keepContent", keepContent != null && keepContent);
        }
        return officeBridgeService.executeOfficeCommand(conversationId, "manage_content_control", args);
    }

    // ==================== Word 文档属性（office_set_document_properties，批次 9，WordApi 1.3） ====================

    @Tool("设置当前 Word 文档的内置属性：标题、主题、作者、关键词、备注、分类。参数至少要给一个，没传的保持原样。" +
          "需要 WordApi 1.3。这是文档元数据，不产生 Word 修订记录（属性面板本身没有修订机制）。")
    @ToolMeta(displayName = "设置文档属性", category = "office", fileEffect = "MODIFIED")
    public String office_set_document_properties(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("标题（不改则不传）") String title,
            @P("主题（不改则不传）") String subject,
            @P("作者（不改则不传）") String author,
            @P("关键词（不改则不传）") String keywords,
            @P("备注（不改则不传）") String comments,
            @P("分类（不改则不传）") String category
    ) {
        log.info("Tool: office_set_document_properties called");
        Map<String, Object> args = new HashMap<>();
        if (title != null) args.put("title", title);
        if (subject != null) args.put("subject", subject);
        if (author != null) args.put("author", author);
        if (keywords != null) args.put("keywords", keywords);
        if (comments != null) args.put("comments", comments);
        if (category != null) args.put("category", category);
        if (args.isEmpty()) {
            return "Error: 未给出任何属性参数（title/subject/author/keywords/comments/category 至少给一个）";
        }
        return officeBridgeService.executeOfficeCommand(conversationId, "set_document_properties", args);
    }

    // ==================== PowerPoint 表格与超链接（office_ppt_*，批次 9） ====================

    @Tool("在当前 PowerPoint 演示文稿的指定幻灯片上插入一张表格。slideNumber 从 1 起。rowsJson 是 JSON 二维字符串数组" +
          "（矩形，每行列数一致），如 [[\"项目\",\"金额\"],[\"咨询费\",\"10000\"]]；不传则插入空白 rows x cols 表格" +
          "（此时 rows/cols 必填）。left/top/width/height 单位磅，不传则用默认位置尺寸。需要 PowerPointApi 1.8。" +
          "直接生效（PowerPoint 没有修订机制）。")
    @ToolMeta(displayName = "插入表格", category = "office", fileEffect = "MODIFIED")
    public String office_ppt_add_table(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标幻灯片页码（1 起）") Integer slideNumber,
            @P("表格内容，JSON 二维字符串数组（矩形）（可选；不传则用 rows/cols 建空表）") String rowsJson,
            @P("行数（rowsJson 未给时必填）") Integer rows,
            @P("列数（rowsJson 未给时必填）") Integer cols,
            @P("左边距（磅）（不传则用默认值）") Double left,
            @P("上边距（磅）（不传则用默认值）") Double top,
            @P("宽度（磅）（不传则用默认值）") Double width,
            @P("高度（磅）（不传则用默认值）") Double height
    ) {
        log.info("Tool: office_ppt_add_table called, slideNumber={}", slideNumber);
        if (slideNumber == null || slideNumber < 1 || slideNumber > MAX_PPT_SLIDE_NUMBER) {
            return "Error: slideNumber 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        java.util.List<java.util.List<String>> rowsData = null;
        if (rowsJson != null && !rowsJson.isBlank()) {
            try {
                rowsData = objectMapper.readValue(rowsJson,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.List<String>>>() {});
            } catch (Exception e) {
                return "Error: rowsJson 不是合法的 JSON 二维数组，示例：[[\"项目\",\"金额\"],[\"咨询费\",\"10000\"]]";
            }
            if (rowsData.isEmpty() || rowsData.get(0) == null || rowsData.get(0).isEmpty()) {
                return "Error: rowsJson 不能为空表";
            }
            int cols0 = rowsData.get(0).size();
            for (java.util.List<String> row : rowsData) {
                if (row == null || row.size() != cols0) {
                    return "Error: rowsJson 必须是矩形二维数组（每行列数一致）";
                }
            }
        } else if (rows == null || rows < 1 || cols == null || cols < 1) {
            return "Error: 未提供 rowsJson 时必须给出 rows 与 cols（正整数）";
        }
        if (width != null && width <= 0) return "Error: width 须为大于 0 的磅值";
        if (height != null && height <= 0) return "Error: height 须为大于 0 的磅值";

        Map<String, Object> args = new HashMap<>();
        args.put("slideNumber", slideNumber);
        if (rowsData != null) {
            args.put("rows", rowsData);
        } else {
            args.put("rowCount", rows);
            args.put("colCount", cols);
        }
        if (left != null) args.put("left", left);
        if (top != null) args.put("top", top);
        if (width != null) args.put("width", width);
        if (height != null) args.put("height", height);
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_add_table", args);
    }

    @Tool("把当前 PowerPoint 演示文稿指定幻灯片上一张表格读成二维数组。slideNumber 从 1 起，shapeId 是表格所在形状的 id" +
          "（office_ppt_get_slide_details 可查到；不传则取该页第一张表格）。需要 PowerPointApi 1.8。")
    @ToolMeta(displayName = "读取表格", category = "office")
    public String office_ppt_table_read(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标幻灯片页码（1 起）") Integer slideNumber,
            @P("表格所在形状 id（可选；不传则取该页第一张表格）") String shapeId
    ) {
        log.info("Tool: office_ppt_table_read called, slideNumber={}, shapeId={}", slideNumber, shapeId);
        if (slideNumber == null || slideNumber < 1 || slideNumber > MAX_PPT_SLIDE_NUMBER) {
            return "Error: slideNumber 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("slideNumber", slideNumber);
        if (shapeId != null && !shapeId.isBlank()) args.put("shapeId", shapeId.trim());
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_table_read", args);
    }

    @Tool("改当前 PowerPoint 演示文稿指定幻灯片上一张表格中一个单元格的文本（整格替换）。" +
          "先用 office_ppt_table_read 看清表格坐标再改。row/col 均 0 开始。shapeId 不传则取该页第一张表格。" +
          "直接生效（PowerPoint 没有修订机制）。需要 PowerPointApi 1.8。")
    @ToolMeta(displayName = "修改表格单元格", category = "office", fileEffect = "MODIFIED")
    public String office_ppt_table_set_cell(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标幻灯片页码（1 起）") Integer slideNumber,
            @P("表格所在形状 id（可选；不传则取该页第一张表格）") String shapeId,
            @P("行号（0 开始）") Integer row,
            @P("列号（0 开始）") Integer col,
            @P("该单元格的新文本") String text
    ) {
        log.info("Tool: office_ppt_table_set_cell called, slideNumber={}, row={}, col={}", slideNumber, row, col);
        if (slideNumber == null || slideNumber < 1 || slideNumber > MAX_PPT_SLIDE_NUMBER) {
            return "Error: slideNumber 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        if (row == null || row < 0) return "Error: row 不能为空且不能为负（0 开始）";
        if (col == null || col < 0) return "Error: col 不能为空且不能为负（0 开始）";
        Map<String, Object> args = new HashMap<>();
        args.put("slideNumber", slideNumber);
        if (shapeId != null && !shapeId.isBlank()) args.put("shapeId", shapeId.trim());
        args.put("row", row);
        args.put("col", col);
        args.put("text", text == null ? "" : text);
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_table_set_cell", args);
    }

    @Tool("在当前 PowerPoint 演示文稿中查找文本并把它设置为超链接。slideNumber 从 1 起，searchText 须与幻灯片文本" +
          "精确一致（命中第一处）。url 只认 http/https 协议。直接生效（PowerPoint 没有修订机制）。需要 PowerPointApi 1.10。")
    @ToolMeta(displayName = "设置超链接", category = "office", fileEffect = "MODIFIED")
    public String office_ppt_set_hyperlink(
            @P("会话ID（系统自动注入）") String conversationId,
            @P("目标幻灯片页码（1 起）") Integer slideNumber,
            @P("要设置超链接的文本（须与幻灯片文本精确一致）") String searchText,
            @P("链接地址，须以 http:// 或 https:// 开头") String url
    ) {
        log.info("Tool: office_ppt_set_hyperlink called, slideNumber={}, url={}", slideNumber, url);
        if (slideNumber == null || slideNumber < 1 || slideNumber > MAX_PPT_SLIDE_NUMBER) {
            return "Error: slideNumber 须为 1~" + MAX_PPT_SLIDE_NUMBER + " 之间的整数";
        }
        if (searchText == null || searchText.isBlank()) {
            return "Error: 查找文本不能为空";
        }
        if (searchText.length() > 255) {
            return "Error: 查找文本过长（上限 255 字符），请缩短后重试";
        }
        if (url == null || url.isBlank() || !HTTP_URL.matcher(url.trim()).matches()) {
            return "Error: url 须以 http:// 或 https:// 开头";
        }
        Map<String, Object> args = new HashMap<>();
        args.put("slideNumber", slideNumber);
        args.put("searchText", searchText);
        args.put("url", url.trim());
        return officeBridgeService.executeOfficeCommand(conversationId, "ppt_set_hyperlink", args);
    }
}
