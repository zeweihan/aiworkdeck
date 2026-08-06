package com.checkba.service.account;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.User;
import com.checkba.service.AdminAccessService;
import com.checkba.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * server 模式下账户连接/权益的机器级操作闸（插件云后端加固）。
 *
 * <p>{@code account.json} / {@code entitlements.json} 是<b>按机器</b>的状态：server 模式下
 * connect/disconnect/余额/权益快照描述的是整台服务器的官网连接，让任意注册用户可读可改
 * 等于把服务器级配置暴露给全体租户（普通用户 disconnect 一下，全服的平台 AI 通道就断了）。
 * 因此 server 模式下这些端点仅 admin 可用（判定复用 {@link AdminAccessService}）。
 *
 * <p><b>local-mode 一字不动</b>：单机产品本机用户就是机器的主人，直接放行。
 *
 * <p>非 admin 的拒绝是业务错误：文案不得含「登录」「未授权」「请先」子串
 * （前端 api.js 据此清会话，licensing 领域地雷 1）。会话缺失仍报「未登录」——
 * 那是真的掉线，本就该触发前端重新认证。
 */
@Service
public class MachineAccountGuard {

    private final boolean localMode;
    private final AdminAccessService adminAccessService;
    private final UserService userService;

    public MachineAccountGuard(
            @Value("${security.local-mode:false}") boolean localMode,
            AdminAccessService adminAccessService,
            UserService userService) {
        this.localMode = localMode;
        this.adminAccessService = adminAccessService;
        this.userService = userService;
    }

    /**
     * local-mode 直接放行；server 模式要求会话有效且为 admin。
     *
     * @throws IllegalArgumentException 会话无效（「未登录」）或非 admin（业务错误）
     */
    public void requireMachineScope(String sessionId) {
        if (localMode) return;
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }
        User user;
        try {
            user = userService.getUserById(userId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未登录");
        }
        if (!adminAccessService.isAdmin(user)) {
            throw new IllegalArgumentException("账户连接与权益同步属于服务器级配置，仅管理员账号可操作");
        }
    }
}
