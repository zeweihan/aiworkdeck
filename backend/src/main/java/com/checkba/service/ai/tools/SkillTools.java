// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillRouter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 调用技能（dev-board#1065，对标 Claude Code 的 Skill 工具）：skill 生效的<b>第三条路</b>。
 *
 * <p>前两条都在起跑时：用户在技能面板里选（{@code skillIds}）、用户这句话命中触发词。
 * 两条都要求用户<b>说对了词</b>——「把这次股东会的材料核一遍」一个触发词都没有，
 * 股东会核查那套逐字段三源对照的流程就整个用不上，模型只能凭通用能力硬做。
 * 这条路让模型自己判断「这件事该按某个 skill 的流程走」。
 *
 * <p><b>语义</b>：
 * <ol>
 *   <li>工具结果就是这个 skill 的指引正文（模板 + 输出约定，按应用语言取），于是指引<b>当轮</b>就进了上下文——
 *       与起跑时注入 system 的是同一份正文（{@link SkillRouter#skillInstructionsFor}）；</li>
 *   <li>生效登记由编排器在分发之后做（{@code AgentOrchestrator.noteToolCategoryExpansion}）：
 *       白名单涉及的类目放回展开集，{@code restrict} 的还登记进 {@link SkillRouter#activateMidRun}，
 *       <b>下一轮</b>起白名单里的工具可见。中途生效只许加不许减（见 {@code SkillRouter.midRunByRun}）。
 *       登记放编排器是因为它是轮次级状态，只有 RunGuard 拿得到——与 {@code list_tools} 的展开同一个做法。</li>
 * </ol>
 *
 * <p>「仅手动」的 skill 不能经这里调用（用户不想让它被自动带上），拒绝时告诉模型去请用户手动选。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillTools implements AgentToolComponent {

    /** 工具名。编排器按它认分发，别只改一边。 */
    public static final String TOOL_NAME = "use_skill";

    private final SkillRouter skillRouter;

    @ToolMeta(displayName = "调用技能", category = "agent")
    @Tool("Switch this turn onto a specialised workflow (a 'skill') and get its full instructions. "
            + "Use it when the user's task clearly matches one of the skills listed by list_tools() under "
            + "'skills', even if the user did not name it. The result IS the skill's instructions - read them "
            + "and follow them for the rest of this turn. Tools the skill needs join your regular tool list "
            + "from the NEXT turn on; to use one right now, call it as <tool_code>name(arg=\"value\")</tool_code>. "
            + "Do not call it again for a skill that is already active.")
    public String use_skill(
            @P("The skill id exactly as listed by list_tools(), e.g. \"litigation-visual\".") String skillId,
            String conversationId
    ) {
        if (skillRouter == null) {
            return "Error: skills are not available in this context.";
        }
        java.util.Optional<SkillDefinition> def = skillRouter.invocableSkill(skillId);
        if (def.isEmpty()) {
            return "Error: no usable skill with id '" + (skillId == null ? "" : skillId.trim()) + "'. "
                    + availableSkillsSentence()
                    + " A skill the user has set to manual-only cannot be started this way - "
                    + "ask the user to pick it in the skill panel instead.";
        }
        SkillDefinition skill = def.get();
        ToolContext ctx = ToolContextHolder.get();
        String runId = ctx == null ? null : ctx.runId();
        String name = skillRouter.displayName(skill);
        log.info("Tool: use_skill conv={} skill={}", conversationId, skill.getId());
        if (skillRouter.isActiveInRun(runId, skill.getId())) {
            return "Skill '" + skill.getId() + "' (" + name + ") is already active for this turn - "
                    + "its instructions are already in your context. Follow them; do not call use_skill again.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Skill: ").append(name).append(" (").append(skill.getId()).append(")\n")
                .append("Follow these instructions for the rest of this turn.\n\n")
                .append(skillRouter.skillInstructionsFor(skill));
        List<String> tools = skill.getAllowedTools() == null ? List.of() : skill.getAllowedTools();
        if (!tools.isEmpty()) {
            sb.append("\n\n---\nTools this skill uses: ").append(String.join(", ", tools))
                    .append(". Any of them not already in your tool list join it from the NEXT turn on; ")
                    .append("to use one right now, call it as <tool_code>name(arg=\"value\")</tool_code>.");
        }
        return sb.toString();
    }

    private String availableSkillsSentence() {
        List<SkillDefinition> skills = skillRouter.invocableSkills();
        if (skills.isEmpty()) {
            return "No skills are available in this session.";
        }
        return "Available skills: "
                + skills.stream().map(SkillDefinition::getId).collect(Collectors.joining(", ")) + ".";
    }
}
