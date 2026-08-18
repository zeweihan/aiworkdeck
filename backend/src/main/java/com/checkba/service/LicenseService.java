package com.checkba.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * 桌面解锁门的授权状态管理（商业化改造 PR-A）。
 *
 * 两条解锁路：
 * - 试用码（离线）：`AWD-T-...`，内置 Ed25519 公钥验签，见 {@link TrialCodeVerifier}；
 * - 账户 Key（在线）：`awdk_...`，POST 官网 /api/license/verify-key 校验。
 *
 * 状态持久化在 ~/.aiworkdeck/license.json（与 H2 local.mv.db 同级目录）。
 * account 模式启动时机会性复验；断网 30 天宽限，超期 status 回落未解锁并提示联网复验。
 *
 * 非 local-mode（团队服务器）部署不设解锁门：status 恒为已解锁正式版。
 *
 * <h3>官方版必须账户登录（2026-08-18）</h3>
 * 官方发布的桌面版把试用码这条解锁路关掉（{@code security.license.trial-code.enabled=false}，
 * 见 application-desktop.yml），解锁门只接受账户凭据——手机号/邮箱登录，或手工粘 {@code awdk_} Key。
 * 商业版 / 私有部署 / 自行 fork 把该项改回 {@code true} 即完全恢复原行为。
 *
 * <p><b>这个闸是默认值不是 DRM</b>：代码里的默认值仍是 {@code true}，
 * 关闭只发生在 desktop profile 的那一行 yml 上，刻意不做防篡改。
 * 之所以不改 {@code security.local-mode}（立项书原方案），是因为那一位是
 * 「这是单机桌面版」的判别位而非「要不要登录」的开关——翻它会连带关掉解锁门本身、
 * 免费额度、平台 AI 通道、本机设备令牌与切站能力，还会让 {@code /api/account/login}
 * 自己把自己锁死（该端点走 MachineAccountGuard，非 local-mode 要求先有 session）。
 * 完整论证见 docs/superpowers/specs/2026-08-18-desktop-account-required-design.md §1。
 *
 * <p>存量用试用码解锁的机器有一段过渡期（{@code legacy-grace-until}，默认与官网手机号
 * 补绑同一天），期内照常可用并倒计时提醒，到期才落回未解锁。数据始终在本机 H2 库里，
 * 被挡住的用户一条也没丢。
 */
@Service
@Slf4j
public class LicenseService {

    static final Duration OFFLINE_GRACE = Duration.ofDays(30);

    /** 离线宽限剩余不足这么多天时，status 开始附 graceKind/daysRemaining 供顶栏预警。 */
    static final long GRACE_WARNING_DAYS = 7;

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    private final boolean localMode;
    private final boolean trialCodeEnabled;
    /** 存量 trial 票据的宽限硬期限；null = 无宽限（缺省或配置非法时按已到期处理）。 */
    private final LocalDate legacyTrialGraceUntil;
    private final com.checkba.service.site.SiteProfileService siteProfileService;
    private final Path licenseFile;
    // 解析失败的异常 message 不许带原文——license.json 里存着明文 awdk_ 账户 Key
    private final ObjectMapper objectMapper = com.checkba.service.account.AccountService.stateMapper();

    private volatile PublicKey trialPublicKey;

    public LicenseService(
            @Value("${security.local-mode:false}") boolean localMode,
            com.checkba.service.site.SiteProfileService siteProfileService,
            @Value("${security.license.dir:${user.home}/.aiworkdeck}") String licenseDir,
            @Value("${security.license.trial-code.enabled:true}") boolean trialCodeEnabled,
            @Value("${security.license.trial-code.legacy-grace-until:}") String legacyGraceUntil) {
        this.localMode = localMode;
        this.trialCodeEnabled = trialCodeEnabled;
        this.legacyTrialGraceUntil = parseGraceDate(legacyGraceUntil);
        // 授权服务器地址由站点决定（协议校验在 SiteProfileService 里，与 AccountService 共用
        // AccountEndpoint 那一份实现：https，回环 http 例外）。切站后当场改指向。
        this.siteProfileService = siteProfileService;
        this.licenseFile = Path.of(licenseDir, "license.json");
    }

    /** 持久化结构：~/.aiworkdeck/license.json */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class State {
        public String mode = "none"; // none | trial | account
        public String code;
        public String activatedAt;
        public String lastVerifiedAt;
    }

    /** 是否单机模式。解锁门只在单机模式下存在，调用方据此决定要不要做解锁的后续动作。 */
    public boolean isLocalMode() {
        return localMode;
    }

    /** account 模式启动时机会性复验（后台线程，不阻塞启动，失败静默）。 */
    @PostConstruct
    void reverifyOnStartup() {
        if (!localMode) return;
        State state = loadState();
        if (!"account".equals(state.mode) || state.code == null) return;
        Thread thread = new Thread(() -> {
            try {
                VerifyKeyOutcome outcome = callVerifyKey(state.code);
                if (outcome == VerifyKeyOutcome.VALID) {
                    synchronized (this) {
                        State latest = loadState();
                        if ("account".equals(latest.mode)) {
                            latest.lastVerifiedAt = Instant.now().toString();
                            saveState(latest);
                        }
                    }
                    log.info("账户授权联网复验通过");
                } else if (outcome == VerifyKeyOutcome.INVALID) {
                    synchronized (this) {
                        State latest = loadState();
                        if ("account".equals(latest.mode)) {
                            saveState(new State());
                        }
                    }
                    log.warn("账户 Key 已失效（官网明确拒绝），授权状态已清除");
                }
                // UNREACHABLE：断网等场景静默跳过，走 30 天宽限
            } catch (Exception e) {
                log.debug("启动期授权复验跳过: {}", e.getMessage());
            }
        }, "license-reverify");
        thread.setDaemon(true);
        thread.start();
    }

    /** GET /api/license/status 的数据源。 */
    public synchronized Map<String, Object> status() {
        if (!localMode) {
            // 团队服务器部署不设解锁门
            return Map.of("unlocked", true, "mode", "account", "plan", "paid");
        }
        Map<String, Object> result = statusOf(loadState());
        // 解锁页据此决定还要不要渲染「试用码」标签：判据只有后端一处，前端不自己猜
        result.put("trialCodeEnabled", trialCodeEnabled);
        return result;
    }

    /** local-mode 下按票据模式分派。 */
    private Map<String, Object> statusOf(State state) {
        switch (state.mode == null ? "none" : state.mode) {
            case "trial": {
                if (trialCodeEnabled) {
                    return unlockedStatus("trial", "trial", state.activatedAt);
                }
                // 官方版关掉试用码之后，存量票据走过渡期：期内照常可用 + 倒计时
                long days = legacyTrialDaysRemaining();
                if (days <= 0) {
                    Map<String, Object> expired = new HashMap<>();
                    expired.put("unlocked", false);
                    expired.put("mode", "trial");
                    expired.put("plan", "trial");
                    expired.put("message", legacyTrialExpiredMessage());
                    return expired;
                }
                Map<String, Object> ok = unlockedStatus("trial", "trial", state.activatedAt);
                ok.put("graceKind", "legacyTrial");
                ok.put("daysRemaining", days);
                return ok;
            }
            case "account": {
                long days = offlineGraceDaysRemaining(state);
                if (days > 0) {
                    Map<String, Object> ok = unlockedStatus("account", "paid", state.activatedAt);
                    // 只在临门几天才带这两个字段：不需要提醒时前端拿到的形状和过去一模一样
                    if (days <= GRACE_WARNING_DAYS) {
                        ok.put("graceKind", "offlineReverify");
                        ok.put("daysRemaining", days);
                    }
                    return ok;
                }
                Map<String, Object> expired = new HashMap<>();
                expired.put("unlocked", false);
                expired.put("mode", "account");
                expired.put("plan", "paid");
                expired.put("message", offlineGraceExpiredMessage());
                return expired;
            }
            default: {
                Map<String, Object> none = new HashMap<>();
                none.put("unlocked", false);
                none.put("mode", "none");
                none.put("plan", "none");
                return none;
            }
        }
    }

    /** POST /api/license/activate。 */
    public synchronized Map<String, Object> activate(String code) {
        if (!localMode) {
            return Map.of("unlocked", true, "mode", "account", "plan", "paid",
                    "message", "团队服务器部署无需激活");
        }
        if (code == null || code.isBlank()) {
            return failure("激活码不能为空");
        }
        String trimmed = code.trim();
        if (trimmed.startsWith("awdk_")) {
            return activateAccountKey(trimmed);
        }
        // 官方版：试用码不再是解锁路。这里兜住的是「非 awdk_ 的一切输入」而不只是 AWD-T-，
        // 因为关掉之后这个输入框事实上只收账户 Key，文案要把两条可用的路都说清楚。
        if (!trialCodeEnabled) {
            return failure(trialDisabledMessage());
        }
        return activateTrialCode(trimmed);
    }

    /**
     * 账户已在别处校验通过，只把解锁票据落下来（不再打一次官网）。
     *
     * <p><b>为什么必须有这条</b>：解锁状态的唯一数据源是 license 票据（{@link #statusOf}），
     * 而桌面端的账户登录走的是 {@code AccountService}——它只写账户状态，从不碰票据。
     * 结果是「登录成功」但 {@code status().unlocked} 仍为 false，launch 页把人原地弹回解锁页，
     * 表现为「弹了个已解锁的提示，然后什么都没发生」。2026-08-18 真机踩到，
     * 自 PR#408 引入账户登录起就一直如此。
     *
     * <p>与 {@link #activateAccountKey} 的区别只有一处：那条要自己打官网验 Key，
     * 这条的调用方（{@code AccountService.connect}）已经拉过 {@code /api/account/me} 验过了，
     * 再验一次既多一次往返，也会让「网络刚好抖一下」把一次成功的登录变成失败。
     */
    public synchronized void markAccountUnlocked(String key) {
        if (!localMode || key == null || key.isBlank()) return;
        State state = new State();
        state.mode = "account";
        state.code = key;
        state.activatedAt = Instant.now().toString();
        state.lastVerifiedAt = state.activatedAt;
        saveState(state);
    }

    /** POST /api/license/deactivate。 */
    public synchronized Map<String, Object> deactivate() {
        if (!localMode) {
            return Map.of("unlocked", true, "mode", "account", "plan", "paid",
                    "message", "团队服务器部署无需激活");
        }
        saveState(new State());
        return Map.of("unlocked", false, "mode", "none", "plan", "none");
    }

    /**
     * 只清除 {@code account} 模式的授权票据，{@code trial} 原样保留。切站专用
     * （{@link com.checkba.service.site.SiteSwitchService}，双主站设计 §2.4）。
     *
     * <p>account 票据是旧站 verify-key 发的，换站必须作废；试用码是内置公钥离线验签的，
     * 与站点无关——顺手抹掉它等于把一个只想换站看看的试用用户直接踢回未解锁页。
     *
     * @return 是否真的清了（原本就不是 account 模式时为 false）
     */
    public synchronized boolean deactivateAccountMode() {
        if (!localMode) return false;
        State state = loadState();
        if (!"account".equals(state.mode)) return false;
        saveState(new State());
        return true;
    }

    // ==================== 激活分支 ====================

    private Map<String, Object> activateAccountKey(String key) {
        VerifyKeyOutcome outcome;
        try {
            outcome = callVerifyKey(key);
        } catch (Exception e) {
            outcome = VerifyKeyOutcome.UNREACHABLE;
        }
        switch (outcome) {
            case VALID: {
                State state = new State();
                state.mode = "account";
                state.code = key;
                state.activatedAt = Instant.now().toString();
                state.lastVerifiedAt = state.activatedAt;
                saveState(state);
                return Map.of("unlocked", true, "mode", "account", "plan", "paid");
            }
            case INVALID:
                return failure(invalidKeyMessage());
            default:
                return failure("无法连接授权服务器，请检查网络后重试");
        }
    }

    /**
     * 「Key 无效」的文案。双站形态下必须点名站点（双主站设计 §2.6）。
     *
     * <p>国际站账户的 Key 粘到国内站，官网回 {@code 200 {valid:false}}，桌面端判 INVALID——
     * 而这把 Key 其实是好的。只说「Key 无效或已被撤销」等于在指控用户，
     * 他会去官网重新生成一把，再撞一次同样的墙。
     *
     * <p>文案红线：不得含「登录」「未授权」「请先」三个子串，
     * {@code frontend/src/services/api.js} 对 {@code code:1} 的 message 做子串匹配识别掉线，
     * 命中会清本地会话。所以这里写「切换站点后重试」而不是「请先切换站点」。
     * 护栏见 {@code LicenseServiceTest}。
     */
    private String invalidKeyMessage() {
        String base = "账户 Key 无效或已被撤销，可到官网账户页确认后重试";
        try {
            if (!siteProfileService.multiSite()) return base;
            String others = siteProfileService.otherSites().stream()
                    .map(com.checkba.service.site.SiteProfile::displayName)
                    .reduce((a, b) -> a + "、" + b)
                    .orElse(null);
            if (others == null) return base;
            return base + "。当前站点是「" + siteProfileService.displayName()
                    + "」；如果你的账户注册在「" + others + "」，切换站点后重试";
        } catch (Exception e) {
            return base;
        }
    }

    private Map<String, Object> activateTrialCode(String code) {
        PublicKey key;
        try {
            key = trialPublicKey();
        } catch (Exception e) {
            log.error("内置试用码公钥加载失败: {}", e.getMessage());
            return failure("试用码校验组件异常，请重装应用后重试");
        }
        TrialCodeVerifier.Result result = TrialCodeVerifier.verify(code, key);
        if (!result.valid()) {
            return failure(result.error());
        }
        State state = new State();
        state.mode = "trial";
        state.code = code;
        state.activatedAt = Instant.now().toString();
        state.lastVerifiedAt = state.activatedAt;
        saveState(state);
        return Map.of("unlocked", true, "mode", "trial");
    }

    // ==================== 在线校验 ====================

    enum VerifyKeyOutcome { VALID, INVALID, UNREACHABLE }

    private VerifyKeyOutcome callVerifyKey(String key) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(VERIFY_TIMEOUT)
                    .build();
            String body = objectMapper.writeValueAsString(Map.of("key", key));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(siteProfileService.baseUrl() + "/api/license/verify-key"))
                    .timeout(VERIFY_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // 4xx 视为明确拒绝；5xx 视为服务器暂不可用，不清除既有授权
                return response.statusCode() >= 400 && response.statusCode() < 500
                        ? VerifyKeyOutcome.INVALID
                        : VerifyKeyOutcome.UNREACHABLE;
            }
            Map<?, ?> parsed = objectMapper.readValue(response.body(), Map.class);
            // Spec §1：账户 Key 在线校验有效即解锁为正式版——valid 即通过，
            // 不额外要求 plan=paid（否则未付费账户会被误判「Key 无效」，
            // 且启动复验会把这类账户的本地授权直接清掉）。
            boolean valid = Boolean.TRUE.equals(parsed.get("valid"));
            return valid ? VerifyKeyOutcome.VALID : VerifyKeyOutcome.INVALID;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VerifyKeyOutcome.UNREACHABLE;
        } catch (Exception e) {
            return VerifyKeyOutcome.UNREACHABLE;
        }
    }

    // ==================== 状态与工具 ====================

    /**
     * 离线宽限还剩几天（向上取整；0 表示已耗尽）。
     *
     * <p>向上取整是为了让文案不说谎：还剩 6 天零 3 小时时显示「剩 7 天」偏保守，
     * 显示「剩 6 天」则会在用户眼里提前一天到期。锚点缺失或格式非法一律按已耗尽处理
     * （安全侧默认：坏掉的票据不该换来无限宽限）。
     */
    private long offlineGraceDaysRemaining(State state) {
        String anchor = state.lastVerifiedAt != null ? state.lastVerifiedAt : state.activatedAt;
        if (anchor == null) return 0;
        try {
            Instant deadline = Instant.parse(anchor).plus(OFFLINE_GRACE);
            long seconds = Duration.between(Instant.now(), deadline).getSeconds();
            if (seconds <= 0) return 0;
            return (long) Math.ceil(seconds / 86400.0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 存量 trial 票据的过渡期还剩几天（0 表示已到期）。
     *
     * <p>{@code legacy-grace-until} 缺省或格式非法时返回 0——安全侧默认，
     * 配错一个日期不会变成永久宽限。硬期限当天也算到期（那一天已经用完了）。
     */
    private long legacyTrialDaysRemaining() {
        if (legacyTrialGraceUntil == null) return 0;
        long days = ChronoUnit.DAYS.between(LocalDate.now(), legacyTrialGraceUntil);
        return Math.max(0, days);
    }

    /** 配置里的宽限硬期限；空串是「没配」，非法值要吼一声再按没配处理。 */
    private static LocalDate parseGraceDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.warn("security.license.trial-code.legacy-grace-until 格式非法（{}），"
                    + "存量试用票据按已到期处理", raw);
            return null;
        }
    }

    // ==================== 文案 ====================
    // 这三条会出现在被挡在门外的用户眼前，是他们唯一的信息来源，必须给出路。
    // 用 LangText 双语：解锁页在 EN 版同样会显示它们。

    private String trialDisabledMessage() {
        return LangText.of(
                "试用码已停用。用手机号登录账户即可继续使用，或粘贴 awdk_ 开头的账户 Key。",
                "Trial codes are no longer accepted. Sign in with your phone number to continue, "
                        + "or paste an account key starting with awdk_.");
    }

    private String legacyTrialExpiredMessage() {
        return LangText.of(
                "试用期已结束。你的项目和文件都还在这台电脑上，一条都没有丢失——"
                        + "登录账户后即可照常打开。遇到问题联系 hi@aiworkdeck.com。",
                "Your trial has ended. Every project and file is still on this computer and "
                        + "nothing has been lost - sign in to your account to open them as usual. "
                        + "Contact hi@aiworkdeck.com if you need help.");
    }

    private String offlineGraceExpiredMessage() {
        return LangText.of(
                "账户授权已超过 30 天未联网复验，需联网重新验证。"
                        + "如果这台电脑长期无法访问外网，联系 hi@aiworkdeck.com 申请离线授权。",
                "This account has not been re-verified online for over 30 days and needs to "
                        + "reconnect. If this computer has no internet access, contact "
                        + "hi@aiworkdeck.com for an offline licence.");
    }

    State loadState() {
        try {
            if (!Files.exists(licenseFile)) return new State();
            return objectMapper.readValue(Files.readAllBytes(licenseFile), State.class);
        } catch (Exception e) {
            log.warn("license.json 读取失败，按未激活处理: {}", e.getMessage());
            return new State();
        }
    }

    void saveState(State state) {
        try {
            Files.createDirectories(licenseFile.getParent());
            Files.write(licenseFile, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(state));
            restrictPermissions(licenseFile);
        } catch (Exception e) {
            throw new IllegalStateException("授权状态写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * license.json 里存着明文 awdk_ 账户 Key，默认 umask 下会落成 0644
     * （同机其他用户可读）。收敛为 0600。Windows 无 POSIX 视图，静默跳过。
     */
    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows：文件默认继承用户目录 ACL，无需处理
        } catch (Exception e) {
            log.warn("license.json 权限收敛失败（文件仍可用）: {}", e.getMessage());
        }
    }

    private PublicKey trialPublicKey() throws Exception {
        PublicKey key = trialPublicKey;
        if (key != null) return key;
        try (var in = LicenseService.class.getResourceAsStream("/license/trial-public-key.pem")) {
            if (in == null) throw new IllegalStateException("缺少内置试用码公钥资源");
            String pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            key = TrialCodeVerifier.parsePublicKeyPem(pem);
            trialPublicKey = key;
            return key;
        }
    }

    private static Map<String, Object> unlockedStatus(String mode, String plan, String activatedAt) {
        Map<String, Object> result = new HashMap<>();
        result.put("unlocked", true);
        result.put("mode", mode);
        result.put("plan", plan);
        if (activatedAt != null) {
            result.put("activatedAt", activatedAt);
        }
        return result;
    }

    private static Map<String, Object> failure(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("unlocked", false);
        result.put("message", message);
        return result;
    }
}
