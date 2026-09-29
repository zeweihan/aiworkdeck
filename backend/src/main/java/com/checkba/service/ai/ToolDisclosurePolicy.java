// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.ai.skill.SkillRouter;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具渐进披露（dev-board#810 B 档，审计 A1 / C-07）。
 *
 * <p><b>要治的病</b>：工具规格是<b>每一轮</b>都要重发的，一条消息跑五到七个往返就付五到七遍。
 * 实测默认工作台会话每轮 192 个工具规格约 2.4 万 token，占固定前缀 5.2 万的将近一半；
 * 而其中绝大多数（litigation_* 的六个出图工具、pdf_* 的七个、pptx_* 的九个、表格与版式那几十个）
 * 在一次普通的「读一份合同、改两处措辞」里一个都用不到。
 *
 * <p><b>做法</b>：默认只下发一份<b>核心集</b>，其余按<b>类目</b>收进目录，模型用
 * {@code list_tools} 查目录、按类目展开。展开<b>只在下一轮生效</b>——这是
 * {@code AgentOrchestrator.RunGuard.activeDocKind} 那条「一轮内工具集不变」契约的延伸：
 * 模型已经在 messages 里宣布要调的工具，下一轮不能消失，否则通道直接 400。
 * 本类的裁剪<b>只做加法</b>（展开集单调增长），所以永远不会让已宣布的工具消失。
 *
 * <p><b>三条安全性质</b>，缺一条这套机制就会变成「模型以为自己没这个能力」：
 * <ol>
 *   <li><b>只裁 spec，不裁 resolve/execute</b>（与 dev-board#729 的活跃文档裁剪同一口径）：
 *       没下发的工具仍然登记着，XML 兜底路径按名字解析得到，所以模型看完
 *       {@code list_tools(category)} 的输出可以<b>当轮</b>就用 {@code <tool_code>} 调它，
 *       不必等下一轮；原生 function calling 那条路才需要多一轮。</li>
 *   <li><b>目录里没有黑洞</b>：{@link #categoryOf} 是全函数——任何工具要么在核心集，
 *       要么落进某个类目，实在认不出的落 {@code misc}，{@code list_tools()} 一定列得出来。
 *       新增工具不需要改本类就已经在目录里（只是不进核心集）。</li>
 *   <li><b>skill 已经裁过就不再裁</b>：skill 的 allowed_tools 本身就是一次披露，
 *       两层叠起来会把 skill 精心挑出来的工具又藏掉一半。判据在
 *       {@code AgentOrchestrator}（skill 返回的集合是否比候选集小）。</li>
 * </ol>
 *
 * <p><b>为什么核心集是一份人工清单而不是 {@code @ToolMeta} 上的一个布尔</b>：
 * 「这个工具算不算高频」不是工具自身的属性（{@code requiresHost} / {@code fileEffect} 才是），
 * 而是一次横切的取舍——要判断它得把四十个候选<b>放在一起</b>看覆盖面，
 * 散在三十四个文件的注解里没人能一眼看出「读一份合同」这条链是不是断的。
 * 清单集中在这里，配套的覆盖面断言也集中在 {@code ToolDisclosurePolicyTest}。
 *
 * <p><b>默认关闭</b>（{@code ai.tools.progressive-disclosure.enabled=false}）：这套机制改的是
 * 模型行为，而回放评测用的是脚本模型——脚本模型永远会按剧本调对工具，证明不了真实模型
 * 找不找得到 {@code list_tools}。绿的回放只说明「编排器没把事情做坏」，不说明
 * 「模型在少了一百个工具之后还能把活干完」。开关默认关，等真实模型的对照数据够了再翻。
 *
 * <p><b>另一把独立的刀：活跃文档类目裁剪</b>（dev-board#1064，
 * {@code ai.tools.doc-session-category-trim.enabled}，<b>默认开</b>）。开着一份文档时，
 * 与「改这份文档 / 就这份文档答疑」无关的整类工具（PDF、诉讼出图、演示文稿、企业数据、
 * 插件开发与能力安装、会议录音、Python）默认不下发，见 {@link #HIDDEN_CATEGORIES_BY_DOC_KIND}。
 * 它与渐进披露<b>同一个形状</b>：可见 = 候选 − （本类型隐藏的类目 − 已放回的类目），放回集就是
 * 渐进披露那份 {@code RunGuard.expandedToolCategories}，只增不减、下一轮生效，没有第二套机制。
 * 两者叠用时先按类目裁、再按核心集收窄，结果仍是「核心集 ∪ 已展开类目」的子集。
 *
 * <p>放回的四个触发点：本轮用户输入命中关键词（{@link #categoriesHintedBy}，起跑时一次算定）、
 * 本轮生效的 skill 白名单涉及的类目（{@link #categoriesCoveredBy}）、模型调 {@code list_tools(category)}、
 * 模型经 XML 兜底直接点名调了一个没下发的工具（它的同类工具下一轮回来）。
 *
 * <p>为什么它可以默认开而渐进披露不行：它藏掉的是「开着一份文档时几乎用不到」的整类，
 * 核心集那种「连 doc_* 版式工具都先藏起来」的激进收窄才真正改模型的做事路径；
 * 而漏网的请求至少有关键词、skill、目录三条路把能力放回来。
 */
@Service
public class ToolDisclosurePolicy {

    /** 目录工具名。它自己永远在核心集里，否则展开机制就没有入口。 */
    public static final String CATALOG_TOOL = "list_tools";

    /**
     * 核心集：任何会话都先下发这些（再与会话能力 / 活跃文档类型 / skill 白名单求交集）。
     *
     * <p>挑选判据是<b>把一条完整的活干完需要哪些工具</b>，不是按调用次数排名：
     * 少一个「读」类工具模型就绕路，少一个「写」类工具它就停在半路告诉用户做不了。
     * 每一组后面的注释写的是这组保证哪条链不断。
     */
    static final Set<String> CORE = Set.of(
            // —— 目录入口与编排：少了 list_tools 整套机制就没有入口；ask_user 是「拿不准先问」
            //    的唯一入口（dev-board#868），藏进目录里等于让模型先查目录才能想起来问 ——
            CATALOG_TOOL, "todo_write", "dispatch_subtask", "ask_user",

            // —— 项目材料：找文件 → 拿 fileId → 读全文 → 落一份新文件 ——
            "doc_list_project_files", "search_project_files", "extract_file_text", "read_document",
            "write_docx", "create_folder", "move_files_batch", "move_to_trash",

            // —— 记忆：检索与保存各一个（memory_* 六个由编排器的 MEMORY_TOOLS 规则另行兜底）——
            "query_memory", "save_memory",

            // —— 法源与公网：律师最高频的外部检索，四个加起来也只有一千出头字符 ——
            "law_search", "law_search_keyword", "get_law_article", "search_web", "browse_url",

            // —— 打开的文档·读：通读、找、看条款、机械核对 ——
            "doc_get_document_text", "doc_get_outline", "doc_get_paragraph", "doc_get_cursor_context",
            "doc_get_selection", "doc_find_text", "doc_get_clauses", "doc_audit_structure",

            // —— 打开的文档·写：改一处、插一段、整篇起草、套标准格式 ——
            "doc_find_replace", "doc_replace_at_anchor", "doc_replace_selection", "doc_insert_at_cursor",
            "doc_insert_under_heading", "doc_delete_text", "doc_start_stream", "doc_apply_standard_format",

            // —— 打开的文档·定位与后悔药：选中、撤销、检查点 ——
            "doc_open_file", "doc_select_anchor", "doc_select_paragraph", "doc_undo", "doc_restore_checkpoint",

            // —— 批注：审查合同时的主要交付物 ——
            "doc_add_comment", "doc_get_comments",

            // —— 表格 / 演示文稿 / Office 任务窗格的最小面 ——
            // 这几族在各自的会话里本来就被上游的能力闸与活跃文档闸裁过一遍，这里留的是
            // 「在那种会话里同样要能开工」的最小集合；doc_* 会话里它们早被裁掉，不占位。
            "sheet_create_file", "sheet_get_overview", "sheet_read_range", "sheet_write_cells",
            "slide_get_overview", "slide_set_shape_text", "slide_add_page",
            "office_get_text", "office_search", "office_insert_text", "office_replace_text",
            "office_replace_batch"
    );

    /**
     * 类目 → 这个类目收哪些工具。按<b>声明顺序</b>匹配，第一个命中的类目即归属，
     * 所以顺序本身是契约的一部分（{@code doc_table_*} 必须排在 {@code doc_} 通配之前）。
     *
     * <p>值里 {@code "name:"} 开头的是精确工具名，其余是名字前缀。
     */
    private static final Map<String, List<String>> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("table", List.of("doc_table_", "name:doc_insert_table"));
        CATEGORIES.put("revision", List.of(
                "name:doc_list_revisions", "name:doc_accept_revision", "name:doc_reject_revision",
                "name:doc_accept_all_revisions", "name:doc_reject_all_revisions",
                "name:doc_reply_comment", "name:doc_resolve_comment", "name:doc_delete_comment",
                "name:doc_debug_revisions"));
        CATEGORIES.put("evidence", List.of(
                "name:doc_link_evidence", "name:doc_list_evidence",
                "name:evidence_verify", "name:retrieve_evidence"));
        CATEGORIES.put("template", List.of(
                "name:docx_inspect_template", "name:doc_apply_style_profile",
                "name:create_file_from_template", "name:list_contributed_templates",
                "name:contribute_template"));
        // doc_* 里剩下的全是版式与排版：字符格式、段落格式、页面、页眉页脚、目录、脚注、图片、超链接……
        CATEGORIES.put("format", List.of("doc_"));
        CATEGORIES.put("spreadsheet", List.of("sheet_"));
        CATEGORIES.put("slides", List.of("slide_", "pptx_"));
        CATEGORIES.put("office", List.of("office_"));
        CATEGORIES.put("pdf", List.of("pdf_"));
        CATEGORIES.put("litigation", List.of("litigation_"));
        CATEGORIES.put("reference", List.of("ref_"));
        CATEGORIES.put("memory", List.of("memory_"));
        CATEGORIES.put("enterprise-data", List.of(
                "qichacha_", "name:tushare_query", "name:web_verify_import", "name:update_project_info"));
        // law_search / law_search_keyword / get_law_article 在核心集，这里收的是剩下的 law_recognition：
        // 归进 legal 比落 misc 好找——模型要的是「法规这一族还有什么」，不是「杂项里翻翻看」。
        CATEGORIES.put("legal", List.of("law_"));
        CATEGORIES.put("meeting", List.of("meeting_"));
        CATEGORIES.put("task", List.of("task_", "tag_"));
        CATEGORIES.put("files", List.of(
                "name:list_files", "name:read_file", "name:write_file", "name:scan_files",
                "name:move_file", "name:move_project_file", "name:rename_project_file",
                "name:list_project_folders", "name:delete_file", "text_", "name:dd_export"));
        CATEGORIES.put("plugin", List.of("plugin_dev_", "capability_"));
        CATEGORIES.put("python", List.of("name:run_python"));
    }

    /** 认不出归属的工具落这里——目录里绝不允许出现黑洞。 */
    public static final String FALLBACK_CATEGORY = "misc";

    /**
     * 活跃文档类型 → 默认不下发的类目（dev-board#1064）。键同 {@code ClientCapabilityService.DOC_KIND_*}。
     *
     * <p>只列<b>类目</b>：slide_* / sheet_* 在上游已经按前缀裁过（{@code visibleForDocKind}），
     * 这里列 {@code slides} 是为了 {@code pptx_*}——它不带 slide_ 前缀，上游一个都裁不掉，
     * 而一份 docx 会话里十个 pptx_* 规格每轮白付。
     *
     * <p><b>刻意留着的</b>：legal / task / memory / evidence / template / revision / format / table /
     * files / reference / misc，以及核心集里的 {@code search_web} / {@code browse_url}。
     * 网页浏览看上去与「改文档」无关，但它和法规检索是律师改合同时最高频的两类外部查证
     * （查监管口径、查一个陌生术语），两个加起来一千字符出头，藏掉换来的是每次都要先查目录。
     *
     * <p>kind 为 null / "text" / 未知值一律不裁——与 {@code visibleForDocKind} 同一条
     * 「判不准倒向全集」。
     */
    static final Map<String, Set<String>> HIDDEN_CATEGORIES_BY_DOC_KIND = Map.of(
            "doc", Set.of("pdf", "litigation", "slides", "enterprise-data", "plugin", "meeting", "python"),
            "sheet", Set.of("pdf", "litigation", "slides", "enterprise-data", "plugin", "meeting", "python"),
            "slide", Set.of("pdf", "litigation", "spreadsheet", "enterprise-data", "plugin", "meeting", "python"));

    /**
     * 类目 → 用户输入里出现就把该类目预先放回的关键词（dev-board#1064）。比较时一律小写；
     * 匹配口径复用 {@link SkillRouter#containsTrigger}：中文是子串，拉丁串两端要求整词
     * （「pptx」不会被「ppt」吃掉，所以两个都列）。
     *
     * <p>这张表只做<b>预先放回</b>，漏词的代价是模型多查一次目录（或经 XML 直接点名），不是能力丢失；
     * 多词的代价是这一轮多付那一类的规格。所以它宁可宽一点，但不收「文件」「合同」这种每句都有的词。
     */
    static final Map<String, List<String>> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        CATEGORY_KEYWORDS.put("pdf", List.of("pdf"));
        CATEGORY_KEYWORDS.put("litigation", List.of("时间轴", "关系图", "流程图", "可视化",
                "timeline", "diagram", "relationship graph"));
        CATEGORY_KEYWORDS.put("slides", List.of("ppt", "pptx", "幻灯片", "演示文稿", "slides", "presentation"));
        CATEGORY_KEYWORDS.put("enterprise-data", List.of("工商", "企业信息", "企查查", "股东", "注册资本",
                "公司背景", "tushare", "股票", "上市公司"));
        CATEGORY_KEYWORDS.put("plugin", List.of("插件", "plugin", "能力安装", "capability"));
        CATEGORY_KEYWORDS.put("meeting", List.of("会议", "录音", "纪要", "转写", "meeting", "transcript"));
        CATEGORY_KEYWORDS.put("python", List.of("python", "脚本", "计算一下", "跑一段代码"));
        CATEGORY_KEYWORDS.put("spreadsheet", List.of("表格", "excel", "xlsx", "工作表"));
        CATEGORY_KEYWORDS.put("task", List.of("事项", "日程", "提醒", "待办"));
    }

    private final boolean enabled;
    private final boolean docSessionCategoryTrim;

    /** 只管渐进披露的旧入口：类目裁剪关着。存量单测靠它保持改动前的行为。 */
    public ToolDisclosurePolicy(boolean enabled) {
        this(enabled, false);
    }

    @Autowired
    public ToolDisclosurePolicy(
            @Value("${ai.tools.progressive-disclosure.enabled:false}") boolean enabled,
            @Value("${ai.tools.doc-session-category-trim.enabled:true}") boolean docSessionCategoryTrim) {
        this.enabled = enabled;
        this.docSessionCategoryTrim = docSessionCategoryTrim;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 活跃文档类目裁剪开没开（dev-board#1064）。与 {@link #isEnabled()} 互相独立。 */
    public boolean isDocSessionCategoryTrimEnabled() {
        return docSessionCategoryTrim;
    }

    /**
     * 这种活跃文档类型下默认不下发的类目。开关关着、或 kind 判不准时返回空集。
     */
    public Set<String> hiddenCategoriesFor(String activeDocKind) {
        if (!docSessionCategoryTrim || activeDocKind == null) {
            return Set.of();
        }
        return HIDDEN_CATEGORIES_BY_DOC_KIND.getOrDefault(activeDocKind, Set.of());
    }

    /**
     * 按活跃文档类型再裁一刀（dev-board#1064）：去掉「属于本类型隐藏类目、且还没被放回」的工具。
     *
     * <p>纯函数、只收窄：核心集与 {@code list_tools} 永不裁（前者 {@link #categoryOf} 返回 core，
     * 后者本身就在核心集里）。开关关着或 kind 判不准时原样返回。
     */
    public List<ToolSpecification> trimForDocKind(List<ToolSpecification> candidates, String activeDocKind,
                                                  Set<String> expanded) {
        Set<String> hidden = hiddenCategoriesFor(activeDocKind);
        if (hidden.isEmpty() || candidates == null) {
            return candidates;
        }
        Set<String> open = expanded == null ? Set.of() : expanded;
        List<ToolSpecification> kept = new ArrayList<>();
        for (ToolSpecification spec : candidates) {
            String category = categoryOf(spec.name());
            if (!hidden.contains(category) || open.contains(category)) {
                kept.add(spec);
            }
        }
        return kept;
    }

    /**
     * 用户这一句话提示了哪些类目（关键词表 {@link #CATEGORY_KEYWORDS}）。
     * 「这是什么文件？」这种中性问句返回空集。只返回 {@link #categoryNames()} 里真有的类目。
     */
    public Set<String> categoriesHintedBy(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Set.of();
        }
        String normalized = userMessage.toLowerCase(Locale.ROOT);
        Set<String> known = categoryNames();
        Set<String> hinted = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : CATEGORY_KEYWORDS.entrySet()) {
            if (!known.contains(entry.getKey())) {
                continue;
            }
            for (String keyword : entry.getValue()) {
                if (SkillRouter.containsTrigger(normalized, keyword.toLowerCase(Locale.ROOT))) {
                    hinted.add(entry.getKey());
                    break;
                }
            }
        }
        return hinted;
    }

    /** 关键词表覆盖的类目（只读）。契约测试据它核对每个键都是目录认得的类目——认不得就永远不会被提示。 */
    public Set<String> keywordCategories() {
        return java.util.Collections.unmodifiableSet(CATEGORY_KEYWORDS.keySet());
    }

    /**
     * 一组工具名涉及哪些类目（不含 core）。用于 skill 放回：skill 白名单里点名的工具，
     * 它们所在的类目整类放回——skill 作者列了 run_python，就是说这一轮要用 Python。
     */
    public Set<String> categoriesCoveredBy(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return Set.of();
        }
        Set<String> covered = new LinkedHashSet<>();
        for (String name : toolNames) {
            String category = categoryOf(name);
            if (!"core".equals(category)) {
                covered.add(category);
            }
        }
        return covered;
    }

    /** 核心集（只读）。供契约测试核对「名字是不是真的存在」与常用链的覆盖面。 */
    public Set<String> coreToolNames() {
        return CORE;
    }

    /** 这个工具属于哪个类目。核心集里的工具返回 {@code "core"}。全函数，永不返回 null。 */
    public String categoryOf(String toolName) {
        if (toolName == null) {
            return FALLBACK_CATEGORY;
        }
        if (CORE.contains(toolName)) {
            return "core";
        }
        for (Map.Entry<String, List<String>> entry : CATEGORIES.entrySet()) {
            for (String rule : entry.getValue()) {
                boolean hit = rule.startsWith("name:")
                        ? toolName.equals(rule.substring(5))
                        : toolName.startsWith(rule);
                if (hit) {
                    return entry.getKey();
                }
            }
        }
        return FALLBACK_CATEGORY;
    }

    /** 目录里出现过的全部类目名（不含 core），供 {@code list_tools} 的描述与校验用。 */
    public Set<String> categoryNames() {
        Set<String> names = new LinkedHashSet<>(CATEGORIES.keySet());
        names.add(FALLBACK_CATEGORY);
        return names;
    }

    /**
     * 本轮真正下发的工具集 = 核心集 ∪ 已展开类目里的工具。
     *
     * <p>入参是上游三层闸（会话能力 / 活跃文档类型 / 运行期可用性）过完的候选集，
     * 本方法只在它上面再收窄一次，绝不放宽。
     */
    public List<ToolSpecification> narrow(List<ToolSpecification> candidates, Set<String> expanded) {
        Set<String> open = expanded == null ? Set.of() : expanded;
        List<ToolSpecification> kept = new ArrayList<>();
        for (ToolSpecification spec : candidates) {
            String category = categoryOf(spec.name());
            if ("core".equals(category) || open.contains(category)) {
                kept.add(spec);
            }
        }
        return kept;
    }

    /**
     * 把 {@code list_tools} 传来的类目参数解析成真正要展开的类目名。
     * 支持逗号分隔（一次展开几个类目，省掉来回几轮）；未知类目原样丢弃。
     */
    public Set<String> parseCategories(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> known = categoryNames();
        Set<String> parsed = new LinkedHashSet<>();
        for (String piece : raw.split("[,，\\s]+")) {
            String name = piece.trim().toLowerCase(Locale.ROOT);
            if (known.contains(name)) {
                parsed.add(name);
            }
        }
        return parsed;
    }
}
