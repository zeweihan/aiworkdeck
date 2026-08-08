package com.checkba.service.ai.skill;

import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.tools.AgentToolComponent;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随仓库分发的内置 skill（backend/skills/）的守卫。
 *
 * <p>盯两件容易烂掉的事：
 * <ol>
 *   <li><b>解析得动。</b>skill.yml 写坏时 SkillRegistry 是"跳过不阻断"——不报错、
 *       只打一行日志，功能静默消失。这里把每个内置 skill 都断言必须注册成功。</li>
 *   <li><b>allowed_tools 里的工具名是真的。</b>这是记在案的地雷：白名单里写错工具名
 *       不会报错，只是零命中回退成"不裁剪"，于是这个 skill 的工具裁剪彻底失效，
 *       而表面上一切正常。改工具名、删工具时这条会红。</li>
 * </ol>
 */
class BuiltinSkillsTest {

    private static SkillRegistry registry;
    private static Set<String> realToolNames;

    /** 测试运行时 cwd 是 backend/，内置 skill 就在它下面的 skills/。 */
    private static final Path SKILLS_DIR = Path.of("skills");

    @BeforeAll
    static void setUp() {
        SkillProperties props = new SkillProperties();
        props.setDir(SKILLS_DIR.toString());
        registry = new SkillRegistry(props, null, new PluginService());
        registry.init();
        realToolNames = scanRealToolNames();
    }

    /**
     * 反射扫出所有 @Tool 方法名，即 ToolRegistry 运行时真正注册的那批名字。
     * 不起 Spring 上下文——只要类路径扫描，跑得快且不依赖任何 Bean。
     */
    private static Set<String> scanRealToolNames() {
        Set<String> names = new HashSet<>();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AgentToolComponent.class));
        for (BeanDefinition bd : scanner.findCandidateComponents("com.checkba.service.ai.tools")) {
            try {
                Class<?> c = Class.forName(bd.getBeanClassName());
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(Tool.class)) {
                        Tool t = m.getAnnotation(Tool.class);
                        // @Tool 的 name 留空时，langchain4j 用方法名作工具名
                        names.add(t.name().isBlank() ? m.getName() : t.name());
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // 扫到但加载不了的类不该让这条守卫红
            }
        }
        return names;
    }

    @Test
    @DisplayName("backend/skills 下每个目录都成功注册（解析失败是静默跳过，必须显式盯住）")
    void everyBuiltinSkillParses() throws Exception {
        List<String> dirs = new ArrayList<>();
        try (var s = Files.list(SKILLS_DIR)) {
            s.filter(Files::isDirectory).forEach(p -> dirs.add(p.getFileName().toString()));
        }
        assertFalse(dirs.isEmpty(), "backend/skills 下应有内置 skill");
        assertEquals(dirs.size(), registry.getSkills().size(),
                "有目录没注册成功（解析失败会被静默跳过）；目录=" + new TreeSet<>(dirs)
                        + " 已注册=" + registry.getSkills().stream().map(SkillDefinition::getId).sorted().toList());
    }

    @Test
    @DisplayName("每个内置 skill 的 allowed_tools 都是真实存在的工具名")
    void allowedToolsAreRealToolNames() {
        assertFalse(realToolNames.isEmpty(), "应能扫到 @Tool 方法");
        List<String> bogus = new ArrayList<>();
        for (SkillDefinition skill : registry.getSkills()) {
            for (String tool : skill.getAllowedTools()) {
                if (!realToolNames.contains(tool)) {
                    bogus.add(skill.getId() + " -> " + tool);
                }
            }
        }
        assertTrue(bogus.isEmpty(),
                "allowed_tools 里有不存在的工具名（白名单零命中会静默回退成不裁剪）：" + bogus);
    }

    @Test
    @DisplayName("诉讼可视化 skill 就位：触发词与出图三件套齐全")
    void litigationVisualSkillIsWiredUp() {
        SkillDefinition s = registry.getSkill("litigation-visual")
                .orElseThrow(() -> new AssertionError("litigation-visual 未注册"));

        assertTrue(s.getTriggers().contains("案件时间轴"), "应能被『案件时间轴』触发");
        assertTrue(s.getTriggers().contains("当事人关系图"), "应能被『当事人关系图』触发");
        assertTrue(s.getAllowedTools().containsAll(
                        List.of("litigation_render", "litigation_checkpoint", "litigation_reference")),
                "出图三件套必须都在白名单里，缺一个模型就调不到：" + s.getAllowedTools());
        assertTrue(s.getAllowedTools().contains("extract_file_text"),
                "抽取阶段要通读文件夹里的材料，缺了它就只能凭对话内容画");
        assertFalse(s.getPromptTemplate().isBlank(), "prompt.md 应有内容");
    }

    @Test
    @DisplayName("prompt 守住那条铁律：模型抽取、脚本画图")
    void promptCarriesTheGoldenRule() {
        String prompt = registry.getSkill("litigation-visual").orElseThrow().getPromptTemplate();
        assertTrue(prompt.contains("不要手写 SVG"), "必须明确禁止手写 SVG 坐标");
        assertTrue(prompt.contains("litigation_checkpoint"), "必须交代出图前要走确认");
        assertTrue(prompt.contains("逐字"), "必须交代原文逐字保留");
    }
}
