package com.checkba.controller;

import com.checkba.model.entity.ProjectInvitation;
import com.checkba.model.entity.User;
import com.checkba.service.ClientInvitationService;
import com.checkba.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final ClientInvitationService clientInvitationService;
    private final com.checkba.service.AdminAccessService adminAccessService;
    private final com.checkba.service.DeviceTokenService deviceTokenService;

    // 简单的 session 存储（内存中，实际生产环境应使用 Redis 或 JWT）
    // 并发安全：登录写入与每请求读取/移除高频并发，普通 HashMap 扩容会损坏桶结构
    // （丢 session 导致误判未登录，甚至 CPU 空转死循环）。
    private static final Map<String, Long> SESSION_STORE = new java.util.concurrent.ConcurrentHashMap<>();

    private static UserService staticUserService;
    private static com.checkba.service.DeviceTokenService staticDeviceTokenService;

    /** DeviceTokenService 构造时反向注册，静态鉴权入口由此识别设备令牌。 */
    public static void registerDeviceTokenService(com.checkba.service.DeviceTokenService svc) {
        staticDeviceTokenService = svc;
    }

    public AuthController(UserService userService, ClientInvitationService clientInvitationService,
                          com.checkba.service.AdminAccessService adminAccessService,
                          com.checkba.service.DeviceTokenService deviceTokenService) {
        this.userService = userService;
        this.clientInvitationService = clientInvitationService;
        this.adminAccessService = adminAccessService;
        this.deviceTokenService = deviceTokenService;
        staticUserService = userService;
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest request) {
        try {
            User user = userService.register(
                    request.getUsername(),
                    request.getPassword(),
                    request.getDisplayName()
            );

            // 注册成功后自动登录
            String sessionId = generateSessionId();
            SESSION_STORE.put(sessionId, user.getId());

            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("message", "注册成功");
            result.put("data", Map.of(
                    "sessionId", sessionId,
                    "user", Map.of(
                            "id", user.getId(),
                            "username", user.getUsername(),
                            "displayName", user.getDisplayName(),
                            "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                            "role", user.getRole(),
                            "subscriptionType", user.getSubscriptionType()
                    )
            ));
            return result;
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        try {
            User user = userService.login(request.getUsername(), request.getPassword());

            String sessionId = generateSessionId();
            SESSION_STORE.put(sessionId, user.getId());

            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("message", "登录成功");
            result.put("data", Map.of(
                    "sessionId", sessionId,
                    "user", Map.of(
                            "id", user.getId(),
                            "username", user.getUsername(),
                            "displayName", user.getDisplayName(),
                            "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                            "role", user.getRole(),
                            "subscriptionType", user.getSubscriptionType()
                    )
            ));
            return result;
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
    }

    /**
     * 客户登录（使用访问码）
     */
    @PostMapping("/client-login")
    public Map<String, Object> clientLogin(@RequestBody ClientLoginRequest request) {
        try {
            ProjectInvitation invitation = clientInvitationService.validateCode(request.getAccessCode());
            
            // Create a new user for this client login if displayName is provided
            // This allows tracking "Who uploaded what"
            User user;
            if (request.getDisplayName() != null && !request.getDisplayName().trim().isEmpty()) {
                user = clientInvitationService.createClientUser(
                    invitation.getProjectId(), 
                    request.getDisplayName(), 
                    request.getAccessCode()
                );
            } else {
                 // Fallback to the generic user linked to the invitation (legacy)
                 user = userService.getUserById(invitation.getRelatedUserId());
            }

            String sessionId = generateSessionId();
            SESSION_STORE.put(sessionId, user.getId());

            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("message", "登录成功");
            result.put("data", Map.of(
                    "sessionId", sessionId,
                    "projectId", invitation.getProjectId(), // Return projectId so frontend knows where to go
                    "user", Map.of(
                            "id", user.getId(),
                            "username", user.getUsername(),
                            "displayName", user.getDisplayName(),
                            "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                            "role", user.getRole(),
                            "subscriptionType", user.getSubscriptionType()
                    )
            ));
            return result;
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public Map<String, Object> getCurrentUser(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId == null || !SESSION_STORE.containsKey(sessionId)) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 1);
            result.put("message", "未登录");
            return result;
        }

        Long userId = SESSION_STORE.get(sessionId);
        User user = userService.getUserById(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                "role", user.getRole(),
                "subscriptionType", user.getSubscriptionType(),
                // 系统管理权限（桌面单机=全员；云端=仅 admin 账号），前端据此显示「系统设置」入口
                "isAdmin", adminAccessService.isAdmin(user)
        ));
        return result;
    }

    /**
     * 用户登出
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId != null) {
            SESSION_STORE.remove(sessionId);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "登出成功");
        return result;
    }

    /**
     * 根据 sessionId 获取用户 ID（供其他 Controller 使用）
     */
    public static Long getUserIdFromSession(String sessionId) {
        if (sessionId == null) return null;
        if (sessionId.startsWith(com.checkba.service.DeviceTokenService.TOKEN_PREFIX)
                && staticDeviceTokenService != null) {
            return staticDeviceTokenService.resolveUserId(sessionId);
        }
        return SESSION_STORE.get(sessionId);
    }

    private String generateSessionId() {
        return "session_" + System.currentTimeMillis() + "_" + String.valueOf(Math.random()).substring(2, 15);
    }

    public static String getUsernameFromSession(String sessionId) {
        Long userId = SESSION_STORE.get(sessionId);
        if (userId == null) return null;
        if (staticUserService != null) {
            try {
                User user = staticUserService.getUserById(userId);
                return user != null ? user.getDisplayName() : null; // Use DisplayName as creator name
            } catch (Exception e) {
                 return null;
            }
        }
        return null;
    }

    /** 用账号密码换长期设备令牌（桌面端连接团队服务器用）。明文只在这里出现一次。 */
    @PostMapping("/device-token")
    public Map<String, Object> issueDeviceToken(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            User user = userService.login(body.get("username"), body.get("password"));
            var issued = deviceTokenService.issue(user.getId(), body.get("name"));
            result.put("code", 0);
            result.put("data", Map.of(
                    "tokenId", issued.id(),
                    "token", issued.plaintext(),
                    "userId", user.getId(),
                    "username", user.getUsername(),
                    "displayName", user.getDisplayName() == null ? user.getUsername() : user.getDisplayName()));
        } catch (Exception e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @GetMapping("/device-tokens")
    public Map<String, Object> listDeviceTokens(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) return Map.of("code", 1, "message", "未登录");
        var items = deviceTokenService.listMine(userId).stream()
                .map(t -> Map.of("id", t.getId(), "name", t.getName(),
                        "createdAt", String.valueOf(t.getCreatedAt()),
                        "lastUsedAt", String.valueOf(t.getLastUsedAt())))
                .toList();
        return Map.of("code", 0, "data", Map.of("tokens", items));
    }

    @PostMapping("/device-token/{id}/revoke")
    public Map<String, Object> revokeDeviceToken(
            @PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) return Map.of("code", 1, "message", "未登录");
        deviceTokenService.revoke(userId, id);
        return Map.of("code", 0, "message", "已撤销");
    }

    static class RegisterRequest {
        private String username;
        private String password;
        private String displayName;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }

    static class LoginRequest {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    static class ClientLoginRequest {
        private String accessCode;
        private String displayName;

        public String getAccessCode() { return accessCode; }
        public void setAccessCode(String accessCode) { this.accessCode = accessCode; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }
}

