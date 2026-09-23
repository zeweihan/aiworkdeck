// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.ToolDisclosurePolicy;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolDiscoveryTools implements AgentToolComponent {

    private final ToolDisclosurePolicy policy;

    /**
     * 渐进披露关着的时候连它自己也不下发。
     *
     * <p>模型手上已经是全集了，再挂一个「查还有什么工具」的目录只会每轮白付约一千字符——
     * 而这条卡要治的正是每轮白付。复用组件级可用性闸（{@code isAvailable()}）而不是
     * {@code @ToolMeta.offerToModel}：后者是永久声明，这里要看的是一个启动时读定的配置。
     * 注意这只是「不下发规格」，登记仍在，XML 兜底路径调到它照样执行（只裁 spec、不裁 execute）。
     */
    @Override
    public boolean isAvailable() {
        return policy == null || policy.isEnabled();
    }

    @ToolMeta(displayName = "查看工具目录", category = "agent")
    @Tool("List the tools that exist but are NOT in the small default set you were given. "
            + "Call this BEFORE telling the user something cannot be done — the capability is very often here. "
            + "Categories: table, revision, evidence, template, format (fonts/paragraphs/page setup/TOC/footnotes/images), "
            + "spreadsheet, slides, office, pdf, litigation (diagrams), reference, memory, enterprise-data, legal, "
            + "meeting, task, files, plugin, python, misc. "
            + "With no argument you get every category with the tool names in it (cheap, do this first). "
            + "With a category (comma-separate several) you get the full signature of each tool in it, "
            + "AND those tools are added to your regular tool list from the NEXT turn on. "
            + "You may call any tool it printed IMMEDIATELY in this same turn by writing it as "
            + "<tool_code>name(arg=\"value\")</tool_code>; plain function calling only works from the next turn.")
    public String list_tools(
            @P(value = "Category to expand, e.g. \"format\" or \"table,revision\". Omit to see all categories.",
                    required = false) String category,
            String conversationId
    ) {
        List<ToolSpecification> candidates = ToolContextHolder.get() == null
                ? List.of() : ToolContextHolder.get().sessionTools();
        if (candidates.isEmpty()) {
            return "Error: the tool catalog is not available in this context.";
        }
        java.util.Set<String> requested = policy.parseCategories(category);
        if (category != null && !category.isBlank() && requested.isEmpty()) {
            return "Error: unknown category '" + category + "'. Known categories: "
                    + String.join(", ", policy.categoryNames())
                    + ". Call list_tools with no argument to see what is in each.";
        }
        log.info("Tool: list_tools conv={} category={} candidates={}",
                conversationId, requested.isEmpty() ? "(all)" : requested, candidates.size());
        return requested.isEmpty() ? renderIndex(candidates) : renderCategories(candidates, requested);
    }

    /** 目录页：每个类目一行，只给工具名。便宜（全量也就两千字符），所以鼓励模型先查这个。 */
    private String renderIndex(List<ToolSpecification> candidates) {
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (ToolSpecification spec : candidates) {
            String category = policy.categoryOf(spec.name());
            if ("core".equals(category)) {
                continue;
            }
            byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(spec.name());
        }
        if (byCategory.isEmpty()) {
            return "Every tool available in this session is already in your tool list — there is nothing else to expand.";
        }
        StringBuilder sb = new StringBuilder(
                "Tools that exist in this session but are not in your current tool list.\n"
                        + "Call list_tools(category=\"<name>\") to get their full signatures.\n\n");
        byCategory.forEach((category, names) ->
                sb.append(category).append(" (").append(names.size()).append("): ")
                        .append(String.join(", ", names)).append('\n'));
        return sb.toString();
    }

    /** 展开页：给全签名，模型据此当轮就能用 XML 形式调用。 */
    private String renderCategories(List<ToolSpecification> candidates, java.util.Set<String> requested) {
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
                sb.append("- ").append(spec.name()).append('(').append(parameterList(spec)).append(")\n  ")
                        .append(spec.description() == null ? "" : spec.description()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("These ").append(shown)
                .append(" tools are now part of your regular tool list from the NEXT turn on. ")
                .append("To use one right now, call it as <tool_code>name(arg=\"value\")</tool_code>.");
        return sb.toString();
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
