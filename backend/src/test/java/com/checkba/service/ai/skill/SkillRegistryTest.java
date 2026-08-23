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
        return newRegistry(skillsDir, null, pluginService);
    }

    private SkillRegistry newRegistry(Path skillsDir, Path builtinDir, PluginService pluginService) {
        SkillProperties props = new SkillProperties();
        props.setDir(skillsDir.toString());
        props.setBuiltinDir(builtinDir == null ? "" : builtinDir.toString());
        SkillRegistry registry = new SkillRegistry(props, null, pluginService, null);
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
        assertTrue(skill.isEnabledByDefault(), "enabled_by_default 缺省应为 true");
    }

    @Test
    @DisplayName("解析 author/author_url/version/license/credits/enabled_by_default")
    void parsesAuthorshipAndDefaultEnablementFields() throws IOException {
        writeSkill(tempDir.resolve("authored-skill"), "authored-skill", """
                author: AI WorkDeck
                author_url: https://www.aiworkdeck.com
                version: 1.0.2
                license: MIT
                credits:
                  - "引擎 xxx by 某人（MIT）"
                enabled_by_default: false
                """);
        SkillRegistry registry = newRegistry(tempDir, new PluginService());
        SkillDefinition skill = registry.getSkill("authored-skill").orElseThrow();

        assertEquals("AI WorkDeck", skill.getAuthor());
        assertEquals("https://www.aiworkdeck.com", skill.getAuthorUrl());
        assertEquals("1.0.2", skill.getVersion());
        assertEquals("MIT", skill.getLicense());
        assertEquals(List.of("引擎 xxx by 某人（MIT）"), skill.getCredits());
        assertFalse(skill.isEnabledByDefault());
        assertFalse(registry.isEnabled("authored-skill"), "enabled_by_default:false 首次扫描即默认禁用");
    }

    @Test
    @DisplayName("解析 requires_pack（原生资源包依赖，可选字段）")
    void parsesRequiresPack() throws IOException {
        writeSkill(tempDir.resolve("packed-skill"), "packed-skill", "requires_pack: litigation-visual\n");
        writeSkill(tempDir.resolve("plain-skill"), "plain-skill", null);
        SkillRegistry registry = newRegistry(tempDir, new PluginService());

        assertEquals("litigation-visual", registry.getSkill("packed-skill").orElseThrow().getRequiresPack());
        assertNull(registry.getSkill("plain-skill").orElseThrow().getRequiresPack(), "缺省即不依赖资源包");
    }

    /**
     * 极简内存版 SystemSettingService：只覆盖 get/set，用来验证禁用/种子名单能跨"重启"
     * （新的 SkillRegistry 实例、同一份存储）持久化。父类真正依赖的仓储用不到，传 null。
     */
    private static class InMemorySystemSettingService extends com.checkba.service.SystemSettingService {
        private final java.util.Map<String, String> store = new java.util.HashMap<>();
        InMemorySystemSettingService() { super(null); }
        @Override public String get(String key, String defaultValue) { return store.getOrDefault(key, defaultValue); }
        @Override public void set(String key, String value) { store.put(key, value); }
    }

    @Test
    @DisplayName("enabled_by_default:false：首次扫描默认禁用一次；用户启用后重启（新实例重新扫描）仍保持启用")
    void enabledByDefaultFalseSeedsOnceThenRespectsUserChoiceAcrossRestart() throws IOException {
        writeSkill(tempDir.resolve("opt-in-skill"), "opt-in-skill", "enabled_by_default: false\n");
        InMemorySystemSettingService settings = new InMemorySystemSettingService();

        SkillProperties props1 = new SkillProperties();
        props1.setDir(tempDir.toString());
        SkillRegistry registry1 = new SkillRegistry(props1, settings, new PluginService(), null);
        registry1.init();
        assertFalse(registry1.isEnabled("opt-in-skill"), "enabled_by_default:false 首次扫描应默认禁用");

        registry1.setEnabled("opt-in-skill", true);
        assertTrue(registry1.isEnabled("opt-in-skill"));

        // 模拟重启：新实例、同一份持久化存储、重新扫描同一目录
        SkillProperties props2 = new SkillProperties();
        props2.setDir(tempDir.toString());
        SkillRegistry registry2 = new SkillRegistry(props2, settings, new PluginService(), null);
        registry2.init();

        assertTrue(registry2.isEnabled("opt-in-skill"),
                "用户启用过的 skill，重启重新扫描后不该被「首次默认关闭」的种子逻辑打回去");

        // 用户显式关闭后再重启：一样不受种子逻辑影响（本来就该是关的）
        registry2.setEnabled("opt-in-skill", false);
        SkillProperties props3 = new SkillProperties();
        props3.setDir(tempDir.toString());
        SkillRegistry registry3 = new SkillRegistry(props3, settings, new PluginService(), null);
        registry3.init();
        assertFalse(registry3.isEnabled("opt-in-skill"));
    }

    @Test
    @DisplayName("「语音」合并插件状态收敛：任一成员启用即全部启用；全关保持全关；缺成员不收敛")
    void convergesVoiceMergedSkillPair() throws IOException {
        // 存量分裂态：语音合成启用、会议录音被用户/旧默认关了 → 收敛为都启用
        writeSkill(tempDir.resolve("text-to-speech"), "text-to-speech", null);
        writeSkill(tempDir.resolve("meeting-recorder"), "meeting-recorder", null);
        InMemorySystemSettingService settings = new InMemorySystemSettingService();
        settings.set(SkillRegistry.DISABLED_KEY, "[\"meeting-recorder\"]");

        SkillProperties props = new SkillProperties();
        props.setDir(tempDir.toString());
        SkillRegistry registry = new SkillRegistry(props, settings, new PluginService(), null);
        registry.init();
        assertTrue(registry.isEnabled("text-to-speech"));
        assertTrue(registry.isEnabled("meeting-recorder"), "任一成员启用即全部启用（启停一体）");

        // 用户把两个都关掉（前端开关一次翻两个成员）：重启后保持全关，不被收敛翻回来
        registry.setEnabled("text-to-speech", false);
        registry.setEnabled("meeting-recorder", false);
        SkillProperties props2 = new SkillProperties();
        props2.setDir(tempDir.toString());
        SkillRegistry registry2 = new SkillRegistry(props2, settings, new PluginService(), null);
        registry2.init();
        assertFalse(registry2.isEnabled("text-to-speech"), "全关是用户的明确选择，收敛不该打回去");
        assertFalse(registry2.isEnabled("meeting-recorder"));
    }

    @Test
    @DisplayName("「语音」收敛只在两个成员都在场时生效：缺一个就不动禁用名单")
    void voiceConvergenceRequiresBothMembersPresent() throws IOException {
        writeSkill(tempDir.resolve("text-to-speech"), "text-to-speech", null);
        InMemorySystemSettingService settings = new InMemorySystemSettingService();
        settings.set(SkillRegistry.DISABLED_KEY, "[\"meeting-recorder\"]");

        SkillProperties props = new SkillProperties();
        props.setDir(tempDir.toString());
        SkillRegistry registry = new SkillRegistry(props, settings, new PluginService(), null);
        registry.init();
        assertEquals("[\"meeting-recorder\"]", settings.get(SkillRegistry.DISABLED_KEY, ""),
                "meeting-recorder 目录不在场时不该动持久化的禁用名单");
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
    @DisplayName("修复：skill.yml 编码错误时，getLoadErrors() 要能看出「解析出错」而不是「没有这个 skill」")
    void encodingFailureIsDistinguishableFromMissingSkill() throws IOException {
        writeSkill(tempDir.resolve("good-skill"), "good-skill", null);

        // 非 UTF-8 字节（GBK 编码的中文）：Files.readString(..., UTF_8) 会抛 MalformedInputException
        Path badEncoding = tempDir.resolve("bad-encoding");
        Files.createDirectories(badEncoding);
        Files.write(badEncoding.resolve("skill.yml"),
                "id: bad-encoding\nname: 中文技能名\ntriggers: [x]\nprompt: prompt.md\n"
                        .getBytes(java.nio.charset.Charset.forName("GBK")));
        Files.writeString(badEncoding.resolve("prompt.md"), "p");

        SkillRegistry registry = newRegistry(tempDir, new PluginService());

        // 与"坏 skill 跳过不阻断"一致：解析失败的 skill 不出现在结果里，好 skill 不受影响
        assertEquals(1, registry.getSkills().size());
        assertTrue(registry.getSkill("bad-encoding").isEmpty(), "编码错误的 skill 不应该注册成功");

        // 新增的区分信号：loadErrors 里要能查到这个目录，且原因点明是编码问题
        assertTrue(registry.getLoadErrors().containsKey("bad-encoding"),
                "解析失败必须在 loadErrors 里留痕，不能和「压根没有这个目录」一样悄无声息");
        assertTrue(registry.getLoadErrors().get("bad-encoding").contains("编码"),
                "原因应该点明是编码问题，而不是一句笼统的失败: " + registry.getLoadErrors().get("bad-encoding"));
        // 正常注册成功的目录不应该出现在 loadErrors 里
        assertFalse(registry.getLoadErrors().containsKey("good-skill"));
    }

    @Test
    @DisplayName("修复：enabled_by_default 写成带引号的真值（\"yes\"）不能被静默当成 false")
    void quotedTruthyEnabledByDefaultIsNotCoercedToFalse() throws IOException {
        writeSkill(tempDir.resolve("quoted-yes"), "quoted-yes", "enabled_by_default: \"yes\"\n");
        SkillRegistry registry = newRegistry(tempDir, new PluginService());

        SkillDefinition skill = registry.getSkill("quoted-yes").orElseThrow();
        assertTrue(skill.isEnabledByDefault(),
                "带引号的真值 \"yes\" 应该被识别为启用，不能被 Boolean.parseBoolean 悄悄当成 false");
        assertTrue(registry.isEnabled("quoted-yes"), "不该被首次扫描的种子逻辑默认关闭");
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

    /**
     * 真机复现（2026-08-23，尽调插件上架当天）：插件携带的 skill 写着 enabled_by_default:false
     * （为的是插件没装前别出现），用户在广场装好插件、点了启用，工具注册上了，但 skill 仍是禁用态
     * ——对话里说「尽调报告」永远不命中。用户视角是第三个看不见的开关。
     * 规则：启用插件 = 启用它携带的全部 skill；别的插件 / 内置 skill 一个都不碰。
     */
    @Test
    @DisplayName("修复：enableSkillsFromPlugin 只把该插件携带的 skill 翻成启用")
    void enablingPluginEnablesItsCarriedSkills() throws IOException {
        Path pluginsDir = tempDir.resolve("plugins");
        Path pluginDir = pluginsDir.resolve("my-plugin");
        Path otherDir = pluginsDir.resolve("other-plugin");
        Files.createDirectories(pluginDir);
        Files.createDirectories(otherDir);
        Files.writeString(pluginDir.resolve("manifest.json"), """
                { "id": "my-plugin", "name": "带技能的插件", "version": "1.0.0", "skills": ["my-skill"] }
                """);
        Files.writeString(otherDir.resolve("manifest.json"), """
                { "id": "other-plugin", "name": "别的插件", "version": "1.0.0", "skills": ["other-skill"] }
                """);
        writeSkill(pluginDir.resolve("my-skill"), "plugin-skill", "enabled_by_default: false\n");
        writeSkill(otherDir.resolve("other-skill"), "other-skill", "enabled_by_default: false\n");
        writeSkill(tempDir.resolve("builtin-off"), "builtin-off", "enabled_by_default: false\n");

        PluginService pluginService = new PluginService(null, pluginsDir.toString());
        pluginService.init();
        SkillRegistry registry = newRegistry(tempDir, pluginService);
        assertFalse(registry.isEnabled("plugin-skill"), "种子化后默认禁用");

        List<String> flipped = registry.enableSkillsFromPlugin("my-plugin");

        assertEquals(List.of("plugin-skill"), flipped);
        assertTrue(registry.isEnabled("plugin-skill"));
        assertFalse(registry.isEnabled("other-skill"), "别的插件的 skill 不动");
        assertFalse(registry.isEnabled("builtin-off"), "内置 skill 不动");
        assertEquals(List.of(), registry.enableSkillsFromPlugin("my-plugin"), "已启用的不重复报告");
        assertEquals(List.of(), registry.enableSkillsFromPlugin("no-such-plugin"));
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
    @DisplayName("生效方式三档：默认 auto；manual 不影响 available；disabled 优先于 manual")
    void activationModeThreeStates() throws IOException {
        writeSkill(tempDir.resolve("test-skill"), "test-skill", null);
        SkillRegistry registry = newRegistry(tempDir, new PluginService());
        SkillDefinition skill = registry.getSkill("test-skill").orElseThrow();

        assertEquals(SkillRegistry.ActivationMode.AUTO, registry.activationMode("test-skill"));
        assertFalse(registry.isManual("test-skill"));

        // manual 只挡自动匹配，skill 本身仍可用（供钉选）
        registry.setActivationMode("test-skill", SkillRegistry.ActivationMode.MANUAL);
        assertEquals(SkillRegistry.ActivationMode.MANUAL, registry.activationMode("test-skill"));
        assertTrue(registry.isManual("test-skill"));
        assertTrue(registry.isAvailable(skill));

        // disabled 优先呈现，且清掉 manual 标记
        registry.setActivationMode("test-skill", SkillRegistry.ActivationMode.DISABLED);
        assertEquals(SkillRegistry.ActivationMode.DISABLED, registry.activationMode("test-skill"));
        assertFalse(registry.isManual("test-skill"));
        assertFalse(registry.isAvailable(skill));

        registry.setActivationMode("test-skill", SkillRegistry.ActivationMode.AUTO);
        assertEquals(SkillRegistry.ActivationMode.AUTO, registry.activationMode("test-skill"));
        assertTrue(registry.isAvailable(skill));

        assertThrows(IllegalArgumentException.class,
                () -> registry.setActivationMode("nope", SkillRegistry.ActivationMode.MANUAL));
    }

    @Test
    @DisplayName("ActivationMode.parse 容错：大小写不敏感，非法值返回 empty")
    void activationModeParse() {
        assertEquals(Optional.of(SkillRegistry.ActivationMode.MANUAL),
                SkillRegistry.ActivationMode.parse("manual"));
        assertEquals(Optional.of(SkillRegistry.ActivationMode.AUTO),
                SkillRegistry.ActivationMode.parse(" Auto "));
        assertEquals(Optional.empty(), SkillRegistry.ActivationMode.parse("bogus"));
        assertEquals(Optional.empty(), SkillRegistry.ActivationMode.parse(null));
    }

    @Test
    @DisplayName("skills 目录不存在时正常启动（0 个 skill）")
    void missingSkillsDirIsFine() {
        SkillRegistry registry = newRegistry(tempDir.resolve("does-not-exist"), new PluginService());
        assertEquals(0, registry.getSkills().size());
        assertEquals(Optional.empty(), registry.getSkill("anything"));
    }

    // ==================== 只读内置目录（随发行版分发） ====================
    //
    // 背景：v0.11.1 及以前 backend/skills/ 从未进过安装包，而打包态后端 cwd 是用户
    // 数据目录，ai.skills.dir 的相对值 'skills' 解析到一个空目录——两个内置 skill
    // 在发行版里实际上根本不存在，只在 dev 生效。这组测试钉住修复后的双目录语义。

    @Test
    @DisplayName("内置只读目录里的 skill 会被扫到（发行版里 backend/skills 的落点）")
    void scansBuiltinDir() throws IOException {
        Path writable = tempDir.resolve("userdata-skills");
        Path builtin = tempDir.resolve("resources-skills");
        Files.createDirectories(writable);
        writeSkill(builtin.resolve("litigation-visual"), "litigation-visual", null);

        SkillRegistry registry = newRegistry(writable, builtin, new PluginService());

        assertTrue(registry.getSkill("litigation-visual").isPresent(),
                "内置目录里的 skill 应被注册");
    }

    @Test
    @DisplayName("两个目录的 skill 合并，互不遮挡")
    void mergesBothDirs() throws IOException {
        Path writable = tempDir.resolve("w");
        Path builtin = tempDir.resolve("b");
        writeSkill(writable.resolve("from-market"), "from-market", null);
        writeSkill(builtin.resolve("built-in"), "built-in", null);

        SkillRegistry registry = newRegistry(writable, builtin, new PluginService());

        assertEquals(2, registry.getSkills().size());
        assertTrue(registry.getSkill("from-market").isPresent());
        assertTrue(registry.getSkill("built-in").isPresent());
    }

    @Test
    @DisplayName("同 id 时可写目录赢——内置 skill 能走广场热修，不必等客户端发版")
    void writableDirWinsOnIdClash() throws IOException {
        Path writable = tempDir.resolve("w");
        Path builtin = tempDir.resolve("b");
        // 用 output 区分两份（name 已在模板里，再写一遍就是重复 YAML 键）
        writeSkill(writable.resolve("dup"), "dup", "output: 广场热修版\n");
        writeSkill(builtin.resolve("dup"), "dup", "output: 随包内置版\n");

        SkillRegistry registry = newRegistry(writable, builtin, new PluginService());

        assertEquals(1, registry.getSkills().size(), "同 id 只应留一个");
        assertEquals("广场热修版", registry.getSkill("dup").orElseThrow().getOutput(),
                "可写目录（广场安装）应覆盖随包内置的同 id skill");
    }

    @Test
    @DisplayName("两处配到同一个目录时不重复扫（相对路径 vs 绝对路径也认得出）")
    void sameDirConfiguredTwiceIsScannedOnce() throws IOException {
        Path dir = tempDir.resolve("skills");
        writeSkill(dir.resolve("only-one"), "only-one", null);

        SkillProperties props = new SkillProperties();
        props.setDir(dir.toString());
        // 同一个目录，换一种等价写法（多绕一层 . ）
        props.setBuiltinDir(dir.resolve(".").toString());
        SkillRegistry registry = new SkillRegistry(props, null, new PluginService(), null);
        registry.init();

        assertEquals(1, registry.getSkills().size());
    }

    @Test
    @DisplayName("内置目录留空 = dev 态，行为与改造前完全一致")
    void blankBuiltinDirKeepsLegacyBehaviour() throws IOException {
        Path dir = tempDir.resolve("skills");
        writeSkill(dir.resolve("a"), "a", null);

        SkillRegistry registry = newRegistry(dir, null, new PluginService());

        assertEquals(1, registry.getSkills().size());
    }

    @Test
    @DisplayName("内置目录配了但不存在时不阻断启动")
    void missingBuiltinDirIsFine() throws IOException {
        Path dir = tempDir.resolve("skills");
        writeSkill(dir.resolve("a"), "a", null);

        SkillRegistry registry = newRegistry(dir, tempDir.resolve("nope"), new PluginService());

        assertEquals(1, registry.getSkills().size());
    }

    /**
     * 读 DISABLED_KEY 时可以卡住的设置服务：用来把 loadDisabledState 停在
     * 「名单还没读回来」的那一瞬间，验证并发的无锁读方不会读到空名单。
     */
    private static class GatedSystemSettingService extends InMemorySystemSettingService {
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean(false);

        @Override
        public String get(String key, String defaultValue) {
            if (SkillRegistry.DISABLED_KEY.equals(key) && armed.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.get(key, defaultValue);
        }
    }

    @Test
    @DisplayName("并发：重载启停名单期间，无锁读方不该读到空名单（被停用的 skill 短暂变成启用）")
    void disabledStateIsNotTornWhileReloading() throws Exception {
        writeSkill(tempDir.resolve("test-skill"), "test-skill", null);
        GatedSystemSettingService settings = new GatedSystemSettingService();

        SkillProperties props = new SkillProperties();
        props.setDir(tempDir.toString());
        props.setDisabledCacheTtlMs(600_000); // 读方 TTL 不到期，走的就是无锁快路径
        SkillRegistry registry = new SkillRegistry(props, settings, new PluginService(), null);
        registry.init();
        registry.setEnabled("test-skill", false);
        assertFalse(registry.isEnabled("test-skill"));

        settings.armed.set(true);
        Thread reloader = new Thread(registry::rescan, "skill-rescan");
        reloader.start();
        try {
            assertTrue(settings.entered.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "重载线程应停在读禁用名单这一步");
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(5),
                    () -> assertFalse(registry.isEnabled("test-skill"),
                            "重载途中被停用的 skill 不该被读成启用"));
        } finally {
            settings.release.countDown();
            reloader.join(10_000);
        }

        assertFalse(registry.isEnabled("test-skill"), "重载结束后仍是停用");
    }
}
