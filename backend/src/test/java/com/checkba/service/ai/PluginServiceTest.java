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

    @Test
    @DisplayName("未装配 ToolRegistry（既有测试直接 new PluginService(...)）时 rescan 不受影响")
    void rescanToleratesMissingToolRegistry() throws IOException {
        writeManifest("hello-plugin", FULL_MANIFEST);
        service.init();
        assertDoesNotThrow(service::rescan);
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
        assertEquals(List.of("file_read"), meta.getPermissions());
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
