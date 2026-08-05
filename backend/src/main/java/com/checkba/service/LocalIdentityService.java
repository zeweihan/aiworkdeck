package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 单机免登模式下的「本机用户」身份解析（商业化改造 PR-A）。
 *
 * local-mode 下桌面端不再有登录概念，所有请求统一解析为同一个本机用户。
 * 解析规则（老安装零迁移）：
 * - username=local 存在则永远用它——一旦某次启动创建过 local 用户（如首个请求赶在
 *   DataInitializer 建 admin 之前的竞态窗口），后续重启不能因 admin 出现而翻转到
 *   另一个 userId，否则 local 名下数据成孤儿。
 * - 否则复用已存在的 username=admin 用户——老安装的项目/文件/记忆全挂在它名下，
 *   userId 绝不能变；只把 displayName 改成「本机用户」，去掉管理员心智。
 * - 两者都不存在（理论上的全新库兜底）时，创建 username=local、displayName「本机用户」。
 *
 * 与 AuthController 的连接沿用 DeviceTokenService 的静态注册模式：
 * 仅在 local-mode 开启时注册，server 模式下静态入口保持 null、行为一字不变。
 */
@Service
@Slf4j
public class LocalIdentityService {

    public static final String LOCAL_DISPLAY_NAME = "本机用户";

    private final UserRepository userRepository;
    private final boolean localMode;

    /** 解析一次后缓存——getUserIdFromSession 是全后端每请求热路径。 */
    private volatile Long cachedUserId;

    public LocalIdentityService(UserRepository userRepository,
                                @Value("${security.local-mode:false}") boolean localMode) {
        this.userRepository = userRepository;
        this.localMode = localMode;
        if (localMode) {
            AuthController.registerLocalIdentityService(this);
        }
    }

    public boolean isLocalMode() {
        return localMode;
    }

    /** 返回本机用户 id（懒解析 + 缓存）。 */
    public Long localUserId() {
        Long id = cachedUserId;
        if (id != null) return id;
        synchronized (this) {
            if (cachedUserId == null) {
                cachedUserId = resolveLocalUser().getId();
            }
            return cachedUserId;
        }
    }

    private User resolveLocalUser() {
        // local 优先：一旦存在（含竞态窗口内创建的），解析结果必须跨重启稳定，
        // 不能因 DataInitializer 后来建出 admin 而翻转 userId。
        return userRepository.findByUsername("local")
                .orElseGet(() -> userRepository.findByUsername("admin")
                        .map(admin -> {
                            if (!LOCAL_DISPLAY_NAME.equals(admin.getDisplayName())) {
                                admin.setDisplayName(LOCAL_DISPLAY_NAME);
                                admin.setUpdatedAt(LocalDateTime.now());
                                admin = userRepository.save(admin);
                                log.info("单机模式：复用已有 admin 用户作为本机用户（id={}）", admin.getId());
                            }
                            return admin;
                        })
                        .orElseGet(this::createLocalUser));
    }

    private User createLocalUser() {
        User user = new User();
        user.setUsername("local");
        user.setDisplayName(LOCAL_DISPLAY_NAME);
        // 本机用户没有登录入口，密码仅为满足非空约束——随机强口令，不可登录使用。
        user.setPassword(UserService.encodePassword(randomPassword()));
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        log.info("单机模式：已创建本机用户（id={}）", saved.getId());
        return saved;
    }

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
