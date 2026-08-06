package com.checkba.version.memory;

import com.checkba.controller.AuthController;
import com.checkba.repository.MemoryRemoteRepository;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 记忆同步配置端点的鉴权：user 仓 owner-only；project 仓成员读 + 写权限写；
 * CLIENT 一律拒。参数序 (projectId, userId) 用真实桩数据钉住（地雷 #3 同款防护）。
 */
class MemorySyncControllerAuthTest {

    private MemorySyncService syncService;
    private MemoryRemoteRepository remotes;
    private ProjectMemberService members;
    private MemorySyncController controller;

    @BeforeEach
    void setUp() {
        syncService = mock(MemorySyncService.class);
        remotes = mock(MemoryRemoteRepository.class);
        members = mock(ProjectMemberService.class);
        when(remotes.findByRepoKey(any())).thenReturn(Optional.empty());
        controller = new MemorySyncController(syncService, remotes, members);
    }

    @Test
    void userRepoIsOwnerOnly() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(42L);
            // 本人可读
            assertEquals(0, ((java.util.Map<?, ?>) controller
                    .status("user-42-memory", "sess").getBody()).get("code"));
            // 别人不行
            assertThrows(IllegalArgumentException.class,
                    () -> controller.status("user-7-memory", "sess"));
        }
    }

    @Test
    void projectRepoRequiresMembershipAndRejectsClient() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(42L);
            when(members.hasReadPermission(5L, 42L)).thenReturn(true);
            when(members.isClient(5L, 42L)).thenReturn(false);
            assertEquals(0, ((java.util.Map<?, ?>) controller
                    .status("project-5-memory", "sess").getBody()).get("code"));

            when(members.isClient(5L, 42L)).thenReturn(true);
            assertThrows(IllegalArgumentException.class,
                    () -> controller.status("project-5-memory", "sess"));

            when(members.isClient(5L, 42L)).thenReturn(false);
            when(members.hasReadPermission(5L, 42L)).thenReturn(false);
            assertThrows(IllegalArgumentException.class,
                    () -> controller.status("project-5-memory", "sess"));
        }
    }

    @Test
    void writeEndpointsNeedWritePermission() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(42L);
            when(members.hasReadPermission(5L, 42L)).thenReturn(true);
            when(members.isClient(5L, 42L)).thenReturn(false);
            when(members.hasWritePermission(5L, 42L)).thenReturn(false);
            assertThrows(IllegalArgumentException.class,
                    () -> controller.sync("project-5-memory", "sess"));

            when(members.hasWritePermission(5L, 42L)).thenReturn(true);
            when(syncService.syncNow(any())).thenReturn(java.util.Map.of("synced", true));
            assertEquals(0, ((java.util.Map<?, ?>) controller
                    .sync("project-5-memory", "sess").getBody()).get("code"));
        }
    }

    @Test
    void anonymousAndBadRepoKeyAreRejected() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);
            assertThrows(IllegalArgumentException.class,
                    () -> controller.status("user-42-memory", null));
        }
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(42L);
            var resp = controller.onVersionError(assertThrows(
                    com.checkba.version.VersionException.class,
                    () -> controller.status("not-a-repo", "sess")));
            assertEquals(1, ((java.util.Map<?, ?>) resp.getBody()).get("code"));
        }
    }
}
