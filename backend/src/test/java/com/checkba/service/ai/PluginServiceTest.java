package com.checkba.service.ai;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PluginService 测试：manifest 规范 v1 解析 + 插件启停状态持久化 + 重新扫描
 */
class PluginServiceTest {

    @TempDir
    Path pluginsDir;

    /** 模拟 system_setting 表的内存 KV 存储 */
    private final Map<String, String> settingStore = new HashMap<>();

    private SystemSettingService systemSettingService;
    private PluginService service;

    @BeforeEach
    void setUp() {
        systemSettingService = mock(SystemSettingService.class);
        when(systemSettingService.get(anyString(), anyString())).thenAnswer(inv ->
                settingStore.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        doAnswer(inv -> settingStore.put(inv.getArgument(0), inv.getArgument(1)))
                .when(systemSettingService).set(anyString(), anyString());

        service = new PluginService(systemSettingService, pluginsDir.toString());
    }

    private void writeManifest(String dirName, String json) throws IOException {
        Path dir = pluginsDir.resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), json, StandardCharsets.UTF_8);
    }

    private static final String FULL_MANIFEST = """
            {
              "id": "hello-plugin",
              "name": "Hello 示例插件",
              "version": "1.0.0",
              "description": "示例插件",
              "icon": "🔌",
              "author": "AI WorkDeck",
              "homepage": "https://example.com/hello-plugin",
              "permissions": ["file_read", "network"],
              "tools": [
                {"name": "helloEcho", "description": "原样回显输入文本"},
                {"name": "helloWordCount", "description": "统计字数"}
              ],
              "backendJars": ["hello-plugin-1.0.0.jar"]
            }
            """;

    // ==== manifest 解析 ====

    @Test
    @DisplayName("解析完整 manifest v1：permissions/tools/author/homepage 全部字段")
    void parsesFullManifest() throws IOException {
        writeManifest("hello-plugin", FULL_MANIFEST);
        service.init();

        assertEquals(1, service.getPlugins().size());
        PluginService.PluginMetadata meta = service.getPlugins().get(0);
        assertEquals("hello-plugin", meta.getId());
        assertEquals("Hello 示例插件", meta.getName());
        assertEquals("1.0.0", meta.getVersion());
        assertEquals("AI WorkDeck", meta.getAuthor());
        assertEquals("https://example.com/hello-plugin", meta.getHomepage());
        assertEquals(List.of("file_read", "network"), meta.getPermissions());
        assertEquals(2, meta.getTools().size());
        assertEquals("helloEcho", meta.getTools().get(0).getName());
        assertEquals("原样回显输入文本", meta.getTools().get(0).getDescription());
    }

    @Test
    @DisplayName("manifest.guide（规范 v2.5）：intro/steps/quickActions 原样解析，quickActions 缺 label 或 prompt 的条目丢弃")
    void parsesGuideBlock() throws IOException {
        writeManifest("guided", """
            {"id": "guided", "name": "带引导的插件",
             "guide": {"intro": "三步上手", "steps": ["第一步", "第二步"],
                       "quickActions": [
                         {"label": "整理底稿", "prompt": "请整理底稿", "hint": "先选根文件夹"},
                         {"label": "缺 prompt 的", "hint": "x"},
                         {"prompt": "缺 label 的"}
                       ]}}
            """);
        service.init();

        PluginService.PluginMetadata meta = service.getPlugins().get(0);
        assertNotNull(meta.getGuide());
        assertEquals("三步上手", meta.getGuide().getIntro());
        assertEquals(List.of("第一步", "第二步"), meta.getGuide().getSteps());
        assertEquals(1, meta.getGuide().getQuickActions().size());
        assertEquals("整理底稿", meta.getGuide().getQuickActions().get(0).getLabel());
        assertEquals("请整理底稿", meta.getGuide().getQuickActions().get(0).getPrompt());
        assertEquals("先选根文件夹", meta.getGuide().getQuickActions().get(0).getHint());
    }

    @Test
    @DisplayName("manifest 没写 guide 时为 null（老插件不受影响）")
    void guideAbsentIsNull() throws IOException {
        writeManifest("mini2", "{\"id\": \"mini2\", \"name\": \"极简插件\"}");
        service.init();
        assertNull(service.getPlugins().get(0).getGuide());
    }

    @Test
    @DisplayName("最小 manifest（仅 id/name）可加载，可选字段为空")
    void parsesMinimalManifest() throws IOException {
        writeManifest("mini", "{\"id\": \"mini\", \"name\": \"极简插件\"}");
        service.init();

        assertEquals(1, service.getPlugins().size());
        PluginService.PluginMetadata meta = service.getPlugins().get(0);
        assertEquals("mini", meta.getId());
        assertNull(meta.getPermissions());
        assertNull(meta.getTools());
        assertTrue(service.isEnabled("mini"), "默认应为启用");
    }

    @Test
    @DisplayName("缺少 id 的 manifest 被跳过")
    void skipsManifestWithoutId() throws IOException {
        writeManifest("no-id", "{\"name\": \"没有 id 的插件\"}");
        service.init();

        assertTrue(service.getPlugins().isEmpty());
    }

    @Test
    @DisplayName("非法 JSON 只影响自身，其他插件正常加载")
    void brokenManifestDoesNotBlockOthers() throws IOException {
        writeManifest("broken", "{ not valid json !!!");
        writeManifest("hello-plugin", FULL_MANIFEST);
        service.init();

        assertEquals(1, service.getPlugins().size());
        assertEquals("hello-plugin", service.getPlugins().get(0).getId());
    }

    @Test
    @DisplayName("重复 id 的插件目录只加载第一个")
    void skipsDuplicatePluginId() throws IOException {
        writeManifest("dir-a", "{\"id\": \"dup\", \"name\": \"A\"}");
        writeManifest("dir-b", "{\"id\": \"dup\", \"name\": \"B\"}");
        service.init();

        assertEquals(1, service.getPlugins().size());
    }

    @Test
    @DisplayName("未知权限值不阻断加载（向前兼容）")
    void unknownPermissionIsTolerated() throws IOException {
        writeManifest("p", "{\"id\": \"p\", \"name\": \"P\", \"permissions\": [\"file_read\", \"future_cap\"]}");
        service.init();

        assertEquals(1, service.getPlugins().size());
        assertEquals(List.of("file_read", "future_cap"), service.getPlugins().get(0).getPermissions());
    }

    // ==== 启停状态 ====

    @Test
    @DisplayName("setEnabled(false/true) 往返生效并持久化到配置表")
    void setEnabledRoundTripPersists() throws IOException {
        writeManifest("hello-plugin", FULL_MANIFEST);
        service.init();

        assertTrue(service.isEnabled("hello-plugin"));

        service.setEnabled("hello-plugin", false);
        assertFalse(service.isEnabled("hello-plugin"));
        assertTrue(settingStore.get(PluginService.DISABLED_KEY).contains("hello-plugin"),
                "禁用名单应写入 system_setting");

        service.setEnabled("hello-plugin", true);
        assertTrue(service.isEnabled("hello-plugin"));
        assertFalse(settingStore.get(PluginService.DISABLED_KEY).contains("hello-plugin"));
    }

    @Test
    @DisplayName("启停状态在重启（重新 init）后从配置表恢复")
    void disabledStateSurvivesRestart() throws IOException {
        writeManifest("hello-plugin", FULL_MANIFEST);
        settingStore.put(PluginService.DISABLED_KEY, "[\"hello-plugin\"]");

        service.init();

        assertFalse(service.isEnabled("hello-plugin"));
        assertTrue(service.isEnabled("some-other-plugin"), "不在名单中的默认启用");
    }

    @Test
    @DisplayName("对未知插件 setEnabled 抛 IllegalArgumentException")
    void setEnabledUnknownPluginThrows() {
        service.init();
        assertThrows(IllegalArgumentException.class, () -> service.setEnabled("ghost", false));
        verify(systemSettingService, never()).set(eq(PluginService.DISABLED_KEY), anyString());
    }

    @Test
    @DisplayName("配置表中的禁用名单损坏时降级为全部启用")
    void corruptDisabledListFallsBackToAllEnabled() throws IOException {
        writeManifest("hello-plugin", FULL_MANIFEST);
        settingStore.put(PluginService.DISABLED_KEY, "not-a-json-array");

        service.init();

        assertTrue(service.isEnabled("hello-plugin"));
    }

    // ==== 规范 v2：tools[].permissions 与权限校验 ====

    private static final String V2_MANIFEST = """
            {
              "id": "v2-plugin",
              "name": "V2 插件",
              "permissions": ["file_read"],
              "tools": [
                {"name": "readTool", "description": "读文件", "permissions": ["file_read"]},
                {"name": "netTool", "description": "网络请求", "permissions": ["network", "file_write"]},
                {"name": "plainTool", "description": "无权限需求"}
              ]
            }
            """;

    @Test
    @DisplayName("v2：解析 tools[].permissions 字段")
    void parsesToolLevelPermissions() throws IOException {
        writeManifest("v2-plugin", V2_MANIFEST);
        service.init();

        PluginService.PluginMetadata meta = service.getPlugins().get(0);
        assertEquals(List.of("file_read"), meta.getTools().get(0).getPermissions());
        assertEquals(List.of("network", "file_write"), meta.getTools().get(1).getPermissions());
        assertNull(meta.getTools().get(2).getPermissions());
    }

    @Test
    @DisplayName("v2：missingPermissionsForTool——覆盖/缺失/无需求/内置工具四种路径")
    void missingPermissionsForToolPaths() throws IOException {
        writeManifest("v2-plugin", V2_MANIFEST);
        service.init();
        // 手工建立工具归属映射（无 JAR 时 loadPlugins 不注册工具）
        service.registerToolObject(new Object() {
            @dev.langchain4j.agent.tool.Tool("read a file")
            public String readTool(@dev.langchain4j.agent.tool.P("path") String path) { return path; }
            @dev.langchain4j.agent.tool.Tool("call network")
            public String netTool(@dev.langchain4j.agent.tool.P("url") String url) { return url; }
            @dev.langchain4j.agent.tool.Tool("plain")
            public String plainTool(@dev.langchain4j.agent.tool.P("x") String x) { return x; }
        }, "v2-plugin");

        assertTrue(service.missingPermissionsForTool("readTool").isEmpty(), "所需 file_read 已声明");
        assertEquals(List.of("network", "file_write"), service.missingPermissionsForTool("netTool"),
                "network/file_write 未在插件 permissions 中声明");
        assertTrue(service.missingPermissionsForTool("plainTool").isEmpty(), "未声明所需权限的工具恒通过");
        assertTrue(service.missingPermissionsForTool("builtin_tool").isEmpty(), "内置工具不参与校验");
    }

    // ==== 启停缓存 TTL ====

    @Test
    @DisplayName("TTL 过期后 isEnabled 从配置表重读（外部改库可收敛）")
    void ttlRefreshPicksUpExternalChange() throws IOException {
        writeManifest("hello-plugin", FULL_MANIFEST);
        service.init();
        assertTrue(service.isEnabled("hello-plugin"));

        // 模拟外部直接改库（不经过 setEnabled）
        settingStore.put(PluginService.DISABLED_KEY, "[\"hello-plugin\"]");
        service.disabledCacheTtlMs = 0;

        assertFalse(service.isEnabled("hello-plugin"), "TTL 过期后应从配置表重读");
    }

    // ==== 重新扫描 ====

    @Test
    @DisplayName("rescan 发现新装插件并重读启停状态")
    void rescanPicksUpNewPlugins() throws IOException {
        writeManifest("hello-plugin", FULL_MANIFEST);
        service.init();
        assertEquals(1, service.getPlugins().size());

        writeManifest("second", "{\"id\": \"second\", \"name\": \"第二个插件\"}");
        settingStore.put(PluginService.DISABLED_KEY, "[\"second\"]");
        service.rescan();

        assertEquals(2, service.getPlugins().size());
        assertFalse(service.isEnabled("second"), "rescan 应重读禁用名单");
        assertTrue(service.isEnabled("hello-plugin"));
    }

    @Test
    @DisplayName("rescan 使 ToolRegistry 的插件工具缓存失效（否则更新/卸载后旧 bean 仍被分发）")
    void rescanInvalidatesToolRegistryPluginCache() throws IOException {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        service.setToolRegistry(toolRegistry);

        writeManifest("hello-plugin", FULL_MANIFEST);
        service.init();
        verifyNoInteractions(toolRegistry);

        service.rescan();

        verify(toolRegistry).invalidatePluginToolCache();
    }

    /**
     * 真机复现（2026-08-23，广场上架尽调插件当天）：从插件广场装完 due-diligence、
     * 在插件页启用之后，10 个 dd_* 工具都注册上了，但它携带的「尽调报告」skill
     * 在 /api/skills/list 里根本不出现——手工 POST /api/skills/rescan 才冒出来。
     *
     * <p>根因是两套注册表各扫各的：插件携带的 skill 目录由 PluginService.loadPlugins()
     * 扫出来放进 pluginSkillDirs，而 SkillRegistry 只在自己 @PostConstruct 和 rescan()
     * 时来拉一次。装插件/启用插件只触发了插件侧 rescan，skill 侧不动，于是要等下次
     * 重启后端才生效。用户看到的形态是「装完开了，但让它干活它不认」。
     */
    @Test
    @DisplayName("修复：rescan 之后插件携带的 skill 也要立刻重扫（不必重启后端）")
    void rescanAlsoRescansPluginSkills() throws IOException {
        com.checkba.service.ai.skill.SkillRegistry skillRegistry =
                mock(com.checkba.service.ai.skill.SkillRegistry.class);
        service.setSkillRegistry(skillRegistry);
        writeManifest("hello-plugin", FULL_MANIFEST);

        service.rescan();

        verify(skillRegistry).rescan();
    }

    @Test
    @DisplayName("修复：启用插件时顺手重扫并启用它携带的 skill（用户只该看见一个开关）")
    void enablingPluginEnablesCarriedSkills() throws IOException {
        com.checkba.service.ai.skill.SkillRegistry skillRegistry =
                mock(com.checkba.service.ai.skill.SkillRegistry.class);
        service.setSkillRegistry(skillRegistry);
        writeManifest("hello-plugin", FULL_MANIFEST);
        service.init();
        service.setEnabled("hello-plugin", false);

        service.setEnabled("hello-plugin", true);

        verify(skillRegistry, atLeastOnce()).rescan();
        verify(skillRegistry).enableSkillsFromPlugin("hello-plugin");
    }

    @Test
    @DisplayName("禁用插件不碰 skill 启停（可用性由 isAvailable 的插件判据兜住）")
    void disablingPluginLeavesSkillStateAlone() throws IOException {
        com.checkba.service.ai.skill.SkillRegistry skillRegistry =
                mock(com.checkba.service.ai.skill.SkillRegistry.class);
        service.setSkillRegistry(skillRegistry);
        writeManifest("hello-plugin", FULL_MANIFEST);
        service.init();

        service.setEnabled("hello-plugin", false);

        verify(skillRegistry, never()).enableSkillsFromPlugin(anyString());
    }

    @Test
    @DisplayName("未装配 SkillRegistry（既有测试直接 new PluginService(...)）时 rescan 不受影响")
    void rescanToleratesMissingSkillRegistry() throws IOException {
        writeManifest("hello-plugin", FULL_MANIFEST);
        service.init();
        assertDoesNotThrow(service::rescan);
    }

    @Test
    @DisplayName("未装配 ToolRegistry（既有测试直接 new PluginService(...)）时 rescan 不受影响")
    void rescanToleratesMissingToolRegistry() throws IOException {
        writeManifest("hello-plugin", FULL_MANIFEST);
        service.init();
        assertDoesNotThrow(service::rescan);
    }

    /**
     * 修复：loadJar() 每次都 new 一个 URLClassLoader 加载插件 JAR，从来没有配对的 close()。
     * rescan()/PluginDevService 热重载/广场装卸插件都会反复调用它，长期运行的服务器
     * 进程上会不断攒 fd 与已加载类的元数据，最终可能导致插件 JAR 加载开始抛 IOException。
     *
     * <p>不能在 loadJar() 里当场关：那会让刚加载出来的插件类立刻失效（已注册的工具对象
     * 若懒加载同一 JAR 里此刻还没碰过的辅助类会失败）。安全的时机是「下一代已经
     * 完整接管注册表之后」——此时上一代不再可能被任何新请求经 pluginTools 查到。
     * 用 JDK 21 实测过的行为验证「真的调用了 close()」：close() 后，loader 上
     * 从未被访问过的资源会读不到（返回 null），而不是抛异常——这是比检查内部字段
     * 更可靠的信号。
     */
    @Test
    @DisplayName("修复：rescan 关闭上一代插件 ClassLoader，不能只增不减地攒 fd")
    void rescanClosesPreviousGenerationClassLoaders() throws IOException {
        Path pluginDir = pluginsDir.resolve("loader-plugin");
        Files.createDirectories(pluginDir);
        writeRealJar(pluginDir.resolve("tool.jar"));
        writeManifest("loader-plugin",
                "{\"id\": \"loader-plugin\", \"name\": \"L\", \"backendJars\": [\"tool.jar\"]}");

        service.init();
        assertEquals(1, service.loadedClassLoaders().size(), "第一代应注册 1 个 loader");
        java.net.URLClassLoader first = service.loadedClassLoaders().get(0);
        assertNotNull(first.getResourceAsStream("hello/Foo.class"), "关闭前应能正常读取 jar 内容");

        service.rescan();

        assertEquals(1, service.loadedClassLoaders().size(), "重扫后应只保留新一代，不累积");
        assertNotSame(first, service.loadedClassLoaders().get(0), "新一代应是全新的 loader 实例");
        assertNull(first.getResourceAsStream("hello/Foo.class"),
                "上一代 loader 必须在新一代接管注册表后被 close，否则 fd 一直攒（病灶）");
    }

    /** 写一个真正合法的 JAR（含两个 class 条目，字节内容不必是合法字节码——不测加载，只测 loader 生命周期）。 */
    // ==== minHostVersion 与 dev 标记（规范 v2.7 P0）====

    @Test
    @DisplayName("宿主达标：声明 minHostVersion 的插件正常生效")
    void minHostVersionSatisfied() throws IOException {
        writeManifest("p1", """
                {"id": "p1", "name": "P1", "version": "1.0.0", "minHostVersion": "0.28.0"}
                """);
        service.appVersion = "0.28.1";
        service.init();
        assertTrue(service.isEnabled("p1"));
        assertNull(service.incompatibleReason("p1"));
    }

    @Test
    @DisplayName("宿主低于 minHostVersion：元数据登记但不生效，enable 明确拒绝")
    void minHostVersionUnsatisfied() throws IOException {
        writeManifest("p1", """
                {"id": "p1", "name": "P1", "version": "1.0.0", "minHostVersion": "0.29.0"}
                """);
        service.appVersion = "0.28.0";
        service.init();
        assertEquals(1, service.getPlugins().size(), "元数据仍要登记，管理页要能展示原因");
        assertFalse(service.isEnabled("p1"));
        assertNotNull(service.incompatibleReason("p1"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.setEnabled("p1", true));
        assertTrue(e.getMessage().contains("0.29.0"));
    }

    @Test
    @DisplayName("dev 态宿主（appVersion 非 semver）跳过 minHostVersion 校验")
    void minHostVersionSkippedOnDevHost() throws IOException {
        writeManifest("p1", """
                {"id": "p1", "name": "P1", "version": "1.0.0", "minHostVersion": "99.0.0"}
                """);
        service.appVersion = "dev";
        service.init();
        assertTrue(service.isEnabled("p1"));
        assertNull(service.incompatibleReason("p1"));
    }

    @Test
    @DisplayName("minHostVersion 格式非法：视为缺省（只警不拒），插件照常生效")
    void invalidMinHostVersionIgnored() throws IOException {
        writeManifest("p1", """
                {"id": "p1", "name": "P1", "version": "1.0.0", "minHostVersion": "next-release"}
                """);
        service.appVersion = "0.28.0";
        service.init();
        assertTrue(service.isEnabled("p1"));
        assertNull(service.getPlugins().get(0).getMinHostVersion());
    }

    @Test
    @DisplayName("目录带 .awd-dev 标记的插件 isDevInstalled=true（实验 API 闸的依据）")
    void devInstalledMarkerDetected() throws IOException {
        writeManifest("p1", """
                {"id": "p1", "name": "P1", "version": "1.0.0"}
                """);
        Files.writeString(pluginsDir.resolve("p1").resolve(".awd-dev"), "{}");
        writeManifest("p2", """
                {"id": "p2", "name": "P2", "version": "1.0.0"}
                """);
        service.init();
        assertTrue(service.isDevInstalled("p1"));
        assertFalse(service.isDevInstalled("p2"));
    }

    private void writeRealJar(Path jarPath) throws IOException {
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new java.util.jar.JarEntry("hello/Foo.class"));
            jos.write(new byte[]{1, 2, 3, 4});
            jos.closeEntry();
        }
    }

    // ==== backendJars 路径校验 ====

    @Test
    @DisplayName("backendJars 指向插件目录外时拒绝加载（路径逃逸防护）")
    void backendJarOutsidePluginDirIsRejected() throws IOException {
        Path pluginDir = pluginsDir.resolve("evil-plugin");
        Files.createDirectories(pluginDir);
        // 在 plugins/ 根下放一个"外部" JAR，插件试图用 ../ 指到它
        Files.write(pluginsDir.resolve("outside.jar"), new byte[]{0x50, 0x4B, 0x03, 0x04});

        assertNull(service.resolveBackendJar(pluginDir.toFile(), "../outside.jar", "evil-plugin"),
                "../ 逃出插件目录必须被拒绝");
        assertNull(service.resolveBackendJar(pluginDir.toFile(), "../../../etc/passwd", "evil-plugin"));
        assertNull(service.resolveBackendJar(pluginDir.toFile(), null, "evil-plugin"));
        assertNull(service.resolveBackendJar(pluginDir.toFile(), "  ", "evil-plugin"));
    }

    @Test
    @DisplayName("修复：backendJar 文件缺失时打一条 WARN，带上插件 id 与缺失的 jar 名")
    void missingBackendJarFileLogsWarningWithPluginIdAndJarName() throws IOException {
        // 病灶：manifest 声明了 backendJars 但解压不全/被手删/打包漏了——此前这条路径
        // 一个字日志都不打，调用方 `if (jarFile != null) loadJar(...)` 又没有 else 分支。
        // 插件照常出现在列表里、启停可用，就是 0 个工具，日志里搜插件 id 与 jar 名全是空。
        Path pluginDir = pluginsDir.resolve("gap-plugin");
        Files.createDirectories(pluginDir);

        ch.qos.logback.classic.Logger logbackLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PluginService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            File result = service.resolveBackendJar(pluginDir.toFile(), "missing-tool.jar", "gap-plugin");
            assertNull(result, "文件不存在时仍应返回 null（行为不变）");
        } finally {
            logbackLogger.detachAppender(appender);
        }

        boolean logged = appender.list.stream().anyMatch(e ->
                e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains("gap-plugin")
                        && e.getFormattedMessage().contains("missing-tool.jar"));
        assertTrue(logged, "应有一条 WARN 日志同时点名插件 id 与缺失的 jar 名，实际日志：" +
                appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .collect(java.util.stream.Collectors.joining(" | ")));
    }

    @Test
    @DisplayName("backendJars 指向插件目录内的真实文件时放行")
    void backendJarInsidePluginDirIsAccepted() throws IOException {
        Path pluginDir = pluginsDir.resolve("ok-plugin");
        Files.createDirectories(pluginDir.resolve("lib"));
        Files.write(pluginDir.resolve("tool.jar"), new byte[]{0x50, 0x4B, 0x03, 0x04});
        Files.write(pluginDir.resolve("lib").resolve("nested.jar"), new byte[]{0x50, 0x4B, 0x03, 0x04});

        assertNotNull(service.resolveBackendJar(pluginDir.toFile(), "tool.jar", "ok-plugin"));
        assertNotNull(service.resolveBackendJar(pluginDir.toFile(), "lib/nested.jar", "ok-plugin"),
                "子目录内的 JAR 应放行");
        assertNull(service.resolveBackendJar(pluginDir.toFile(), "missing.jar", "ok-plugin"),
                "声明了但文件不存在时返回 null");
    }

    // ==== 禁用插件不加载 ====

    // ==== 规范 v2.3：frontendEntry 校验 ====

    /** 写一个带 web/index.html 的插件目录，frontendEntry 由调用方指定（null = 不写该字段） */
    private void writeWebPlugin(String id, String frontendEntry) throws IOException {
        Path dir = pluginsDir.resolve(id);
        Files.createDirectories(dir.resolve("web"));
        Files.writeString(dir.resolve("web").resolve("index.html"), "<h1>hi</h1>", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("outside.html"), "<h1>out</h1>", StandardCharsets.UTF_8);
        String entryJson = frontendEntry == null ? "null" : "\"" + frontendEntry + "\"";
        writeManifest(id, "{\"id\": \"" + id + "\", \"name\": \"W\", \"frontendEntry\": " + entryJson + "}");
    }

    @Test
    @DisplayName("frontendEntry 指向 web/ 内真实文件时保留，并识别为 Web 插件")
    void validFrontendEntryIsKept() throws IOException {
        writeWebPlugin("web-plugin", "web/index.html");
        service.init();

        assertEquals("web/index.html", service.getPlugins().get(0).getFrontendEntry());
        assertTrue(service.hasWebEntry("web-plugin"));
        assertNotNull(service.getPluginDir("web-plugin"));
    }

    @Test
    @DisplayName("frontendEntry 指向 web/ 之外、或文件不存在时置空并当作无前端入口")
    void invalidFrontendEntryIsDropped() throws IOException {
        writeWebPlugin("escape-plugin", "../outside.html");
        writeWebPlugin("outside-web", "outside.html");
        writeWebPlugin("missing-file", "web/missing.html");
        writeWebPlugin("escape-inside-web", "web/../outside.html");
        service.init();

        for (String id : List.of("escape-plugin", "outside-web", "missing-file", "escape-inside-web")) {
            assertNull(service.getPlugin(id).getFrontendEntry(), id + " 的非法入口应被置空");
            assertFalse(service.hasWebEntry(id), id);
        }
    }

    @Test
    @DisplayName("frontendEntry 是绝对 http(s) URL 时原样保留，且不算 Web 插件（旧形态不变）")
    void absoluteUrlFrontendEntryIsPassedThrough() throws IOException {
        writeManifest("legacy", "{\"id\": \"legacy\", \"name\": \"L\", "
                + "\"frontendEntry\": \"https://example.com/panel\"}");
        service.init();

        assertEquals("https://example.com/panel", service.getPlugin("legacy").getFrontendEntry());
        assertFalse(service.hasWebEntry("legacy"), "绝对 URL 不经 PluginWebController，也不走桥");
    }

    @Test
    @DisplayName("resolveWebFile：web/ 内放行，穿越与目录一律 null")
    void resolveWebFileGuards() throws IOException {
        writeWebPlugin("web-plugin", "web/index.html");
        service.init();
        java.io.File dir = service.getPluginDir("web-plugin");

        assertNotNull(service.resolveWebFile(dir, "index.html"));
        assertNull(service.resolveWebFile(dir, "../outside.html"));
        assertNull(service.resolveWebFile(dir, "../../"));
        assertNull(service.resolveWebFile(dir, "missing.html"));
        assertNull(service.resolveWebFile(dir, ""));
        assertNull(service.resolveWebFile(dir, null));
        assertNull(service.resolveWebFile(null, "index.html"));
    }

    // ==== 规范 v2.3：packs ====

    @Test
    @DisplayName("examples/hello-web-plugin 是一个能真正加载起来的 Web 插件")
    void bundledWebPluginExampleIsValid() throws IOException {
        // 示例插件是三方作者照抄的样板，坏了没人会发现——扫描口径变化时这条先红
        Path example = Path.of("..", "examples", "hello-web-plugin");
        assertTrue(Files.isDirectory(example), "示例插件目录不存在：" + example.toAbsolutePath());
        Path dest = pluginsDir.resolve("hello-web-plugin");
        try (var walk = Files.walk(example)) {
            for (Path src : walk.toList()) {
                Path target = dest.resolve(example.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(src, target);
                }
            }
        }
        service.init();

        PluginService.PluginMetadata meta = service.getPlugin("hello-web-plugin");
        assertNotNull(meta, "示例插件应被扫描到");
        assertEquals("web/index.html", meta.getFrontendEntry(),
                "frontendEntry 被置空说明 web/index.html 缺失或路径写错");
        assertTrue(service.hasWebEntry("hello-web-plugin"));
        // v2.7 起示例演示 doc.exec（editor）与 ai.request（ai），并声明 minHostVersion
        assertEquals(List.of("file_read", "editor", "ai"), meta.getPermissions());
        assertEquals("0.27.4", meta.getMinHostVersion());
        assertNotNull(service.resolveWebFile(service.getPluginDir("hello-web-plugin"), "awd-plugin-sdk.js"),
                "index.html 同步引入的 SDK 副本必须在包内");
    }

    @Test
    @DisplayName("packs 字段解析；非法 id 被丢弃（会被拼进注册表 URL 与磁盘路径）")
    void parsesPacksAndDropsInvalidIds() throws IOException {
        writeManifest("with-packs", "{\"id\": \"with-packs\", \"name\": \"P\", "
                + "\"packs\": [\"litviz-fonts\", \"BAD-ID\", \"../escape\", \"\", \"ok2\"]}");
        service.init();

        assertEquals(List.of("litviz-fonts", "ok2"), service.getPlugin("with-packs").getPacks());
    }

    @Test
    @DisplayName("未声明 packs 时为 null（v1/v2 兼容）")
    void packsAbsentStaysNull() throws IOException {
        writeManifest("no-packs", "{\"id\": \"no-packs\", \"name\": \"P\"}");
        service.init();

        assertNull(service.getPlugin("no-packs").getPacks());
    }

    @Test
    @DisplayName("启动时被禁用的插件仍登记元数据，但不加载其 JAR")
    void disabledPluginIsRegisteredButJarNotLoaded() throws IOException {
        writeManifest("hello-plugin", FULL_MANIFEST);
        settingStore.put(PluginService.DISABLED_KEY, "[\"hello-plugin\"]");

        service.init();

        assertEquals(1, service.getPlugins().size(), "禁用插件仍要在管理页可见");
        assertFalse(service.isEnabled("hello-plugin"));
        assertTrue(service.getPluginTools().isEmpty(), "禁用插件的工具不应被注册");
    }
}
