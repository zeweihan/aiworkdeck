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
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * awdk_ → server 会话桥（插件云后端，server 侧）。
 *
 * <p>Office 插件持官网账户 Key（awdk_）来换本服务器的长期设备令牌（awdt_）：
 * 用 Key 调官网 {@code GET /api/account/me} 实时校验，通过后按官网返回的稳定
 * {@code accountId} 查/建 {@code account_binding} 映射，首登自动建无密码用户，
 * 最后经 {@link DeviceTokenService#issue} 签发 awdt_——插件端长期凭据形态与
 * 既有的设备令牌完全一致。
 *
 * <p>红线：
 * <ul>
 *   <li>awdk_ 明文<b>不落库</b>——本期只换会话，每次 awdk-login 都重验官网；</li>
 *   <li>映射键只认 {@code accountId}（官网侧尚未实施，见 doc/desktop-contract.md），
 *       缺失时按 MALFORMED 拒绝，<b>不回落 username</b>——username 可改名，
 *       以它为键会在改名后凭空生出第二个 server 用户；</li>
 *   <li>绝不把官网用户名直接绑到本服务器已有的同名账号上（等于账户接管），
 *       首登一律新建 {@code awd_} 前缀用户；</li>
 *   <li>失败文案不含「登录」「未授权」「请先」子串（licensing 领域地雷 1）。</li>
 * </ul>
 *
 * <p>配置开关 {@code security.awdk-login-enabled} 默认 false：团队服务器与桌面
 * 单机形态都用不到这条桥，只有官方托管的插件云后端显式打开。
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
        if (!enabled) {
            throw new IllegalArgumentException(LangText.of("本服务器未开启账户桥接功能", "This server has not enabled account bridging"));
        }
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
        // per-user 平台 AI key：此刻是 server 唯一合法持有该用户 awdk_ 的时机，顺手换一把
        // 属于他自己的 OpenRouter runtime key 存起来；awdk_ 本身用完即弃，仍然不落库。
        // 取不到（最常见是还没分配额度）绝不影响桥接——插件的绝大多数能力与 AI 额度无关。
        platformAiKeyService.tryProvision(user.getId(), key);
        DeviceTokenService.IssuedToken issued =
                deviceTokenService.issue(user.getId(), LangText.of("账户桥接", "Account bridging"));
        return new BridgeSession(issued.plaintext(), user.getId(), user.getUsername());
    }

    // ==================== 内部 ====================

    private Map<String, Object> fetchMe(String key) {
        AccountTransport.Reply reply = transport.send("GET", baseUrl + "/api/account/me", key, null);
        if (reply.networkFailure()) {
            throw new AccountException(AccountException.Kind.NETWORK,
                    LangText.of("无法连接 AI Workdeck 服务器，请检查网络后重试", "Could not connect to the AI Workdeck server, please check your network and retry"));
        }
        int status = reply.status();
        if (status == 401 || status == 403) {
            throw new AccountException(AccountException.Kind.UNAUTHORIZED,
                    LangText.of("账户 Key 无效或已被撤销，请到官网账户页重新生成", "Account key is invalid or has been revoked; please generate a new one on the website account page"));
        }
        if (status >= 500) {
            throw new AccountException(AccountException.Kind.NETWORK,
                    LangText.of("AI Workdeck 服务器暂时不可用，请稍后重试", "The AI Workdeck server is temporarily unavailable, please retry shortly"));
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
