package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 锁定本机用户解析规则（商业化改造 PR-A）：
 * - local 用户存在则永远优先（跨重启稳定，防止 DataInitializer 竞态后翻转 userId）；
 * - 老安装：复用 username=admin 的 userId（数据全挂在它名下，绝不能变），displayName 改「本机用户」；
 * - 全新库：创建 username=local、displayName「本机用户」；
 * - local-mode 下静态注册后，getUserIdFromSession 无论 header 是什么都返回本机用户 id。
 */
class LocalIdentityServiceTest {

    @AfterEach
    void resetStatic() {
        // 构造 localMode=true 实例会静态注册到 AuthController，必须清理防止泄漏到其他测试
        AuthController.registerLocalIdentityService(null);
    }

    private static User user(Long id, String username, String displayName) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        return u;
    }

    @Test
    void reusesExistingAdminAndRenamesDisplayName() {
        UserRepository repo = mock(UserRepository.class);
        User admin = user(7L, "admin", "管理员");
        when(repo.findByUsername("local")).thenReturn(Optional.empty());
        when(repo.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(repo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalIdentityService svc = new LocalIdentityService(repo, false);
        assertEquals(7L, svc.localUserId(), "必须复用 admin 的 userId，老安装数据不能变更归属");
        assertEquals(LocalIdentityService.LOCAL_DISPLAY_NAME, admin.getDisplayName());
        verify(repo).save(admin);
    }

    @Test
    void existingLocalUserAlwaysWinsOverAdmin() {
        // 竞态窗口内创建过 local 用户后，即使 admin 后来出现，解析结果也不能翻转
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByUsername("local"))
                .thenReturn(Optional.of(user(3L, "local", LocalIdentityService.LOCAL_DISPLAY_NAME)));

        LocalIdentityService svc = new LocalIdentityService(repo, false);
        assertEquals(3L, svc.localUserId(), "local 用户存在时必须优先，跨重启不能换 userId");
        verify(repo, never()).findByUsername("admin");
        verify(repo, never()).save(any());
    }

    @Test
    void reusedAdminAlreadyRenamedIsNotRewritten() {
        UserRepository repo = mock(UserRepository.class);
        User admin = user(7L, "admin", LocalIdentityService.LOCAL_DISPLAY_NAME);
        when(repo.findByUsername("admin")).thenReturn(Optional.of(admin));

        LocalIdentityService svc = new LocalIdentityService(repo, false);
        assertEquals(7L, svc.localUserId());
        verify(repo, never()).save(any());
    }

    @Test
    void createsLocalUserWhenAdminAbsent() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByUsername("admin")).thenReturn(Optional.empty());
        when(repo.findByUsername("local")).thenReturn(Optional.empty());
        when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });

        LocalIdentityService svc = new LocalIdentityService(repo, false);
        assertEquals(42L, svc.localUserId());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(repo).save(captor.capture());
        User created = captor.getValue();
        assertEquals("local", created.getUsername());
        assertEquals(LocalIdentityService.LOCAL_DISPLAY_NAME, created.getDisplayName());
        assertNotNull(created.getPassword(), "密码列非空约束需要满足");
        assertFalse(created.getPassword().isBlank());
    }

    @Test
    void cachesResolvedId() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByUsername("admin"))
                .thenReturn(Optional.of(user(7L, "admin", LocalIdentityService.LOCAL_DISPLAY_NAME)));

        LocalIdentityService svc = new LocalIdentityService(repo, false);
        assertEquals(7L, svc.localUserId());
        assertEquals(7L, svc.localUserId());
        verify(repo, times(1)).findByUsername("admin");
    }

    @Test
    void localModeHijacksSessionResolutionRegardlessOfHeader() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByUsername("admin"))
                .thenReturn(Optional.of(user(7L, "admin", LocalIdentityService.LOCAL_DISPLAY_NAME)));

        // localMode=true 构造即静态注册
        new LocalIdentityService(repo, true);
        assertEquals(7L, AuthController.getUserIdFromSession(null), "无 header 也应解析为本机用户");
        assertEquals(7L, AuthController.getUserIdFromSession("session_garbage"), "任意无效 session 也应解析为本机用户");
    }

    @Test
    void serverModeDoesNotRegister() {
        UserRepository repo = mock(UserRepository.class);
        new LocalIdentityService(repo, false);
        // 非 local-mode 行为一字不变：无效 session 仍然是未登录
        assertNull(AuthController.getUserIdFromSession("session_garbage"));
        assertNull(AuthController.getUserIdFromSession(null));
    }
}
