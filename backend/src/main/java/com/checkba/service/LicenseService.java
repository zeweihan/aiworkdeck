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
 */
@Service
@Slf4j
public class LicenseService {

    static final Duration OFFLINE_GRACE = Duration.ofDays(30);
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    private final boolean localMode;
    private final String accountBaseUrl;
    private final Path licenseFile;
    // 解析失败的异常 message 不许带原文——license.json 里存着明文 awdk_ 账户 Key
    private final ObjectMapper objectMapper = com.checkba.service.account.AccountService.stateMapper();

    private volatile PublicKey trialPublicKey;

    public LicenseService(
            @Value("${security.local-mode:false}") boolean localMode,
            @Value("${ai.account.base-url:https://www.aiworkdeck.com}") String accountBaseUrl,
            @Value("${security.license.dir:${user.home}/.aiworkdeck}") String licenseDir) {
        this.localMode = localMode;
        // 授权服务器地址的协议校验与 AccountService 共用一份实现（https，回环 http 例外）
        this.accountBaseUrl = com.checkba.service.account.AccountEndpoint.requireSecure(accountBaseUrl);
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
        State state = loadState();
        switch (state.mode == null ? "none" : state.mode) {
            case "trial":
                return unlockedStatus("trial", "trial", state.activatedAt);
            case "account": {
                if (withinOfflineGrace(state)) {
                    return unlockedStatus("account", "paid", state.activatedAt);
                }
                Map<String, Object> result = new HashMap<>();
                result.put("unlocked", false);
                result.put("mode", "account");
                result.put("plan", "paid");
                result.put("message", "账户授权已超过 30 天未联网复验，需联网重新验证");
                return result;
            }
            default:
                return Map.of("unlocked", false, "mode", "none", "plan", "none");
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
        return activateTrialCode(trimmed);
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
                return failure("账户 Key 无效或已被撤销，请到官网账户页确认后重试");
            default:
                return failure("无法连接授权服务器，请检查网络后重试");
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
                    .uri(URI.create(accountBaseUrl + "/api/license/verify-key"))
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

    private boolean withinOfflineGrace(State state) {
        String anchor = state.lastVerifiedAt != null ? state.lastVerifiedAt : state.activatedAt;
        if (anchor == null) return false;
        try {
            return Instant.parse(anchor).plus(OFFLINE_GRACE).isAfter(Instant.now());
        } catch (Exception e) {
            return false;
        }
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
