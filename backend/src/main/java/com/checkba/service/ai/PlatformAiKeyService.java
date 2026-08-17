package com.checkba.service.ai;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.PlatformAiKey;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.PlatformAiKeyRepository;
import com.checkba.service.LangText;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * per-user 平台 AI 通道密钥的取用与存储（server 模式多租户）。
 *
 * <h3>凭据模型</h3>
 * awdk_ 账户 Key 明文<b>永不落库</b>。server 只在两个时刻短暂持有它——桥接登录、显式刷新——
 * 用它向官网换一把该账户的 provisioned OpenRouter runtime key，加密存本表，awdk_ 用完即弃。
 * 因此本库被拖走的后果被限定为「各 key 上的剩余额度」（额度由 OpenRouter 侧 limit 强制），
 * 拿不到官网账户本身：读余额、同步权益、买广场付费项、签发新 key 全都要 awdk_。
 *
 * <h3>吊销与过期</h3>
 * 用户在官网禁用/重发 runtime key → OpenRouter 侧立即失效 → 对账探针拿到 401/403 即
 * {@link #evict} 删行（与「官网明确拒绝 → 立刻清缓存、不吃宽限」同源）。
 * 网络不可达一律保留（服务器故障不等于凭据失效）。外层再由 {@link #OFFLINE_GRACE}
 * 封顶：30 天没有一次成功验证即判过期，需走刷新——永久离线不能等于永久可用。
 *
 * <h3>生效范围</h3>
 * 只有<b>已桥接</b>（存在 {@code account_binding}）的 server 用户走这条路。
 * local-mode 与未桥接的团队服务器成员一律走 {@link PlatformAiChannel} 的机器级文件缓存，
 * 行为与本次改动之前逐字一致。
 */
@Service
@Slf4j
public class PlatformAiKeyService {

    /** 与 EntitlementService.OFFLINE_GRACE / LicenseService.OFFLINE_GRACE 同口径同值。 */
    static final Duration OFFLINE_GRACE = Duration.ofDays(30);

    private static final String KEY_PREFIX = "awdk_";

    private final PlatformAiKeyRepository keyRepository;
    private final AccountBindingRepository bindingRepository;
    private final PlatformAiKeyCipher cipher;
    private final AccountService accountService;

    public PlatformAiKeyService(PlatformAiKeyRepository keyRepository,
                                AccountBindingRepository bindingRepository,
                                PlatformAiKeyCipher cipher,
                                AccountService accountService) {
        this.keyRepository = keyRepository;
        this.bindingRepository = bindingRepository;
        this.cipher = cipher;
        this.accountService = accountService;
    }

    /** 解出来的可用密钥。 */
    public record Resolved(String apiKey, String fingerprint, Double limitUsd) {}

    // ==================== 判据 ====================

    /** 该用户是否已通过账户 Key 完成桥接（有映射才可能有 per-user key）。 */
    public boolean isBound(Long userId) {
        return userId != null && bindingRepository.findByUserId(userId).isPresent();
    }

    /**
     * 本实例是否是多租户形态（存在任一桥接绑定）。
     *
     * 用它把「缺身份即拒绝」的严格判据限定在真正的多租户部署上：一台谁都没桥接过的团队服务器
     * 行为逐字不变，不会因为某处漏传身份就把机器级平台通道打断。
     */
    public boolean multiTenant() {
        return bindingRepository.count() > 0;
    }

    /** 本实例上是否已经存在任一 per-user 密钥。 */
    public boolean anyKeyExists() {
        return keyRepository.count() > 0;
    }

    // ==================== 取用 ====================

    /** 该用户当前可用的密钥；没有 / 解不开 / 已过期都返回空。不发网络请求。 */
    public Optional<Resolved> resolve(Long userId) {
        if (userId == null || !cipher.isConfigured()) return Optional.empty();
        Optional<PlatformAiKey> row = keyRepository.findByUserId(userId);
        if (row.isEmpty()) return Optional.empty();
        PlatformAiKey entity = row.get();
        if (isStale(entity)) return Optional.empty();
        String plaintext;
        try {
            plaintext = cipher.decrypt(entity.getKeyEnc());
        } catch (Exception e) {
            // 不删行：secret 被换过/临时配错时删掉等于把全体用户的 key 静默清空，
            // 而这类故障是可修复的（改回 secret 即恢复）。
            log.warn("用户 {} 的平台 AI 通道密钥无法解密，按不可用处理: {}", userId, e.getMessage());
            return Optional.empty();
        }
        return Optional.of(new Resolved(plaintext, entity.getKeyFingerprint(), entity.getLimitUsd()));
    }

    /** 仅取指纹（对账分桶用），不解密。过期同样视为没有。 */
    public String fingerprintOrNull(Long userId) {
        if (userId == null) return null;
        return keyRepository.findByUserId(userId)
                .filter(e -> !isStale(e))
                .map(PlatformAiKey::getKeyFingerprint)
                .orElse(null);
    }

    // ==================== 写入 ====================

    /**
     * 桥接登录时的「顺手取一把」：<b>任何失败都不得拖垮桥接</b>。
     *
     * 尤其 409 no_allocation 是常态（该账户还没从余额分配 AI 额度），
     * 若据此拒绝桥接，用户会连插件都进不去——而插件绝大多数能力与 AI 额度无关。
     */
    public void tryProvision(Long userId, String awdkKey) {
        try {
            provision(userId, awdkKey);
        } catch (AccountException e) {
            log.info("桥接登录未能取得平台 AI 通道密钥（不影响桥接）userId={} kind={}: {}",
                    userId, e.getKind(), e.getMessage());
        } catch (Exception e) {
            log.warn("桥接登录取平台 AI 通道密钥异常（不影响桥接）userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * 用 awdk_ 明文向官网换该账户的 runtime key 并加密入库（覆盖旧行）。
     * awdk_ 用完即弃。
     *
     * @throws AccountException 官网侧的各类失败（NOT_CONNECTED 除外，此处必然带 Key）
     */
    public void provision(Long userId, String awdkKey) {
        if (!cipher.isConfigured()) {
            throw new AccountException(AccountException.Kind.CONFLICT,
                    LangText.of("本服务器未配置平台 AI 通道密钥的存储密钥，暂无法启用该通道",
                            "This server has no storage secret configured for the platform AI channel key; this channel cannot be enabled yet"));
        }
        Map<String, Object> body = accountService.fetchAiKeyWith(awdkKey);
        String plaintext = String.valueOf(body.get("openrouterKey"));
        Object limit = body.get("limitUsd");

        PlatformAiKey entity = keyRepository.findByUserId(userId).orElseGet(PlatformAiKey::new);
        entity.setUserId(userId);
        entity.setKeyEnc(cipher.encrypt(plaintext));
        entity.setKeyFingerprint(PlatformAiKeyCipher.fingerprint(plaintext));
        entity.setLimitUsd(limit instanceof Number n ? n.doubleValue() : null);
        LocalDateTime now = LocalDateTime.now();
        entity.setFetchedAt(now);
        entity.setLastVerifiedAt(now);
        keyRepository.save(entity);
        log.info("已为用户 {} 取得平台 AI 通道密钥（指纹 {}）", userId, entity.getKeyFingerprint());
    }

    /**
     * 显式刷新：用户在官网分配额度/重发 key 之后，server 手里没有 awdk_ 可用来重取。
     *
     * <p>必须校验这枚 awdk_ 对应的官网 accountId 与当前会话用户的绑定一致——
     * 否则 A 可以把 B 的 Key 贴进来，把 B 的额度装到自己名下用。
     */
    public void refresh(Long userId, String awdkKey) {
        String key = awdkKey == null ? "" : awdkKey.trim();
        if (key.isEmpty() || !key.startsWith(KEY_PREFIX)) {
            throw new AccountException(AccountException.Kind.UNAUTHORIZED,
                    LangText.of("账户 Key 格式不正确，应以 awdk_ 开头（在官网账户页「桌面连接」生成）",
                            "Invalid account key format; it must start with awdk_ (generate one on the website account page under \"Desktop Connection\")"));
        }
        AccountBinding binding = bindingRepository.findByUserId(userId)
                .orElseThrow(() -> new AccountException(AccountException.Kind.CONFLICT,
                        LangText.of("该账号尚未通过账户 Key 完成直连，无法刷新平台 AI 通道额度",
                                "This account has not been connected via an account key yet; the platform AI channel Credits cannot be refreshed")));
        Map<String, Object> me = accountService.fetchProfileWith(key);
        Object accountId = me.get("accountId");
        if (accountId == null || String.valueOf(accountId).isBlank()) {
            throw new AccountException(AccountException.Kind.MALFORMED,
                    LangText.of("官网账户信息缺少 accountId 字段，本服务器暂无法完成额度刷新",
                            "The website account information is missing the accountId field; this server cannot refresh Credits yet"));
        }
        if (!binding.getExternalAccountId().equals(String.valueOf(accountId))) {
            throw new AccountException(AccountException.Kind.CONFLICT,
                    LangText.of("这枚账户 Key 属于另一个 AI WorkDeck 账户，与本账号的直连关系不一致",
                            "This account key belongs to a different AI WorkDeck account and does not match this account's connection"));
        }
        provision(userId, key);
    }

    /** 官网明确拒绝（OpenRouter 401/403）时调用：密钥已被吊销或重发，本地这份立刻作废。 */
    public void evict(Long userId) {
        if (userId == null) return;
        keyRepository.findByUserId(userId).ifPresent(entity -> {
            keyRepository.delete(entity);
            log.info("用户 {} 的平台 AI 通道密钥已被官网侧吊销，本地记录已清除", userId);
        });
    }

    /** 探针成功即刷新验证时间（30 天宽限的计时起点）。 */
    public void markVerified(Long userId) {
        if (userId == null) return;
        keyRepository.findByUserId(userId).ifPresent(entity -> {
            entity.setLastVerifiedAt(LocalDateTime.now());
            keyRepository.save(entity);
        });
    }

    // ==================== 展示 ====================

    /**
     * 额度面板的数据源。密钥明文<b>不出后端</b>——只回掩码与数字。
     * 用量取自 OpenRouter，由 server 代查；查不到时只降级用量这一段，不整块报错。
     */
    public Map<String, Object> status(Long userId, PlatformUsageAccountant accountant) {
        Map<String, Object> result = new HashMap<>();
        boolean bound = isBound(userId);
        result.put("bound", bound);
        result.put("configured", cipher.isConfigured());

        Optional<PlatformAiKey> row = keyRepository.findByUserId(userId);
        result.put("hasKey", row.isPresent());
        result.put("stale", row.map(this::isStale).orElse(false));
        result.put("limitUsd", row.map(PlatformAiKey::getLimitUsd).orElse(null));
        result.put("lastVerifiedAt", row.map(e -> e.getLastVerifiedAt().toString()).orElse(null));
        result.put("keyMasked", row.map(e -> "sk-or-****" + e.getKeyFingerprint()).orElse(null));

        Optional<Resolved> resolved = resolve(userId);
        result.put("available", resolved.isPresent());
        Double usage = resolved.map(r -> accountant.probeUsageForDisplay(userId, r.apiKey())).orElse(null);
        result.put("usageUsd", usage);
        Double limit = row.map(PlatformAiKey::getLimitUsd).orElse(null);
        result.put("remainingUsd", usage == null || limit == null ? null : Math.max(0d, limit - usage));
        // 用量拿不到时前端显示「—」，绝不把 0 当成剩余额度（licensing 领域既有口径）
        result.put("usageAvailable", usage != null);
        return result;
    }

    boolean isStale(PlatformAiKey entity) {
        LocalDateTime verified = entity.getLastVerifiedAt();
        return verified == null || verified.isBefore(LocalDateTime.now().minus(OFFLINE_GRACE));
    }
}
