// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.skill.SkillProperties;
import com.checkba.service.ai.skill.SkillRegistry;
import com.checkba.service.ai.skill.SkillRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * use_skill（dev-board#1065）：结果就是 skill 的指引正文；未知 / 停用 / 仅手动的回 Error 并列出可用 id；
 * 本轮已经生效的只回一句提醒，不再把整份指引灌一遍。
 */
class SkillToolsTest {

    @TempDir
    Path tempDir;

    private SkillRegistry registry;
    private SkillRouter router;
    private SkillTools tools;

    @BeforeEach
    void setUp() throws IOException {
        writeSkill("verify", "核查流程", "逐字段三源对照，每项结论注明出处。", List.of("extract_file_text", "run_python"));
        writeSkill("draw", "画图", "把事实经过画成时间轴。", List.of());
        SkillProperties props = new SkillProperties();
        props.setDir(tempDir.toString());
        props.setBaseTools(List.of("read_document"));
        registry = new SkillRegistry(props, null, new PluginService(), null);
        registry.init();
        router = new SkillRouter(registry, props,
                new com.checkba.service.telemetry.TelemetryService(
                        org.mockito.Mockito.mock(com.checkba.repository.TelemetryEventRepository.class),
                        new com.checkba.service.telemetry.InstallIdentityService(tempDir.toString()),
                        "test"),
                null);
        tools = new SkillTools(router);
        ToolContextHolder.set(new ToolContext(1L, "conv", 7L, null, List.of(), Set.of(), "run-1", null));
    }

    @AfterEach
    void clear() {
        ToolContextHolder.clear();
    }

    private void writeSkill(String id, String name, String prompt, List<String> allowedTools) throws IOException {
        Path dir = tempDir.resolve(id);
        Files.createDirectories(dir);
        StringBuilder yml = new StringBuilder("id: " + id + "\nname: " + name
                + "\ndescription: " + name + "的说明\ntriggers:\n  - 永远不会命中的触发词" + id + "\n");
        yml.append("tool_policy: restrict\nallowed_tools:\n");
        allowedTools.forEach(t -> yml.append("  - ").append(t).append("\n"));
        Files.writeString(dir.resolve("skill.yml"), yml.toString());
        Files.writeString(dir.resolve("prompt.md"), prompt);
    }

    @Test
    @DisplayName("返回 skill 的指引正文 + 它要用的工具与「下一轮生效 / 当轮用 <tool_code>」提示")
    void returnsTheSkillInstructions() {
        String out = tools.use_skill("verify", "conv");

        assertFalse(out.startsWith("Error"), out);
        assertTrue(out.contains("# Skill: 核查流程 (verify)"), out);
        assertTrue(out.contains("逐字段三源对照，每项结论注明出处。"), "指引正文必须原样进结果：" + out);
        assertTrue(out.contains("Tools this skill uses: extract_file_text, run_python"), out);
        assertTrue(out.contains("NEXT turn") && out.contains("<tool_code>"), out);
    }

    @Test
    @DisplayName("没有白名单的 skill 不说「它要用的工具」")
    void skillWithoutToolsSaysNothingAboutTools() {
        String out = tools.use_skill("draw", "conv");
        assertTrue(out.contains("把事实经过画成时间轴。"), out);
        assertFalse(out.contains("Tools this skill uses"), out);
    }

    @Test
    @DisplayName("未知 id：Error 开头，并列出可用的 skill id")
    void unknownSkillListsTheAvailableOnes() {
        String out = tools.use_skill("no-such-skill", "conv");
        assertTrue(out.startsWith("Error: no usable skill with id 'no-such-skill'"), out);
        assertTrue(out.contains("Available skills: ") && out.contains("verify") && out.contains("draw"), out);
    }

    @Test
    @DisplayName("仅手动 / 停用的 skill 调不了，也不出现在可用清单里")
    void manualAndDisabledSkillsAreRefused() {
        registry.setActivationMode("verify", SkillRegistry.ActivationMode.MANUAL);
        registry.setActivationMode("draw", SkillRegistry.ActivationMode.DISABLED);

        String out = tools.use_skill("verify", "conv");
        assertTrue(out.startsWith("Error"), out);
        assertTrue(out.contains("manual-only"), out);
        assertTrue(out.contains("No skills are available"), out);
    }

    @Test
    @DisplayName("本轮已经生效：只回一句提醒，不再把整份指引灌一遍")
    void alreadyActiveSkillIsNotRepeated() {
        router.activateMidRun("conv", "run-1", "verify");
        String out = tools.use_skill("verify", "conv");
        assertTrue(out.contains("already active for this turn"), out);
        assertFalse(out.contains("逐字段三源对照"), out);
    }
}
