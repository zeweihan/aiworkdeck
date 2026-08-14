package com.checkba.service.ai.skill;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 路由器（Phase 3B，规范见 docs/SKILL_SPEC.md）。
 *
 * 按触发条件（关键词，忽略大小写）匹配用户输入：
 * - 命中多个 skill 时取"最长命中关键词"的那个（更长的关键词 = 更 specific 的意图）；
 * - 命中后：a) prompt 模板由 ContextAssemblerService 在组装系统消息时注入（{@link #promptInjectionFor}）；
 *   b) 本轮 LLM 可见工具集裁剪为 allowed_tools ∪ 基础工具集 ∪ 编排类工具（{@link #visibleTools}，
 *   复用 Phase 3A 的可见性出口：对 ToolRegistry.getAllSpecifications() 的结果做白名单过滤）。
 * - 未命中任何 skill 时行为与现状完全一致（不注入、不裁剪）。
 *
 * 注意：裁剪只影响"可见性"（LLM 看不到即不会调用），不拦截分发——与插件启停的
 * 可见性语义保持一致，也保证老对话历史里的工具调用仍可回放。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillRouter {

    /**
     * 恒定可见的编排类工具：不管命中哪个 skill、它的 allowed_tools 怎么写，这些工具永远在可见集合里。
     *
     * 与 base-tools（ai.skills.base-tools）的区别——**不要把两者合并**：
     * - base-tools 是"业务能力兜底"：被裁剪的回合也得留最基本的读取/记忆能力，是一份
     *   随部署形态可调的清单，所以放 yml（云端与桌面可以给不同的值）。
     * - 本清单是"编排能力"：todo_write（多步任务的进度可见）与 dispatch_subtask（长程子任务委派）
     *   属于 Agent 怎么干活，不属于任何一个法律业务领域，任何 skill 都没有理由把它们藏起来。
     *
     * 刻意写死在代码里而不做成配置项：这是不变式，不是部署旋钮。一旦允许 yml 覆盖，
     * application-prod.yml / application-desktop.yml 里的一次覆写就能重新把编排能力裁掉，
     * 而这正是本次要消灭的静默故障——skill 裁掉 todo_write / dispatch_subtask 时既不报错也不告警，
     * 表现只是"模型不写任务清单了 / 不派子任务了"（#323 给两个自带 skill 补了 allowed_tools，
     * 但结构性问题还在：下一个新 skill 会再踩一次）。
     *
     * 反问（{@code <question>} 标签）不在此列：它走标签而不是工具，工具可见性裁剪根本碰不到它。
     */
    static final Set<String> ORCHESTRATION_TOOLS = Set.of("todo_write", "dispatch_subtask");

    private final SkillRegistry skillRegistry;
    private final SkillProperties properties;
    // 埋点：只记 skillId 枚举值，用户输入原文绝不进入（构造器变更需同步 EvalHarness/SkillRouterTest）
    private final com.checkba.service.telemetry.TelemetryService telemetryService;
    // 应用语言（EN 版 PR5）：英文模式下触发词并入 triggers_en、注入块选英文模板/前缀。
    // 可空（部分单测/评测不接语言服务）——为 null 时按 zh-CN 语义，行为与引入前一致。
    @org.springframework.lang.Nullable
    private final com.checkba.service.AppLanguageService appLanguageService;

    /**
     * 本轮命中的 skill：conversationId -> skillId。
     * 每次用户消息（handleUserMessage）刷新一次；未命中即移除。
     * 条目只是两个短字符串，会话量级下的常驻内存可忽略。
     */
    private final Map<String, String> activeSkillByConversation = new ConcurrentHashMap<>();

    /**
     * 触发匹配（无状态）：在所有可用 skill 中找命中关键词的；
     * 多命中时取最长命中关键词的 skill（并列时取先注册的）。
     *
     * 生效方式为"仅手动"的 skill 不参与自动匹配——它只能由用户钉选生效。
     */
    public Optional<SkillDefinition> match(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }
        String normalized = userInput.toLowerCase();
        boolean english = isEnglish();
        SkillDefinition best = null;
        int bestLen = 0;
        for (SkillDefinition skill : skillRegistry.getSkills()) {
            if (!skillRegistry.isAvailable(skill) || skillRegistry.isManual(skill.getId())) {
                continue;
            }
            for (String trigger : matchTriggers(skill, english)) {
                if (trigger == null || trigger.isBlank()) {
                    continue;
                }
                if (normalized.contains(trigger.toLowerCase()) && trigger.length() > bestLen) {
                    best = skill;
                    bestLen = trigger.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 为本轮对话做一次触发匹配并记录结果（编排器在每条用户消息入口调用一次）。
     * 未命中时清除该会话的旧记录，保证行为回到"与现状完全一致"。
     */
    public void activateForTurn(String conversationId, String userInput) {
        activateForTurn(conversationId, userInput, null);
    }

    /**
     * 同上，但用户可钉选一个 skill 强制本轮生效。
     *
     * 钉选优先于触发词匹配：用户明确指定的意图不该被关键词猜测覆盖。
     * 钉选 id 不存在或不可用（已停用 / 所属插件已停用）时退回自动匹配，
     * 避免前端状态过期把本轮变成"无 skill 也无提示"。
     */
    public void activateForTurn(String conversationId, String userInput, String pinnedSkillId) {
        if (pinnedSkillId != null && !pinnedSkillId.isBlank()) {
            Optional<SkillDefinition> pinned = skillRegistry.getSkill(pinnedSkillId)
                    .filter(skillRegistry::isAvailable);
            if (pinned.isPresent()) {
                activeSkillByConversation.put(conversationId, pinned.get().getId());
                log.info("Skill '{}' activated for conversation {} (pinned by user)",
                        pinned.get().getId(), conversationId);
                recordActivation(conversationId, pinned.get(), "pinned");
                return;
            }
            log.warn("Pinned skill '{}' not found or unavailable, fall back to trigger matching", pinnedSkillId);
        }
        Optional<SkillDefinition> matched = match(userInput);
        if (matched.isPresent()) {
            activeSkillByConversation.put(conversationId, matched.get().getId());
            log.info("Skill '{}' activated for conversation {} (trigger matched)",
                    matched.get().getId(), conversationId);
            recordActivation(conversationId, matched.get(), "matched");
        } else {
            activeSkillByConversation.remove(conversationId);
        }
    }

    /** 埋点：skill 激活即事项类型信号（skill 带 category 时同步产出 matter.classified） */
    private void recordActivation(String conversationId, SkillDefinition skill, String how) {
        telemetryService.recordConv("skill.activated", conversationId,
                Map.of("skillId", skill.getId(), "how", how));
        if (skill.getCategory() != null && !skill.getCategory().isBlank()) {
            telemetryService.recordConv("matter.classified", conversationId,
                    Map.of("category", skill.getCategory(), "source", "skill"));
        }
    }

    /** 本轮命中的 skill（未命中或已被禁用返回 empty） */
    public Optional<SkillDefinition> activeSkill(String conversationId) {
        String skillId = activeSkillByConversation.get(conversationId);
        if (skillId == null) {
            return Optional.empty();
        }
        return skillRegistry.getSkill(skillId).filter(skillRegistry::isAvailable);
    }

    /**
     * 工具可见性裁剪：命中 skill 时把传给 LLM 的工具规格过滤为
     * allowed_tools ∪ 基础工具集（ai.skills.base-tools）∪ 编排类工具（{@link #ORCHESTRATION_TOOLS}）；
     * 未命中时原样返回。
     *
     * 白名单过滤结果为空（allowed_tools 全部拼错等误配置）时回退为不裁剪并告警，
     * 避免把 Agent 裁成"无工具可用"。
     */
    public List<ToolSpecification> visibleTools(String conversationId, List<ToolSpecification> all) {
        Optional<SkillDefinition> active = activeSkill(conversationId);
        if (active.isEmpty()) {
            return all;
        }
        SkillDefinition skill = active.get();
        Set<String> whitelist = new HashSet<>(skill.getAllowedTools());
        whitelist.addAll(properties.getBaseTools());
        whitelist.addAll(ORCHESTRATION_TOOLS);
        List<ToolSpecification> filtered = all.stream()
                .filter(spec -> whitelist.contains(spec.name()))
                .toList();
        // 误配置保护：allowed_tools ∪ base-tools 在注册工具里零交集时回退为不裁剪。
        // 判据刻意排除编排类工具——它们恒在白名单里，不排除的话"零交集"永远至少剩下那两个，
        // 原来的回退保护就被本次改动悄悄废掉，skill 会被裁成只剩写清单/派子任务。
        // （空集合下 allMatch 恒为真，所以这一个判断同时覆盖 filtered 为空的情况。）
        if (filtered.stream().allMatch(spec -> ORCHESTRATION_TOOLS.contains(spec.name()))) {
            log.warn("Skill '{}' whitelist matched no business tools ({}), fall back to full tool set",
                    skill.getId(), whitelist);
            return all;
        }
        log.info("Skill '{}' trimmed visible tools: {} -> {}", skill.getId(), all.size(), filtered.size());
        return filtered;
    }

    /** 组装注入块：skill 的 prompt 模板 + 输出约定（由 ContextAssemblerService 追加到系统消息） */
    public String promptInjectionFor(SkillDefinition skill) {
        if (isEnglish()) {
            return promptInjectionForEn(skill);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n# Active Skill: ").append(skill.getName() != null ? skill.getName() : skill.getId());
        sb.append("\n[SYSTEM INJECTION] 用户本轮请求命中了技能「")
                .append(skill.getName() != null ? skill.getName() : skill.getId())
                .append("」，请按以下技能指引处理本轮请求：\n\n");
        sb.append(skill.getPromptTemplate());
        if (skill.getOutput() != null && !skill.getOutput().isBlank()) {
            sb.append("\n\n## 输出约定\n").append(skill.getOutput());
        }
        return sb.toString();
    }

    // ==================== 应用语言（EN 版 PR5） ====================

    private boolean isEnglish() {
        return appLanguageService != null && appLanguageService.isEnglish();
    }

    /**
     * 参与匹配的触发词：zh-CN 只用 triggers（行为保持）；en-US 为 triggers ∪ triggers_en——
     * 英文界面下中文输入仍可命中（注入的会是英文指引），而 triggers_en 绝不参与中文匹配，
     * 保证中文版匹配行为逐字节不变。
     */
    private static List<String> matchTriggers(SkillDefinition skill, boolean english) {
        if (!english || skill.getTriggersEn() == null || skill.getTriggersEn().isEmpty()) {
            return skill.getTriggers();
        }
        List<String> merged = new java.util.ArrayList<>(skill.getTriggers());
        merged.addAll(skill.getTriggersEn());
        return merged;
    }

    /** 注入块英文版：前缀/标题走英文，模板与输出约定优先取英文字段，缺省回退中文（可用胜于空白）。 */
    private String promptInjectionForEn(SkillDefinition skill) {
        String displayName = skill.getNameEn() != null && !skill.getNameEn().isBlank()
                ? skill.getNameEn()
                : (skill.getName() != null ? skill.getName() : skill.getId());
        String template = skill.getPromptTemplateEn() != null && !skill.getPromptTemplateEn().isBlank()
                ? skill.getPromptTemplateEn()
                : skill.getPromptTemplate();
        String output = skill.getOutputEn() != null && !skill.getOutputEn().isBlank()
                ? skill.getOutputEn()
                : skill.getOutput();
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n# Active Skill: ").append(displayName);
        sb.append("\n[SYSTEM INJECTION] The user's request this turn matched the skill \"")
                .append(displayName)
                .append("\". Handle this turn according to the skill guidance below:\n\n");
        sb.append(template);
        if (output != null && !output.isBlank()) {
            sb.append("\n\n## Output Conventions\n").append(output);
        }
        return sb.toString();
    }
}
