package com.checkba.controller;

import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.entitlement.EntitlementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * GET /api/entitlements —— 功能权益快照（Spec §6）。
 *
 * 前端 {@code useEntitlement(feature)} 的数据源：目录内每项是否已获得、来源（本地票据/账户同步）、
 * 以及缓存新鲜度（{@code stale=true} 表示超过 30 天未联网同步，账户型权益已回落为未拥有）。
 *
 * 鉴权同全站（local-mode 免登，server 模式要求会话）：已购权益属于账户隐私，
 * 团队服务器部署下不能匿名可读。
 */
@RestController
public class EntitlementController {

    private final EntitlementService entitlementService;
    private final MachineAccountGuard machineAccountGuard;

    public EntitlementController(EntitlementService entitlementService,
                                 MachineAccountGuard machineAccountGuard) {
        this.entitlementService = entitlementService;
        this.machineAccountGuard = machineAccountGuard;
    }

    /**
     * @param refresh 前端「刚连接账户 / 刚在官网买完回来」的显式刷新：先同步拉一次官网再出快照。
     *                默认 false 走本地缓存，另有陈旧自动刷新兜底（见 EntitlementService.snapshot）。
     */
    @GetMapping("/api/entitlements")
    public Map<String, Object> list(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(value = "refresh", required = false, defaultValue = "false") boolean refresh) {
        // 插件云后端加固：server 模式下权益缓存是机器级状态（EntitlementService 无 userId 维度），
        // 仅 admin 可读；local-mode 行为一字不动（恒放行）。
        machineAccountGuard.requireMachineScope(sessionId);
        if (refresh) {
            entitlementService.refreshQuietly();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", entitlementService.snapshot());
        return result;
    }
}
