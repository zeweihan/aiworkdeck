package com.checkba.service.ai.skill;

import com.checkba.service.ai.PluginService;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillRouter 单测：触发匹配（命中/未命中/多命中取最长）、
 * 工具集裁剪（白名单∪基础工具、未命中不裁剪、空白名单回退）。
 */
class SkillRouterTest {

    @TempDir
    Path tempDir;

    private SkillRegistry registry;
    private SkillRouter router;
    private SkillProperties props;

    @BeforeEach
    void setUp() throws IOException {
        // skill A：通用触发词
        writeSkill("skill-a", List.of("上市", "IPO"), List.of("law_search", "write_docx"));
        // skill B：更长（更 specific）的触发词
        writeSkill("skill-b", List.of("上市路径"), List.of("search_web"));

        props = new SkillProperties();
        props.setDir(tempDir.toString());
        props.setBaseTools(List.of("read_document"));
        registry = new SkillRegistry(props, null, new PluginService());
        registry.init();
        router = new SkillRouter(registry, props,
                new com.checkba.service.telemetry.TelemetryService(
                        org.mockito.Mockito.mock(com.checkba.repository.TelemetryEventRepository.class),
                        new com.checkba.service.telemetry.InstallIdentityService(tempDir.toString()),
                        "test"));
    }

    private void writeSkill(String id, List<String> triggers, List<String> allowedTools) throws IOException {
        Path dir = tempDir.resolve(id);
        Files.createDirectories(dir);
        StringBuilder yml = new StringBuilder("id: " + id + "\nname: " + id + "\ntriggers:\n");
        triggers.forEach(t -> yml.append("  - ").append(t).append("\n"));
        yml.append("allowed_tools:\n");
        allowedTools.forEach(t -> yml.append("  - ").append(t).append("\n"));
        Files.writeString(dir.resolve("skill.yml"), yml.toString());
        Files.writeString(dir.resolve("prompt.md"), "prompt of " + id);
    }

    private static List<ToolSpecification> specs(String... names) {
        return java.util.Arrays.stream(names)
                .map(n -> ToolSpecification.builder().name(n).description(n).build())
                .toList();
    }

    @Test
    @DisplayName("触发匹配：命中 / 未命中 / 大小写不敏感")
    void basicMatch() {
        assertEquals("skill-a", router.match("公司考虑ipo，帮忙分析").orElseThrow().getId());
        assertEquals(Optional.empty(), router.match("帮我起草一份保密协议"));
        assertEquals(Optional.empty(), router.match(null));
        assertEquals(Optional.empty(), router.match("  "));
    }

    @Test
    @DisplayName("多命中取最长触发词：'上市路径' 同时命中 A(上市) 与 B(上市路径)，B 胜出")
    void longestTriggerWins() {
        assertEquals("skill-b", router.match("比较一下上市路径怎么选").orElseThrow().getId());
        // 只命中短词时仍归 A
        assertEquals("skill-a", router.match("公司想上市").orElseThrow().getId());
    }

    @Test
    @DisplayName("被禁用的 skill 不参与匹配")
    void disabledSkillNotMatched() {
        registry.setEnabled("skill-b", false);
        assertEquals("skill-a", router.match("比较一下上市路径怎么选").orElseThrow().getId(),
                "B 被禁用后由 A 的短触发词兜住");
        registry.setEnabled("skill-a", false);
        assertEquals(Optional.empty(), router.match("比较一下上市路径怎么选"));
    }

    @Test
    @DisplayName("工具裁剪：命中后可见工具 = allowed_tools ∪ 基础工具集")
    void trimsToWhitelistPlusBaseTools() {
        router.activateForTurn("conv-1", "公司考虑IPO");
        List<ToolSpecification> all = specs("law_search", "write_docx", "doc_open_file",
                "pptx_generate", "read_document");

        List<ToolSpecification> visible = router.visibleTools("conv-1", all);
        List<String> names = visible.stream().map(ToolSpecification::name).toList();
        assertEquals(List.of("law_search", "write_docx", "read_document"), names);
    }

    @Test
    @DisplayName("未命中任何 skill：不裁剪，原样返回（行为保持）")
    void noMatchNoTrim() {
        router.activateForTurn("conv-2", "帮我起草一份保密协议");
        List<ToolSpecification> all = specs("law_search", "doc_open_file");
        assertSame(all, router.visibleTools("conv-2", all));
        assertEquals(Optional.empty(), router.activeSkill("conv-2"));
    }

    @Test
    @DisplayName("新一轮未命中会清掉上一轮的激活状态")
    void unmatchedTurnClearsActivation() {
        router.activateForTurn("conv-3", "公司考虑IPO");
        assertTrue(router.activeSkill("conv-3").isPresent());
        router.activateForTurn("conv-3", "换个话题，帮我改一下合同措辞");
        assertEquals(Optional.empty(), router.activeSkill("conv-3"));
    }

    @Test
    @DisplayName("白名单与注册工具零交集时回退为不裁剪（误配置保护）")
    void emptyIntersectionFallsBack() throws IOException {
        writeSkill("skill-typo", List.of("特殊触发词xyz"), List.of("no_such_tool"));
        registry.rescan();
        props.setBaseTools(List.of());
        router.activateForTurn("conv-4", "包含特殊触发词xyz的请求");
        List<ToolSpecification> all = specs("law_search", "doc_open_file");
        assertSame(all, router.visibleTools("conv-4", all));
    }

    @Test
    @DisplayName("prompt 注入块包含技能名、模板与输出约定")
    void promptInjectionBlock() throws IOException {
        Path dir = tempDir.resolve("skill-out");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("skill.yml"),
                "id: skill-out\nname: 有输出约定\ntriggers: [输出约定触发]\noutput: 输出一张比较表\n");
        Files.writeString(dir.resolve("prompt.md"), "模板正文ABC");
        registry.rescan();

        String block = router.promptInjectionFor(registry.getSkill("skill-out").orElseThrow());
        assertTrue(block.contains("有输出约定"));
        assertTrue(block.contains("模板正文ABC"));
        assertTrue(block.contains("输出一张比较表"));
    }

    @Test
    @DisplayName("仅手动的 skill 不参与自动匹配，但仍可被钉选生效")
    void manualSkillSkippedByAutoMatchButPinnable() {
        registry.setActivationMode("skill-a", SkillRegistry.ActivationMode.MANUAL);

        // 触发词命中也不自动激活；此处输入只含 skill-a 的触发词
        assertEquals(Optional.empty(), router.match("公司考虑IPO"));
        router.activateForTurn("conv-m", "公司考虑IPO");
        assertEquals(Optional.empty(), router.activeSkill("conv-m"));

        // 钉选后照常生效
        router.activateForTurn("conv-m", "随便问点别的", "skill-a");
        assertEquals("skill-a", router.activeSkill("conv-m").orElseThrow().getId());
    }

    @Test
    @DisplayName("钉选优先于触发词匹配")
    void pinnedBeatsTriggerMatch() {
        router.activateForTurn("conv-p", "帮我分析上市路径", "skill-a");
        assertEquals("skill-a", router.activeSkill("conv-p").orElseThrow().getId());
        // 不钉选时同一句话命中的是触发词更长的 skill-b
        router.activateForTurn("conv-p", "帮我分析上市路径");
        assertEquals("skill-b", router.activeSkill("conv-p").orElseThrow().getId());
    }

    @Test
    @DisplayName("钉选 id 不存在或已停用时退回自动匹配")
    void invalidPinFallsBackToAutoMatch() {
        router.activateForTurn("conv-x", "帮我分析上市路径", "no-such-skill");
        assertEquals("skill-b", router.activeSkill("conv-x").orElseThrow().getId());

        registry.setActivationMode("skill-b", SkillRegistry.ActivationMode.DISABLED);
        router.activateForTurn("conv-y", "公司考虑IPO", "skill-b");
        assertEquals("skill-a", router.activeSkill("conv-y").orElseThrow().getId());
    }
}
