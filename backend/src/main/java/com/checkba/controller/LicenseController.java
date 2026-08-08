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

import java.util.HashMap;
import java.util.Map;

/**
 * 解锁门端点（匿名——解锁前没有任何身份，天然不能要求登录）。
 *
 * - GET  /api/license/status     当前授权状态（前端启动链第一跳）。除 LicenseService 的
 *                                {unlocked, mode, plan} 外，另附展示口径的 accountConnected 与
 *                                edition（paid|trial|none），见 {@link #resolveEdition}
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
    private final com.checkba.service.site.SiteProfileService siteProfileService;

    public LicenseController(LicenseService licenseService,
                             AccountService accountService,
                             EntitlementService entitlementService,
                             com.checkba.service.site.SiteProfileService siteProfileService) {
        this.licenseService = licenseService;
        this.accountService = accountService;
        this.entitlementService = entitlementService;
        this.siteProfileService = siteProfileService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>(licenseService.status());
        boolean connected = accountConnected();
        result.put("accountConnected", connected);
        result.put("edition", resolveEdition(String.valueOf(result.get("mode")), connected));
        // 站点：解锁页与顶栏 chip 用它标注「这台机器面向哪个站」（双主站设计 §2.8）。
        // 与 accountConnected/edition 一样是**只读展示口径**，绝不回写 license.json
        result.put("site", siteProfileService.currentSite());
        result.put("siteDisplayName", siteProfileService.displayName());
        result.put("multiSite", siteProfileService.multiSite());
        return result;
    }

    /**
     * 展示口径的唯一出口：授权票据（license.json 的 mode）与账户连接（account.json）
     * 是两条独立状态，先用试用码解锁、后连账户的用户 mode 永远停在 trial，
     * 只读 mode 的界面会一直显示「试用版」。
     *
     * 这里只做**只读组合**，绝不回写 license.json——连账户时改写票据会抹掉试用码，
     * 断开账户后直接掉回未解锁。
     */
    static String resolveEdition(String mode, boolean accountConnected) {
        if (accountConnected || "account".equals(mode)) return "paid";
        if ("trial".equals(mode)) return "trial";
        return "none";
    }

    /**
     * 账户是否已连接。非 local-mode 一律不查、恒回 false：
     * 账户连接是**机器级**状态，server 模式下 /api/account/* 由 MachineAccountGuard 收到只对 admin 开放，
     * 而本端点是匿名的——照查等于把那一位泄给任何匿名请求。
     * 团队服务器本就恒为正式版（mode=account），edition 不依赖这一位。
     */
    private boolean accountConnected() {
        if (!licenseService.isLocalMode()) return false;
        try {
            return accountService.isConnected();
        } catch (Exception e) {
            log.debug("账户连接状态读取失败，按未连接处理: {}", e.getMessage());
            return false;
        }
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
        Map<String, Object> enriched = new HashMap<>(result);
        connectAccountIfKey(code, enriched);
        return ResponseEntity.ok(enriched);
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
     *
     * 但失败也不能只写日志：用户看到「解锁成功」进了产品，账户却是未连接状态——
     * 顶栏没有账户 chip、平台 AI 通道不可选，而他毫无感知。结果写进 {@code out}
     * 的 accountConnected / accountNotice，由解锁页提示（不阻断进入产品）。
     */
    private void connectAccountIfKey(String code, Map<String, Object> out) {
        // 团队服务器部署没有解锁门（activate 恒回「无需激活」），这条端点又是匿名的——
        // 不加这道闸，任何人都能往团队服务器上绑一把自己的账户 Key
        if (!licenseService.isLocalMode()) return;
        String key = code == null ? "" : code.trim();
        if (!key.startsWith("awdk_")) return;
        try {
            accountService.connect(key);
            entitlementService.refreshAsync();
            out.put("accountConnected", true);
        } catch (Exception e) {
            log.warn("解锁成功但账户连接未完成（可在设置页重试）: {}", e.getMessage());
            out.put("accountConnected", false);
            // 文案红线：不得含「登录 / 未授权 / 请先」，否则前端 api.js 会当成掉线清会话
            out.put("accountNotice", "已解锁，但账户连接未完成，可稍后在设置的账户分区重试");
        }
    }

    @PostMapping("/deactivate")
    public Map<String, Object> deactivate() {
        return licenseService.deactivate();
    }
}
