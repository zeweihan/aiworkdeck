// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个已注册的 Skill（打包格式与字段定义见 docs/SKILL_SPEC.md）。
 *
 * Skill = prompt 模板 + 工具白名单 + 触发条件 + 输出约定的打包格式：
 * skills/&lt;id&gt;/{skill.yml, prompt.md}
 */
public class SkillDefinition {

    /** 全局唯一稳定标识（kebab-case），启停状态以此为键持久化 */
    private String id;

    /** 展示名称（中文优先） */
    private String name;

    /** 一句话描述 */
    private String description;

    /** 触发条件：关键词列表（用户输入包含任一关键词即命中，不区分大小写） */
    private List<String> triggers = new ArrayList<>();

    /** prompt 模板文件名（相对 skill 目录，默认 prompt.md） */
    private String promptFile = "prompt.md";

    /** prompt 模板内容（扫描时加载进内存） */
    private String promptTemplate = "";

    /**
     * 工具白名单：{@code tool_policy: restrict} 时命中本 skill 的那一轮，
     * LLM 可见工具 = allowedTools ∪ 基础工具集 ∪ 编排类工具。
     * {@code passthrough}（缺省）下本清单<b>不参与裁剪</b>，只作为文档说明本 skill 的能力边界。
     */
    private List<String> allowedTools = new ArrayList<>();

    /**
     * 本 skill 要不要限制本轮可见工具（skill.yml: {@code tool_policy}，dev-board#799 / 审计 A2）。
     *
     * <p><b>缺省是 {@link ToolPolicy#PASSTHROUGH}（不裁剪）</b>，这是本次改掉的默认值。
     * 改之前：裁剪与否只看 allowed_tools 有没有内容，而它的缺省是空 ArrayList——于是
     * 「本身不带工具」的 skill（desensitize / text-to-speech，它们的作用是把用户引导去
     * 左栏面板，刻意不带工具）一旦被触发词命中，整轮可见工具会从一百多个塌缩成
     * base-tools ∪ 编排类工具十来个，doc_* 全部消失，模型只能回一句「我无法修改文档」。
     * {@code SkillRouter} 里那条误配置回退救不了它：回退判据是「filtered 里是不是只剩
     * 编排类工具」，而 base-tools 的三个恰好让这条判据为假。text-to-speech 还是
     * {@code enabled_by_default: true}，默认对所有用户生效——不报错、不告警，
     * 表现只是「AI 突然不会改文档了」。
     *
     * <p>换成显式声明之后，「不声明 = 不管工具这件事」，要裁剪必须自己写
     * {@code tool_policy: restrict}。方向是安全的：判不准时多给工具，而不是把能用的藏起来。
     */
    private ToolPolicy toolPolicy = ToolPolicy.PASSTHROUGH;

    /** {@link #toolPolicy} 的取值。 */
    public enum ToolPolicy {
        /** 不裁剪本轮工具集（缺省）：本 skill 只管 prompt，不管模型能看见哪些工具。 */
        PASSTHROUGH,
        /** 裁到 allowed_tools ∪ base-tools ∪ 编排类工具：本 skill 是一个聚焦模式。 */
        RESTRICT;

        /** 解析 skill.yml 的 {@code tool_policy}；空值与无法识别的值返回 empty（调用方决定怎么兜）。 */
        public static java.util.Optional<ToolPolicy> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return java.util.Optional.empty();
            }
            try {
                return java.util.Optional.of(
                        ToolPolicy.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return java.util.Optional.empty();
            }
        }
    }

    /** 输出结构约定（自然语言描述，随 prompt 一起注入） */
    private String output;

    /** 声明依赖的能力契约（如 evidence.retrieve.v1），由插件/内置实现提供；仅声明，不阻断加载 */
    private List<String> requires = new ArrayList<>();

    /** 来源插件 id（插件携带的 skill）；内置 skill 为 null */
    private String sourcePluginId;

    /**
     * 依赖的原生资源包 id（skill.yml: requires_pack，可选）。
     *
     * <p>声明了它就意味着「功能要用的重资源不随安装包分发」，广场安装时先下 pack
     * 再启用 skill；旧版本应用忽略未知字段，向前兼容。规范见
     * docs/NATIVE_PACK_DISTRIBUTION.md §7.1。
     */
    private String requiresPack;

    /**
     * 法律事项类别（可选，用于匿名统计的事项类型分布，枚举值见
     * com.checkba.service.telemetry.MatterCategory；缺省不参与事项统计）
     */
    private String category;

    /**
     * 默认是否启用（默认 true）。为 false 时表示"随包分发但需要用户手动打开"——
     * 见 {@link SkillRegistry} 里 seeded-once 的解释；skill.yml 对应字段 enabled_by_default。
     */
    private boolean enabledByDefault = true;

    /** 展示用作者名（skill.yml: author），如 "AI WorkDeck" */
    private String author;

    /** 作者主页/仓库链接（skill.yml: author_url） */
    private String authorUrl;

    /** 展示用版本号，自由格式，不参与 id 覆盖判断（skill.yml: version） */
    private String version;

    /** 许可证标识，如 "MIT"（skill.yml: license） */
    private String license;

    /**
     * 随 skill 分发的第三方内容署名（如 vendor 引擎），满足 MIT 等许可证的版权声明
     * 保留要求；每条一行自由文本，前端原样展示（skill.yml: credits）。
     */
    private List<String> credits = new ArrayList<>();

    // ==================== 应用语言（EN 版 PR5，skill.yml 可选字段） ====================

    /**
     * 本 skill 可用的应用语言列表（skill.yml: languages，值为 zh-CN / en-US）。
     * **缺省（空列表）= 只在 zh-CN 可用**：存量第三方 skill 没有这个字段，英文版下
     * 自动隐藏——方向安全（中国法深度绑定的 skill 不会在英文输入上误触发）。
     * 过滤收口在 {@link SkillRegistry#isAvailable}（match/钉选/注入三条路径共用）。
     */
    private List<String> languages = new ArrayList<>();

    /** 英文展示名（skill.yml: name_en，可选）；英文模式下注入块用它，缺省回退 name */
    private String nameEn;

    /** 英文触发词（skill.yml: triggers_en，可选）；仅英文模式参与匹配（连同 triggers 一起） */
    private List<String> triggersEn = new ArrayList<>();

    /** 英文输出约定（skill.yml: output_en，可选）；英文模式下注入块用它，缺省回退 output */
    private String outputEn;

    /** 英文 prompt 模板（目录下存在 prompt.en.md 时加载）；英文模式下注入用它，缺省回退 promptTemplate */
    private String promptTemplateEn;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getTriggers() { return triggers; }
    public void setTriggers(List<String> triggers) { this.triggers = triggers; }
    public String getPromptFile() { return promptFile; }
    public void setPromptFile(String promptFile) { this.promptFile = promptFile; }
    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
    public ToolPolicy getToolPolicy() { return toolPolicy; }
    public void setToolPolicy(ToolPolicy toolPolicy) {
        this.toolPolicy = toolPolicy == null ? ToolPolicy.PASSTHROUGH : toolPolicy;
    }
    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }
    public List<String> getRequires() { return requires; }
    public void setRequires(List<String> requires) { this.requires = requires; }
    public String getSourcePluginId() { return sourcePluginId; }
    public void setSourcePluginId(String sourcePluginId) { this.sourcePluginId = sourcePluginId; }
    public String getRequiresPack() { return requiresPack; }
    public void setRequiresPack(String requiresPack) { this.requiresPack = requiresPack; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public boolean isEnabledByDefault() { return enabledByDefault; }
    public void setEnabledByDefault(boolean enabledByDefault) { this.enabledByDefault = enabledByDefault; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getAuthorUrl() { return authorUrl; }
    public void setAuthorUrl(String authorUrl) { this.authorUrl = authorUrl; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }
    public List<String> getCredits() { return credits; }
    public void setCredits(List<String> credits) { this.credits = credits; }
    public List<String> getLanguages() { return languages; }
    public void setLanguages(List<String> languages) { this.languages = languages; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public List<String> getTriggersEn() { return triggersEn; }
    public void setTriggersEn(List<String> triggersEn) { this.triggersEn = triggersEn; }
    public String getOutputEn() { return outputEn; }
    public void setOutputEn(String outputEn) { this.outputEn = outputEn; }
    public String getPromptTemplateEn() { return promptTemplateEn; }
    public void setPromptTemplateEn(String promptTemplateEn) { this.promptTemplateEn = promptTemplateEn; }
}
