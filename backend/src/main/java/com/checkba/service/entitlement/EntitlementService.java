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
 *
 * 刷新时机：启动时 + 每次 connect 之后，均为异步且失败不阻塞启动。
 */
@Service
@Slf4j
public class EntitlementService {

    /** 与 {@code LicenseService.OFFLINE_GRACE} 同口径。 */
    static final Duration OFFLINE_GRACE = Duration.ofDays(30);

    private final LicenseService licenseService;
    private final AccountService accountService;
    private final Path cacheFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        Thread thread = new Thread(this::refreshQuietly, "entitlement-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    /** 同步刷新，吞掉全部异常并返回是否成功。单测与刷新线程共用。 */
    boolean refreshQuietly() {
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
            // 网络不可达走宽限，鉴权失败也只是不刷新——清除连接是 connect/disconnect 的职责
            log.debug("账户权益同步跳过（{}）: {}", e.getKind(), e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("账户权益同步失败: {}", e.getMessage());
            return false;
        }
    }

    /** 断开账户连接时清空账户型权益缓存（本地票据不受影响）。 */
    public synchronized void clearAccountCache() {
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

    /** 账户型权益：超过 30 天未同步即整体失效。 */
    private Set<String> accountFeatures() {
        Cache cache = loadCache();
        if (cache.features == null || cache.features.isEmpty()) return Set.of();
        if (!withinGrace(cache)) {
            return Set.of();
        }
        return new LinkedHashSet<>(cache.features);
    }

    private static boolean withinGrace(Cache cache) {
        if (cache.syncedAt == null) return false;
        try {
            return Instant.parse(cache.syncedAt).plus(OFFLINE_GRACE).isAfter(Instant.now());
        } catch (Exception e) {
            return false;
        }
    }

    synchronized Cache loadCache() {
        try {
            if (!Files.exists(cacheFile)) return new Cache();
            Cache cache = objectMapper.readValue(Files.readAllBytes(cacheFile), Cache.class);
            return cache == null ? new Cache() : cache;
        } catch (Exception e) {
            log.warn("entitlements.json 读取失败，按无账户权益处理: {}", e.getMessage());
            return new Cache();
        }
    }

    synchronized void saveCache(Cache cache) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.write(cacheFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(cache));
        } catch (Exception e) {
            log.warn("权益缓存写入失败（不影响本次判定）: {}", e.getMessage());
        }
    }
}
