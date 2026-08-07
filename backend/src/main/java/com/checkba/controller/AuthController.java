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
    private final com.checkba.service.AuthAbuseGuard authAbuseGuard;
    private final com.checkba.service.account.AwdkLoginService awdkLoginService;
    private final com.checkba.service.sms.SmsAuthService smsAuthService;
    private final com.checkba.service.auth.SecondFactorService secondFactorService;

    /**
     * 密码校验通过但还缺二次验证码时的响应 code（前端 api.js 据此弹验证码输入步骤，
     * 与 4001 featureNotConfigured / 4003 quotaExceeded 同一族约定）。
     * data 里的 {@code method} 区分 totp / sms。
     */
    static final int CODE_SMS_REQUIRED = 4005;

    /** 认证器 App 里显示的服务名。 */
    private static final String TOTP_ISSUER = "AI Workdeck";

    /**
     * awdk 桥接限速的用户名维度占位：与真实用户名共用一套失败锁定，
     * 但键上带冒号（注册时用户名不可能撞上），不会误伤同名账号。
     */
    private static final String AWDK_BRIDGE_RATE_KEY = "::awdk-bridge";

    // 简单的 session 存储（内存中，实际生产环境应使用 Redis 或 JWT）
    // 并发安全：登录写入与每请求读取/移除高频并发，普通 HashMap 扩容会损坏桶结构
    // （丢 session 导致误判未登录，甚至 CPU 空转死循环）。
    private static final Map<String, Long> SESSION_STORE = new java.util.concurrent.ConcurrentHashMap<>();

    private static UserService staticUserService;
    private static com.checkba.service.DeviceTokenService staticDeviceTokenService;
    private static com.checkba.service.LocalIdentityService staticLocalIdentityService;

    /** DeviceTokenService 构造时反向注册，静态鉴权入口由此识别设备令牌。 */
    public static void registerDeviceTokenService(com.checkba.service.DeviceTokenService svc) {
        staticDeviceTokenService = svc;
    }

    /** LocalIdentityService 仅在 local-mode 下反向注册（同上模式）；server 模式恒为 null。 */
    public static void registerLocalIdentityService(com.checkba.service.LocalIdentityService svc) {
        staticLocalIdentityService = svc;
    }

    public AuthController(UserService userService, ClientInvitationService clientInvitationService,
                          com.checkba.service.AdminAccessService adminAccessService,
                          com.checkba.service.DeviceTokenService deviceTokenService,
                          com.checkba.service.AuthAbuseGuard authAbuseGuard,
                          com.checkba.service.account.AwdkLoginService awdkLoginService,
                          com.checkba.service.sms.SmsAuthService smsAuthService,
                          com.checkba.service.auth.SecondFactorService secondFactorService) {
        this.userService = userService;
        this.clientInvitationService = clientInvitationService;
        this.adminAccessService = adminAccessService;
        this.deviceTokenService = deviceTokenService;
        this.authAbuseGuard = authAbuseGuard;
        this.awdkLoginService = awdkLoginService;
        this.smsAuthService = smsAuthService;
        this.secondFactorService = secondFactorService;
        staticUserService = userService;
    }

    /**
     * 两条密码入口（/login、/device-token）共用的二次验证闸。
     *
     * @return 需要补验证码时返回 4005 信封；返回 null 表示已通过、调用方继续。
     *         码不对则抛 IllegalArgumentException，由调用方的 catch 计入失败锁定。
     */
    private Map<String, Object> secondFactorChallenge(User user, String code) {
        if (secondFactorService == null) return null;
        var method = secondFactorService.required(user);
        if (method == com.checkba.service.auth.SecondFactorService.Method.NONE) return null;
        if (code == null || code.isBlank()) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", CODE_SMS_REQUIRED);
            result.put("message", "本次操作需要二次验证");
            result.put("data", Map.of(
                    "smsRequired", true, // 保留旧字段名，老客户端仍能识别
                    "method", method.name().toLowerCase(),
                    "phoneMasked", secondFactorService.target(user)));
            return result;
        }
        secondFactorService.verify(user, code);
        return null;
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest request,
                                        jakarta.servlet.http.HttpServletRequest http) {
        try {
            // 注册闸 + 按 IP 限频（server 模式；local-mode 旁路）
            authAbuseGuard.requireRegistrationOpen();
            authAbuseGuard.checkRegistrationRate(http.getRemoteAddr());
            User user = userService.register(
                    request.getUsername(),
                    request.getPassword(),
                    request.getDisplayName()
            );
            authAbuseGuard.recordRegistration(http.getRemoteAddr());

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
    public Map<String, Object> login(@RequestBody LoginRequest request,
                                     jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        // 锁定检查独立于凭据校验的 try：锁定拒绝不再计入失败（否则轮询会把锁无限续期）
        try {
            authAbuseGuard.checkLoginAttempt(ip, request.getUsername());
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
        try {
            User user = userService.login(request.getUsername(), request.getPassword());

            // 二次验证（TOTP 或短信，见 SecondFactorService）：
            // 缺码 → 4005 让前端进入验证码步骤（不发会话、不动失败计数——密码是对的）；
            // 错码 → 落入下方 catch 计一次失败（叠加各自的单码尝试上限双重兜底）。
            Map<String, Object> challenge = secondFactorChallenge(user, request.getSmsCode());
            if (challenge != null) {
                return challenge;
            }
            authAbuseGuard.recordLoginSuccess(ip, request.getUsername());

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
            authAbuseGuard.recordLoginFailure(ip, request.getUsername());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
    }

    /**
     * awdk_ → server 会话桥（插件云后端）：官网账户 Key 换本服务器的 awdt_ 设备令牌。
     * 匿名端点；开关 security.awdk-login-enabled 默认 false。限速与密码登录共用一套
     * 失败锁定（按 IP + 固定桥接维度）。
     */
    @PostMapping("/awdk-login")
    public Map<String, Object> awdkLogin(@RequestBody(required = false) Map<String, String> body,
                                         jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Map<String, Object> result = new HashMap<>();
        try {
            authAbuseGuard.checkLoginAttempt(ip, AWDK_BRIDGE_RATE_KEY);
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
        try {
            var session = awdkLoginService.login(body == null ? null : body.get("key"));
            authAbuseGuard.recordLoginSuccess(ip, AWDK_BRIDGE_RATE_KEY);
            result.put("code", 0);
            result.put("data", Map.of(
                    "token", session.token(),
                    "userId", session.userId(),
                    "username", session.username()));
        } catch (com.checkba.service.account.AccountException e) {
            // 只有官网明确拒绝（Key 无效）才计失败；网络不可达不该消耗尝试次数
            if (e.getKind() == com.checkba.service.account.AccountException.Kind.UNAUTHORIZED) {
                authAbuseGuard.recordLoginFailure(ip, AWDK_BRIDGE_RATE_KEY);
            }
            result.put("code", 1);
            result.put("message", e.getMessage());
        } catch (IllegalArgumentException e) {
            // 开关关闭等业务态
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 发送短信验证码。两个场景：
     * <ul>
     *   <li>{@code scene=login}：匿名但必须携带正确的用户名密码（否则该端点就是
     *       给任意手机号发短信的水龙头 + 免锁定的密码试探口），发往该用户已绑定的手机号。
     *       失败锁定与 /login 共用同一套计数。</li>
     *   <li>{@code scene=bind}：需已登录会话，发往待绑定的新手机号。</li>
     * </ul>
     * IP 维度限频在 AuthAbuseGuard，手机号维度冷却/日上限在 SmsCodeStore。
     */
    @PostMapping("/sms/send-code")
    public Map<String, Object> sendSmsCode(@RequestBody SmsSendCodeRequest request,
                                           @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                                           jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Map<String, Object> result = new HashMap<>();
        try {
            authAbuseGuard.checkSmsSendRate(ip);
            String phoneMasked;
            if ("bind".equals(request.getScene())) {
                Long userId = getUserIdFromSession(sessionId);
                if (userId == null) {
                    result.put("code", 1);
                    result.put("message", "未登录");
                    return result;
                }
                phoneMasked = smsAuthService.sendBindCode(userId, request.getPhone());
            } else {
                // login 场景：先过锁定闸再校验凭据，语义与 /login 完全一致
                authAbuseGuard.checkLoginAttempt(ip, request.getUsername());
                User user;
                try {
                    user = userService.login(request.getUsername(), request.getPassword());
                } catch (IllegalArgumentException e) {
                    authAbuseGuard.recordLoginFailure(ip, request.getUsername());
                    throw e;
                }
                phoneMasked = smsAuthService.sendLoginCode(user);
            }
            authAbuseGuard.recordSmsSend(ip);
            result.put("code", 0);
            result.put("message", "验证码已发送");
            result.put("data", Map.of("phoneMasked", phoneMasked));
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 开始绑定认证器（需已登录）：返回手工录入的密钥与扫码用的 otpauth URI。
     * 此时尚未启用，必须再调 activate 验一次码才生效。
     */
    @PostMapping("/totp/setup")
    public Map<String, Object> totpSetup(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) return Map.of("code", 1, "message", "未登录");
        try {
            var setup = secondFactorService.startSetup(userId, TOTP_ISSUER);
            return Map.of("code", 0, "data", Map.of(
                    "secret", setup.secret(),
                    "provisioningUri", setup.provisioningUri()));
        } catch (IllegalArgumentException e) {
            return Map.of("code", 1, "message", e.getMessage());
        }
    }

    /** 完成绑定：验一次认证器生成的码，证明 App 已配好。 */
    @PostMapping("/totp/activate")
    public Map<String, Object> totpActivate(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) return Map.of("code", 1, "message", "未登录");
        try {
            secondFactorService.activate(userId, body == null ? null : body.get("code"));
            return Map.of("code", 0, "message", "认证器已绑定");
        } catch (IllegalArgumentException e) {
            return Map.of("code", 1, "message", e.getMessage());
        }
    }

    /** 解绑认证器：必须带当前码（防止被借用的会话直接摘掉二次验证）。 */
    @PostMapping("/totp/disable")
    public Map<String, Object> totpDisable(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) return Map.of("code", 1, "message", "未登录");
        try {
            secondFactorService.disable(userId, body == null ? null : body.get("code"));
            return Map.of("code", 0, "message", "认证器已解绑");
        } catch (IllegalArgumentException e) {
            return Map.of("code", 1, "message", e.getMessage());
        }
    }

    /** 管理员为丢失认证器的用户清除绑定（否则该用户会被永久锁在门外）。 */
    @PostMapping("/totp/reset/{targetUserId}")
    public Map<String, Object> totpResetByAdmin(
            @PathVariable Long targetUserId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) return Map.of("code", 1, "message", "未登录");
        if (!adminAccessService.isAdmin(userService.getUserById(userId))) {
            return Map.of("code", 1, "message", "仅系统管理员可执行该操作");
        }
        try {
            secondFactorService.resetByAdmin(targetUserId);
            return Map.of("code", 0, "message", "已清除该账号的认证器绑定");
        } catch (IllegalArgumentException e) {
            return Map.of("code", 1, "message", e.getMessage());
        }
    }

    /** 绑定/更换手机号（需已登录；验证码走 scene=bind 的 send-code）。 */
    @PostMapping("/sms/bind")
    public Map<String, Object> bindPhone(@RequestBody SmsBindRequest request,
                                         @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        Map<String, Object> result = new HashMap<>();
        if (userId == null) {
            result.put("code", 1);
            result.put("message", "未登录");
            return result;
        }
        try {
            String phoneMasked = smsAuthService.confirmBind(userId, request.getPhone(), request.getCode());
            result.put("code", 0);
            result.put("message", "绑定成功");
            result.put("data", Map.of("phoneMasked", phoneMasked));
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
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
        // 经 getUserIdFromSession 解析：local-mode 免登下无 session 也要能拿到
        // 本机用户（userprofile/侧栏靠它显示身份与 isAdmin 的「系统设置」入口，
        // 原来直查 SESSION_STORE 恒回「未登录」——app-e2e J2 抓到）。
        // server 模式行为不变（getUserIdFromSession 落回 SESSION_STORE）。
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 1);
            result.put("message", "未登录");
            return result;
        }

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
                "isAdmin", adminAccessService.isAdmin(user),
                // 二次验证：入口显隐与当前绑定状态（Map.of 不收 null，空串=未绑定）
                "smsAuthEnabled", smsAuthService != null && smsAuthService.active(),
                "phoneMasked", com.checkba.service.sms.SmsAuthService.maskPhone(user.getPhone()),
                "totpEnabled", user.isTotpEnabled()
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
        // 设备令牌分支保持原样（团队服务器连接凭据，与本机登录解耦）
        if (sessionId != null
                && sessionId.startsWith(com.checkba.service.DeviceTokenService.TOKEN_PREFIX)
                && staticDeviceTokenService != null) {
            return staticDeviceTokenService.resolveUserId(sessionId);
        }
        // 单机免登模式：无论 header 是什么（含 null / 过期 session），一律解析为本机用户。
        // 安全前提由 LocalModeLoopbackGuard 保证（local-mode 必须绑定回环地址）。
        var localIdentity = staticLocalIdentityService;
        if (localIdentity != null && localIdentity.isLocalMode()) {
            return localIdentity.localUserId();
        }
        if (sessionId == null) return null;
        return SESSION_STORE.get(sessionId);
    }

    /**
     * 会话 ID 必须不可预测：它是全站唯一的持有者凭证。
     * Math.random() 背后是 48 位 LCG，攻击者用自己登录拿到的一个样本即可反解种子、
     * 推算出其他人的会话 ID（时间戳部分本就可猜），因此只能用 CSPRNG。
     */
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    private String generateSessionId() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "session_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String getUsernameFromSession(String sessionId) {
        // local-mode 免登：请求可以完全不带 session 头（sessionId == null）。
        // 此前直接 SESSION_STORE.get(sessionId)，ConcurrentHashMap.get(null) 抛 NPE，
        // 上传/版本信号整条链 500（app-e2e 抓到）。设备令牌保持原行为（此处历来
        // 解析不出用户名，署名由 CloudConnection 身份链路负责），只补 local-mode
        // 与 null 两个分支。
        Long userId;
        var localIdentity = staticLocalIdentityService;
        boolean isDeviceToken = sessionId != null
                && sessionId.startsWith(com.checkba.service.DeviceTokenService.TOKEN_PREFIX);
        if (localIdentity != null && localIdentity.isLocalMode() && !isDeviceToken) {
            userId = localIdentity.localUserId();
        } else if (sessionId == null) {
            return null;
        } else {
            userId = SESSION_STORE.get(sessionId);
        }
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
    public Map<String, Object> issueDeviceToken(@RequestBody Map<String, String> body,
                                                jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Map<String, Object> result = new HashMap<>();
        // 这也是一次密码登录，与 /login 共用同一套失败锁定
        try {
            authAbuseGuard.checkLoginAttempt(ip, body.get("username"));
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
        try {
            User user = userService.login(body.get("username"), body.get("password"));
            // 与 /login 同一道二次验证闸：换令牌也是一次密码登录，留了旁路等于没设闸
            Map<String, Object> challenge = secondFactorChallenge(user, body.get("smsCode"));
            if (challenge != null) {
                return challenge;
            }
            authAbuseGuard.recordLoginSuccess(ip, body.get("username"));
            var issued = deviceTokenService.issue(user.getId(), body.get("name"));
            result.put("code", 0);
            result.put("data", Map.of(
                    "tokenId", issued.id(),
                    "token", issued.plaintext(),
                    "userId", user.getId(),
                    "username", user.getUsername(),
                    "displayName", user.getDisplayName() == null ? user.getUsername() : user.getDisplayName()));
        } catch (Exception e) {
            authAbuseGuard.recordLoginFailure(ip, body.get("username"));
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
                .map(t -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", t.getId());
                    item.put("name", t.getName());
                    item.put("createdAt", t.getCreatedAt() == null ? null : String.valueOf(t.getCreatedAt()));
                    item.put("lastUsedAt", t.getLastUsedAt() == null ? null : String.valueOf(t.getLastUsedAt()));
                    return item;
                })
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
        private String smsCode;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getSmsCode() { return smsCode; }
        public void setSmsCode(String smsCode) { this.smsCode = smsCode; }
    }

    static class SmsSendCodeRequest {
        private String scene;
        private String username;
        private String password;
        private String phone;

        public String getScene() { return scene; }
        public void setScene(String scene) { this.scene = scene; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
    }

    static class SmsBindRequest {
        private String phone;
        private String code;

        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
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

