package com.checkba.service.telemetry;

import com.checkba.model.entity.TelemetryEvent;
import com.checkba.repository.TelemetryEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 埋点唯一采集入口（设计 §5.3）。
 *
 * 不变式：
 * - 本地账本永远记录（纯本地数据，先例同 token_usage）；开关只控制上报（见 TelemetryUploadService）。
 * - record() 绝不向调用方抛异常、绝不阻塞业务线程（独立单线程 executor，队列满即丢弃）。
 * - 一切属性先过 TelemetryAttrWhitelist：未知事件整条拒绝，白名单外字段剔除，均计入 dropped 自监控。
 */
@Slf4j
@Service
public class TelemetryService {

    private static final int RETENTION_DAYS = 90;

    private final TelemetryEventRepository repository;
    private final InstallIdentityService identity;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String appVersion;

    /** 独立于业务线程池：埋点洪峰不与编排循环抢线程；有界队列，满了直接丢 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telemetry-writer");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong dropped = new AtomicLong();

    public TelemetryService(TelemetryEventRepository repository,
                            InstallIdentityService identity,
                            @Value("${telemetry.app-version:${AWD_APP_VERSION:dev}}") String appVersion) {
        this.repository = repository;
        this.identity = identity;
        this.appVersion = appVersion;
    }

    /** 记录一条事件；attrs 可为 null。任何异常吞掉，不影响业务。 */
    public void record(String eventName, Map<String, Object> attrs) {
        recordInternal(eventName, null, attrs);
    }

    /** 带会话关联键的记录：conversationId 只用于派生 convKey，绝不落库。 */
    public void recordConv(String eventName, String conversationId, Map<String, Object> attrs) {
        recordInternal(eventName, conversationId, attrs);
    }

    private void recordInternal(String eventName, String conversationId, Map<String, Object> attrs) {
        try {
            Set<String> allowed = TelemetryAttrWhitelist.allowedAttrs(eventName);
            if (allowed == null) {
                dropped.incrementAndGet();
                return;
            }
            Map<String, Object> clean = new LinkedHashMap<>();
            if (attrs != null) {
                for (Map.Entry<String, Object> e : attrs.entrySet()) {
                    if (allowed.contains(e.getKey())
                            && TelemetryAttrWhitelist.isAllowedValue(e.getValue())) {
                        clean.put(e.getKey(), e.getValue());
                    } else {
                        dropped.incrementAndGet();
                    }
                }
            }
            Instant now = Instant.now();
            String convKey = conversationId == null ? null : identity.convKey(conversationId);
            executor.execute(() -> persist(eventName, clean, convKey, now));
        } catch (Exception e) {
            // 采集绝不影响业务
            dropped.incrementAndGet();
        }
    }

    private void persist(String eventName, Map<String, Object> clean, String convKey, Instant ts) {
        try {
            TelemetryEvent ev = new TelemetryEvent();
            ev.setTs(ts);
            ev.setEventName(eventName);
            ev.setAttrs(clean.isEmpty() ? null : mapper.writeValueAsString(clean));
            ev.setConvKey(convKey);
            ev.setAppVersion(appVersion);
            repository.save(ev);
        } catch (Exception e) {
            dropped.incrementAndGet();
        }
    }

    /** 测试用：等待异步队列排空 */
    void flush() throws InterruptedException {
        try {
            executor.submit(() -> {}).get();
        } catch (java.util.concurrent.ExecutionException ignored) {
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    public String appVersion() {
        return appVersion;
    }

    /** 明细 90 天滚动清理，每日一次 */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000L, initialDelay = 60 * 60 * 1000L)
    @Transactional
    public void cleanupOldEvents() {
        try {
            int n = repository.deleteByTsBefore(Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS));
            if (n > 0) log.info("telemetry 明细清理 {} 条（>{} 天）", n, RETENTION_DAYS);
        } catch (Exception e) {
            log.warn("telemetry 清理失败: {}", e.toString());
        }
    }
}
