package com.checkba.service.ai.skill;

import com.checkba.service.ai.PluginService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillRegistry 单测：skill.yml 解析（含坏文件跳过）、插件携带 skill 注册、启停。
 * 规范见 docs/SKILL_SPEC.md。
 */
class SkillRegistryTest {

    @TempDir
    Path tempDir;

    private SkillRegistry newRegistry(Path skillsDir, PluginService pluginService) {
        SkillProperties props = new SkillProperties();
        props.setDir(skillsDir.toString());
        SkillRegistry registry = new SkillRegistry(props, null, pluginService);
        registry.init();
        return registry;
    }

    private void writeSkill(Path dir, String id, String yamlExtra) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("skill.yml"), """
                id: %s
                name: 测试技能
                description: 一个测试技能
                triggers:
                  - 上市路径
                  - IPO
                prompt: prompt.md
                allowed_tools:
                  - law_search
                  - write_docx
                %s""".formatted(id, yamlExtra == null ? "" : yamlExtra));
        Files.writeString(dir.resolve("prompt.md"), "## 技能指引\n按框架分析。");
    }

    @Test
    @DisplayName("解析合法 skill.yml：字段与 prompt 模板全部就位")
    void parsesValidSkill() throws IOException {
        writeSkill(tempDir.resolve("test-skill"), "test-skill", "output: 输出一份比较分析\n");
        SkillRegistry registry = newRegistry(tempDir, new PluginService());

        assertEquals(1, registry.getSkills().size());
        SkillDefinition skill = registry.getSkill("test-skill").orElseThrow();
        assertEquals("测试技能", skill.getName());
        assertEquals(List.of("上市路径", "IPO"), skill.getTriggers());
        assertEquals(List.of("law_search", "write_docx"), skill.getAllowedTools());
        assertTrue(skill.getPromptTemplate().contains("技能指引"));
        assertEquals("输出一份比较分析", skill.getOutput().trim());
        assertNull(skill.getSourcePluginId());
        assertTrue(registry.isEnabled("test-skill"), "默认启用");
    }

    @Test
    @DisplayName("坏 skill 跳过不阻断：YAML 语法错误 / 缺 id / 缺 prompt 文件 / 缺 triggers")
    void skipsBrokenSkillsWithoutBlocking() throws IOException {
        // 好的 skill
        writeSkill(tempDir.resolve("good-skill"), "good-skill", null);
        // YAML 语法错误
        Path badYaml = tempDir.resolve("bad-yaml");
        Files.createDirectories(badYaml);
        Files.writeString(badYaml.resolve("skill.yml"), "id: [unclosed\n  - broken");
        // 缺 id
        Path noId = tempDir.resolve("no-id");
        Files.createDirectories(noId);
        Files.writeString(noId.resolve("skill.yml"), "name: 没有id\ntriggers: [x]\n");
        Files.writeString(noId.resolve("prompt.md"), "p");
        // prompt 文件缺失
        Path noPrompt = tempDir.resolve("no-prompt");
        Files.createDirectories(noPrompt);
        Files.writeString(noPrompt.resolve("skill.yml"), "id: no-prompt\ntriggers: [x]\n");
        // 缺 triggers（永远不可能命中）
        Path noTriggers = tempDir.resolve("no-triggers");
        Files.createDirectories(noTriggers);
        Files.writeString(noTriggers.resolve("skill.yml"), "id: no-triggers\n");
        Files.writeString(noTriggers.resolve("prompt.md"), "p");
        // 没有 skill.yml 的目录
        Files.createDirectories(tempDir.resolve("empty-dir"));

        SkillRegistry registry = newRegistry(tempDir, new PluginService());

        assertEquals(1, registry.getSkills().size(), "只有合法 skill 被注册");
        assertTrue(registry.getSkill("good-skill").isPresent());
    }

    @Test
    @DisplayName("插件携带 skill（manifest.skills）：经 PluginService 收集后注册，带 sourcePluginId")
    void registersPluginCarriedSkills() throws IOException {
        Path pluginsDir = tempDir.resolve("plugins");
        Path pluginDir = pluginsDir.resolve("my-plugin");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("manifest.json"), """
                { "id": "my-plugin", "name": "带技能的插件", "version": "1.0.0",
                  "skills": ["my-skill", "missing-dir"] }
                """);
        writeSkill(pluginDir.resolve("my-skill"), "plugin-skill", null);

        PluginService pluginService = new PluginService(null, pluginsDir.toString());
        pluginService.init();
        assertEquals(1, pluginService.getPluginSkillDirs().size(), "不存在的 skill 子目录被跳过");

        Path emptySkillsDir = tempDir.resolve("skills-empty");
        SkillRegistry registry = newRegistry(emptySkillsDir, pluginService);

        SkillDefinition skill = registry.getSkill("plugin-skill").orElseThrow();
        assertEquals("my-plugin", skill.getSourcePluginId());
        assertTrue(registry.isAvailable(skill), "插件启用时 skill 可用");

        // 禁用所属插件后 skill 不可用（但仍在列表中，供管理页展示）
        pluginService.setEnabled("my-plugin", false);
        assertFalse(registry.isAvailable(skill));
        assertTrue(registry.getSkill("plugin-skill").isPresent());
    }

    @Test
    @DisplayName("启停：setEnabled 生效；未知 id 抛 IllegalArgumentException；rescan 后保持")
    void enableDisableAndRescan() throws IOException {
        writeSkill(tempDir.resolve("test-skill"), "test-skill", null);
        SkillRegistry registry = newRegistry(tempDir, new PluginService());

        registry.setEnabled("test-skill", false);
        assertFalse(registry.isEnabled("test-skill"));
        assertFalse(registry.isAvailable(registry.getSkill("test-skill").orElseThrow()));
        registry.setEnabled("test-skill", true);
        assertTrue(registry.isEnabled("test-skill"));

        assertThrows(IllegalArgumentException.class, () -> registry.setEnabled("nope", false));

        registry.rescan();
        assertEquals(1, registry.getSkills().size());
    }

    @Test
    @DisplayName("skills 目录不存在时正常启动（0 个 skill）")
    void missingSkillsDirIsFine() {
        SkillRegistry registry = newRegistry(tempDir.resolve("does-not-exist"), new PluginService());
        assertEquals(0, registry.getSkills().size());
        assertEquals(Optional.empty(), registry.getSkill("anything"));
    }
}
