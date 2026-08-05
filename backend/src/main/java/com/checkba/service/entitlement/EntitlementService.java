package com.checkba.service.entitlement;

import com.checkba.service.LicenseService;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 功能权益的单一出口（Spec §6）。
 *
 * 来源合并（并集）：
 * <ol>
 *   <li><b>本地票据</b>：PR-A 的 {@link LicenseService} —— 试用码/账户 Key 解锁应用本体，
 *       映射为 {@link FeatureCatalog#APP_UNLOCKED}；</li>
 *   <li><b>账户同步</b>：GET /api/account/entitlements 的结果，落盘缓存
 *       {@code ~/.aiworkdeck/entitlements.json}。</li>
 * </ol>
 *
 * 断网宽限 30 天（与 license 同机制、同时长）：缓存里的 syncedAt 超过 30 天，
 * 账户型权益整体回落为「未拥有」——不是「保持拥有」，否则一台永久离线的机器等于永久买断。
 * 本地票据不受宽限影响（试用码是离线验签的，本就不需要联网）。
 * 宽限只覆盖「联系不上官网」；官网**明确拒绝**（401/403 = Key 已吊销）时立即清缓存，
 * 与 {@code LicenseService.reverifyOnStartup} 对 INVALID 的处理同口径。
 *
 * 刷新时机：启动时 + 每次 connect 之后 + 快照发现缓存陈旧时（均为异步、失败静默），
 * 另有 {@code GET /api/entitlements?refresh=true} 的显式同步刷新。
 *
 * <h3>已知边界：entitlements.json 未签名</h3>
 * 总 Spec §5 写的是「本地 entitlements.json（签名票据集合）」，本轮实现是明文 JSON。
 * 机器主人手改这个文件可以给自己开出 ¥19.9 档的本地 SKU——这是**有意接受**的：
 * 这两个 SKU 的判定本就完全在本地执行（剪贴板裁剪、Stage 容量），没有任何服务端往返
 * 能拦住改文件的人，签名只是抬高门槛。真正要钱的两条路都另有服务端闸门：
 * 付费 Skill/插件的 bundle 下载由官网 402 把关（见官网契约 doc/desktop-contract.md），
 * 平台 AI 额度由 OpenRouter 侧的 key limit 强制执行。
 * 缓存只在账户已连接时生效（见 {@link #accountFeatures()}），删掉 account.json 就一起失效。
 */
@Service
@Slf4j
public class EntitlementService {

    /** 与 {@code LicenseService.OFFLINE_GRACE} 同口径。 */
    static final Duration OFFLINE_GRACE = Duration.ofDays(30);

    /**
     * 快照发现缓存比这个还旧就顺手异步刷一次。没有它，一台长期不重启的机器
     * 会一直用启动那一刻的权益（官网上刚买的东西看不见），并且 syncedAt 永不推进，
     * 到第 31 天当场把账户型权益整体判失效——尽管它一直在线。
     */
    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(10);

    private final LicenseService licenseService;
    private final AccountService accountService;
    private final Path cacheFile;
    private final ObjectMapper objectMapper = AccountService.stateMapper();

    /** 内存缓存：isEnabled 是热路径（PR-C 的剪贴板裁剪、Stage 容量检查每次都要问）。 */
    private Cache memory;
    /** 陈旧自动刷新的在途标记，避免每次快照都起一个线程。 */
    private final java.util.concurrent.atomic.AtomicBoolean refreshing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public EntitlementService(
            LicenseService licenseService,
            AccountService accountService,
            @Value("${security.license.dir:${user.home}/.aiworkdeck}") String stateDir) {
        this.licenseService = licenseService;
        this.accountService = accountService;
        this.cacheFile = Path.of(stateDir, "entitlements.json");
    }

    /** 持久化结构：~/.aiworkdeck/entitlements.json */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Cache {
        public String syncedAt;
        public List<String> features = new ArrayList<>();
    }

    // ==================== 单一出口 ====================

    /**
     * 该 feature 是否已获得。业务侧一律走这个方法，不要自己拼来源。
     *
     * @param feature {@link FeatureCatalog} 常量，或动态的 {@code skill:<id>} / {@code plugin:<id>}
     */
    public boolean isEnabled(String feature) {
        if (feature == null || feature.isBlank()) return false;
        if (FeatureCatalog.APP_UNLOCKED.equals(feature) && localAppUnlocked()) {
            return true;
        }
        return accountFeatures().contains(feature);
    }

    /**
     * GET /api/entitlements 的数据源。
     *
     * 两个列表刻意分开，因为它们回答的是不同问题：
     * <ul>
     *   <li>{@code features}：**只含已拥有的**（目录内 + 动态的 skill:/plugin:）。
     *       「出现在列表里 = 已拥有」是这个字段名唯一自然的读法，前端
     *       {@code useEntitlement} 就是这么用的——如果这里塞进未拥有的条目，
     *       调用方会把一切都判成已解锁，权益体系直接失效；</li>
     *   <li>{@code catalog}：目录全集带 enabled 标志，给设置页「已有/去购买」列表用。</li>
     * </ul>
     */
    public Map<String, Object> snapshot() {
        refreshIfStale();
        Set<String> account = accountFeatures();
        boolean appUnlocked = localAppUnlocked();

        List<Map<String, Object>> owned = new ArrayList<>();
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (String feature : FeatureCatalog.all()) {
            Map<String, Object> item = describe(feature, account, appUnlocked);
            catalog.add(item);
            if (Boolean.TRUE.equals(item.get("enabled"))) {
                owned.add(describe(feature, account, appUnlocked));
            }
        }
        // 目录之外的动态权益（付费 Skill / 插件）：拿到就是已购，供插件广场判定
        account.stream().filter(f -> !FeatureCatalog.isKnown(f)).sorted()
                .forEach(f -> owned.add(describe(f, account, appUnlocked)));

        Cache cache = loadCache();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("features", owned);
        result.put("catalog", catalog);
        result.put("accountConnected", accountService.isConnected());
        result.put("syncedAt", cache.syncedAt);
        result.put("stale", cache.syncedAt != null && !withinGrace(cache));
        return result;
    }

    private static Map<String, Object> describe(String feature, Set<String> account, boolean appUnlocked) {
        boolean fromAccount = account.contains(feature);
        boolean fromLocal = FeatureCatalog.APP_UNLOCKED.equals(feature) && appUnlocked;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("feature", feature);
        item.put("displayName", FeatureCatalog.displayName(feature));
        item.put("enabled", fromAccount || fromLocal);
        // 来源分开标注：本地票据（试用/离线）与账户同步是两套口径，前端文案不同
        item.put("source", fromLocal ? "local" : (fromAccount ? "account" : "none"));
        return item;
    }

    // ==================== 刷新 ====================

    /** 启动时刷新一次：后台线程，失败静默，绝不阻塞启动。 */
    @PostConstruct
    void refreshOnStartup() {
        refreshAsync();
    }

    /** 异步刷新账户型权益。connect 成功后也调这个。 */
    public void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            try {
                refreshQuietly();
            } finally {
                refreshing.set(false);
            }
        }, "entitlement-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    /** 缓存陈旧（或压根没有）时顺手异步刷一次；已在刷新中则跳过。 */
    private void refreshIfStale() {
        if (!accountService.isConnected()) return;
        Cache cache = loadCache();
        if (cache.syncedAt != null && !olderThan(cache.syncedAt, REFRESH_INTERVAL)) return;
        refreshAsync();
    }

    /** 同步刷新，吞掉全部异常并返回是否成功。控制器的显式刷新与刷新线程共用。 */
    public boolean refreshQuietly() {
        if (!accountService.isConnected()) {
            return false;
        }
        try {
            List<Map<String, Object>> entitlements = accountService.fetchEntitlements();
            Cache cache = new Cache();
            cache.syncedAt = Instant.now().toString();
            cache.features = new ArrayList<>(new LinkedHashSet<>(entitlements.stream()
                    .map(e -> e.get("feature"))
                    .filter(f -> f instanceof String s && !s.isBlank())
                    .map(String::valueOf)
                    .toList()));
            saveCache(cache);
            log.info("账户权益已同步，共 {} 项", cache.features.size());
            return true;
        } catch (AccountException e) {
            if (e.getKind() == AccountException.Kind.UNAUTHORIZED) {
                // 官网**明确拒绝** = Key 已吊销。30 天宽限是给「联系不上官网」的，
                // 拿它兜吊销等于用户止损后付费功能还能再用一个月。立刻清缓存。
                // 本地连接状态不动：设置页仍显示已连接 + 明确的「Key 已失效」提示，
                // 比凭据凭空消失更容易让用户找到下一步。
                log.warn("账户 Key 已被拒绝（官网 401/403），账户型权益已清除");
                clearAccountCache();
                return false;
            }
            // 网络不可达（含 5xx）走宽限：服务器故障不等于凭据失效
            log.debug("账户权益同步跳过（{}）: {}", e.getKind(), e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("账户权益同步失败: {}", e.getMessage());
            return false;
        }
    }

    /** 断开账户连接时清空账户型权益缓存（本地票据不受影响）。 */
    public synchronized void clearAccountCache() {
        memory = new Cache();
        try {
            Files.deleteIfExists(cacheFile);
        } catch (Exception e) {
            log.warn("权益缓存清除失败: {}", e.getMessage());
        }
    }

    // ==================== 内部 ====================

    private boolean localAppUnlocked() {
        try {
            return Boolean.TRUE.equals(licenseService.status().get("unlocked"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 账户型权益：超过 30 天未同步即整体失效。
     * 另外必须账户仍处于连接状态——缓存文件是账户同步的产物，
     * 没有 account.json 却还认这份缓存，等于把「删掉凭据」和「手写一份缓存」都变成免费解锁。
     */
    private Set<String> accountFeatures() {
        if (!accountService.isConnected()) return Set.of();
        Cache cache = loadCache();
        if (cache.features == null || cache.features.isEmpty()) return Set.of();
        if (cache.syncedAt == null || olderThan(cache.syncedAt, OFFLINE_GRACE)) {
            return Set.of();
        }
        return new LinkedHashSet<>(cache.features);
    }

    /** ISO 时间戳距今是否超过 duration；无法解析按「已超过」处理。 */
    private static boolean olderThan(String isoTimestamp, Duration duration) {
        try {
            return !Instant.parse(isoTimestamp).plus(duration).isAfter(Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean withinGrace(Cache cache) {
        return cache.syncedAt != null && !olderThan(cache.syncedAt, OFFLINE_GRACE);
    }

    synchronized Cache loadCache() {
        if (memory != null) return memory;
        Cache cache;
        try {
            cache = Files.exists(cacheFile)
                    ? objectMapper.readValue(Files.readAllBytes(cacheFile), Cache.class)
                    : new Cache();
            if (cache == null) cache = new Cache();
        } catch (Exception e) {
            log.warn("entitlements.json 读取失败，按无账户权益处理: {}", e.getMessage());
            cache = new Cache();
        }
        memory = cache;
        return cache;
    }

    synchronized void saveCache(Cache cache) {
        memory = cache;
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.write(cacheFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(cache));
        } catch (Exception e) {
            log.warn("权益缓存写入失败（不影响本次判定）: {}", e.getMessage());
        }
    }
}
