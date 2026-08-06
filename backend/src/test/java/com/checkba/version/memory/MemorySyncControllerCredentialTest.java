package com.checkba.version.memory;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.MemoryRemote;
import com.checkba.repository.MemoryRemoteRepository;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 记忆同步配置端点的凭据纪律（桌面设置 UI 的契约）：
 * 明文只写不读——status 只回打码后的 secretMasked，永不回显明文；
 * 重存配置时 secret 留空表示沿用已存令牌，不视为清除。
 */
class MemorySyncControllerCredentialTest {

    private MemorySyncService syncService;
    private MemoryRemoteRepository remotes;
    private MemorySyncController controller;

    @BeforeEach
    void setUp() {
        syncService = mock(MemorySyncService.class);
        remotes = mock(MemoryRemoteRepository.class);
        ProjectMemberService members = mock(ProjectMemberService.class);
        controller = new MemorySyncController(syncService, remotes, members);
        when(syncService.syncNow(any())).thenReturn(Map.of("synced", true));
    }

    private static MemoryRemote remote(String secret) {
        MemoryRemote cfg = new MemoryRemote();
        cfg.setRepoKey("user-42-memory");
        cfg.setUrl("https://team.example.com/git/user-42-memory.git");
        cfg.setUsername("lawyer");
        cfg.setSecret(secret);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> statusData() {
        Map<?, ?> body = (Map<?, ?>) controller.status("user-42-memory", "sess").getBody();
        assertEquals(0, body.get("code"));
        return (Map<String, Object>) body.get("data");
    }

    @Test
    void statusMasksSecretAndNeverEchoesPlaintext() {
        String plaintext = "awdt_super_secret_token_9876";
        when(remotes.findByRepoKey("user-42-memory")).thenReturn(Optional.of(remote(plaintext)));
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(42L);
            Map<String, Object> data = statusData();
            assertEquals("****9876", data.get("secretMasked"));
            assertFalse(data.containsKey("secret"), "status 不允许有明文 secret 字段");
            assertFalse(data.containsValue(plaintext), "status 任何字段都不允许带出明文令牌");
        }
    }

    @Test
    void shortSecretIsFullyMasked() {
        when(remotes.findByRepoKey("user-42-memory")).thenReturn(Optional.of(remote("abc123")));
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(42L);
            assertEquals("****", statusData().get("secretMasked"));
        }
        assertNull(MemorySyncController.maskSecret(null));
        assertNull(MemorySyncController.maskSecret("  "));
    }

    @Test
    void blankSecretOnRewriteKeepsExistingToken() {
        when(remotes.findByRepoKey("user-42-memory")).thenReturn(Optional.of(remote("old_token_value_1234")));
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(42L);
            controller.setRemote("user-42-memory",
                    Map.of("url", "https://new.example.com/repo.git", "username", "lawyer", "secret", ""),
                    "sess");
        }
        ArgumentCaptor<MemoryRemote> saved = ArgumentCaptor.forClass(MemoryRemote.class);
        verify(remotes).save(saved.capture());
        assertEquals("old_token_value_1234", saved.getValue().getSecret(), "留空表示沿用已存令牌");
        assertEquals("https://new.example.com/repo.git", saved.getValue().getUrl());
    }

    @Test
    void providedSecretOverwritesExistingToken() {
        when(remotes.findByRepoKey("user-42-memory")).thenReturn(Optional.of(remote("old_token_value_1234")));
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(42L);
            controller.setRemote("user-42-memory",
                    Map.of("url", "https://team.example.com/git/user-42-memory.git",
                            "username", "lawyer", "secret", "new_token_value_5678"),
                    "sess");
        }
        ArgumentCaptor<MemoryRemote> saved = ArgumentCaptor.forClass(MemoryRemote.class);
        verify(remotes).save(saved.capture());
        assertEquals("new_token_value_5678", saved.getValue().getSecret());
    }
}
