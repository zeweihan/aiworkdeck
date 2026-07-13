package com.checkba.service;

import com.checkba.model.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 管理员判定的唯一出口（AdminConfig / Wizard / auth "me" 共用）。
 *
 * 两种模式：
 * - 云端/多人部署（默认）：仅用户名为 admin 的账号是管理员；
 * - 桌面/单机（desktop profile 置 security.admin.allow-all-users=true）：
 *   所有登录用户都是管理员——本地安装是单人产品，新注册的账号也要能
 *   进「系统管理」配 AI Key，不该被锁在默认 admin 账号外。
 */
@Service
public class AdminAccessService {

    @Value("${security.admin.allow-all-users:false}")
    private boolean allowAllUsers;

    public boolean isAdmin(User user) {
        if (user == null) return false;
        if (allowAllUsers) return true;
        return "admin".equalsIgnoreCase(user.getUsername());
    }
}
