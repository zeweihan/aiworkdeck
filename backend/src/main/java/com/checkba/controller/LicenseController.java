package com.checkba.controller;

import com.checkba.service.LicenseService;
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
public class LicenseController {

    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return licenseService.status();
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody Map<String, String> body) {
        Map<String, Object> result = licenseService.activate(body == null ? null : body.get("code"));
        // 前端契约（api.js request 层）：激活失败必须走非 200 才能 reject 到 unlock 页内联报错；
        // 200 + unlocked:false 会被当成功 resolve（响应无 code 字段时直接放行）。
        if (!Boolean.TRUE.equals(result.get("unlocked"))) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/deactivate")
    public Map<String, Object> deactivate() {
        return licenseService.deactivate();
    }
}
