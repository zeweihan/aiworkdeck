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
 *   b) 本轮 LLM 可见工具集裁剪为 allowed_tools ∪ 基础工具集（{@link #visibleTools}，
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

    private final SkillRegistry skillRegistry;
    private final SkillProperties properties;

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
        SkillDefinition best = null;
        int bestLen = 0;
        for (SkillDefinition skill : skillRegistry.getSkills()) {
            if (!skillRegistry.isAvailable(skill) || skillRegistry.isManual(skill.getId())) {
                continue;
            }
            for (String trigger : skill.getTriggers()) {
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
                return;
            }
            log.warn("Pinned skill '{}' not found or unavailable, fall back to trigger matching", pinnedSkillId);
        }
        Optional<SkillDefinition> matched = match(userInput);
        if (matched.isPresent()) {
            activeSkillByConversation.put(conversationId, matched.get().getId());
            log.info("Skill '{}' activated for conversation {} (trigger matched)",
                    matched.get().getId(), conversationId);
        } else {
            activeSkillByConversation.remove(conversationId);
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
     * allowed_tools ∪ 基础工具集（ai.skills.base-tools）；未命中时原样返回。
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
        List<ToolSpecification> filtered = all.stream()
                .filter(spec -> whitelist.contains(spec.name()))
                .toList();
        if (filtered.isEmpty()) {
            log.warn("Skill '{}' whitelist matched no registered tools ({}), fall back to full tool set",
                    skill.getId(), whitelist);
            return all;
        }
        log.info("Skill '{}' trimmed visible tools: {} -> {}", skill.getId(), all.size(), filtered.size());
        return filtered;
    }

    /** 组装注入块：skill 的 prompt 模板 + 输出约定（由 ContextAssemblerService 追加到系统消息） */
    public String promptInjectionFor(SkillDefinition skill) {
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
}
