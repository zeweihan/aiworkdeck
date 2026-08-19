package com.checkba.controller.ai;

import com.checkba.service.SystemSettingService;
import com.checkba.service.ai.PluginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PluginWebController 测试：路径穿越 / 禁用插件 404 / CSP 随 network 权限变化 / Content-Type。
 *
 * 用真的 PluginService（临时插件目录）而不是 mock：被测的核心是「哪些请求能拿到字节」，
 * 而那条判定链一半在 PluginService.resolveWebFile 的 canonical 校验里，mock 掉就等于
 * 把被测对象换成了测试自己的假设。
 */
class PluginWebControllerTest {

    @TempDir
    Path pluginsDir;

    private final Map<String, String> settingStore = new HashMap<>();
    private PluginService pluginService;
    private PluginWebController controller;

    @BeforeEach
    void setUp() {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), anyString())).thenAnswer(inv ->
                settingStore.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        doAnswer(inv -> settingStore.put(inv.getArgument(0), inv.getArgument(1)))
                .when(settings).set(anyString(), anyString());
        pluginService = new PluginService(settings, pluginsDir.toString());
        controller = new PluginWebController(pluginService);
    }

    /** 写一个带 web/ 的插件；permissions 直接拼进 manifest */
    private void writeWebPlugin(String id, String permissionsJson) throws IOException {
        Path dir = pluginsDir.resolve(id);
        Files.createDirectories(dir.resolve("web").resolve("assets"));
        Files.writeString(dir.resolve("manifest.json"), """
                {
                  "id": "%s",
                  "name": "Web 插件",
                  "version": "1.0.0",
                  "permissions": %s,
                  "frontendEntry": "web/index.html"
                }
                """.formatted(id, permissionsJson), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("web").resolve("index.html"), "<h1>hi</h1>", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("web").resolve("assets").resolve("app.js"), "console.log(1)", StandardCharsets.UTF_8);
        Files.write(dir.resolve("web").resolve("logo.png"), new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        // 插件目录里、但在 web/ 之外的东西：不许被服务出去
        Files.writeString(dir.resolve("secret.txt"), "隐私", StandardCharsets.UTF_8);
    }

    private ResponseEntity<byte[]> get(String id, String subPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/plugin-web/" + id + "/" + subPath);
        request.setRequestURI("/api/plugin-web/" + id + "/" + subPath);
        return controller.serve(id, request);
    }

    // ==== 正常服务 ====

    @Test
    @DisplayName("启用插件的 web/ 文件正常返回，带 CSP / nosniff / no-cache")
    void servesEnabledPluginAssets() throws IOException {
        writeWebPlugin("web-plugin", "[]");
        pluginService.init();

        ResponseEntity<byte[]> res = get("web-plugin", "index.html");
        assertEquals(200, res.getStatusCode().value());
        assertEquals("<h1>hi</h1>", new String(res.getBody(), StandardCharsets.UTF_8));
        assertEquals("text/html; charset=utf-8", res.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals("nosniff", res.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("no-cache", res.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertNotNull(res.getHeaders().getFirst("Content-Security-Policy"));

        assertEquals(200, get("web-plugin", "assets/app.js").getStatusCode().value(), "子目录资产应可服务");
    }

    @Test
    @DisplayName("Content-Type 按扩展名给；未知扩展名退 octet-stream")
    void contentTypeByExtension() throws IOException {
        writeWebPlugin("web-plugin", "[]");
        pluginService.init();

        assertEquals("text/javascript; charset=utf-8",
                get("web-plugin", "assets/app.js").getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals("image/png",
                get("web-plugin", "logo.png").getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals("application/octet-stream", PluginWebController.contentTypeOf("data.bin"));
        assertEquals("application/octet-stream", PluginWebController.contentTypeOf("noext"));
        assertEquals("font/woff2", PluginWebController.contentTypeOf("f.woff2"));
        assertEquals("image/svg+xml", PluginWebController.contentTypeOf("i.SVG"), "扩展名大小写不敏感");
    }

    // ==== CSP 随 network 权限变化 ====

    @Test
    @DisplayName("未声明 network 的插件 connect-src 'none'；声明了才放开 https:")
    void cspFollowsNetworkPermission() throws IOException {
        writeWebPlugin("offline-plugin", "[]");
        writeWebPlugin("online-plugin", "[\"network\"]");
        pluginService.init();

        String offline = get("offline-plugin", "index.html").getHeaders().getFirst("Content-Security-Policy");
        assertTrue(offline.contains("default-src 'none'"), offline);
        assertTrue(offline.endsWith("connect-src 'none'"), offline);

        String online = get("online-plugin", "index.html").getHeaders().getFirst("Content-Security-Policy");
        assertTrue(online.endsWith("connect-src https:"), online);
        assertTrue(online.contains("img-src 'self' data:"), online);
    }

    // ==== 路径穿越 ====

    @Test
    @DisplayName("路径穿越一律 404：../ 出 web/、出插件目录、编码后的 ..")
    void pathTraversalIsRejected() throws IOException {
        writeWebPlugin("web-plugin", "[]");
        pluginService.init();

        assertEquals(404, get("web-plugin", "../secret.txt").getStatusCode().value(),
                "web/ 之外的插件文件不许服务");
        assertEquals(404, get("web-plugin", "../manifest.json").getStatusCode().value());
        assertEquals(404, get("web-plugin", "../../../etc/passwd").getStatusCode().value());
        assertEquals(404, get("web-plugin", "%2e%2e/secret.txt").getStatusCode().value(),
                "百分号编码的 .. 解码后同样要被 canonical 校验挡住");
        assertEquals(404, get("web-plugin", "assets/../../secret.txt").getStatusCode().value());
        assertEquals(404, get("web-plugin", "missing.html").getStatusCode().value());
        assertEquals(404, get("web-plugin", "assets").getStatusCode().value(), "目录不是文件");
    }

    @Test
    @DisplayName("非法插件 id 一律 404，不落到文件系统")
    void invalidPluginIdIsRejected() throws IOException {
        writeWebPlugin("web-plugin", "[]");
        pluginService.init();

        assertEquals(404, get("../web-plugin", "index.html").getStatusCode().value());
        assertEquals(404, get("Web-Plugin", "index.html").getStatusCode().value(), "大写不合规则");
        assertEquals(404, get("a", "index.html").getStatusCode().value(), "长度不足");
        assertEquals(404, get("web-plugin", "").getStatusCode().value(), "空子路径");
    }

    // ==== 启停与未安装 ====

    @Test
    @DisplayName("禁用的插件 404（与「禁用即不加载」同口径），重新启用后恢复")
    void disabledPluginIsNotServed() throws IOException {
        writeWebPlugin("web-plugin", "[]");
        settingStore.put(PluginService.DISABLED_KEY, "[\"web-plugin\"]");
        pluginService.init();

        assertEquals(404, get("web-plugin", "index.html").getStatusCode().value());

        pluginService.setEnabled("web-plugin", true);
        assertEquals(200, get("web-plugin", "index.html").getStatusCode().value());
    }

    @Test
    @DisplayName("未安装的插件 404")
    void unknownPluginIsNotServed() {
        pluginService.init();
        assertEquals(404, get("ghost-plugin", "index.html").getStatusCode().value());
    }
}
