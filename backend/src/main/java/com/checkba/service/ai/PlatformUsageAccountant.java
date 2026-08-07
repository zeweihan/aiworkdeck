package com.checkba.service.ai;

import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.account.AccountTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 平台通道的真实花费对账（Spec §3）。
 *
 * <h3>为什么不是直接读响应体的 usage.cost</h3>
 * Spec 写的是「桌面端单请求成本直接读响应 {@code usage.cost}（流式在最后一条 SSE）」，
 * 但本仓固定在 langchain4j 0.36 / openai4j 0.23：{@code dev.ai4j.openai4j.shared.Usage}
 * 只有 prompt/completion/total 三个字段，OpenRouter 多返回的 {@code cost} 在反序列化时被丢弃，
 * 且拿不到原始响应；OpenRouter 还要求请求体里带 {@code usage:{include:true}} 才会返回 cost，
 * 而这个 client 的请求 POJO 也不支持透传自定义字段。升 langchain4j 是另一个量级的改动
 * （MCP 那次已知 1.0.0+ 不兼容 0.36）。
 *
 * <h3>改用的口径</h3>
 * 每次记账后异步查一次 {@code GET https://openrouter.ai/api/v1/key}（用平台 runtime key 鉴权），
 * 该端点返回这把 key 的**累计**消费（美元）。相邻两次采样之差即这段时间的真实花费。
 * OpenRouter 记账有秒级延迟，所以采样会重试几次等数字变动。
 *
 * <h3>按 key 指纹分桶（server 模式多租户，2026-08-07）</h3>
 * baseline 从单个字段改成 {@code Map<指纹, 累计值>}，用<b>密钥指纹</b>而不是 userId 做键：
 * key 轮换后指纹变化、baseline 自动重建，不会把两把 key 的累计值之差整个记到下一条消息上。
 * worker 也从单线程改为按指纹分片的固定线程池——同一把 key 仍严格串行（差分正确性不变），
 * 不同用户并行，否则多租户下「待结算」会被排队拖成分钟级。
 *
 * <p>取舍（已知且可接受）：同一把 key 的并发轮次之间归属可能串位，但**总额始终精确**
 * （差分自洽）。per-user 化之后这个串位被收敛在用户自己的并发轮次内，不再跨租户。
 *
 * <h3>兼做吊销探测</h3>
 * 探针拿到 401/403 = 这把 key 已在官网侧被禁用或重发，立刻让 {@link PlatformAiChannel}
 * 作废本地这份（与「官网明确拒绝 → 立刻清缓存、不吃宽限」同源）；网络不可达一律保留。
 */
@Component
@Slf4j
public class PlatformUsageAccountant {

    /** {@code TokenUsage.costSource} 的两个取值，展示层也要按它分辨口径。 */
    public static final String SOURCE_PLATFORM = "platform";
    public static final String SOURCE_ESTIMATE = "estimate";

    /** OpenRouter 记账延迟：最多重采样这么多次。 */
    private static final int MAX_POLLS = 4;

    /** 分片数。同一指纹恒落同一片，保证差分串行。 */
    private static final int SHARDS = 4;

    /** 重采样间隔。单测会调小，生产不改。 */
    private long pollIntervalMs = 1500L;

    private final TokenUsageRepository tokenUsageRepository;
    private final PlatformAiChannel platformAiChannel;
    private final AccountTransport transport;
    private final String openRouterBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService[] workers = new ExecutorService[SHARDS];

    /** 上一次观测到的累计消费，按密钥指纹分桶。缺键 = 尚未建立基线。 */
    private final Map<String, BigDecimal> baselines = new ConcurrentHashMap<>();

    public PlatformUsageAccountant(
            TokenUsageRepository tokenUsageRepository,
            PlatformAiChannel platformAiChannel,
            AccountTransport transport,
            @Value("${ai.model.open-router.base-url:https://openrouter.ai/api/v1}") String openRouterBaseUrl) {
        this.tokenUsageRepository = tokenUsageRepository;
        this.platformAiChannel = platformAiChannel;
        this.transport = transport;
        this.openRouterBaseUrl = openRouterBaseUrl.endsWith("/")
                ? openRouterBaseUrl.substring(0, openRouterBaseUrl.length() - 1)
                : openRouterBaseUrl;
        for (int i = 0; i < SHARDS; i++) {
            final int index = i;
            workers[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "platform-usage-accountant-" + index);
                t.setDaemon(true);
                return t;
            });
        }
    }

    /** 排队对账某条 token_usage 记录。立即返回，不阻塞调用方。 */
    public void reconcileAsync(Long tokenUsageId, Long userId) {
        if (tokenUsageId == null) return;
        PlatformAiKeyService.Resolved resolved = platformAiChannel.resolveFor(userId);
        if (resolved == null) {
            log.debug("无可用平台密钥，跳过对账 id={}", tokenUsageId);
            return;
        }
        submit(resolved.fingerprint(), () -> reconcile(tokenUsageId, userId, resolved),
                "对账 id=" + tokenUsageId);
    }

    /**
     * 在平台通道**发起请求之前**建立基线。
     *
     * baseline 是内存字段，进程重启即丢；没有这一步，重启后第一条消息只够建基线，
     * cost 永远留空、界面上永久显示「待结算」。这里排在与后续对账同一个分片上，
     * 所以一定先于那条消息随后入队的对账任务跑完。已有基线时是内存判断，不发请求。
     */
    public void ensureBaselineAsync(Long userId) {
        PlatformAiKeyService.Resolved resolved = platformAiChannel.resolveFor(userId);
        if (resolved == null) return;
        String fingerprint = resolved.fingerprint();
        if (baselines.containsKey(fingerprint)) return;
        submit(fingerprint, () -> {
            if (baselines.containsKey(fingerprint)) return;
            BigDecimal observed = probeCumulativeUsage(userId, resolved.apiKey());
            if (observed != null) baselines.put(fingerprint, observed);
        }, "建立用量基线");
    }

    /**
     * 换账户/断开连接：旧 key 的累计消费与新 key 毫无关系，
     * 留着会把两把 key 的累计值之差整个记到下一条消息头上。
     */
    public void resetBaseline() {
        baselines.clear();
    }

    /** 某把 key 作废（吊销/轮换）时精确清除它的基线。 */
    public void forget(String fingerprint) {
        if (fingerprint != null) baselines.remove(fingerprint);
    }

    /**
     * 额度面板用的同步查询：返回这把 key 的累计消费（美元），查不到返回 null。
     * 401/403 同样触发作废——面板刷新时顺手把已吊销的密钥清掉。
     */
    public Double probeUsageForDisplay(Long userId, String apiKey) {
        BigDecimal usage = probeCumulativeUsage(userId, apiKey);
        return usage == null ? null : usage.doubleValue();
    }

    private void submit(String fingerprint, Runnable task, String what) {
        int shard = Math.floorMod(String.valueOf(fingerprint).hashCode(), SHARDS);
        try {
            workers[shard].submit(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("平台用量对账已停止，跳过{}", what);
        }
    }

    /** 包可见供单测直接驱动。 */
    void reconcile(Long tokenUsageId, Long userId, PlatformAiKeyService.Resolved resolved) {
        String fingerprint = resolved.fingerprint();
        try {
            BigDecimal observed = probeCumulativeUsage(userId, resolved.apiKey());
            if (observed == null) return;

            BigDecimal previous = baselines.get(fingerprint);
            if (previous == null) {
                // 首次只建基线：这条记录之前的消费不属于它
                baselines.put(fingerprint, observed);
                return;
            }
            for (int i = 0; i < MAX_POLLS && observed.compareTo(previous) <= 0; i++) {
                Thread.sleep(pollIntervalMs);
                BigDecimal again = probeCumulativeUsage(userId, resolved.apiKey());
                if (again == null) return;
                observed = again;
            }
            baselines.put(fingerprint, observed);

            BigDecimal delta = observed.subtract(previous);
            if (delta.signum() <= 0) {
                log.debug("平台用量未变动，跳过对账 id={}", tokenUsageId);
                return;
            }
            tokenUsageRepository.findById(tokenUsageId).ifPresent(entity -> {
                entity.setCost(delta);
                entity.setCostSource(SOURCE_PLATFORM);
                tokenUsageRepository.save(entity);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("平台用量对账失败（不影响对话）: {}", e.getMessage());
        }
    }

    /**
     * GET /api/v1/key → {"data":{"usage": 1.234, "limit": 10, ...}}；失败返回 null。
     *
     * 401/403 是**官网侧已吊销/重发**的确证，立刻作废本地密钥并清基线；
     * 网络不可达与 5xx 一律保留（服务器故障不等于凭据失效）。
     */
    private BigDecimal probeCumulativeUsage(Long userId, String apiKey) {
        if (apiKey == null) return null;
        AccountTransport.Reply reply = transport.send("GET", openRouterBaseUrl + "/key", apiKey, null);
        if (reply.networkFailure()) return null;
        if (reply.status() == 401 || reply.status() == 403) {
            String fingerprint = PlatformAiKeyCipher.fingerprint(apiKey);
            log.info("平台通道密钥已被拒绝（HTTP {}），作废本地记录 userId={}", reply.status(), userId);
            platformAiChannel.onKeyRejected(userId);
            forget(fingerprint);
            return null;
        }
        if (reply.status() < 200 || reply.status() >= 300 || reply.body() == null) {
            return null;
        }
        try {
            Map<?, ?> parsed = objectMapper.readValue(reply.body(), Map.class);
            Object data = parsed.get("data");
            if (!(data instanceof Map<?, ?> map)) return null;
            Object usage = map.get("usage");
            if (!(usage instanceof Number n)) return null;
            platformAiChannel.onKeyVerified(userId);
            return new BigDecimal(n.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** 便于单测断言与复位。 */
    void setBaselineForTest(String fingerprint, BigDecimal value) {
        baselines.put(fingerprint, value);
    }

    BigDecimal baselineForTest(String fingerprint) {
        return baselines.get(fingerprint);
    }

    /** 单测把重采样间隔调小，避免为了等 OpenRouter 记账延迟白等几秒。 */
    void setPollIntervalMsForTest(long millis) {
        this.pollIntervalMs = millis;
    }

    @PreDestroy
    void shutdown() {
        for (ExecutorService worker : workers) {
            worker.shutdownNow();
        }
    }
}
