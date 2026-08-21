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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillRouter 单测：触发匹配（命中/未命中/多命中取最长）、
 * 工具集裁剪（白名单∪基础工具、编排类工具恒定可见、未命中不裁剪、空白名单回退）。
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
        registry = new SkillRegistry(props, null, new PluginService(), null);
        registry.init();
        router = new SkillRouter(registry, props,
                new com.checkba.service.telemetry.TelemetryService(
                        org.mockito.Mockito.mock(com.checkba.repository.TelemetryEventRepository.class),
                        new com.checkba.service.telemetry.InstallIdentityService(tempDir.toString()),
                        "test"),
                null);
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
    @DisplayName("工具裁剪：命中后可见工具 = allowed_tools ∪ 基础工具集（本例注册表里没有编排类工具）")
    void trimsToWhitelistPlusBaseTools() {
        router.activateForTurn("conv-1", "公司考虑IPO");
        List<ToolSpecification> all = specs("law_search", "write_docx", "doc_open_file",
                "pptx_generate", "read_document");

        List<ToolSpecification> visible = router.visibleTools("conv-1", all);
        List<String> names = visible.stream().map(ToolSpecification::name).toList();
        assertEquals(List.of("law_search", "write_docx", "read_document"), names);
    }

    @Test
    @DisplayName("护栏：编排类工具恒定可见——skill 的 allowed_tools 不含它们也裁不掉")
    void orchestrationToolsAlwaysVisible() {
        // skill-a 的 allowed_tools 只有 law_search / write_docx，故意不含编排类工具；
        // base-tools 也只有 read_document。按"allowed_tools ∪ base-tools"的老口径，
        // todo_write 与 dispatch_subtask 会被静默裁掉（不报错不告警，只是模型不写清单/不派子任务）。
        // 这条断言是防"下一个新 skill 再踩一次"的唯一屏障，不要因为自带 skill 已显式声明就删掉它。
        router.activateForTurn("conv-orch", "公司考虑IPO");
        List<ToolSpecification> all = specs("law_search", "write_docx", "doc_open_file",
                "read_document", "todo_write", "dispatch_subtask");

        List<String> names = router.visibleTools("conv-orch", all).stream()
                .map(ToolSpecification::name).toList();
        assertTrue(names.contains("todo_write"), "编排类工具 todo_write 必须恒定可见");
        assertTrue(names.contains("dispatch_subtask"), "编排类工具 dispatch_subtask 必须恒定可见");
        // 裁剪本身照旧生效：不在白名单里的业务工具仍然看不见
        assertFalse(names.contains("doc_open_file"), "白名单外的业务工具仍应被裁掉");
    }

    @Test
    @DisplayName("误配置回退不被编排类工具带偏：业务工具零交集时仍回退为不裁剪")
    void orchestrationToolsDoNotDefeatEmptyWhitelistFallback() throws IOException {
        writeSkill("skill-typo2", List.of("特殊触发词orch"), List.of("no_such_tool"));
        registry.rescan();
        props.setBaseTools(List.of());
        router.activateForTurn("conv-orch2", "包含特殊触发词orch的请求");
        // 若判据不排除恒定可见的编排类工具，这里会只剩那两个，skill 被裁成"只会写清单/派子任务"
        List<ToolSpecification> all = specs("law_search", "todo_write", "dispatch_subtask");
        assertSame(all, router.visibleTools("conv-orch2", all));
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

    // ==== 手动选择（对话面板的 skill 选择器 / POST /chat 的 skillIds）====

    @Test
    @DisplayName("手动选择与自动命中取并集：两个 skill 同时生效，工具白名单也是并集")
    void manualSelectionUnionsWithAutoMatch() {
        // 输入只命中 skill-b（触发词「上市路径」），用户另外手动勾了 skill-a
        router.activateForTurn("conv-union", "帮我分析上市路径", null, List.of("skill-a"));

        List<SkillRouter.ActiveSkill> active = router.activeSkills("conv-union");
        assertEquals(2, active.size(), "手动选的和自动命中的都要生效：" + active);
        assertEquals("skill-a", active.get(0).definition().getId(), "手动选择排在前面");
        assertEquals(SkillRouter.SOURCE_MANUAL, active.get(0).source());
        assertEquals("skill-b", active.get(1).definition().getId());
        assertEquals(SkillRouter.SOURCE_AUTO, active.get(1).source());

        // 工具可见性必须同时含两边的白名单——只裁到其中一个 skill 的能力就等于
        // 另一半静默消失（skill 漏工具是排查成本最高的一类 bug）
        List<String> names = router.visibleTools("conv-union",
                        specs("law_search", "write_docx", "search_web", "doc_open_file", "read_document"))
                .stream().map(ToolSpecification::name).toList();
        assertTrue(names.contains("law_search"), "skill-a 的工具应可见");
        assertTrue(names.contains("search_web"), "skill-b 的工具应可见");
        assertFalse(names.contains("doc_open_file"), "两边白名单外的工具仍应被裁掉");
    }

    @Test
    @DisplayName("手动选择多枚：全部生效且顺序稳定")
    void multipleManualSelections() {
        router.activateForTurn("conv-multi", "随便问点别的", null, List.of("skill-a", "skill-b"));

        assertEquals(List.of("skill-a", "skill-b"),
                router.activeSkills("conv-multi").stream()
                        .map(a -> a.definition().getId()).toList());
        assertTrue(router.activeSkills("conv-multi").stream()
                .allMatch(a -> SkillRouter.SOURCE_MANUAL.equals(a.source())));
    }

    @Test
    @DisplayName("无效的手动 id 静默忽略：不存在 / 已停用的都跳过，其余照常生效")
    void invalidManualIdsAreIgnored() {
        registry.setActivationMode("skill-b", SkillRegistry.ActivationMode.DISABLED);
        router.activateForTurn("conv-bad", "随便问点别的", null,
                java.util.Arrays.asList("no-such-skill", null, "  ", "skill-b", "skill-a"));

        assertEquals(List.of("skill-a"),
                router.activeSkills("conv-bad").stream().map(a -> a.definition().getId()).toList(),
                "无效 id 不该让整轮失败，也不该让停用的 skill 复活");
    }

    @Test
    @DisplayName("「仅手动」的 skill 正是靠手动选择生效（自动匹配永远碰不到它）")
    void manualOnlySkillActivatedBySelection() {
        registry.setActivationMode("skill-a", SkillRegistry.ActivationMode.MANUAL);
        router.activateForTurn("conv-manual-only", "公司考虑IPO", null, List.of("skill-a"));

        assertEquals(List.of("skill-a"),
                router.activeSkills("conv-manual-only").stream()
                        .map(a -> a.definition().getId()).toList());
    }

    @Test
    @DisplayName("手动选择不持久化：下一轮不带 skillIds 就真的不带")
    void manualSelectionIsPerTurn() {
        router.activateForTurn("conv-turn", "随便问点别的", null, List.of("skill-a"));
        assertEquals(1, router.activeSkills("conv-turn").size());

        router.activateForTurn("conv-turn", "随便问点别的", null, null);
        assertTrue(router.activeSkills("conv-turn").isEmpty(), "上一轮的手动选择不该粘住");
    }

    @Test
    @DisplayName("同一个 skill 既被手动选中又命中触发词时只出现一次，且标记为 manual")
    void manualWinsSourceLabelOnOverlap() {
        router.activateForTurn("conv-dup", "公司考虑IPO", null, List.of("skill-a"));

        List<SkillRouter.ActiveSkill> active = router.activeSkills("conv-dup");
        assertEquals(1, active.size(), "同一个 skill 不该在 chip 行里出现两次");
        assertEquals(SkillRouter.SOURCE_MANUAL, active.get(0).source(),
                "用户看到的应该是「我选的」，不是「碰巧被关键词猜中」");
    }

    // ==== 应用语言（EN 版 PR5）====

    /** 写一个双语 skill（languages: zh-CN/en-US + triggers_en + prompt.en.md + output_en + name_en） */
    private void writeBilingualSkill(String id) throws IOException {
        Path dir = tempDir.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("skill.yml"), """
                id: %s
                name: 双语技能
                name_en: Bilingual Skill
                languages: [zh-CN, en-US]
                triggers: [双语触发词]
                triggers_en: [bilingual trigger phrase]
                output: 中文输出约定
                output_en: English output convention
                allowed_tools: [law_search]
                """.formatted(id));
        Files.writeString(dir.resolve("prompt.md"), "中文模板正文");
        Files.writeString(dir.resolve("prompt.en.md"), "English template body");
    }

    private SkillRouter englishRouter() {
        com.checkba.service.AppLanguageService en =
                org.mockito.Mockito.mock(com.checkba.service.AppLanguageService.class);
        org.mockito.Mockito.when(en.language()).thenReturn(com.checkba.service.AppLanguageService.EN_US);
        org.mockito.Mockito.when(en.isEnglish()).thenReturn(true);
        SkillRegistry enRegistry = new SkillRegistry(props, null, new PluginService(), en);
        enRegistry.init();
        return new SkillRouter(enRegistry, props, null, en);
    }

    @Test
    @DisplayName("英文模式：triggers_en 参与匹配；缺 languages 的存量 skill 整体隐藏")
    void englishModeMatchesEnTriggersAndHidesZhOnlySkills() throws IOException {
        writeBilingualSkill("skill-bi");
        SkillRouter enRouter = englishRouter();

        assertEquals("skill-bi",
                enRouter.match("please use the bilingual trigger phrase").orElseThrow().getId());
        // skill-a / skill-b 没有 languages 字段（= 只在 zh-CN 可用），英文模式下触发词命中也不生效
        assertEquals(Optional.empty(), enRouter.match("公司考虑IPO"));
        // 双语 skill 的中文触发词在英文模式下仍可命中（英文界面下输入中文是合法场景）
        assertEquals("skill-bi", enRouter.match("请用双语触发词处理").orElseThrow().getId());
    }

    @Test
    @DisplayName("中文模式：triggers_en 绝不参与匹配（中文匹配行为保持不变）")
    void chineseModeIgnoresEnTriggers() throws IOException {
        writeBilingualSkill("skill-bi2");
        registry.rescan();

        assertEquals(Optional.empty(), router.match("please use the bilingual trigger phrase"),
                "triggers_en 不应在 zh-CN 下参与匹配");
        assertEquals("skill-bi2", router.match("请用双语触发词处理").orElseThrow().getId());
    }

    @Test
    @DisplayName("英文模式注入块：英文前缀 + prompt.en.md + output_en + name_en；中文模式保持原样")
    void promptInjectionSwitchesByLanguage() throws IOException {
        writeBilingualSkill("skill-bi3");
        SkillRouter enRouter = englishRouter();
        registry.rescan();

        SkillDefinition viaEn = enRouter.match("bilingual trigger phrase").orElseThrow();
        String enBlock = enRouter.promptInjectionFor(viaEn);
        assertTrue(enBlock.contains("# Active Skill: Bilingual Skill"), "英文块应用 name_en");
        assertTrue(enBlock.contains("matched the skill"), "前缀应为英文");
        assertTrue(enBlock.contains("English template body"), "应注入 prompt.en.md 模板");
        assertTrue(enBlock.contains("## Output Conventions"), "输出约定标题应为英文");
        assertTrue(enBlock.contains("English output convention"), "应注入 output_en");
        assertFalse(enBlock.contains("中文模板正文"), "英文块不应夹带中文模板");

        String zhBlock = router.promptInjectionFor(registry.getSkill("skill-bi3").orElseThrow());
        assertTrue(zhBlock.contains("用户本轮请求命中了技能「双语技能」"), "中文前缀保持原样");
        assertTrue(zhBlock.contains("中文模板正文"), "中文模式仍注入 prompt.md");
        assertTrue(zhBlock.contains("## 输出约定"), "中文输出约定标题保持原样");
    }

    @Test
    @DisplayName("展示名按应用语言解析：en 优先 name_en，缺省回退 name / id（skill_update 载荷用的就是它）")
    void displayNameFollowsAppLanguage() throws IOException {
        writeBilingualSkill("skill-bi4");
        registry.rescan();

        assertEquals("双语技能", router.displayName(registry.getSkill("skill-bi4").orElseThrow()));
        assertEquals("Bilingual Skill",
                englishRouter().displayName(registry.getSkill("skill-bi4").orElseThrow()));
        // skill-a 没有 name_en，英文下回退中文名（可用胜于空白，与注入块同口径）
        assertEquals("skill-a", englishRouter().displayName(registry.getSkill("skill-a").orElseThrow()));
    }

    // ==== activeByConversation 的无界增长 ====
    // 背景：只有"这一轮没有任何 skill 生效"才会从登记簿里 remove；一个会话只要最后一轮命中过
    // skill，条目就永久留着，进程越久攒得越多。修法是给每条记录带上激活时刻，配一个每日一次的
    // 惰性过期扫描（对齐 TodoListService.purgeStaleLists 的既有先例）。

    @Test
    @DisplayName("修复：超过过期窗口未再激活的会话，purgeStaleActivations 应把登记簿条目清掉")
    void purgeStaleActivationsRemovesOldEntries() {
        long[] now = {1_000_000L};
        router.setClockMillis(() -> now[0]);

        router.activateForTurn("conv-old", "公司考虑IPO");
        assertEquals(1, router.activeByConversationSize());

        // 推进到超过 24 小时过期窗口之后
        now[0] += java.time.Duration.ofHours(25).toMillis();
        router.purgeStaleActivations();

        assertEquals(0, router.activeByConversationSize(),
                "超过过期窗口未再激活的会话条目应被清掉，不能无限期占着登记簿");
        assertEquals(List.of(), router.activeSkills("conv-old"), "过期后该会话不应再有生效 skill");
    }

    @Test
    @DisplayName("未超过过期窗口的会话不受影响：purgeStaleActivations 不会误删刚激活的记录")
    void purgeStaleActivationsKeepsFreshEntries() {
        long[] now = {1_000_000L};
        router.setClockMillis(() -> now[0]);

        router.activateForTurn("conv-fresh", "公司考虑IPO");

        now[0] += java.time.Duration.ofHours(1).toMillis();
        router.purgeStaleActivations();

        assertEquals(1, router.activeByConversationSize(), "未超过过期窗口的记录不该被误删");
        assertEquals(1, router.activeSkills("conv-fresh").size());
    }
}
