package com.checkba.controller;

import com.checkba.service.entitlement.EntitlementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * GET /api/entitlements —— 功能权益快照（Spec §6）。
 *
 * 前端 {@code useEntitlement(feature)} 的数据源：目录内每项是否已获得、来源（本地票据/账户同步）、
 * 以及缓存新鲜度（{@code stale=true} 表示超过 30 天未联网同步，账户型权益已回落为未拥有）。
 */
@RestController
public class EntitlementController {

    private final EntitlementService entitlementService;

    public EntitlementController(EntitlementService entitlementService) {
        this.entitlementService = entitlementService;
    }

    @GetMapping("/api/entitlements")
    public Map<String, Object> list() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", entitlementService.snapshot());
        return result;
    }
}
