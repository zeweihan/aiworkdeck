package com.checkba.service;

import com.checkba.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定管理员判定的两种模式：
 * - 默认（云端多人）：仅用户名 admin；
 * - 桌面单机（security.admin.allow-all-users=true）：所有登录用户都是管理员。
 */
class AdminAccessServiceTest {

    private AdminAccessService withFlag(boolean allowAllUsers) {
        AdminAccessService svc = new AdminAccessService();
        ReflectionTestUtils.setField(svc, "allowAllUsers", allowAllUsers);
        return svc;
    }

    private User user(String name) {
        User u = new User();
        u.setUsername(name);
        return u;
    }

    @Test
    void cloudDefaultOnlyAdminUsername() {
        AdminAccessService svc = withFlag(false);
        assertTrue(svc.isAdmin(user("admin")));
        assertTrue(svc.isAdmin(user("Admin"))); // 大小写不敏感（沿用原语义）
        assertFalse(svc.isAdmin(user("alice")));
        assertFalse(svc.isAdmin(null));
    }

    @Test
    void desktopAllUsersAreAdmins() {
        AdminAccessService svc = withFlag(true);
        assertTrue(svc.isAdmin(user("alice")));
        assertTrue(svc.isAdmin(user("admin")));
        assertFalse(svc.isAdmin(null)); // 未登录仍然不是
    }
}
