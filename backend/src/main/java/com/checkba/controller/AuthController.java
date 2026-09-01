package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.config.PhoneLoginGuard;
import com.checkba.model.entity.ProjectInvitation;
import com.checkba.model.entity.User;
import com.checkba.service.ClientInvitationService;
import com.checkba.service.LangText;
import com.checkba.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
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
    private final com.checkba.service.mail.MailAuthService mailAuthService;
    private final com.checkba.service.auth.SecondFactorService secondFactorService;
    private final com.checkba.service.UserSessionService userSessionService;
    /** 单机免登模式。设备令牌的会话签发路径只在这一模式下存在（见 issueLocalDeviceToken）。 */
    private final boolean localMode;
    /** 存量账号补绑手机号的三态闸；未接线的调用方传 null 表示不设闸。 */
    private final PhoneLoginGuard phoneLoginGuard;

    /**
     * 密码校验通过但还缺二次验证码时的响应 code（前端 api.js 据此弹验证码输入步骤，
     * 与 4001 featureNotConfigured / 4003 quotaExceeded 同一族约定）。
     * data 里的 {@code method} 区分 totp / sms。
     */
    static final int CODE_SMS_REQUIRED = 4005;

    /**
     * 补绑期已过、账号仍未绑手机号时的拒登 code。
     *
     * <p><b>刻意不用 4010</b>：那是前端 {@code api.js} 认定的「会话失效」专用码，
     * 收到它会清掉本地会话并弹回登录页。这里的语义是「账号缺一次补绑」而不是「你掉线了」，
     * 回 4010 会让客户端把用户的会话一起清掉，还会把文案冲掉，人就不知道该去发邮件了。
     */
    static final int CODE_PHONE_BINDING_REQUIRED = 4006;

    /** 认证器 App 里显示的服务名。 */
    private static final String TOTP_ISSUER = "AI WorkDeck";

    /**
     * awdk 桥接限速的用户名维度占位：与真实用户名共用一套失败锁定，
     * 但键上带冒号（注册时用户名不可能撞上），不会误伤同名账号。
     */
    private static final String AWDK_BRIDGE_RATE_KEY = "::awdk-bridge";

    /**
     * 账户登录（手机号/邮箱）的限速维度，同上带冒号避开真实用户名空间。
     *
     * <p>刻意<b>不</b>按手机号/邮箱分桶，而是整条桥共用一个：本服务器只是个转发器，
     * 真正的凭据校验在官网，官网那边看到的来源 IP 恒为本机。按手机号分桶意味着
     * 一个 IP 可以换着手机号无限试，失败全部原样打到官网的按 IP 计数上
     * （15 分钟 30 次即锁），最后被锁在门外的是本服务器的全体用户。
     * 按 IP 计的这一档必须比官网那一档更紧。
     */
    private static final String ACCOUNT_LOGIN_RATE_KEY = "::account-login";

    private static UserService staticUserService;
    private static com.checkba.service.DeviceTokenService staticDeviceTokenService;
    private static com.checkba.service.LocalIdentityService staticLocalIdentityService;
    private static com.checkba.service.UserSessionService staticUserSessionService;

    /** DeviceTokenService 构造时反向注册，静态鉴权入口由此识别设备令牌。 */
    public static void registerDeviceTokenService(com.checkba.service.DeviceTokenService svc) {
        staticDeviceTokenService = svc;
    }

    /** UserSessionService 构造时反向注册（同上模式），静态鉴权入口由此解析登录会话。 */
    public static void registerUserSessionService(com.checkba.service.UserSessionService svc) {
        staticUserSessionService = svc;
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
                          com.checkba.service.mail.MailAuthService mailAuthService,
                          com.checkba.service.auth.SecondFactorService secondFactorService,
                          com.checkba.service.UserSessionService userSessionService,
                          @org.springframework.beans.factory.annotation.Value("${security.local-mode:false}")
                          boolean localMode,
                          PhoneLoginGuard phoneLoginGuard) {
        this.userService = userService;
        this.clientInvitationService = clientInvitationService;
        this.adminAccessService = adminAccessService;
        this.deviceTokenService = deviceTokenService;
        this.authAbuseGuard = authAbuseGuard;
        this.awdkLoginService = awdkLoginService;
        this.smsAuthService = smsAuthService;
        this.mailAuthService = mailAuthService;
        this.secondFactorService = secondFactorService;
        this.userSessionService = userSessionService;
        this.localMode = localMode;
        this.phoneLoginGuard = phoneLoginGuard;
        staticUserService = userService;
    }

    /** 本次登录的手机号闸判定；未接线（null）一律放行，不把既有链路连坐。 */
    private PhoneLoginGuard.PhoneGate phoneGate(User user) {
        return phoneLoginGuard == null ? PhoneLoginGuard.PhoneGate.OK : phoneLoginGuard.gateFor(user);
    }

    /**
     * 补绑期已过、账号仍未绑手机号时的拒登信封（spec §5）。
     *
     * <p>调用点必须落在**签发会话/设备令牌之前**——先签发再拒等于已经给出了一个能用的凭据，
     * 客户端存下来照样能用。文案指向 {@code hi@aiworkdeck.com}：被锁在门外的人只剩这一个出口。
     *
     * @return 需要拒登时返回 4006 信封；返回 null 表示放行，调用方据
     *         {@link PhoneLoginGuard.PhoneGate#MUST_BIND} 决定是否让客户端弹强制补绑。
     */
    private Map<String, Object> phoneBindingRefusal(PhoneLoginGuard.PhoneGate gate) {
        if (gate != PhoneLoginGuard.PhoneGate.BLOCKED) return null;
        String deadline = phoneLoginGuard.bindingDeadline().toString();
        Map<String, Object> result = new HashMap<>();
        result.put("code", CODE_PHONE_BINDING_REQUIRED);
        result.put("message", LangText.of(
                "该账号未绑定手机号，补绑期已于 " + deadline + " 结束。"
                        + "请发邮件到 " + PhoneLoginGuard.SUPPORT_EMAIL + " 申请人工代绑。",
                "This account has no phone number linked, and the grace period ended on " + deadline
                        + ". Please email " + PhoneLoginGuard.SUPPORT_EMAIL + " to have it linked manually."));
        return result;
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
            result.put("message", LangText.of("本次操作需要二次验证", "This action requires a second verification step"));
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
            String sessionId = userSessionService.issue(user.getId());

            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("message", LangText.of("注册成功", "Registration successful"));
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

            // 手机号补绑闸：期限后未绑号拒在这里——**必须早于下面的 issue()**，
            // 先签发再拒等于已经把一个能用的会话交出去了。凭据是对的，因此不计失败。
            PhoneLoginGuard.PhoneGate gate = phoneGate(user);
            Map<String, Object> refusal = phoneBindingRefusal(gate);
            if (refusal != null) {
                return refusal;
            }

            String sessionId = userSessionService.issue(user.getId());

            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("message", LangText.of("登录成功", "Signed in successfully"));
            result.put("data", Map.of(
                    "sessionId", sessionId,
                    // 期限内未绑号：放行但让客户端立刻弹不可跳过的强制补绑（走现成的 /sms/bind）
                    "mustBindPhone", gate == PhoneLoginGuard.PhoneGate.MUST_BIND,
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
     * 账户登录：给手机号发验证码（匿名，转发官网 {@code /api/auth/sms-login/send-code}）。
     *
     * <p>与下面的 {@code /account-login} 一起，让 Office 插件用户直接用手机号/邮箱登录，
     * 不必再去官网账户页生成 awdk_ Key 手工搬运。受同一个
     * {@code security.awdk-login-enabled} 开关约束（见 AwdkLoginService）。
     *
     * <p><b>与 {@code /sms/send-code?scene=login} 的两处刻意不同</b>：
     * 那条要求先给出正确的用户名密码（本服务器自己的账号体系，端点直通短信网关）；
     * 这条是纯转发，真正的手机号维度冷却/日配额在官网。因此这里的闸只有 IP 维度，
     * 且<b>把尝试记在出站之前</b>——只记成功的话，拿一串无效手机号刷本服务器
     * 就能免费换来等量的对官网出站请求，IP 额度永远不会耗尽。
     */
    /**
     * 官网人机验证的公开配置（匿名），供 Office 插件在发码前渲染控件用。只有公开参数，没有密钥。
     *
     * <p><b>为什么不能复用 {@code /api/account/captcha-config}</b>：那条开头是
     * {@code requireUser(sessionId)}。桌面端 local-mode 会把任何请求解析成本机用户所以没事，
     * 云后端 {@code local-mode=false} 下插件用户此刻还没登录——「取控件参数得先有会话、
     * 有会话得先登录、登录得先过控件」是死循环。与 {@code /account-login} 不复用
     * {@code /api/account/login} 是同一个理由。
     *
     * <p>未启用时官网回 {@code {"provider": null}}，调用方据此跳过控件直接发码。
     */
    @GetMapping("/account-login/captcha-config")
    public Map<String, Object> accountLoginCaptchaConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", awdkLoginService.captchaConfig());
        return result;
    }

    @PostMapping("/account-login/send-code")
    public Map<String, Object> accountLoginSendCode(@RequestBody(required = false) Map<String, String> body,
                                                    jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Map<String, Object> result = new HashMap<>();
        try {
            authAbuseGuard.checkCodeSendRate(ip);
            authAbuseGuard.recordCodeSend(ip);
            awdkLoginService.sendLoginCode(
                    body == null ? null : body.get("phone"),
                    body == null ? null : body.get("captchaToken"));
            result.put("code", 0);
            result.put("message", LangText.of("验证码已发送", "Verification code sent"));
            result.put("data", Map.of("sent", true));
        } catch (com.checkba.service.account.AccountException | IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 账户登录：手机号+验证码 或 账号+口令 → 本服务器的 awdt_ 设备令牌（匿名端点）。
     *
     * <p>与 {@code /awdk-login} 是同一条桥的两个入口，信封与限速形状刻意保持一致。
     * 两种凭据形状按站点分（大陆站手机号、国际站邮箱），这里不判站点——判站点的是官网，
     * 本服务器按用户填了什么转发即可。
     *
     * <p>注意不能复用 {@code /api/account/login}：那条开头就要 {@code requireUser(sessionId)}，
     * 在 local-mode 的桌面端会自动解析成本机用户所以没事，云后端 {@code local-mode=false}
     * 下「登录前得先有会话」是个死循环。
     */
    @PostMapping("/account-login")
    public Map<String, Object> accountLogin(@RequestBody(required = false) Map<String, String> body,
                                            jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Map<String, Object> result = new HashMap<>();
        try {
            authAbuseGuard.checkLoginAttempt(ip, ACCOUNT_LOGIN_RATE_KEY);
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
        String phone = body == null ? null : body.get("phone");
        try {
            var session = (phone != null && !phone.isBlank())
                    ? awdkLoginService.loginWithPhone(phone, body.get("code"))
                    : awdkLoginService.loginWithPassword(
                            body == null ? null : body.get("account"),
                            body == null ? null : body.get("password"));
            authAbuseGuard.recordLoginSuccess(ip, ACCOUNT_LOGIN_RATE_KEY);
            result.put("code", 0);
            result.put("data", Map.of(
                    "token", session.token(),
                    "userId", session.userId(),
                    "username", session.username()));
        } catch (com.checkba.service.account.AccountException e) {
            // 只有官网明确拒绝凭据（验证码错/口令错）才计失败。网络不可达不该消耗尝试次数；
            // CONFLICT（补绑期已过）也不该——那个用户的凭据本来就是对的，锁他没有意义。
            if (e.getKind() == com.checkba.service.account.AccountException.Kind.UNAUTHORIZED) {
                authAbuseGuard.recordLoginFailure(ip, ACCOUNT_LOGIN_RATE_KEY);
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
     * IP 维度限频在 AuthAbuseGuard，手机号维度冷却/日上限在 VerificationCodeStore。
     */
    @PostMapping("/sms/send-code")
    public Map<String, Object> sendSmsCode(@RequestBody SmsSendCodeRequest request,
                                           @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                                           jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Map<String, Object> result = new HashMap<>();
        try {
            authAbuseGuard.checkCodeSendRate(ip);
            String phoneMasked;
            if ("bind".equals(request.getScene())) {
                Long userId = getUserIdFromSession(sessionId);
                if (userId == null) {
                    result.put("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED);
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
            authAbuseGuard.recordCodeSend(ip);
            result.put("code", 0);
            result.put("message", LangText.of("验证码已发送", "Verification code sent"));
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
        if (userId == null) return Map.of("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED, "message", "未登录");
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
        if (userId == null) return Map.of("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED, "message", "未登录");
        try {
            secondFactorService.activate(userId, body == null ? null : body.get("code"));
            return Map.of("code", 0, "message", LangText.of("认证器已绑定", "Authenticator linked"));
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
        if (userId == null) return Map.of("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED, "message", "未登录");
        try {
            secondFactorService.disable(userId, body == null ? null : body.get("code"));
            return Map.of("code", 0, "message", LangText.of("认证器已解绑", "Authenticator unlinked"));
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
        if (userId == null) return Map.of("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED, "message", "未登录");
        if (!adminAccessService.isAdmin(userService.getUserById(userId))) {
            return Map.of("code", 1, "message", LangText.of("仅系统管理员可执行该操作", "Only a system administrator can perform this action"));
        }
        try {
            secondFactorService.resetByAdmin(targetUserId);
            return Map.of("code", 0, "message", LangText.of("已清除该账号的认证器绑定", "This account's authenticator link has been cleared"));
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
            result.put("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED);
            result.put("message", "未登录");
            return result;
        }
        try {
            String phoneMasked = smsAuthService.confirmBind(userId, request.getPhone(), request.getCode());
            result.put("code", 0);
            result.put("message", LangText.of("绑定成功", "Linked successfully"));
            result.put("data", Map.of("phoneMasked", phoneMasked));
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 发送邮箱验证码。两个场景，与 {@code /sms/send-code} 完全对称：
     * <ul>
     *   <li>{@code scene=login}：匿名但必须携带正确的用户名密码，发往该用户已绑定的邮箱。
     *       失败锁定与 /login 共用同一套计数。</li>
     *   <li>{@code scene=bind}：需已登录会话，发往待绑定的新邮箱。</li>
     * </ul>
     * IP 维度限频与短信共用 AuthAbuseGuard 那把闸——否则换个通道就能绕过限频。
     */
    @PostMapping("/mail/send-code")
    public Map<String, Object> sendMailCode(@RequestBody MailSendCodeRequest request,
                                            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                                            jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Map<String, Object> result = new HashMap<>();
        try {
            authAbuseGuard.checkCodeSendRate(ip);
            String emailMasked;
            if ("bind".equals(request.getScene())) {
                Long userId = getUserIdFromSession(sessionId);
                if (userId == null) {
                    result.put("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED);
                    result.put("message", "未登录");
                    return result;
                }
                emailMasked = mailAuthService.sendBindCode(userId, request.getEmail());
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
                emailMasked = mailAuthService.sendLoginCode(user);
            }
            authAbuseGuard.recordCodeSend(ip);
            result.put("code", 0);
            result.put("message", LangText.of("验证码已发送", "Verification code sent"));
            result.put("data", Map.of("emailMasked", emailMasked));
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** 校验验证码并绑定邮箱（需已登录）。也用于换绑：直接绑新地址覆盖。 */
    @PostMapping("/mail/bind")
    public Map<String, Object> bindEmail(@RequestBody MailBindRequest request,
                                         @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        Map<String, Object> result = new HashMap<>();
        if (userId == null) {
            result.put("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED);
            result.put("message", "未登录");
            return result;
        }
        try {
            String emailMasked = mailAuthService.confirmBind(userId, request.getEmail(), request.getCode());
            result.put("code", 0);
            result.put("message", LangText.of("绑定成功", "Linked successfully"));
            result.put("data", Map.of("emailMasked", emailMasked));
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 邮箱免密登录第一步：发码。
     *
     * <p>这是一条**匿名登录入口**，默认关（mail.passwordless-login-enabled）。
     * 回包对「已注册」和「未注册」完全一致——未注册时 MailAuthService 不发信但照常返回，
     * 否则这个端点就是账号枚举器。IP 维度限频与短信/绑定共用同一把闸。
     */
    @PostMapping("/mail-login/send-code")
    public Map<String, Object> mailLoginSendCode(@RequestBody MailLoginRequest request,
                                                 jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Map<String, Object> result = new HashMap<>();
        try {
            authAbuseGuard.checkCodeSendRate(ip);
            mailAuthService.sendSigninCode(request.getEmail());
            authAbuseGuard.recordCodeSend(ip);
            result.put("code", 0);
            result.put("message", LangText.of("验证码已发送", "Verification code sent"));
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 邮箱免密登录第二步：验码换会话。
     *
     * <p>走与密码登录同一套失败锁定，锁定键用邮箱本身——否则这条路就是一个不计失败次数的
     * 6 位码爆破口（单枚码的尝试上限只管那一枚，换一枚重来不受限）。
     */
    @PostMapping("/mail-login/verify")
    public Map<String, Object> mailLoginVerify(@RequestBody MailLoginRequest request,
                                               jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Map<String, Object> result = new HashMap<>();
        String lockKey = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase();
        try {
            authAbuseGuard.checkLoginAttempt(ip, lockKey);
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
        try {
            User user = mailAuthService.verifySigninCode(request.getEmail(), request.getCode());
            authAbuseGuard.recordLoginSuccess(ip, lockKey);
            // 同 /login 的手机号补绑闸：这条也是一次给未绑号账号发会话的入口，
            // 只护住密码那条等于换个端点就绕过去了。
            PhoneLoginGuard.PhoneGate gate = phoneGate(user);
            Map<String, Object> refusal = phoneBindingRefusal(gate);
            if (refusal != null) {
                return refusal;
            }
            String newSessionId = userSessionService.issue(user.getId());
            result.put("code", 0);
            result.put("message", LangText.of("登录成功", "Signed in successfully"));
            result.put("data", Map.of(
                    "sessionId", newSessionId,
                    "mustBindPhone", gate == PhoneLoginGuard.PhoneGate.MUST_BIND,
                    "user", Map.of(
                            "id", user.getId(),
                            "username", user.getUsername(),
                            "displayName", user.getDisplayName(),
                            "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                            "role", user.getRole(),
                            "subscriptionType", user.getSubscriptionType()
                    )
            ));
        } catch (IllegalArgumentException e) {
            authAbuseGuard.recordLoginFailure(ip, lockKey);
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 手机号免密登录/注册：发验证码。
     * 形状与 mail-login/send-code 完全一致，刻意不发明新范式。
     */
    @PostMapping("/sms-login/send-code")
    public Map<String, Object> smsLoginSendCode(@RequestBody SmsLoginRequest request,
                                                jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Map<String, Object> result = new HashMap<>();
        try {
            authAbuseGuard.checkCodeSendRate(ip);
            smsAuthService.sendSigninCode(request.getPhone());
            authAbuseGuard.recordCodeSend(ip);
            result.put("code", 0);
            result.put("message", LangText.of("验证码已发送", "Verification code sent"));
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 手机号免密登录/注册：核销验证码。
     *
     * 注册与登录合一——号码没见过就建号，`isNewUser` 让前端引导补昵称。
     * 「是否新用户」只在核销成功后才透露，`send-code` 阶段对任何号码都是同样的响应。
     */
    @PostMapping("/sms-login/verify")
    public Map<String, Object> smsLoginVerify(@RequestBody SmsLoginRequest request,
                                              jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Map<String, Object> result = new HashMap<>();
        String lockKey = request.getPhone() == null ? "" : request.getPhone().trim();
        try {
            authAbuseGuard.checkLoginAttempt(ip, lockKey);
        } catch (IllegalArgumentException e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
        try {
            String phone = smsAuthService.verifySigninCode(request.getPhone(), request.getCode());
            UserService.PhoneAccount account = userService.findOrCreateByPhone(phone);
            User user = account.user();
            authAbuseGuard.recordLoginSuccess(ip, lockKey);
            String newSessionId = userSessionService.issue(user.getId());
            result.put("code", 0);
            result.put("message", LangText.of("登录成功", "Signed in successfully"));
            result.put("data", Map.of(
                    "sessionId", newSessionId,
                    "isNewUser", account.created(),
                    "user", Map.of(
                            "id", user.getId(),
                            "username", user.getUsername(),
                            "displayName", user.getDisplayName(),
                            "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                            "role", user.getRole(),
                            "subscriptionType", user.getSubscriptionType()
                    )
            ));
        } catch (IllegalArgumentException e) {
            authAbuseGuard.recordLoginFailure(ip, lockKey);
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

            String sessionId = userSessionService.issue(user.getId());

            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("message", LangText.of("登录成功", "Signed in successfully"));
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
        // 原来直查会话表恒回「未登录」——app-e2e J2 抓到）。
        // server 模式行为不变（getUserIdFromSession 落回 UserSessionService）。
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED);
            result.put("message", "未登录");
            return result;
        }

        User user = userService.getUserById(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        // Map.of 最多 10 对，这里已经 12 项——用 ofEntries，加字段时不会突然编译不过
        result.put("data", Map.ofEntries(
                Map.entry("id", user.getId()),
                Map.entry("username", user.getUsername()),
                // local-mode 免登下 displayName 可能是库里的中文哨兵值（LocalIdentityService.
                // LOCAL_DISPLAY_NAME），经 displayNameOf 按界面语言本地化，不动库里存的值
                // ——顶栏「Lead:」、设置页个人区（AdminPane.vue）都读这个字段。
                Map.entry("displayName", com.checkba.service.LocalIdentityService.displayNameOf(user.getDisplayName())),
                Map.entry("avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : ""),
                Map.entry("role", user.getRole()),
                Map.entry("subscriptionType", user.getSubscriptionType()),
                // 系统管理权限（桌面单机=全员；云端=仅 admin 账号），前端据此显示「系统设置」入口
                Map.entry("isAdmin", adminAccessService.isAdmin(user)),
                // 二次验证：入口显隐与当前绑定状态（Map.entry 不收 null，空串=未绑定）
                Map.entry("smsAuthEnabled", smsAuthService != null && smsAuthService.active()),
                Map.entry("phoneMasked", com.checkba.service.sms.SmsAuthService.maskPhone(user.getPhone())),
                Map.entry("mailAuthEnabled", mailAuthService != null && mailAuthService.active()),
                Map.entry("emailMasked", com.checkba.service.mail.MailAuthService.maskEmail(user.getVerifiedEmail())),
                Map.entry("totpEnabled", user.isTotpEnabled())
        ));
        return result;
    }

    /**
     * 用户登出
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId != null && userSessionService != null) {
            userSessionService.revoke(sessionId);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", LangText.of("登出成功", "Signed out successfully"));
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
        if (sessionId == null || staticUserSessionService == null) return null;
        return staticUserSessionService.resolveUserId(sessionId);
    }

    public static String getUsernameFromSession(String sessionId) {
        // local-mode 免登：请求可以完全不带 session 头（sessionId == null）。
        // 此前直查内存 SESSION_STORE 时 ConcurrentHashMap.get(null) 抛 NPE，
        // 上传/版本信号整条链 500（app-e2e 抓到）。设备令牌保持原行为（此处历来
        // 解析不出用户名，署名由 CloudConnection 身份链路负责），只补 local-mode
        // 与 null 两个分支。
        Long userId;
        var localIdentity = staticLocalIdentityService;
        boolean isDeviceToken = sessionId != null
                && sessionId.startsWith(com.checkba.service.DeviceTokenService.TOKEN_PREFIX);
        if (localIdentity != null && localIdentity.isLocalMode() && !isDeviceToken) {
            userId = localIdentity.localUserId();
        } else if (sessionId == null || staticUserSessionService == null) {
            return null;
        } else {
            userId = staticUserSessionService.resolveUserId(sessionId);
        }
        if (userId == null) return null;
        if (staticUserService != null) {
            try {
                User user = staticUserService.getUserById(userId);
                return user != null ? user.getDisplayName() : null; // Use DisplayName as creator name
            } catch (Exception e) {
                // 只补日志、不改行为：这里吞掉的异常与"用户真的不存在"返回同一个 null，
                // 调用方（署名归属等）区分不出"这次查询失败"和"查无此人"，但改成向上
                // 抛/返回错误码会动到所有调用方的既有语义，代价大于收益——先把故障留痕。
                log.warn("getUsernameFromSession: getUserById({}) 失败，按 null 处理: {}", userId, e.toString());
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
            // 同 /login 的手机号补绑闸：换令牌也是一次密码登录，而且发出去的是**长期**凭据，
            // 留了旁路等于补绑期一到就有条比会话还好用的后门。
            PhoneLoginGuard.PhoneGate gate = phoneGate(user);
            Map<String, Object> refusal = phoneBindingRefusal(gate);
            if (refusal != null) {
                return refusal;
            }
            var issued = deviceTokenService.issue(user.getId(), body.get("name"));
            result.put("code", 0);
            result.put("data", Map.of(
                    "tokenId", issued.id(),
                    "token", issued.plaintext(),
                    "mustBindPhone", gate == PhoneLoginGuard.PhoneGate.MUST_BIND,
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

    /**
     * 单机免登模式下用当前本机会话直接换设备令牌（供 Microsoft Office 插件等外部客户端连接本机后端）。
     *
     * 为什么不能复用 {@link #issueDeviceToken}：那条路要账号 + 口令，而桌面单机版免登、
     * 本机用户根本没有口令，桌面端无从生成令牌。
     *
     * 安全边界不新开口子，与其余 local-mode 端点同一套前提：
     * - {@code LocalModeLoopbackGuard} 启动期强制 local-mode 必须绑回环地址；
     * - {@code LocalModeAccessFilter} 每请求校验回环来源、拒绝反代痕迹与跨站 Origin。
     * 因此能打到这里的只有本机进程。口令路径与它的失败锁定、二次验证闸一字未动；
     * 非 local-mode（团队服务器）本端点直接拒绝，那边仍然只有账号口令一条路。
     */
    @PostMapping("/device-token/issue-local")
    public Map<String, Object> issueLocalDeviceToken(
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Map<String, Object> result = new HashMap<>();
        if (!localMode) {
            // 业务错误文案红线：不得含「登录 / 未授权 / 请先」，否则前端 api.js 会当成掉线清会话
            result.put("code", 1);
            result.put("message", LangText.of("该服务器需用账号密码换取设备令牌", "This server requires a username and password to issue a device token"));
            return result;
        }
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            result.put("code", 1);
            result.put("message", LangText.of("本机身份尚未就绪，稍后重试", "Local identity is not ready yet, please retry shortly"));
            return result;
        }
        try {
            var issued = deviceTokenService.issue(userId, body == null ? null : body.get("name"));
            User user = userService == null ? null : userService.getUserById(userId);
            String username = user == null || user.getUsername() == null ? "" : user.getUsername();
            String displayName = user == null || user.getDisplayName() == null ? username : user.getDisplayName();
            Map<String, Object> data = new HashMap<>();
            data.put("tokenId", issued.id());
            data.put("token", issued.plaintext());
            data.put("userId", userId);
            data.put("username", username);
            data.put("displayName", displayName);
            result.put("code", 0);
            result.put("data", data);
        } catch (Exception e) {
            result.put("code", 1);
            result.put("message", LangText.of("令牌生成失败：", "Token generation failed: ") + e.getMessage());
        }
        return result;
    }

    @GetMapping("/device-tokens")
    public Map<String, Object> listDeviceTokens(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) return Map.of("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED, "message", "未登录");
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
        if (userId == null) return Map.of("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED, "message", "未登录");
        deviceTokenService.revoke(userId, id);
        return Map.of("code", 0, "message", LangText.of("已撤销", "Revoked"));
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

    static class MailSendCodeRequest {
        private String scene;
        private String username;
        private String password;
        private String email;

        public String getScene() { return scene; }
        public void setScene(String scene) { this.scene = scene; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    static class MailBindRequest {
        private String email;
        private String code;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }

    /** 免密登录两步共用：第一步只填 email，第二步再带 code。 */
    static class MailLoginRequest {
        private String email;
        private String code;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }

    static class SmsLoginRequest {
        private String phone;
        private String code;

        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
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

