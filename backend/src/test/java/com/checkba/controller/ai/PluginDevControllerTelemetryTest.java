// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.ai.PluginDevService;
import com.checkba.service.telemetry.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 插件开发形态三个端点的埋点必须走白名单里已有的 plugin.lifecycle 事件
 * （op 用 dev_ 前缀与市场安装区分）。历史上这里发的是 plugin.dev，
 * 白名单没有这个事件名，TelemetryService 整条拒绝，三个事件从未落库（dev-board#498）。
 */
class PluginDevControllerTelemetryTest {

    private PluginDevService pluginDevService;
    private TelemetryService telemetryService;
    private PluginDevController controller;

    @BeforeEach
    void setUp() {
        pluginDevService = mock(PluginDevService.class);
        telemetryService = mock(TelemetryService.class);
        UserRepository users = mock(UserRepository.class);
        AdminAccessService admin = mock(AdminAccessService.class);
        User u = new User();
        when(users.findById(anyLong())).thenReturn(Optional.of(u));
        when(admin.isAdmin(any())).thenReturn(true);
        controller = new PluginDevController(pluginDevService, users, admin, telemetryService);
    }

    @Test
    void scaffoldRecordsPluginLifecycleWithDevOp() {
        when(pluginDevService.scaffold(eq(1L), eq(7L), eq("my-plugin"), any())).thenReturn(99L);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            controller.scaffold(Map.of("projectId", 1, "id", "my-plugin"), "sess");
        }
        verify(telemetryService).record("plugin.lifecycle", Map.of("pluginId", "my-plugin", "op", "dev_scaffold"));
        verifyNoMoreInteractions(telemetryService);
    }

    @Test
    void installRecordsPluginLifecycleWithDevOp() {
        when(pluginDevService.install(1L, 99L)).thenReturn("my-plugin");
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            controller.install(Map.of("projectId", 1, "folderId", 99), "sess");
        }
        verify(telemetryService).record("plugin.lifecycle", Map.of("pluginId", "my-plugin", "op", "dev_install"));
        verifyNoMoreInteractions(telemetryService);
    }

    @Test
    void uninstallRecordsPluginLifecycleWithDevOp() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            controller.uninstall(Map.of("id", "my-plugin"), "sess");
        }
        verify(pluginDevService).uninstall("my-plugin");
        verify(telemetryService).record("plugin.lifecycle", Map.of("pluginId", "my-plugin", "op", "dev_uninstall"));
        verifyNoMoreInteractions(telemetryService);
    }
}
