// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
            // —— 目录入口与编排：少了 list_tools 整套机制就没有入口 ——
            CATALOG_TOOL, "todo_write", "dispatch_subtask",

            // —— 项目材料：找文件 → 拿 fileId → 读全文 → 落一份新文件 ——
            "doc_list_project_files", "search_project_files", "extract_file_text", "read_document",
            "write_docx", "create_folder", "move_files_batch",

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

    private final boolean enabled;

    public ToolDisclosurePolicy(
            @Value("${ai.tools.progressive-disclosure.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
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
