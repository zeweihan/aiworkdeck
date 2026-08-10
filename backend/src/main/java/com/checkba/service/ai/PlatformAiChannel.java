package com.checkba.service.ai;

import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * 平台 AI 通道「AI Workdeck 云端」（Spec §3）。
 *
 * 用户不必自备 OpenRouter key：连接账户后由官网 provision 一把带额度上限的 runtime key
 * （POST /api/account/ai-key），桌面端拿来直连 OpenRouter。额度强制在 OpenRouter 侧执行
 * （key 自带 limit，超限 402），桌面端不做也做不了额度校验。
 *
 * key 缓存在 {@code ~/.aiworkdeck/platform-ai-key.json}（0600）。官网端点是幂等的，
 * 缓存只是省一次往返；官网撤销重发后本地 clearCache 即可重新拉取。
 *
 * 未连接账户时该通道不可用（{@link #isAvailable()} 为 false，前端据此不展示为可选供应商）。
 *
 * <h3>server 模式多租户（2026-08-07）</h3>
 * 上面描述的是<b>机器级</b>路径，local-mode 与未桥接的团队服务器成员继续走它，行为逐字不变。
 * server 模式下<b>已桥接</b>（存在 account_binding）的用户改走 per-user 库记录
 * （{@link PlatformAiKeyService}）：OpenRouter 的额度上限是 per-key 的，一把机器级 key
 * 等于一个共享额度池，A 能把 B 的额度花光，且对账差分会把 A 的消费记到 B 头上。
 *
 * <p>身份从 {@link PlatformAiUserScope} 取。多租户形态下缺身份一律拒绝，
 * <b>绝不回落机器级 key</b>——错账是静默的，报错是能被测试抓到的。
 */
@Service
@Slf4j
public class PlatformAiChannel {

    private final AccountService accountService;
    private final PlatformAiKeyService perUser;
    private final boolean localMode;
    private final Path keyFile;
    // 解析失败的异常 message 不许带原文——这个文件里是 provision 出来的 OpenRouter 明文密钥
    private final ObjectMapper objectMapper = AccountService.stateMapper();

    private volatile Cached memory;

    public PlatformAiChannel(
            AccountService accountService,
            PlatformAiKeyService perUser,
            @Value("${security.local-mode:false}") boolean localMode,
            @Value("${security.license.dir:${user.home}/.aiworkdeck}") String stateDir) {
        this.accountService = accountService;
        this.perUser = perUser;
        this.localMode = localMode;
        this.keyFile = Path.of(stateDir, "platform-ai-key.json");
    }

    /** 持久化结构：~/.aiworkdeck/platform-ai-key.json */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Cached {
        public String openrouterKey;
        public Double limitUsd;
        public String fetchedAt;
        /**
         * 签发这把 key 的账户指纹（{@link AccountService#accountFingerprintOrNull()}）。
         * 缓存文件是<b>机器级</b>的、而 key 是<b>账户级</b>的，没有这个字段就没法判断
         * 「现在连着的还是不是当初换来它的那个账户」——换个没充值的账号进来会继续花上一个账号的额度。
         * 存量文件没有这个字段，读到 null 视为不匹配、丢弃重取（见 {@link #current()}）。
         */
        public String owner;
    }

    /** 机器级通道是否可用（账户已连接）。不发网络请求。AccountController 的机器级视图用它。 */
    public boolean isAvailable() {
        return accountService.isConnected();
    }

    /** 指定用户视角下平台通道是否可用（前端「可选供应商」判据）。不发网络请求。 */
    public boolean availableFor(Long userId) {
        if (perUserApplies(userId)) return perUser.resolve(userId).isPresent();
        if (strictMultiTenant()) return false;
        return accountService.isConnected();
    }

    /**
     * 取当前身份的平台通道密钥。
     *
     * @throws AccountException NOT_CONNECTED / CONFLICT（未分配额度、未直连、缺身份）/ NETWORK
     */
    public String apiKey() {
        return resolveOrThrow(PlatformAiUserScope.current()).apiKey();
    }

    /** 当前额度上限（美元）；未取到返回 null。不触发网络请求。机器级视图专用。 */
    public Double limitUsd() {
        Cached cached = current();
        return cached == null ? null : cached.limitUsd;
    }

    /**
     * 模型实例缓存用的密钥指纹。key 换了指纹就变，
     * {@link ChatModelFactory} 因此不会把旧 key 建的模型实例一直用到进程重启；
     * per-user 化之后不同用户指纹不同，模型实例天然按用户分叉，不会串用。
     */
    public String keyFingerprint() {
        Long userId = PlatformAiUserScope.current();
        if (perUserApplies(userId)) {
            String fingerprint = perUser.fingerprintOrNull(userId);
            return fingerprint == null ? "none" : fingerprint;
        }
        return machineFingerprint();
    }

    /**
     * 给定用户的密钥与指纹，不依赖线程作用域——对账 worker 在自己的线程上跑，用这个。
     * 取不到返回 null（对账拿不到 key 只是跳过，不该抛）。
     */
    public PlatformAiKeyService.Resolved resolveFor(Long userId) {
        try {
            return resolveOrThrow(userId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * OpenRouter 明确拒绝（401/403）：这把 key 已被官网侧吊销或重发，本地这份立刻作废。
     * 与「官网明确拒绝 → 立刻清缓存、不吃宽限」同源；网络不可达绝不走这条。
     */
    public void onKeyRejected(Long userId) {
        if (perUserApplies(userId)) {
            perUser.evict(userId);
            return;
        }
        clearCache();
    }

    /** 探针成功：刷新 30 天宽限的计时起点。 */
    public void onKeyVerified(Long userId) {
        if (perUserApplies(userId)) perUser.markVerified(userId);
    }

    /** 本实例上是否存在按用户的平台通道密钥（断开机器级账户时的降级判据）。 */
    public boolean hasPerUserKeys() {
        return !localMode && perUser.anyKeyExists();
    }

    // ==================== 路由 ====================

    /** 已桥接的 server 用户走 per-user；local-mode 与未桥接成员走机器级。 */
    private boolean perUserApplies(Long userId) {
        return !localMode && userId != null && perUser.isBound(userId);
    }

    /**
     * 这次调用会不会用到<b>机器级</b>密钥文件。{@link PlatformCreditsGate} 用它决定余额闸要不要生效：
     * 机器级路径的余额是「本机连着的那个账户」的，能用 {@code AccountService} 直接查；
     * per-user 路径的额度由官网在签发 key 时就按人闸住了，这里再查一遍既查不到（拿不到对方的 awdk_ Key）
     * 也没必要。
     */
    public boolean usesMachineKey(Long userId) {
        return !perUserApplies(userId) && !strictMultiTenant();
    }

    /** 多租户形态（本实例存在任一桥接绑定）下，机器级回落是禁止的。 */
    private boolean strictMultiTenant() {
        return !localMode && perUser.multiTenant();
    }

    private PlatformAiKeyService.Resolved resolveOrThrow(Long userId) {
        if (perUserApplies(userId)) {
            return perUser.resolve(userId).orElseThrow(() -> new AccountException(
                    AccountException.Kind.CONFLICT,
                    "本账号的「AI Workdeck 云端」额度尚未就绪，可在插件设置页用账户 Key 刷新额度"));
        }
        if (strictMultiTenant()) {
            // 多租户实例上回落机器级 key = 拿别人的额度花钱，宁可报错
            if (userId == null) {
                throw new AccountException(AccountException.Kind.CONFLICT,
                        "本次 AI 调用未携带用户身份，「AI Workdeck 云端」无法确定额度归属");
            }
            throw new AccountException(AccountException.Kind.CONFLICT,
                    "本账号尚未通过账户 Key 完成直连，暂不能使用「AI Workdeck 云端」通道");
        }
        if (!accountService.isConnected()) {
            throw new AccountException(AccountException.Kind.NOT_CONNECTED,
                    "「AI Workdeck 云端」需要连接账户，请到设置页粘贴账户 Key");
        }
        Cached cached = current();
        if (cached == null) cached = fetch();
        return new PlatformAiKeyService.Resolved(
                cached.openrouterKey, machineFingerprint(), cached.limitUsd);
    }

    private String machineFingerprint() {
        Cached cached = current();
        if (cached == null || cached.openrouterKey == null) return "none";
        String fingerprint = fingerprint(cached.openrouterKey);
        return fingerprint == null ? "none" : fingerprint;
    }

    private static String fingerprint(String material) {
        if (material == null || material.isBlank()) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            return null;
        }
    }

    /** 断开账户或官网撤销重发后调用。 */
    public synchronized void clearCache() {
        memory = null;
        try {
            Files.deleteIfExists(keyFile);
        } catch (Exception e) {
            log.warn("平台 AI 通道密钥缓存清除失败: {}", e.getMessage());
        }
    }

    // ==================== 内部 ====================

    /**
     * 缓存必须与「当初换来它的那个账户」对得上，否则一律丢弃重取。
     *
     * <p>没有这道判断时，换账号会留下一把别人的 key 继续用：解锁页粘 {@code awdk_} 走的是
     * {@code LicenseController} 那条路，它调 {@code AccountService.connect} 而<b>不经过</b>
     * {@code AccountController.connect}，也就吃不到那里的 {@link #clearCache()}。
     * 结果是一个没充值的新账号接着花上一个账号的 OpenRouter 额度。
     * 把判据放在读缓存这一刻，任何入口漏调 clearCache 都不再造成漏钱——
     * 「记得清缓存」是会忘的，「归属对不上就不认」不会。
     *
     * <p>存量文件的 {@code owner} 是 null，与已连接账户的非空指纹不相等，因此会走一次重取并重新绑定。
     * 平台通道本来就要联网打 OpenRouter，不存在「离线靠这份缓存续命」的场景，丢弃是安全的。
     */
    private synchronized Cached current() {
        if (memory != null) return ownedByCurrentAccount(memory) ? memory : discard();
        try {
            if (Files.exists(keyFile)) {
                Cached cached = objectMapper.readValue(Files.readAllBytes(keyFile), Cached.class);
                if (cached != null && cached.openrouterKey != null && !cached.openrouterKey.isBlank()) {
                    if (!ownedByCurrentAccount(cached)) return discard();
                    memory = cached;
                    return memory;
                }
            }
        } catch (Exception e) {
            log.warn("平台 AI 通道密钥缓存读取失败，将重新获取: {}", e.getMessage());
        }
        return null;
    }

    private boolean ownedByCurrentAccount(Cached cached) {
        return java.util.Objects.equals(cached.owner, accountService.accountFingerprintOrNull());
    }

    private Cached discard() {
        log.info("平台 AI 通道密钥缓存归属与当前账户不符，已丢弃并将重新获取");
        clearCache();
        return null;
    }

    private synchronized Cached fetch() {
        Map<String, Object> body = accountService.fetchAiKey();
        Cached cached = new Cached();
        cached.openrouterKey = String.valueOf(body.get("openrouterKey"));
        Object limit = body.get("limitUsd");
        cached.limitUsd = limit instanceof Number n ? n.doubleValue() : null;
        cached.fetchedAt = Instant.now().toString();
        cached.owner = accountService.accountFingerprintOrNull();
        try {
            Files.createDirectories(keyFile.getParent());
            Files.write(keyFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(cached));
            AccountService.restrictPermissions(keyFile);
        } catch (Exception e) {
            // 落盘失败不致命：本次进程内仍可用，下次重启再拉一次
            log.warn("平台 AI 通道密钥缓存写入失败: {}", e.getMessage());
        }
        memory = cached;
        return cached;
    }
}
