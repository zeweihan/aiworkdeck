package com.checkba.service.ai;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
              "author": "AI Workdeck",
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
        assertEquals("AI Workdeck", meta.getAuthor());
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
}
