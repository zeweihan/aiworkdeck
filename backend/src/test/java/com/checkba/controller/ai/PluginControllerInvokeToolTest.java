package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.SystemSettingService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.ToolRegistry;
import com.checkba.service.ai.tools.ToolContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 直调端点 POST /api/plugins/{id}/tools/{tool}（规范 v2.5）的安全闸测试：
 * 未登录 401 / 插件未启用或工具未声明 404 / 无项目写权限 403 /
 * 放行时 ToolContext 的 projectId 与 userId 必须来自服务端而不是请求体 args。
 */
class PluginControllerInvokeToolTest {

    @TempDir
    Path pluginsDir;

    private final Map<String, String> settingStore = new HashMap<>();
    private PluginService pluginService;
    private ToolRegistry toolRegistry;
    private ProjectMemberService projectMemberService;
    private PluginController controller;

    @BeforeEach
    void setUp() throws IOException {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), anyString())).thenAnswer(inv ->
                settingStore.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        doAnswer(inv -> settingStore.put(inv.getArgument(0), inv.getArgument(1)))
                .when(settings).set(anyString(), anyString());
        Path dir = pluginsDir.resolve("dd-demo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), """
                {
                  "id": "dd-demo",
                  "name": "尽调演示",
                  "version": "1.0.0",
                  "permissions": ["file_read", "file_write"],
                  "tools": [{"name": "dd_ping", "description": "ping"}]
                }
                """);
        pluginService = new PluginService(settings, pluginsDir.toString());
        pluginService.init();
        toolRegistry = mock(ToolRegistry.class);
        projectMemberService = mock(ProjectMemberService.class);
        controller = new PluginController(pluginService,
                mock(com.checkba.service.ai.PluginMarketService.class),
                mock(com.checkba.service.ai.PluginRevocationService.class),
                mock(com.checkba.repository.UserRepository.class),
                mock(com.checkba.service.AdminAccessService.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                toolRegistry, projectMemberService);
    }

    private Map<String, Object> body(Long projectId) {
        Map<String, Object> b = new HashMap<>();
        b.put("projectId", projectId);
        Map<String, Object> args = new HashMap<>();
        args.put("chapter", 3);
        args.put("projectId", 999L); // 模型/面板伪造的跨项目 id，必须被服务端值压掉
        b.put("args", args);
        return b;
    }

    @Test
    @DisplayName("未登录 401")
    void unauthenticated() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(null);
            ResponseEntity<Map<String, Object>> r = controller.invokeTool("dd-demo", "dd_ping", body(1L), null);
            assertEquals(401, r.getStatusCode().value());
            verifyNoInteractions(toolRegistry);
        }
    }

    @Test
    @DisplayName("未声明的工具 404，不进 ToolRegistry")
    void undeclaredTool() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            ResponseEntity<Map<String, Object>> r = controller.invokeTool("dd-demo", "read_file", body(1L), "sess");
            assertEquals(404, r.getStatusCode().value());
            verifyNoInteractions(toolRegistry);
        }
    }

    @Test
    @DisplayName("插件禁用后 404")
    void disabledPlugin() {
        pluginService.setEnabled("dd-demo", false);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            ResponseEntity<Map<String, Object>> r = controller.invokeTool("dd-demo", "dd_ping", body(1L), "sess");
            assertEquals(404, r.getStatusCode().value());
            verifyNoInteractions(toolRegistry);
        }
    }

    @Test
    @DisplayName("无项目写权限 403")
    void noProjectPermission() {
        when(projectMemberService.hasWritePermission(1L, 7L)).thenReturn(false);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            ResponseEntity<Map<String, Object>> r = controller.invokeTool("dd-demo", "dd_ping", body(1L), "sess");
            assertEquals(403, r.getStatusCode().value());
            verifyNoInteractions(toolRegistry);
        }
    }

    @Test
    @DisplayName("放行：ToolContext 用服务端 projectId/userId，args 原样透传")
    void happyPath() {
        when(projectMemberService.hasWritePermission(1L, 7L)).thenReturn(true);
        when(toolRegistry.execute(eq("dd_ping"), anyString(), any()))
                .thenReturn(new ToolRegistry.ToolResult("pong: dd-demo", null, true));
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            ResponseEntity<Map<String, Object>> r = controller.invokeTool("dd-demo", "dd_ping", body(1L), "sess");
            assertEquals(200, r.getStatusCode().value());
            assertEquals(0, r.getBody().get("code"));
            assertEquals("pong: dd-demo", r.getBody().get("output"));
            ArgumentCaptor<ToolContext> ctx = ArgumentCaptor.forClass(ToolContext.class);
            ArgumentCaptor<String> argsJson = ArgumentCaptor.forClass(String.class);
            verify(toolRegistry).execute(eq("dd_ping"), argsJson.capture(), ctx.capture());
            assertEquals(1L, ctx.getValue().projectId());
            assertEquals(7L, ctx.getValue().userId());
            assertNull(ctx.getValue().conversationId());
            assertTrue(argsJson.getValue().contains("\"chapter\":3"));
        }
    }

    @Test
    @DisplayName("缺 projectId 直接 403，不猜项目")
    void missingProjectId() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            Map<String, Object> b = new HashMap<>();
            b.put("args", Map.of());
            ResponseEntity<Map<String, Object>> r = controller.invokeTool("dd-demo", "dd_ping", b, "sess");
            assertEquals(403, r.getStatusCode().value());
            verifyNoInteractions(toolRegistry);
        }
    }
}
