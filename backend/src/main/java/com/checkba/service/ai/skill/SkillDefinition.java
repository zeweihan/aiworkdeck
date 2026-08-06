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
}
