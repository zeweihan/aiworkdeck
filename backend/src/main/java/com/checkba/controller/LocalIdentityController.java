package com.checkba.controller;

import com.checkba.service.LocalIdentityService;
import com.checkba.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机工作区（免登身份）端点，local-mode 专属。
 *
 * <ul>
 *   <li>GET  /api/local-identity/status     启动链在解锁后、进工作区前的分流依据（needsSelection）</li>
 *   <li>GET  /api/local-identity/candidates 候选账号 + 数据量，供选择页与设置页展示</li>
 *   <li>POST /api/local-identity/select     {"userId": 2} 选定并持久化</li>
 * </ul>
 *
 * 与 {@code LicenseController} 同为匿名端点：这条链跑在「进入工作区之前」，
 * 此时本来就还没有身份可言，要求登录是循环依赖。安全前提与免登模式的其余部分一致——
 * {@code LocalModeLoopbackGuard}（只绑回环）+ {@code LocalModeAccessFilter}
 * （逐请求校验回环来源、拒绝反代痕迹、非安全方法的 Origin 白名单）。
 * select 是 POST，自动落在该过滤器的跨站硬拦截分支内。
 *
 * server（团队服务器）模式下这三条端点不做任何事：status 恒回 localMode=false，
 * select 直接 400——否则任何人都能匿名改写团队服务器上的身份解析。
 */
@RestController
@RequestMapping("/api/local-identity")
@Slf4j
public class LocalIdentityController {

    private final LocalIdentityService localIdentityService;
    private final UserService userService;

    public LocalIdentityController(LocalIdentityService localIdentityService, UserService userService) {
        this.localIdentityService = localIdentityService;
        this.userService = userService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        if (!localIdentityService.isLocalMode()) {
            result.put("localMode", false);
            result.put("needsSelection", false);
            return result;
        }
        LocalIdentityService.Resolution resolution = localIdentityService.resolution();
        result.put("localMode", true);
        result.put("needsSelection", resolution.needsSelection());
        result.put("userId", resolution.userId());
        try {
            var user = userService.getUserById(resolution.userId());
            if (user != null) {
                result.put("username", user.getUsername());
                result.put("displayName", user.getDisplayName());
            }
        } catch (Exception e) {
            log.debug("本机身份展示信息读取失败（忽略）: {}", e.getMessage());
        }
        return result;
    }

    @GetMapping("/candidates")
    public Map<String, Object> candidates() {
        Map<String, Object> result = new HashMap<>();
        if (!localIdentityService.isLocalMode()) {
            result.put("localMode", false);
            result.put("candidates", List.of());
            return result;
        }
        LocalIdentityService.Resolution resolution = localIdentityService.resolution();
        List<Map<String, Object>> list = new ArrayList<>();
        for (LocalIdentityService.Candidate c : localIdentityService.candidates()) {
            Map<String, Object> item = new HashMap<>();
            item.put("userId", c.userId());
            item.put("username", c.username());
            item.put("displayName", c.displayName());
            item.put("projectCount", c.projectCount());
            item.put("fileCount", c.fileCount());
            list.add(item);
        }
        result.put("localMode", true);
        result.put("needsSelection", resolution.needsSelection());
        // 待选定时 currentUserId 只是临时落点，前端不要把它渲染成「已选中」
        result.put("currentUserId", resolution.needsSelection() ? null : resolution.userId());
        result.put("candidates", list);
        return result;
    }

    @PostMapping("/select")
    public ResponseEntity<Map<String, Object>> select(@RequestBody(required = false) Map<String, Object> body) {
        if (!localIdentityService.isLocalMode()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "团队服务器部署不支持切换本机工作区"));
        }
        Long userId = parseUserId(body == null ? null : body.get("userId"));
        try {
            LocalIdentityService.Resolution resolution = localIdentityService.select(userId);
            Map<String, Object> result = new HashMap<>();
            result.put("userId", resolution.userId());
            result.put("needsSelection", false);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** userId 可能是 Integer / Long / String（JSON 数字宽度与前端序列化都不受控）。 */
    private static Long parseUserId(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
