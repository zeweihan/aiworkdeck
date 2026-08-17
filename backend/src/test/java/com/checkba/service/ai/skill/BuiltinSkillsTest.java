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
        registry = new SkillRegistry(props, null, new PluginService(), null);
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
    @DisplayName("会议录音 skill 就位：触发词、meeting 工具与产出链路齐全")
    void meetingRecorderSkillIsWiredUp() {
        SkillDefinition s = registry.getSkill("meeting-recorder")
                .orElseThrow(() -> new AssertionError("meeting-recorder 未注册"));

        assertTrue(s.getTriggers().contains("会议纪要"),
                "面板 kick-off prompt 以「会议纪要」开头，触发词缺了整条链路断掉");
        assertTrue(s.getAllowedTools().containsAll(
                        List.of("meeting_get_transcript", "meeting_list_recordings", "write_docx")),
                "读稿两件套与 write_docx 必须都在白名单里：" + s.getAllowedTools());
        assertFalse(s.isEnabledByDefault(), "默认不安装（广场装启停），别悄悄改成默认开");
        assertFalse(s.getPromptTemplate().isBlank(), "prompt.md 应有内容");
        assertTrue(s.getPromptTemplate().contains("meeting_get_transcript"),
                "prompt 必须交代先读转写稿再写纪要");

        // 英文侧：面板已双语，skill 侧缺一项就是「按钮点了产出不是纪要」
        assertTrue(s.getLanguages().contains("en-US"),
                "会议录音不绑法域，必须声明 en-US；缺了英文版下 SkillRouter 永远命不中它");
        assertTrue(s.getTriggersEn().contains("meeting minutes"),
                "MeetingRecordingService.buildMinutesKickoffPrompt 的英文开头就是它，"
                        + "两处必须一致：" + s.getTriggersEn());
        assertTrue(s.getPromptTemplateEn() != null
                        && s.getPromptTemplateEn().contains("meeting_get_transcript"),
                "prompt.en.md 应被加载且同样交代先读转写稿");
        assertFalse(s.getOutputEn() == null || s.getOutputEn().isBlank(),
                "output_en 缺失会让英文模式注入中文产出约定");
    }

    @Test
    @DisplayName("prompt 守住那条铁律：模型抽取、脚本画图")
    void promptCarriesTheGoldenRule() {
        String prompt = registry.getSkill("litigation-visual").orElseThrow().getPromptTemplate();
        assertTrue(prompt.contains("不要手写 SVG"), "必须明确禁止手写 SVG 坐标");
        assertTrue(prompt.contains("litigation_checkpoint"), "必须交代出图前要走确认");
        assertTrue(prompt.contains("逐字"), "必须交代原文逐字保留");
    }

    // ==== 应用语言（EN 版 PR5）====

    @Test
    @DisplayName("英文模式：两个中国法深度绑定 skill 真隐藏，诉讼可视化双语可用且英文侧文本齐全")
    void englishModeHidesChinaBoundSkillsAndKeepsLitigationVisual() {
        com.checkba.service.AppLanguageService en =
                org.mockito.Mockito.mock(com.checkba.service.AppLanguageService.class);
        org.mockito.Mockito.when(en.language()).thenReturn(com.checkba.service.AppLanguageService.EN_US);
        org.mockito.Mockito.when(en.isEnglish()).thenReturn(true);

        SkillProperties props = new SkillProperties();
        props.setDir(SKILLS_DIR.toString());
        SkillRegistry enRegistry = new SkillRegistry(props, null, new PluginService(), en);
        enRegistry.init();
        // 排除启停因素，单测语言过滤本身（litigation-visual 默认 enabled_by_default:false）
        enRegistry.setEnabled("litigation-visual", true);
        enRegistry.setEnabled("shareholder-meeting-verification", true);
        enRegistry.setEnabled("listing-pathway", true);

        assertFalse(enRegistry.isAvailable(enRegistry.getSkill("shareholder-meeting-verification").orElseThrow()),
                "股东大会核查是中国证券法语境交付物，英文版必须隐藏");
        assertFalse(enRegistry.isAvailable(enRegistry.getSkill("listing-pathway").orElseThrow()),
                "上市路径的触发词含 IPO/SPAC/VIE 会命中英文输入，英文版必须真隐藏");
        assertTrue(enRegistry.isAvailable(enRegistry.getSkill("litigation-visual").orElseThrow()),
                "诉讼可视化声明了 en-US，英文版应可用");

        SkillDefinition lv = enRegistry.getSkill("litigation-visual").orElseThrow();
        assertFalse(lv.getTriggersEn().isEmpty(), "litigation-visual 应带英文触发词（triggers_en）");
        assertTrue(lv.getPromptTemplateEn() != null
                        && lv.getPromptTemplateEn().contains("litigation_checkpoint"),
                "prompt.en.md 应被加载且保留出图前确认的铁律");
        assertTrue(lv.getPromptTemplateEn().contains("NEVER hand-write SVG"),
                "英文 prompt 应保留禁止手写 SVG 的铁律");

        // 英文输入 "IPO" 不能召来中国上市路径分析（不隐藏的话 listing-pathway 的英文触发词会命中）
        SkillRouter enRouter = new SkillRouter(enRegistry, props, null, en);
        assertTrue(enRouter.match("We are considering an IPO next year").isEmpty(),
                "英文模式下 IPO 不应命中任何中国法 skill");
        assertEquals("litigation-visual",
                enRouter.match("Please draw a case timeline of the dispute").orElseThrow().getId(),
                "英文触发词应命中诉讼可视化");
    }

    /**
     * 英文模式下「生成会议纪要」按钮的整条链路。
     *
     * <p>这里用的字面量就是 {@code MeetingRecordingService.buildMinutesKickoffPrompt} 英文分支的
     * 开头（那边有对应的单测断言 prompt 以它开头）。两端各锁一半：那边保证发出去的是这句话，
     * 这边保证这句话能命中本 skill。缺任一半，按钮在英文版下就是「有反应、产出不对」。
     */
    @Test
    @DisplayName("英文模式：会议录音可用，面板 kick-off prompt 的英文开头能命中它")
    void englishModeKeepsMeetingRecorderReachable() {
        com.checkba.service.AppLanguageService en =
                org.mockito.Mockito.mock(com.checkba.service.AppLanguageService.class);
        org.mockito.Mockito.when(en.language()).thenReturn(com.checkba.service.AppLanguageService.EN_US);
        org.mockito.Mockito.when(en.isEnglish()).thenReturn(true);

        SkillProperties props = new SkillProperties();
        props.setDir(SKILLS_DIR.toString());
        SkillRegistry enRegistry = new SkillRegistry(props, null, new PluginService(), en);
        enRegistry.init();
        enRegistry.setEnabled("meeting-recorder", true); // 默认不安装，这里只测语言过滤

        assertTrue(enRegistry.isAvailable(enRegistry.getSkill("meeting-recorder").orElseThrow()),
                "会议录音声明了 en-US，英文版应可用");

        SkillRouter enRouter = new SkillRouter(enRegistry, props, null, en);
        assertEquals("meeting-recorder",
                enRouter.match("Meeting minutes generation task.").orElseThrow().getId(),
                "面板拼出的英文 kick-off prompt 必须命中会议录音 skill");
    }

    @Test
    @DisplayName("中文模式（默认）：语言过滤不改变任何既有可用性")
    void chineseModeAvailabilityUnchanged() {
        for (SkillDefinition skill : registry.getSkills()) {
            // 本测试类的 registry 未接语言服务（null = zh-CN 语义）：
            // 可用性只由启停决定，languages 字段不应产生任何影响
            boolean expected = registry.isEnabled(skill.getId());
            assertEquals(expected, registry.isAvailable(skill),
                    "zh-CN 下 " + skill.getId() + " 的可用性不应被语言过滤改变");
        }
    }
}
