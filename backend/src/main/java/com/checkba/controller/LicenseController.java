package com.checkba.controller;

import com.checkba.service.LicenseService;
import com.checkba.service.account.AccountService;
import com.checkba.service.entitlement.EntitlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 解锁门端点（匿名——解锁前没有任何身份，天然不能要求登录）。
 *
 * - GET  /api/license/status     当前授权状态（前端启动链第一跳）
 * - POST /api/license/activate   {"code": "AWD-T-..."} 试用码离线验签 / {"code": "awdk_..."} 账户 Key 在线校验
 * - POST /api/license/deactivate 清除授权状态
 *
 * 非 local-mode（团队服务器）部署恒为已解锁正式版，不设解锁门。
 */
@RestController
@RequestMapping("/api/license")
@Slf4j
public class LicenseController {

    private final LicenseService licenseService;
    private final AccountService accountService;
    private final EntitlementService entitlementService;

    public LicenseController(LicenseService licenseService,
                             AccountService accountService,
                             EntitlementService entitlementService) {
        this.licenseService = licenseService;
        this.accountService = accountService;
        this.entitlementService = entitlementService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return licenseService.status();
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody Map<String, String> body) {
        String code = body == null ? null : body.get("code");
        Map<String, Object> result = licenseService.activate(code);
        // 前端契约（api.js request 层）：激活失败必须走非 200 才能 reject 到 unlock 页内联报错；
        // 200 + unlocked:false 会被当成功 resolve（响应无 code 字段时直接放行）。
        if (!Boolean.TRUE.equals(result.get("unlocked"))) {
            return ResponseEntity.badRequest().body(result);
        }
        connectAccountIfKey(code);
        return ResponseEntity.ok(result);
    }

    /**
     * 解锁门里粘 awdk_ Key = 连接账户一步到位（Spec §1）。
     *
     * 少了这一步，用户在解锁页粘完 Key 解锁为正式版之后账户仍是「未连接」：
     * 顶栏没有账户 chip、平台 AI 通道不可选、官网已购功能一个都不生效，
     * 必须再去设置页把同一把 Key 粘第二遍。
     *
     * 连接失败不回滚解锁：verify-key 已经确认这把 Key 有效，
     * 此处多半只是 /api/account/me 这一跳的抖动，设置页里随时可以重连。
     */
    private void connectAccountIfKey(String code) {
        // 团队服务器部署没有解锁门（activate 恒回「无需激活」），这条端点又是匿名的——
        // 不加这道闸，任何人都能往团队服务器上绑一把自己的账户 Key
        if (!licenseService.isLocalMode()) return;
        String key = code == null ? "" : code.trim();
        if (!key.startsWith("awdk_")) return;
        try {
            accountService.connect(key);
            entitlementService.refreshAsync();
        } catch (Exception e) {
            log.warn("解锁成功但账户连接未完成（可在设置页重试）: {}", e.getMessage());
        }
    }

    @PostMapping("/deactivate")
    public Map<String, Object> deactivate() {
        return licenseService.deactivate();
    }
}
