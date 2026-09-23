// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.account.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 模型选择器的「实付价」折算系数（dev-board#853）。
 *
 * <p>平台通道（AI WorkDeck 云端）下用户真正付的不是厂商的美元标价，而是
 * <b>美元标价 × 官网计费汇率 × 毛利乘数</b>、以站点币种计的 Credits。
 * 这两个数只有官网知道，桌面端从已有的 {@code GET /api/account/ai-usage} 里读——
 * <b>不新增出站请求</b>（{@code legal/PRIVACY.md} 的硬红线），只是多读几个字段。
 *
 * <h3>三条约束</h3>
 * <ol>
 *   <li><b>不许编造汇率</b>。字段缺失、非正数、币种判不出来、账户没连、官网不可达——一律返回
 *       {@code null}，由调用方退回「厂商美元标价」口径并在脚注里说明。宁可显示标价，也不显示一个
 *       我们自己猜的人民币价：那个数用户会当真。</li>
 *   <li><b>不拖慢下拉</b>。模型目录在 AI 面板挂载时就拉，官网慢一次就让整个选择器空着是不可接受的。
 *       取数放在后台线程，本次请求最多等 {@code ai.model-catalog.price-rate-wait-ms}（默认 1200ms）；
 *       等不到就先用上一份同账户的结果（没有就退回标价），后台那次取完照样写进缓存，下次打开就有了。</li>
 *   <li><b>结果按账户指纹记</b>，换账户自动作废——与 {@link PlatformCreditsGate} 同一判据。</li>
 * </ol>
 *
 * <p><b>缓存</b>：成功结果保鲜 10 分钟（汇率本身按日级变动，10 分钟足够新）；
 * 失败结果只记 60 秒，避免官网抖一下就在 10 分钟里一直显示标价，也避免每次打开面板都去撞一次。
 *
 * <p><b>为什么不和 {@link PlatformCreditsGate} 共用一份缓存</b>：那边 60 秒保鲜、首次同步阻塞，
 * 服务的是「零余额必须当场拦住」；这边要的是「绝不阻塞、10 分钟保鲜」。两种时限揉在一起，
 * 要么让发消息多等一次官网，要么让选择器被余额闸的同步首查拖住。
 */
@Service
@Slf4j
public class ModelPriceDisplayService {

    static final long FRESH_MS = 10 * 60_000L;
    static final long FAILURE_RETRY_MS = 60_000L;

    /**
     * 官网 exchangeRate 的语义是「站点币种 / 美元」。旧版官网没有 currency 字段时靠它推断：
     * 国际站本币就是美元，官网把它硬约束成 1（见官网 {@code lib/ai-config.ts} 的 DEFAULT_EXCHANGE_RATE
     * 注释：国际站若沿用 7.3，充 $100 只拿到约 $11 额度）；国内站是人民币对美元，历史上在 6~8 之间。
     * 两者之间隔着一个数量级，所以「≈1 判 USD、&gt;3 判 CNY」不会误判；落在 (1.05, 3] 这段说明
     * 官网给了一个我们不认识的币种或配置出错，<b>不猜</b>，退回标价。
     */
    static final double USD_RATE_TOLERANCE = 0.05;
    static final double CNY_RATE_MIN = 3.0;

    /** 折算口径。factor = exchangeRate × marginMultiplier。 */
    public record ChargedRate(String currency,
                              double exchangeRate,
                              double marginMultiplier,
                              /** 'reported' = 官网给了 currency；'inferred' = 由汇率推断 */
                              String currencyBasis,
                              /** 'live' | 'manual' | 'default'，旧版官网为 null */
                              String rateSource,
                              /** ISO 时间串，旧版官网或未知为 null */
                              String rateUpdatedAt) {
        public double factor() {
            return exchangeRate * marginMultiplier;
        }
    }

    private record Snapshot(String owner, long fetchedAt, ChargedRate rate) {
        boolean freshFor(String who, long now) {
            if (!who.equals(owner)) return false;
            long ttl = rate != null ? FRESH_MS : FAILURE_RETRY_MS;
            return now - fetchedAt < ttl;
        }
    }

    private record Inflight(String owner, CompletableFuture<Snapshot> future) {
    }

    private final AccountService accountService;
    private final long waitMs;
    private final ExecutorService fetcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "model-price-display");
        t.setDaemon(true);
        return t;
    });

    private volatile Snapshot cached;
    private Inflight inflight;

    public ModelPriceDisplayService(AccountService accountService,
                                    @Value("${ai.model-catalog.price-rate-wait-ms:1200}") long waitMs) {
        this.accountService = accountService;
        this.waitMs = Math.max(0L, waitMs);
    }

    /**
     * 当前账户的实付价折算口径；拿不到（未连接 / 官网不可达 / 字段缺失 / 超时且无旧值）返回 null。
     * 最多阻塞 {@code waitMs}。
     */
    public ChargedRate currentRate() {
        String owner;
        try {
            if (!accountService.isConnected()) return null;
            owner = accountService.accountFingerprintOrNull();
        } catch (Exception e) {
            log.debug("[PriceDisplay] 账户状态读取失败，按标价显示: {}", e.getMessage());
            return null;
        }
        if (owner == null) return null;

        long now = System.currentTimeMillis();
        Snapshot snap = cached;
        if (snap != null && snap.freshFor(owner, now)) {
            return snap.rate();
        }

        CompletableFuture<Snapshot> future = startFetch(owner);
        try {
            Snapshot fresh = future.get(waitMs, TimeUnit.MILLISECONDS);
            return owner.equals(fresh.owner()) ? fresh.rate() : null;
        } catch (TimeoutException e) {
            // 官网慢：先用上一份同账户的旧值（它本来就是官网给的真值，只是不新），没有就退回标价。
            // 后台那次取数不取消，完成后写进缓存。
            log.debug("[PriceDisplay] ai-usage 超过 {}ms 未返回，本次先不等", waitMs);
            return snap != null && owner.equals(snap.owner()) ? snap.rate() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized CompletableFuture<Snapshot> startFetch(String owner) {
        if (inflight != null && owner.equals(inflight.owner()) && !inflight.future().isDone()) {
            return inflight.future();
        }
        CompletableFuture<Snapshot> future = CompletableFuture.supplyAsync(() -> {
            ChargedRate rate = null;
            try {
                rate = parse(accountService.fetchAiUsage());
                if (rate == null) {
                    log.info("[PriceDisplay] ai-usage 缺少可用的汇率字段，模型价格按厂商美元标价显示");
                }
            } catch (Exception e) {
                log.debug("[PriceDisplay] ai-usage 不可用，模型价格按厂商美元标价显示: {}", e.getMessage());
            }
            Snapshot s = new Snapshot(owner, System.currentTimeMillis(), rate);
            cached = s;
            return s;
        }, fetcher);
        inflight = new Inflight(owner, future);
        return future;
    }

    /** 换账户时调用方可以主动作废；不调也没关系，结果本来就按账户指纹记。 */
    public void reset() {
        cached = null;
    }

    // ==================== 纯函数：解析 ai-usage ====================

    /**
     * 从 ai-usage 响应里取折算口径。任何一项缺失或不合法都返回 null（退回标价），不补默认值——
     * 官网自己的默认汇率只有官网知道，这里补一个就是编造。
     */
    static ChargedRate parse(Map<String, Object> aiUsage) {
        if (aiUsage == null) return null;
        Double rate = positive(aiUsage.get("exchangeRate"));
        Double margin = positive(aiUsage.get("marginMultiplier"));
        if (rate == null || margin == null) return null;

        String currency = normalizeCurrency(aiUsage.get("currency"));
        String basis = "reported";
        if (currency == null) {
            if (aiUsage.get("currency") != null) {
                // 官网给了币种但我们不认识：不猜
                return null;
            }
            currency = inferCurrency(rate);
            basis = "inferred";
            if (currency == null) return null;
        }
        return new ChargedRate(currency, rate, margin, basis,
                enumOrNull(aiUsage.get("exchangeRateSource"), "live", "manual", "default"),
                textOrNull(aiUsage.get("exchangeRateUpdatedAt")));
    }

    /** 旧版官网没有 currency 字段时的推断，理由见 {@link #USD_RATE_TOLERANCE}。 */
    static String inferCurrency(double exchangeRate) {
        if (Math.abs(exchangeRate - 1.0) <= USD_RATE_TOLERANCE) return "USD";
        if (exchangeRate > CNY_RATE_MIN) return "CNY";
        return null;
    }

    private static String normalizeCurrency(Object raw) {
        if (!(raw instanceof String s)) return null;
        String c = s.trim().toUpperCase(Locale.ROOT);
        return "CNY".equals(c) || "USD".equals(c) ? c : null;
    }

    private static Double positive(Object raw) {
        if (!(raw instanceof Number n)) return null;
        double v = n.doubleValue();
        return Double.isFinite(v) && v > 0 ? v : null;
    }

    private static String enumOrNull(Object raw, String... allowed) {
        if (!(raw instanceof String s)) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        for (String a : allowed) {
            if (a.equals(v)) return a;
        }
        return null;
    }

    private static String textOrNull(Object raw) {
        if (!(raw instanceof String s) || s.isBlank() || s.length() > 64) return null;
        return s.trim();
    }
}
