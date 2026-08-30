package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.plugin.api.HostQuotaException;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.SystemSettingService;
import com.checkba.service.ai.AuxModelResolver;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.TokenUsageService;
import com.checkba.service.plugin.PluginHostFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 桥 ai.request 的服务端落点 POST /api/plugins/{id}/ai/complete（规范 v2.7 P2）安全闸测试：
 * 未登录 401 / 插件未启用 404 / 未声明 ai 权限 403 / 无项目写权限 403 /
 * 超长与频控 200+quota_exceeded / 放行时走辅助模型并记账（projectId/userId 服务端为准）。
 */
class PluginControllerAiCompleteTest {

    @TempDir
    Path pluginsDir;

    private PluginService pluginService;
    private ProjectMemberService projectMemberService;
    private PluginHostFactory pluginHostFactory;
    private ChatModelFactory chatModelFactory;
    private AuxModelResolver auxModelResolver;
    private TokenUsageService tokenUsageService;
    private ChatLanguageModel auxModel;
    private PluginController controller;

    @BeforeEach
    void setUp() throws IOException {
        Map<String, String> store = new HashMap<>();
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), anyString())).thenAnswer(inv ->
                store.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        Path dir = pluginsDir.resolve("ai-demo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), """
                {"id": "ai-demo", "name": "AI 演示", "version": "1.0.0", "permissions": ["ai"]}
                """);
        Path noAi = pluginsDir.resolve("no-ai");
        Files.createDirectories(noAi);
        Files.writeString(noAi.resolve("manifest.json"), """
                {"id": "no-ai", "name": "无 AI 权限", "version": "1.0.0", "permissions": ["file_read"]}
                """);
        pluginService = new PluginService(settings, pluginsDir.toString());
        pluginService.init();
        projectMemberService = mock(ProjectMemberService.class);
        pluginHostFactory = mock(PluginHostFactory.class);
        chatModelFactory = mock(ChatModelFactory.class);
        auxModelResolver = mock(AuxModelResolver.class);
        tokenUsageService = mock(TokenUsageService.class);
        auxModel = mock(ChatLanguageModel.class);
        when(auxModelResolver.auxModelId()).thenReturn("qwen/qwen3.7-flash");
        when(chatModelFactory.getAuxChatModel()).thenReturn(auxModel);
        when(auxModel.generate(any(List.class))).thenReturn(Response.from(AiMessage.from("答复")));
        controller = new PluginController(pluginService,
                mock(com.checkba.service.ai.PluginMarketService.class),
                mock(com.checkba.service.ai.PluginRevocationService.class),
                mock(com.checkba.repository.UserRepository.class),
                mock(com.checkba.service.AdminAccessService.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.ai.ToolRegistry.class), projectMemberService,
                mock(com.checkba.service.ai.PluginContributionService.class),
                pluginHostFactory, chatModelFactory, auxModelResolver, tokenUsageService);
    }

    private Map<String, Object> body(Long projectId, String prompt) {
        Map<String, Object> b = new HashMap<>();
        b.put("projectId", projectId);
        b.put("prompt", prompt);
        return b;
    }

    @Test
    @DisplayName("未登录 401")
    void unauthenticated() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(null);
            ResponseEntity<Map<String, Object>> res = controller.aiComplete("ai-demo", body(1L, "hi"), null);
            assertEquals(401, res.getStatusCode().value());
        }
    }

    @Test
    @DisplayName("插件不存在/未启用 404")
    void unknownPlugin() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(7L);
            assertEquals(404, controller.aiComplete("nope", body(1L, "hi"), "s").getStatusCode().value());
            pluginService.setEnabled("ai-demo", false);
            assertEquals(404, controller.aiComplete("ai-demo", body(1L, "hi"), "s").getStatusCode().value());
        }
    }

    @Test
    @DisplayName("manifest 未声明 ai 权限 403（服务端是权威，桥端校验只是快速失败）")
    void missingAiPermission() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(7L);
            assertEquals(403, controller.aiComplete("no-ai", body(1L, "hi"), "s").getStatusCode().value());
        }
    }

    @Test
    @DisplayName("无项目写权限 403")
    void noProjectPermission() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(7L);
            when(projectMemberService.hasWritePermission(1L, 7L)).thenReturn(false);
            assertEquals(403, controller.aiComplete("ai-demo", body(1L, "hi"), "s").getStatusCode().value());
        }
    }

    @Test
    @DisplayName("prompt+system 超 16000 字符：200 + quota_exceeded")
    void tooLong() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(7L);
            when(projectMemberService.hasWritePermission(1L, 7L)).thenReturn(true);
            ResponseEntity<Map<String, Object>> res =
                    controller.aiComplete("ai-demo", body(1L, "x".repeat(16001)), "s");
            assertEquals(200, res.getStatusCode().value());
            assertEquals(1, res.getBody().get("code"));
            assertEquals("quota_exceeded", res.getBody().get("errorCode"));
        }
    }

    @Test
    @DisplayName("频控超限：200 + quota_exceeded")
    void quotaExceeded() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(7L);
            when(projectMemberService.hasWritePermission(1L, 7L)).thenReturn(true);
            doThrow(new HostQuotaException("limit")).when(pluginHostFactory).acquireAiQuota("ai-demo");
            ResponseEntity<Map<String, Object>> res = controller.aiComplete("ai-demo", body(1L, "hi"), "s");
            assertEquals(200, res.getStatusCode().value());
            assertEquals("quota_exceeded", res.getBody().get("errorCode"));
        }
    }

    @Test
    @DisplayName("放行：走辅助模型、返回 text/modelId、按服务端 projectId/userId 记账")
    void happyPath() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(7L);
            when(projectMemberService.hasWritePermission(1L, 7L)).thenReturn(true);
            ResponseEntity<Map<String, Object>> res = controller.aiComplete("ai-demo", body(1L, "你好"), "s");
            assertEquals(200, res.getStatusCode().value());
            assertEquals(0, res.getBody().get("code"));
            assertEquals("答复", res.getBody().get("text"));
            assertEquals("qwen/qwen3.7-flash", res.getBody().get("modelId"));
            verify(pluginHostFactory).acquireAiQuota("ai-demo");
            verify(auxModel).generate(any(List.class));
        }
    }
}
