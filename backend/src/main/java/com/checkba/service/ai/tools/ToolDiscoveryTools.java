// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.ToolDisclosurePolicy;
import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillRouter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具目录（dev-board#810 B 档）：模型手上没有的工具，从这里查、从这里展开。
 *
 * <p>渐进披露开着的时候，每轮只下发一份核心集，其余按类目收在目录里。
 * 本工具是<b>唯一</b>的入口，所以它自己在核心集里，总跟着核心集一起下发。
 *
 * <p><b>skill 裁剪过的回合里它不出现</b>，这是故意的：那种回合根本不做渐进披露
 *（skill 的 allowed_tools 本身就是一次披露），展开集对可见工具没有任何作用——
 * 摆一个点了也拿不到工具的目录入口，比不摆更糟。
 *
 * <p><b>展开的两条路，时效不同，描述里必须都说清楚</b>：
 * <ul>
 *   <li>XML 兜底（{@code <tool_code>}）按名字在注册表里解析，<b>当轮</b>就能调——
 *       没下发规格的工具仍然登记着（「只裁 spec、不裁 resolve/execute」）；</li>
 *   <li>原生 function calling 要等规格进到 tools 数组里，所以<b>下一轮</b>才行。</li>
 * </ul>
 * 不写清楚的话，模型查完目录直接原生调用，拿回一句 "Tool not found"，
 * 然后就会去告诉用户这个功能不存在——这正是 dev-board#396 那次 OCR 事故的形状。
 *
 * <p><b>三种查法</b>（dev-board#1065，对标 Claude Code 的 ToolSearch；同时给了几个时优先级
 * names &gt; query &gt; category）：
 * <ul>
 *   <li>{@code category}：按类目整类展开；</li>
 *   <li>{@code query}：按关键词在本会话候选集的工具名与描述里搜（空白分隔的每个词都要出现，AND，
 *       大小写不敏感），最多 {@value #MAX_QUERY_HITS} 条给全签名，并顺带列出命中的 skill；</li>
 *   <li>{@code names}：按确切工具名要全签名，查不到的名字单列一行说明（不以 Error 开头：
 *       查到的那些照样有效，整次调用判成失败会让编排器不记展开）。</li>
 * </ul>
 * 后两种展示了哪些工具，只有本工具算得出来，所以它把这些工具的类目写进
 * {@link ToolContext#disclosedCategories()}，编排器分发后读它记进展开集——不解析渲染出来的文字。
 * category 那种也写（与编排器按参数解析的结果一致，两条路取并集）。
 *
 * <p><b>skill 目录</b>：无参目录页末尾附一段「skills: id — 名称：一句话说明」，列本轮还没生效、
 * 模型可调用的 skill（{@link SkillRouter#invocableSkills()} 口径），配合 {@code use_skill}
 * 让模型在用户没说触发词时也找得到专门流程。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolDiscoveryTools implements AgentToolComponent {

    private final ToolDisclosurePolicy policy;

    /** query 模式最多给多少条全签名。再多就是整类展开，那该用 category。 */
    static final int MAX_QUERY_HITS = 15;
    /** query 模式顺带列出的 skill 上限。 */
    static final int MAX_QUERY_SKILL_HITS = 5;

    /**
     * skill 目录来源（dev-board#1065）。setter 注入、可空：本类的构造器被评测与各单测直接 new，
     * 往构造器里加参数要牵动它们；拿不到时目录页只是不列 skills 那一段。
     */
    private SkillRouter skillRouter;

    @Autowired(required = false)
    public void setSkillRouter(SkillRouter skillRouter) {
        this.skillRouter = skillRouter;
    }

    /**
     * 渐进披露关着的时候连它自己也不下发。
     *
     * <p>模型手上已经是全集了，再挂一个「查还有什么工具」的目录只会每轮白付约一千字符——
     * 而这条卡要治的正是每轮白付。复用组件级可用性闸（{@code isAvailable()}）而不是
     * {@code @ToolMeta.offerToModel}：后者是永久声明，这里要看的是一个启动时读定的配置。
     * 注意这只是「不下发规格」，登记仍在，XML 兜底路径调到它照样执行（只裁 spec、不裁 execute）。
     *
     * <p>活跃文档类目裁剪（dev-board#1064）开着时也要它：那种会话里 pdf_* / pptx_* 等整类没下发，
     * 目录是放回的入口之一。没开文档、什么都没藏的会话里，编排器会把它从本轮下发集里摘掉，
     * 不在这里判——这里是进程级的闸，看不到会话。
     */
    @Override
    public boolean isAvailable() {
        return policy == null || policy.isEnabled() || policy.isDocSessionCategoryTrimEnabled();
    }

    @ToolMeta(displayName = "查看工具目录", category = "agent")
    @Tool("Find tools that exist in this session but are NOT in the tool list you were given, and the "
            + "specialised workflows (skills) you can switch to with use_skill. "
            + "Call this BEFORE telling the user something cannot be done — the capability is very often here. "
            + "Categories: table, revision (incl. checkpoint restore), evidence, template, "
            + "edit (cursor/selection, delete, rewrite a paragraph, insert under a heading), "
            + "files (incl. export to PDF), format (fonts/paragraphs/page setup/TOC/footnotes/images), "
            + "spreadsheet, slides, office, pdf, litigation (diagrams), reference, memory, enterprise-data, legal, "
            + "meeting, task, tag (file tags), plugin, python, plugin-tools (tools from installed plugins), misc. "
            + "With no argument you get every category with the tool names in it, plus the skills (cheap, do this first). "
            + "With query you search tool names and descriptions by keywords (every word must match, up to 15 hits); "
            + "with names you get exactly those tools; with a category (comma-separate several) you get the whole category. "
            + "If several are given, names wins over query, and query over category. "
            + "Every tool whose full signature is printed is added to your regular tool list from the NEXT turn on. "
            + "You may call any tool it printed IMMEDIATELY in this same turn by writing it as "
            + "<tool_code>name(arg=\"value\")</tool_code>; plain function calling only works from the next turn.")
    public String list_tools(
            @P(value = "Category to expand, e.g. \"format\" or \"table,revision\". Omit to see all categories.",
                    required = false) String category,
            @P(value = "Keywords to search tool names and descriptions, e.g. \"pdf merge\" or \"页码\". "
                    + "Every word must match. Returns up to 15 tools with full signatures.",
                    required = false) String query,
            @P(value = "Exact tool names, comma-separated, e.g. \"pdf_merge,pdf_split\". "
                    + "Returns their full signatures.",
                    required = false) String names,
            String conversationId
    ) {
        ToolContext ctx = ToolContextHolder.get();
        List<ToolSpecification> candidates = ctx == null ? List.of() : ctx.sessionTools();
        Set<String> offered = ctx == null ? null : ctx.offeredTools();
        if (candidates.isEmpty()) {
            return "Error: the tool catalog is not available in this context.";
        }
        if (names != null && !names.isBlank()) {
            log.info("Tool: list_tools conv={} names={} candidates={}", conversationId, names, candidates.size());
            return renderNames(candidates, names, ctx);
        }
        if (query != null && !query.isBlank()) {
            log.info("Tool: list_tools conv={} query=({} chars) candidates={}",
                    conversationId, query.length(), candidates.size());
            return renderQuery(candidates, query, ctx);
        }
        Set<String> requested = policy.parseCategories(category);
        if (category != null && !category.isBlank() && requested.isEmpty()) {
            return "Error: unknown category '" + category + "'. Known categories: "
                    + String.join(", ", policy.categoryNames())
                    + ". Call list_tools with no argument to see what is in each.";
        }
        log.info("Tool: list_tools conv={} category={} candidates={}",
                conversationId, requested.isEmpty() ? "(all)" : requested, candidates.size());
        if (requested.isEmpty()) {
            return renderIndex(candidates, offered, ctx == null ? null : ctx.runId());
        }
        record(ctx, requested);
        return renderCategories(candidates, requested);
    }

    /**
     * 目录页：每个类目一行，只给工具名。便宜（全量也就两千字符），所以鼓励模型先查这个。
     *
     * <p>列的是「本会话有、但本轮没下发」的工具 = 候选集 − 本轮下发集（dev-board#1064）。
     * 编排器没告诉我们下发了哪些（{@code offered == null}，旧调用方）时退回「非核心即未下发」——
     * 那正是只开渐进披露时的真实情况。末尾附 skill 目录（dev-board#1065）。
     */
    private String renderIndex(List<ToolSpecification> candidates, Set<String> offered, String runId) {
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (ToolSpecification spec : candidates) {
            String category = policy.categoryOf(spec.name());
            boolean alreadyOffered = offered != null
                    ? offered.contains(spec.name())
                    : "core".equals(category);
            if (alreadyOffered || "core".equals(category)) {
                continue;
            }
            byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(spec.name());
        }
        StringBuilder sb = new StringBuilder();
        if (byCategory.isEmpty()) {
            sb.append("Every tool available in this session is already in your tool list — "
                    + "there is nothing else to expand.\n");
        } else {
            sb.append("Tools that exist in this session but are not in your current tool list.\n"
                    + "Call list_tools(category=\"<name>\"), list_tools(query=\"<keywords>\") or "
                    + "list_tools(names=\"a,b\") to get their full signatures.\n\n");
            byCategory.forEach((category, names) ->
                    sb.append(category).append(" (").append(names.size()).append("): ")
                            .append(String.join(", ", names)).append('\n'));
        }
        List<SkillDefinition> skills = invocableSkills(runId);
        if (!skills.isEmpty()) {
            sb.append("\nskills (specialised workflows; switch with use_skill(skillId=\"<id>\")):\n");
            skills.forEach(skill -> sb.append(skillLine(skill)).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    /** 展开页：给全签名，模型据此当轮就能用 XML 形式调用。 */
    private String renderCategories(List<ToolSpecification> candidates, Set<String> requested) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String category : requested) {
            List<ToolSpecification> specs = candidates.stream()
                    .filter(s -> category.equals(policy.categoryOf(s.name())))
                    .toList();
            sb.append("== ").append(category).append(" (").append(specs.size()).append(" tools) ==\n");
            if (specs.isEmpty()) {
                sb.append("(nothing in this category is usable in this session — "
                        + "it needs a different document type or client)\n");
            }
            for (ToolSpecification spec : specs) {
                shown++;
                appendTool(sb, spec);
            }
            sb.append('\n');
        }
        appendTrailer(sb, shown);
        return sb.toString();
    }

    /** 按确切工具名给全签名；查不到的单列一行（不是错误：查到的那些照样有效）。 */
    private String renderNames(List<ToolSpecification> candidates, String names, ToolContext ctx) {
        Map<String, ToolSpecification> byName = new LinkedHashMap<>();
        candidates.forEach(spec -> byName.put(spec.name(), spec));
        List<ToolSpecification> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String piece : names.split("[,，\\s]+")) {
            String name = piece.trim();
            if (name.isEmpty() || !seen.add(name)) {
                continue;
            }
            ToolSpecification spec = byName.get(name);
            if (spec == null) {
                missing.add(name);
            } else {
                found.add(spec);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!found.isEmpty()) {
            appendGrouped(sb, found);
        }
        if (!missing.isEmpty()) {
            sb.append("not found in this session: ").append(String.join(", ", missing))
                    .append("; use list_tools() to see categories, or list_tools(query=\"...\") to search.\n");
        }
        if (found.isEmpty()) {
            return sb.toString().trim();
        }
        record(ctx, categoriesOf(found));
        appendTrailer(sb, found.size());
        return sb.toString();
    }

    /** 关键词搜索：每个词都要出现在「工具名 + 描述」里（大小写不敏感），最多 {@value #MAX_QUERY_HITS} 条。 */
    private String renderQuery(List<ToolSpecification> candidates, String query, ToolContext ctx) {
        List<String> terms = terms(query);
        List<ToolSpecification> hits = new ArrayList<>();
        int total = 0;
        for (ToolSpecification spec : candidates) {
            String haystack = spec.name() + " " + (spec.description() == null ? "" : spec.description());
            if (matchesAll(haystack, terms)) {
                total++;
                if (hits.size() < MAX_QUERY_HITS) {
                    hits.add(spec);
                }
            }
        }
        List<String> skillHits = matchingSkills(terms, ctx == null ? null : ctx.runId());
        StringBuilder sb = new StringBuilder();
        if (hits.isEmpty()) {
            sb.append("No tool in this session matches \"").append(query.trim()).append("\". ")
                    .append("Try fewer or different words, or call list_tools() to browse the categories.\n");
        } else {
            appendGrouped(sb, hits);
            if (total > hits.size()) {
                sb.append("(").append(total - hits.size())
                        .append(" more tools matched; add words to narrow the search.)\n\n");
            }
        }
        if (!skillHits.isEmpty()) {
            sb.append("skills matching this (switch with use_skill(skillId=\"<id>\")):\n");
            skillHits.forEach(line -> sb.append(line).append('\n'));
            sb.append('\n');
        }
        if (hits.isEmpty()) {
            return sb.toString().trim();
        }
        record(ctx, categoriesOf(hits));
        appendTrailer(sb, hits.size());
        return sb.toString();
    }

    private static List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        for (String piece : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!piece.isEmpty()) {
                terms.add(piece);
            }
        }
        return terms;
    }

    private static boolean matchesAll(String haystack, List<String> terms) {
        if (terms.isEmpty()) {
            return false;
        }
        String text = haystack.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!text.contains(term)) {
                return false;
            }
        }
        return true;
    }

    /** id、名称或描述命中全部关键词、且本轮还没生效的 skill，每条一行。 */
    private List<String> matchingSkills(List<String> terms, String runId) {
        List<String> lines = new ArrayList<>();
        for (SkillDefinition skill : invocableSkills(runId)) {
            String haystack = skill.getId() + " " + nullToEmpty(skill.getName()) + " "
                    + nullToEmpty(skill.getNameEn()) + " " + nullToEmpty(skill.getDescription());
            if (matchesAll(haystack, terms)) {
                lines.add(skillLine(skill));
                if (lines.size() >= MAX_QUERY_SKILL_HITS) {
                    break;
                }
            }
        }
        return lines;
    }

    /** 模型可调用、本轮还没生效的 skill。没接 SkillRouter 时为空。 */
    private List<SkillDefinition> invocableSkills(String runId) {
        if (skillRouter == null) {
            return List.of();
        }
        try {
            return skillRouter.invocableSkills().stream()
                    .filter(skill -> !skillRouter.isActiveInRun(runId, skill.getId()))
                    .toList();
        } catch (RuntimeException e) {
            // 目录页是尽力而为的：skill 登记簿出问题不该让整次目录查询失败
            log.warn("list_tools: skill catalog unavailable: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private String skillLine(SkillDefinition skill) {
        String description = nullToEmpty(skill.getDescription()).strip();
        int newline = description.indexOf('\n');
        if (newline >= 0) {
            description = description.substring(0, newline).strip();
        }
        if (description.length() > 120) {
            description = description.substring(0, 120) + "…";
        }
        return "- " + skill.getId() + " — " + skillRouter.displayName(skill)
                + (description.isEmpty() ? "" : ": " + description);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 把这次展示了全签名的类目写给编排器（核心集不算——它们本来就在手上）。 */
    private static void record(ToolContext ctx, Set<String> categories) {
        if (ctx == null || ctx.disclosedCategories() == null) {
            return;
        }
        for (String category : categories) {
            if (!"core".equals(category)) {
                ctx.disclosedCategories().add(category);
            }
        }
    }

    private Set<String> categoriesOf(List<ToolSpecification> specs) {
        Set<String> categories = new LinkedHashSet<>();
        specs.forEach(spec -> categories.add(policy.categoryOf(spec.name())));
        return categories;
    }

    /** 按类目分组给全签名（query / names 共用；类目按首次出现的顺序）。 */
    private void appendGrouped(StringBuilder sb, List<ToolSpecification> specs) {
        Map<String, List<ToolSpecification>> byCategory = new LinkedHashMap<>();
        for (ToolSpecification spec : specs) {
            byCategory.computeIfAbsent(policy.categoryOf(spec.name()), k -> new ArrayList<>()).add(spec);
        }
        byCategory.forEach((category, group) -> {
            sb.append("== ").append(category).append(" ==\n");
            group.forEach(spec -> appendTool(sb, spec));
            sb.append('\n');
        });
    }

    private void appendTool(StringBuilder sb, ToolSpecification spec) {
        sb.append("- ").append(spec.name()).append('(').append(parameterList(spec)).append(")\n  ")
                .append(spec.description() == null ? "" : spec.description()).append('\n');
    }

    private static void appendTrailer(StringBuilder sb, int shown) {
        sb.append("These ").append(shown)
                .append(" tools are now part of your regular tool list from the NEXT turn on. ")
                .append("To use one right now, call it as <tool_code>name(arg=\"value\")</tool_code>.");
    }

    private String parameterList(ToolSpecification spec) {
        if (spec.parameters() == null || spec.parameters().properties() == null) {
            return "";
        }
        List<String> required = spec.parameters().required() == null
                ? List.of() : spec.parameters().required();
        List<String> parts = new ArrayList<>();
        spec.parameters().properties().forEach((name, schema) ->
                parts.add(required.contains(name) ? name : name + "?"));
        return String.join(", ", parts);
    }
}
