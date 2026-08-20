package com.checkba.service.account;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.User;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.LangText;
import com.checkba.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * 官网账户 → server 会话桥（插件云后端，server 侧）。
 *
 * <p>核心一段永远是同一条：拿一枚官网账户 Key（awdk_）调官网
 * {@code GET /api/account/me} 实时校验，通过后按官网返回的稳定 {@code accountId}
 * 查/建 {@code account_binding} 映射，首登自动建无密码用户，最后经
 * {@link DeviceTokenService#issue} 签发 awdt_——插件端长期凭据形态与既有的设备令牌完全一致。
 *
 * <p>Key 有两种来源，桥接之后的处理完全相同：
 * <ul>
 *   <li><b>账户登录</b>（主路径）：{@link #loginWithPhone} / {@link #loginWithPassword}
 *       先拿手机号验证码或邮箱口令调官网 {@code /api/auth/exchange-key} 换出 Key，
 *       用户从头到尾看不见它；</li>
 *   <li><b>手工粘贴</b>（保留给私有部署与团队服务器）：{@link #login(String)} 直接收 Key。</li>
 * </ul>
 *
 * <p>红线：
 * <ul>
 *   <li>awdk_ 明文<b>不落库</b>——本期只换会话，每次 awdk-login 都重验官网；</li>
 *   <li>映射键只认 {@code accountId}（官网侧已实施并进了权威契约，见 doc/desktop-contract.md），
 *       缺失时按 MALFORMED 拒绝，<b>不回落 username</b>——username 可改名，
 *       以它为键会在改名后凭空生出第二个 server 用户；</li>
 *   <li>绝不把官网用户名直接绑到本服务器已有的同名账号上（等于账户接管），
 *       首登一律新建 {@code awd_} 前缀用户；</li>
 *   <li>失败文案不含「登录」「未授权」「请先」子串（licensing 领域地雷 1）。</li>
 * </ul>
 *
 * <p>配置开关 {@code security.awdk-login-enabled} 默认 false：团队服务器与桌面
 * 单机形态都用不到这条桥，只有官方托管的插件云后端显式打开。<b>账户登录不另设开关</b>——
 * 它与手工粘 Key 是同一条桥的两个入口（同样出站到官网、同样建 account_binding、
 * 同样签发 awdt_），拆成两个开关只会造出「登录能用但桥是关的」这种自相矛盾的配置，
 * 以及一份没人记得同时设置的部署清单。
 */
@Service
@Slf4j
public class AwdkLoginService {

    static final String KEY_PREFIX = "awdk_";
    /** 首登自动建号的用户名前缀，与人工注册的账号空间隔离。 */
    static final String USERNAME_PREFIX = "awd_";

    private final boolean enabled;
    private final String baseUrl;
    private final AccountTransport transport;
    private final AccountBindingRepository bindingRepository;
    private final UserService userService;
    private final DeviceTokenService deviceTokenService;
    private final com.checkba.service.ai.PlatformAiKeyService platformAiKeyService;
    private final ObjectMapper objectMapper = AccountService.stateMapper();

    public AwdkLoginService(
            @Value("${security.awdk-login-enabled:false}") boolean enabled,
            @Value("${ai.account.base-url:https://www.aiworkdeck.com}") String baseUrl,
            AccountTransport transport,
            AccountBindingRepository bindingRepository,
            UserService userService,
            DeviceTokenService deviceTokenService,
            com.checkba.service.ai.PlatformAiKeyService platformAiKeyService) {
        this.enabled = enabled;
        // 与 LicenseService / AccountService 共用同一条红线（https，回环 http 例外）
        this.baseUrl = AccountEndpoint.requireSecure(baseUrl);
        this.transport = transport;
        this.bindingRepository = bindingRepository;
        this.userService = userService;
        this.deviceTokenService = deviceTokenService;
        this.platformAiKeyService = platformAiKeyService;
    }

    /** 桥接结果：awdt_ 设备令牌 + 映射到的 server 用户。 */
    public record BridgeSession(String token, Long userId, String username) {}

    /**
     * @throws IllegalArgumentException 开关关闭（业务错误，非鉴权错误）
     * @throws AccountException UNAUTHORIZED（Key 无效/格式错）/ NETWORK / MALFORMED（缺 accountId 等）
     */
    public synchronized BridgeSession login(String rawKey) {
        requireEnabled();
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isEmpty() || !key.startsWith(KEY_PREFIX)) {
            throw new AccountException(AccountException.Kind.UNAUTHORIZED,
                    LangText.of("账户 Key 格式不正确，应以 awdk_ 开头（在官网账户页「桌面连接」生成）",
                            "Invalid account key format; it must start with awdk_ (generate one on the website account page under \"Desktop Connection\")"));
        }
        Map<String, Object> me = fetchMe(key);
        String accountId = str(me.get("accountId"));
        if (accountId == null || accountId.isBlank()) {
            throw new AccountException(AccountException.Kind.MALFORMED,
                    LangText.of("官网账户信息缺少 accountId 字段，本服务器暂无法完成账户桥接",
                            "The website account information is missing the accountId field; this server cannot complete account bridging yet"));
        }

        User user = resolveUser(accountId, str(me.get("username")), str(me.get("displayName")));
        // 手机端账号归一（dev-board#30）：官网账户带手机号时认领到桥接用户名下，
        // 此后手机端 sms-login 解析到同一账号。方法自吞异常，不影响桥接。
        userService.claimPhoneFromWebsite(user, str(me.get("phone")));
        // per-user 平台 AI key：此刻是 server 唯一合法持有该用户 awdk_ 的时机，顺手换一把
        // 属于他自己的 OpenRouter runtime key 存起来；awdk_ 本身用完即弃，仍然不落库。
        // 取不到（最常见是还没分配额度）绝不影响桥接——插件的绝大多数能力与 AI 额度无关。
        platformAiKeyService.tryProvision(user.getId(), key);
        DeviceTokenService.IssuedToken issued =
                deviceTokenService.issue(user.getId(), LangText.of("账户桥接", "Account bridging"));
        return new BridgeSession(issued.plaintext(), user.getId(), user.getUsername());
    }

    // ==================== 账户登录（手机号/邮箱直登，用户不必人肉搬运 Key） ====================

    /**
     * 官网登录：给手机号发验证码（转发官网 {@code /api/auth/sms-login/send-code}）。
     *
     * <p>这条与下面两个 login 一起，替掉了插件用户「去官网账户页生成 Key、复制、粘进设置页」
     * 那三步。{@code awdk_} Key 本身<b>保留不动</b>——{@code account_binding} 映射、
     * 平台 AI 取 key 全挂在它上面，只是用户不再需要亲眼见到它。
     *
     * <p>受同一个 {@code security.awdk-login-enabled} 开关约束：桥接关着的服务器
     * 不该变成一个任人调用的短信转发口。
     *
     * @throws IllegalArgumentException 开关关闭
     * @throws AccountException NETWORK / UNAUTHORIZED（手机号不合法等）/ MALFORMED
     */
    public void sendLoginCode(String phone, String captchaToken) {
        requireEnabled();
        Map<String, Object> body = new HashMap<>();
        body.put("phone", phone == null ? "" : phone.trim());
        // 人机验证 token 必须原样透传。官网启用后不带就是 403，而请求体是这里拼的——
        // 插件端填了也传不过去。这是到官网 send-code 的**第二条**转发链
        // （另一条在 AccountService，桌面端用），漏掉任何一条那条闸都是摆设。
        body.put("captchaToken", captchaToken == null ? "" : captchaToken.trim());
        AccountLoginExchange.post(transport, objectMapper, baseUrl, "/api/auth/sms-login/send-code", body);
    }

    /** 官网人机验证的公开配置，原样转给插件端（只有公开参数，没有密钥）。 */
    public Map<String, Object> captchaConfig() {
        AccountTransport.Reply reply = transport.send("GET", baseUrl + "/api/auth/captcha-config", null, null);
        if (reply.networkFailure() || reply.status() < 200 || reply.status() >= 300) {
            Map<String, Object> off = new HashMap<>();
            off.put("provider", null);
            return off;
        }
        return AccountLoginExchange.parse(objectMapper, reply.body());
    }

    /** 官网登录：手机号 + 验证码换 Key 再桥接（大陆站主路径）。 */
    public BridgeSession loginWithPhone(String phone, String code) {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("phone", phone == null ? "" : phone.trim());
        credentials.put("code", code == null ? "" : code.trim());
        return exchangeAndBridge(credentials);
    }

    /** 官网登录：账号 + 口令换 Key 再桥接（国际站主路径，及大陆站补绑期内的存量账号）。 */
    public BridgeSession loginWithPassword(String account, String password) {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("account", account == null ? "" : account.trim());
        credentials.put("password", password == null ? "" : password);
        return exchangeAndBridge(credentials);
    }

    /**
     * 换 Key 后走 {@link #login(String)} 那条既有链路。
     *
     * <p>刻意<b>不</b>给本方法加 synchronized：{@code login} 已经是全局串行的（保护建号与
     * 绑定映射的竞态），把 exchange-key 这一次出站也圈进同一把锁，等于让所有用户的登录
     * 排队等一次公网往返。锁的范围与改造前保持一致。
     *
     * <p>换回的 Key 用完即弃，与手工粘贴那条路一样不落库。
     */
    private BridgeSession exchangeAndBridge(Map<String, Object> credentials) {
        requireEnabled();
        // 这条服务本身就是「Office 插件」的桥接口，设备名固定为插件口径（不像桌面端那样按主机名取）；
        // 按 baseUrl 分站，因为国际站账户页文案是英文。
        credentials.put("deviceName", baseUrl.contains("workdeck.ai") ? "Office Add-in" : "Office 插件");
        Map<String, Object> payload =
                AccountLoginExchange.post(transport, objectMapper, baseUrl, "/api/auth/exchange-key", credentials);
        String key = str(payload.get("key"));
        // 前缀在 login() 里还会再验一遍，但那句文案是写给「手工粘 Key」的用户看的
        // （教他去官网重新生成）。走到这里的用户从头到尾没见过 Key，得说是官网的问题。
        if (key == null || key.isBlank() || !key.startsWith(KEY_PREFIX)) {
            throw new AccountException(AccountException.Kind.MALFORMED,
                    LangText.of("官网没有返回可用的账户凭据，请稍后重试",
                            "The website did not return a usable account credential, please retry shortly"));
        }
        return login(key);
    }

    // ==================== 内部 ====================

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalArgumentException(LangText.of("本服务器未开启账户桥接功能", "This server has not enabled account bridging"));
        }
    }

    private Map<String, Object> fetchMe(String key) {
        AccountTransport.Reply reply = transport.send("GET", baseUrl + "/api/account/me", key, null);
        if (reply.networkFailure()) {
            throw new AccountException(AccountException.Kind.NETWORK,
                    LangText.of("无法连接 AI WorkDeck 服务器，请检查网络后重试", "Could not connect to the AI WorkDeck server, please check your network and retry"));
        }
        int status = reply.status();
        if (status == 401 || status == 403) {
            throw new AccountException(AccountException.Kind.UNAUTHORIZED,
                    LangText.of("账户 Key 无效或已被撤销，请到官网账户页重新生成", "Account key is invalid or has been revoked; please generate a new one on the website account page"));
        }
        if (status >= 500) {
            throw new AccountException(AccountException.Kind.NETWORK,
                    LangText.of("AI WorkDeck 服务器暂时不可用，请稍后重试", "The AI WorkDeck server is temporarily unavailable, please retry shortly"));
        }
        if (status < 200 || status >= 300) {
            throw new AccountException(AccountException.Kind.MALFORMED,
                    LangText.of("官网返回了预期外的状态（", "The website returned an unexpected status (") + status
                            + LangText.of("），请稍后重试", "), please retry shortly"));
        }
        try {
            Map<String, Object> parsed =
                    objectMapper.readValue(reply.body(), new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new AccountException(AccountException.Kind.MALFORMED,
                    LangText.of("官网返回的内容无法解析，请稍后重试", "Could not parse the website's response, please retry shortly"));
        }
    }

    private User resolveUser(String accountId, String externalUsername, String displayName) {
        Optional<AccountBinding> existing = bindingRepository.findByExternalAccountId(accountId);
        if (existing.isPresent()) {
            AccountBinding binding = existing.get();
            try {
                User user = userService.getUserById(binding.getUserId());
                binding.setLastLoginAt(LocalDateTime.now());
                bindingRepository.save(binding);
                return user;
            } catch (IllegalArgumentException e) {
                // 绑定指向的用户已被删除：按首登重建，绑定改指新用户
                log.warn("account_binding {} 指向的用户 {} 不存在，重建", accountId, binding.getUserId());
                User user = createExternalUser(accountId, externalUsername, displayName);
                binding.setUserId(user.getId());
                binding.setLastLoginAt(LocalDateTime.now());
                bindingRepository.save(binding);
                return user;
            }
        }
        User user = createExternalUser(accountId, externalUsername, displayName);
        AccountBinding binding = new AccountBinding();
        binding.setExternalAccountId(accountId);
        binding.setUserId(user.getId());
        binding.setCreatedAt(LocalDateTime.now());
        binding.setLastLoginAt(LocalDateTime.now());
        bindingRepository.save(binding);
        log.info("awdk 桥接首登建号: accountId={} -> userId={} username={}",
                accountId, user.getId(), user.getUsername());
        return user;
    }

    private User createExternalUser(String accountId, String externalUsername, String displayName) {
        String username = allocateUsername(externalUsername, accountId);
        String display = displayName != null && !displayName.isBlank() ? displayName
                : (externalUsername != null && !externalUsername.isBlank() ? externalUsername : username);
        return userService.registerExternal(username, display);
    }

    /**
     * 用户名分配：awd_ 前缀 + 官网用户名（清洗后），撞名时追加 accountId 短哈希。
     * 绝不复用已存在的账号——官网用户名与本服务器同名账号是两个世界的身份。
     */
    private String allocateUsername(String externalUsername, String accountId) {
        String base = sanitize(externalUsername);
        if (base.isEmpty()) base = "user";
        String candidate = truncate(USERNAME_PREFIX + base);
        if (userService.getUserByUsername(candidate).isEmpty()) return candidate;
        candidate = truncate(USERNAME_PREFIX + base + "_" + shortHash(accountId));
        if (userService.getUserByUsername(candidate).isEmpty()) return candidate;
        throw new AccountException(AccountException.Kind.MALFORMED,
                LangText.of("无法为该账户分配用户名，请联系服务器管理员", "Could not allocate a username for this account, please contact the server administrator"));
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        return name.replaceAll("[^A-Za-z0-9_-]", "").toLowerCase();
    }

    /** User.username 列宽 64。 */
    private static String truncate(String username) {
        return username.length() <= 64 ? username : username.substring(0, 64);
    }

    private static String shortHash(String accountId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(accountId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
