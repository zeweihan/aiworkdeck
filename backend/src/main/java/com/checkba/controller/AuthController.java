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
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserService userService;
    private final ClientInvitationService clientInvitationService;

    // 简单的 session 存储（内存中，实际生产环境应使用 Redis 或 JWT）
    // 并发安全：登录写入与每请求读取/移除高频并发，普通 HashMap 扩容会损坏桶结构
    // （丢 session 导致误判未登录，甚至 CPU 空转死循环）。
    private static final Map<String, Long> SESSION_STORE = new java.util.concurrent.ConcurrentHashMap<>();

    private static UserService staticUserService;

    public AuthController(UserService userService, ClientInvitationService clientInvitationService) {
        this.userService = userService;
        this.clientInvitationService = clientInvitationService;
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
                "subscriptionType", user.getSubscriptionType()
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

