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
 * 取舍（已知且可接受）：并发轮次之间的归属可能串位，但**总额始终精确**（差分自洽）；
 * 权威结算数字本来也在官网/OpenRouter 侧，本地这份是给用户看明细的。
 *
 * 单线程 worker 串行执行，既保证差分正确，也不占用流式回调线程（否则会拖住 bubble_end）。
 */
@Component
@Slf4j
public class PlatformUsageAccountant {

    static final String SOURCE_PLATFORM = "platform";
    static final String SOURCE_ESTIMATE = "estimate";

    /** OpenRouter 记账延迟：最多重采样这么多次。 */
    private static final int MAX_POLLS = 4;

    /** 重采样间隔。单测会调小，生产不改。 */
    private long pollIntervalMs = 1500L;

    private final TokenUsageRepository tokenUsageRepository;
    private final PlatformAiChannel platformAiChannel;
    private final AccountTransport transport;
    private final String openRouterBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "platform-usage-accountant");
        t.setDaemon(true);
        return t;
    });

    /** 上一次观测到的累计消费。null = 尚未建立基线。 */
    private volatile BigDecimal baseline;

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
    }

    /** 排队对账某条 token_usage 记录。立即返回，不阻塞调用方。 */
    public void reconcileAsync(Long tokenUsageId) {
        if (tokenUsageId == null) return;
        try {
            worker.submit(() -> reconcile(tokenUsageId));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("平台用量对账已停止，跳过 id={}", tokenUsageId);
        }
    }

    /** 包可见供单测直接驱动。 */
    void reconcile(Long tokenUsageId) {
        try {
            BigDecimal observed = probeCumulativeUsage();
            if (observed == null) return;

            BigDecimal previous = baseline;
            if (previous == null) {
                // 首次只建基线：这条记录之前的消费不属于它
                baseline = observed;
                return;
            }
            for (int i = 0; i < MAX_POLLS && observed.compareTo(previous) <= 0; i++) {
                Thread.sleep(pollIntervalMs);
                BigDecimal again = probeCumulativeUsage();
                if (again == null) return;
                observed = again;
            }
            baseline = observed;

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

    /** GET /api/v1/key → {"data":{"usage": 1.234, "limit": 10, ...}}；失败返回 null。 */
    private BigDecimal probeCumulativeUsage() {
        String key;
        try {
            key = platformAiChannel.apiKey();
        } catch (Exception e) {
            return null;
        }
        AccountTransport.Reply reply = transport.send("GET", openRouterBaseUrl + "/key", key, null);
        if (reply.networkFailure() || reply.status() < 200 || reply.status() >= 300 || reply.body() == null) {
            return null;
        }
        try {
            Map<?, ?> parsed = objectMapper.readValue(reply.body(), Map.class);
            Object data = parsed.get("data");
            if (!(data instanceof Map<?, ?> map)) return null;
            Object usage = map.get("usage");
            return usage instanceof Number n ? new BigDecimal(n.toString()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 便于单测断言与复位。 */
    void setBaselineForTest(BigDecimal value) {
        this.baseline = value;
    }

    /** 单测把重采样间隔调小，避免为了等 OpenRouter 记账延迟白等几秒。 */
    void setPollIntervalMsForTest(long millis) {
        this.pollIntervalMs = millis;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
