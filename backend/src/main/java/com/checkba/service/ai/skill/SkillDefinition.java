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

    /** 工具白名单：命中后本轮 LLM 可见工具 = allowedTools ∪ 基础工具集 */
    private List<String> allowedTools = new ArrayList<>();

    /** 输出结构约定（自然语言描述，随 prompt 一起注入） */
    private String output;

    /** 声明依赖的能力契约（如 evidence.retrieve.v1），由插件/内置实现提供；仅声明，不阻断加载 */
    private List<String> requires = new ArrayList<>();

    /** 来源插件 id（插件携带的 skill）；内置 skill 为 null */
    private String sourcePluginId;

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

    /** 展示用作者名（skill.yml: author），如 "AI Workdeck" */
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
    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }
    public List<String> getRequires() { return requires; }
    public void setRequires(List<String> requires) { this.requires = requires; }
    public String getSourcePluginId() { return sourcePluginId; }
    public void setSourcePluginId(String sourcePluginId) { this.sourcePluginId = sourcePluginId; }
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
