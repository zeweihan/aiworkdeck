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
 * 本轮生效集合 = 用户手动选择（{@code POST /api/agent/chat} 的 skillIds，含旧字段 pinnedSkillId）
 * ∪ 触发词自动命中（至多一个）。自动匹配仍是"多命中取最长关键词"的单选；能同时生效多个
 * 只是因为手动选择可以有多枚。prompt 注入与工具白名单都按整个集合做并集，
 * 见 {@link #activateForTurn(String, String, String, java.util.Collection)}。
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

    /** 来源：触发词自动命中 */
    public static final String SOURCE_AUTO = "auto";
    /** 来源：用户在对话面板里主动选择（含旧字段 pinnedSkillId） */
    public static final String SOURCE_MANUAL = "manual";

    /**
     * 本轮生效的一个 skill。
     *
     * @param definition  skill 定义（工具裁剪与 prompt 注入用）
     * @param displayName 按当前应用语言解析好的展示名（zh 用 name、en 优先 name_en）——
     *                    解析放在本类是因为只有这里持有 AppLanguageService，
     *                    调用方（编排器）不必为了发一个事件多注入一个服务
     * @param source      {@link #SOURCE_AUTO} / {@link #SOURCE_MANUAL}
     */
    public record ActiveSkill(SkillDefinition definition, String displayName, String source) {
    }

    /**
     * 本轮生效的 skill：conversationId -> [(skillId, source)]，手动选择在前、自动命中在后。
     * 每次用户消息（handleUserMessage）刷新一次；一个都不生效即移除。
     *
     * <p><b>顺序是契约</b>：{@link #activeSkill} 取第一个，于是"用户明确选的"永远压过
     * "关键词猜的"——这条语义从单选时代（pinnedSkillId 优先于触发词匹配）延续下来。
     *
     * <p>只存 id 不存定义：registry 可能在两轮之间 rescan，存定义会拿到已经不存在的旧对象。
     *
     * <p><b>无界增长</b>：只有"这一轮没有任何 skill 生效"才会 {@code remove}——一个会话只要
     * 最后一轮命中过 skill，条目就永久留着，进程不重启就一直涨（审计条目：activeByConversation
     * never evicts...）。value 额外带上激活时刻，配 {@link #purgeStaleActivations()} 做惰性过期；
     * 24 小时对齐本仓库同类"内存登记簿"的既有先例（{@code ConversationIssuanceService} 24h 过期）——
     * 会话超过这么久没有新一轮 activateForTurn，下次用户回来发消息时会重新触发一次，不丢功能。
     */
    private final Map<String, ActivationRecord> activeByConversation = new ConcurrentHashMap<>();

    /** 过期窗口：见 {@link #activeByConversation} 字段注释。 */
    private static final long STALE_ACTIVATION_MILLIS = 24L * 60 * 60 * 1000;

    /** 时间源，测试可注入固定值以避免真实等待 24 小时。 */
    private java.util.function.LongSupplier clockMillis = System::currentTimeMillis;

    void setClockMillis(java.util.function.LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** 供测试断言登记簿大小（不下沉成生产代码路径）。 */
    int activeByConversationSize() {
        return activeByConversation.size();
    }

    private record ActiveEntry(String skillId, String source) {
    }

    /** {@link #activeByConversation} 的 value：本轮生效集合 + 激活时刻，供惰性过期判断。 */
    private record ActivationRecord(List<ActiveEntry> entries, long activatedAtMillis) {
    }

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
     * 同上，但用户可钉选一个 skill 强制本轮生效（旧的单选字段 pinnedSkillId）。
     * 语义等价于把它当作只有一项的手动选择列表，见
     * {@link #activateForTurn(String, String, String, java.util.Collection)}。
     */
    public void activateForTurn(String conversationId, String userInput, String pinnedSkillId) {
        activateForTurn(conversationId, userInput, pinnedSkillId, null);
    }

    /**
     * 本轮生效集合 = <b>用户手动选择 ∪ 触发词自动命中</b>（每条用户消息刷新一次）。
     *
     * <p>手动选择由前端每次请求携带（{@code POST /api/agent/chat} 的 {@code skillIds}），
     * 后端不持久化——用户在面板上勾掉一个 skill，下一条消息就该真的不带它。
     * 旧的单选字段 {@code pinnedSkillId} 收编成"手动列表里的一项"，语义完全一致。
     *
     * <p><b>并集而不是覆盖</b>：手动选择表达的是"这轮务必带上它"，不是"只准用它"。
     * 用户勾了「诉讼可视化」又在句子里写了别的技能的触发词时，两个都该生效——
     * 强行二选一只会让另一半能力静默消失。集合内的顺序把手动放在前面，
     * 于是 {@link #activeSkill}（单值出口，事项分类等旧调用方在用）仍返回用户明确选的那个。
     *
     * <p>无效 id（不存在 / 已停用 / 所属插件已停用 / 当前应用语言下不可用）静默忽略：
     * 前端状态过期不该让整轮报错，只是那个 skill 这轮不生效——而 SSE {@code skill_update}
     * 下发的是真正生效的清单，用户看得见它没被点亮。
     */
    public void activateForTurn(String conversationId, String userInput, String pinnedSkillId,
                                java.util.Collection<String> manualSkillIds) {
        java.util.LinkedHashMap<String, String> active = new java.util.LinkedHashMap<>();

        List<String> manual = new java.util.ArrayList<>();
        if (pinnedSkillId != null && !pinnedSkillId.isBlank()) {
            manual.add(pinnedSkillId);
        }
        if (manualSkillIds != null) {
            manual.addAll(manualSkillIds);
        }
        for (String id : manual) {
            if (id == null || id.isBlank()) {
                continue;
            }
            Optional<SkillDefinition> picked = skillRegistry.getSkill(id).filter(skillRegistry::isAvailable);
            if (picked.isEmpty()) {
                log.warn("Manually selected skill '{}' not found or unavailable, ignored", id);
                continue;
            }
            active.putIfAbsent(picked.get().getId(), SOURCE_MANUAL);
        }

        // 手动选过的 skill 即便同时命中触发词也仍标 manual：用户看到的应该是"我选的"，
        // 而不是"碰巧也被关键词猜中了"。
        match(userInput).ifPresent(matched -> active.putIfAbsent(matched.getId(), SOURCE_AUTO));

        if (active.isEmpty()) {
            activeByConversation.remove(conversationId);
            return;
        }
        List<ActiveEntry> entries = active.entrySet().stream()
                .map(e -> new ActiveEntry(e.getKey(), e.getValue()))
                .toList();
        activeByConversation.put(conversationId, new ActivationRecord(entries, clockMillis.getAsLong()));
        log.info("Skills activated for conversation {}: {}", conversationId, active);
        recordActivation(conversationId, activeSkills(conversationId));
    }

    /**
     * 清理超过 {@link #STALE_ACTIVATION_MILLIS} 未再激活的会话条目——见
     * {@link #activeByConversation} 字段注释的无界增长问题。与 {@code TodoListService.purgeStaleLists}
     * 同款节奏（每日一次 + 15 分钟初始延迟错峰）。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 24L * 60 * 60 * 1000,
            initialDelay = 15L * 60 * 1000)
    public void purgeStaleActivations() {
        try {
            long cutoff = clockMillis.getAsLong() - STALE_ACTIVATION_MILLIS;
            int before = activeByConversation.size();
            activeByConversation.entrySet().removeIf(e -> e.getValue().activatedAtMillis() < cutoff);
            int removed = before - activeByConversation.size();
            if (removed > 0) {
                log.info("清理冷 skill 激活记录 {} 条", removed);
            }
        } catch (Exception e) {
            log.warn("Failed to purge stale skill activations", e);
        }
    }

    /**
     * 埋点：skill 激活即事项类型信号（skill 带 category 时同步产出 matter.classified）。
     * skill.activated 每个生效的 skill 各记一条；matter.classified 只取首个（= 用户选的，
     * 否则是自动命中的那个）——一轮对话只能有一个事项类型，多记会把分布统计打歪。
     */
    private void recordActivation(String conversationId, List<ActiveSkill> active) {
        boolean matterRecorded = false;
        for (ActiveSkill entry : active) {
            SkillDefinition skill = entry.definition();
            telemetryService.recordConv("skill.activated", conversationId,
                    Map.of("skillId", skill.getId(),
                            // 埋点取值沿用旧字面量（pinned/matched），别改成 manual/auto——
                            // 官网账本里已有历史数据按这两个值分组
                            "how", SOURCE_MANUAL.equals(entry.source()) ? "pinned" : "matched"));
            if (!matterRecorded && skill.getCategory() != null && !skill.getCategory().isBlank()) {
                telemetryService.recordConv("matter.classified", conversationId,
                        Map.of("category", skill.getCategory(), "source", "skill"));
                matterRecorded = true;
            }
        }
    }

    /**
     * 本轮生效的全部 skill（手动在前、自动在后；一个都没有时返回空列表）。
     * 注入前复查可用性——两轮之间可能被管理员停用。
     */
    public List<ActiveSkill> activeSkills(String conversationId) {
        ActivationRecord record = activeByConversation.get(conversationId);
        List<ActiveEntry> entries = record == null ? null : record.entries();
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .map(entry -> skillRegistry.getSkill(entry.skillId())
                        .filter(skillRegistry::isAvailable)
                        .map(def -> new ActiveSkill(def, displayName(def), entry.source()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 本轮生效的首个 skill（手动优先；一个都没有返回 empty）。单值出口，供只关心"有没有"的旧调用方。 */
    public Optional<SkillDefinition> activeSkill(String conversationId) {
        return activeSkills(conversationId).stream().findFirst().map(ActiveSkill::definition);
    }

    /** 按当前应用语言解析展示名：en-US 优先 name_en，缺省回退 name，再缺回退 id。 */
    public String displayName(SkillDefinition skill) {
        if (isEnglish() && skill.getNameEn() != null && !skill.getNameEn().isBlank()) {
            return skill.getNameEn();
        }
        return skill.getName() != null && !skill.getName().isBlank() ? skill.getName() : skill.getId();
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
        List<ActiveSkill> active = activeSkills(conversationId);
        if (active.isEmpty()) {
            return all;
        }
        // 多个 skill 同时生效时取白名单并集：手动选了 A 又自动命中 B，两边的能力都得在。
        Set<String> whitelist = new HashSet<>();
        List<String> activeIds = new java.util.ArrayList<>();
        for (ActiveSkill entry : active) {
            whitelist.addAll(entry.definition().getAllowedTools());
            activeIds.add(entry.definition().getId());
        }
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
            log.warn("Skills {} whitelist matched no business tools ({}), fall back to full tool set",
                    activeIds, whitelist);
            return all;
        }
        log.info("Skills {} trimmed visible tools: {} -> {}", activeIds, all.size(), filtered.size());
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
