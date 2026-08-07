package com.checkba.controller;

import com.checkba.service.account.AccountException;
import com.checkba.service.ai.PlatformAiKeyService;
import com.checkba.service.ai.PlatformUsageAccountant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 按用户的平台 AI 通道额度（server 模式多租户）。
 *
 * <ul>
 *   <li>GET  /api/platform-ai/key/status   本账号的额度概览（上限/已用/剩余/最近验证）</li>
 *   <li>POST /api/platform-ai/key/refresh  {"key":"awdk_..."} 重新取一把 runtime key</li>
 * </ul>
 *
 * <p><b>会话级，不是机器级</b>：这里描述的是「我这个账号自己的额度」，
 * 因此不走 {@code MachineAccountGuard} 的 admin 闸——那道闸管的是
 * {@code account.json}/{@code entitlements.json} 这类整台服务器的连接状态。
 *
 * <p>刷新路径存在的理由：用户在官网分配额度或重发密钥之后，server 手里没有 awdk_ 可以重取
 * （明文不落库）。不复用 awdk-login 做刷新是因为那条路每次都会多签发一枚 awdt_ 设备令牌。
 *
 * <p>密钥明文<b>不出后端</b>：status 只回掩码与数字。
 * 失败文案不含「登录」「未授权」「请先」子串（前端据此判定掉线，licensing 领域地雷 1）。
 */
@RestController
@RequestMapping("/api/platform-ai/key")
@Slf4j
public class PlatformAiKeyController {

    private final PlatformAiKeyService platformAiKeyService;
    private final PlatformUsageAccountant platformUsageAccountant;

    public PlatformAiKeyController(PlatformAiKeyService platformAiKeyService,
                                   PlatformUsageAccountant platformUsageAccountant) {
        this.platformAiKeyService = platformAiKeyService;
        this.platformUsageAccountant = platformUsageAccountant;
    }

    @GetMapping("/status")
    public Map<String, Object> status(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        return ok(platformAiKeyService.status(userId, platformUsageAccountant));
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        platformAiKeyService.refresh(userId, body == null ? null : body.get("key"));
        // 换了 key 就等于换了额度池，旧指纹的对账基线必须作废
        platformUsageAccountant.resetBaseline();
        return ok(platformAiKeyService.status(userId, platformUsageAccountant));
    }

    private Long requireUser(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            // 会话缺失是真的掉线，这里就该触发前端重新认证
            throw new IllegalArgumentException("未登录");
        }
        return userId;
    }

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<Map<String, Object>> handleAccountException(AccountException e) {
        log.warn("平台 AI 通道额度操作失败 [{}]: {}", e.getKind(), e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("kind", e.getKind().name());
        result.put("message", e.getMessage());
        return ResponseEntity.ok(result);
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", data);
        return result;
    }
}
