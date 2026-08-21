package com.checkba.service.telemetry;

import com.checkba.model.entity.TelemetryDailyRollup;
import com.checkba.model.entity.TelemetryEvent;
import com.checkba.repository.TelemetryDailyRollupRepository;
import com.checkba.repository.TelemetryEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 匿名上报（设计 §4、§5.5）。节奏照抄 PluginRevocationService：
 * 启动守护线程一次 + 每 24 小时一次；5 秒超时；失败静默、uploaded 标记留待下轮补传
 * （最多回溯 30 天）。开关语义：rollup 开关关 = 零外发；events 开关（Tier 2）
 * 单独控制脱敏事件流批量上报。
 */
@Slf4j
@Service
public class TelemetryUploadService {

    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final int EVENT_BATCH_LIMIT = 5000;

    private final TelemetryDailyRollupRepository rollupRepository;
    private final TelemetryEventRepository eventRepository;
    private final TelemetryRollupService rollupService;
    private final TelemetrySettings settings;
    private final InstallIdentityService identity;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String ingestBaseUrl;
    private final HttpTransport transport;

    /** 可注入的传输层（测试替身用）；生产走 java.net.http */
    public interface HttpTransport {
        /** @return HTTP 状态码；网络异常直接抛 */
        int post(String url, String jsonBody) throws Exception;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TelemetryUploadService(TelemetryDailyRollupRepository rollupRepository,
                                  TelemetryEventRepository eventRepository,
                                  TelemetryRollupService rollupService,
                                  TelemetrySettings settings,
                                  InstallIdentityService identity,
                                  @Value("${telemetry.ingest-url:https://www.aiworkdeck.com/api/telemetry}") String ingestBaseUrl) {
        this(rollupRepository, eventRepository, rollupService, settings, identity, ingestBaseUrl, null);
    }

    TelemetryUploadService(TelemetryDailyRollupRepository rollupRepository,
                           TelemetryEventRepository eventRepository,
                           TelemetryRollupService rollupService,
                           TelemetrySettings settings,
                           InstallIdentityService identity,
                           String ingestBaseUrl,
                           HttpTransport transport) {
        this.rollupRepository = rollupRepository;
        this.eventRepository = eventRepository;
        this.rollupService = rollupService;
        this.settings = settings;
        this.identity = identity;
        this.ingestBaseUrl = ingestBaseUrl;
        this.transport = transport != null ? transport : defaultTransport();
    }

    private static HttpTransport defaultTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (url, body) -> client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @PostConstruct
    public void onStartup() {
        // 启动时异步补算昨日 + 尝试补传，绝不拖慢应用启动
        Thread t = new Thread(this::sync, "telemetry-upload-init");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelay = DAY_MS, initialDelay = DAY_MS)
    public void scheduledSync() {
        sync();
    }

    /** 补算窗口：与 uploadPendingRollups 的补传窗口口径一致，不无限往前找缺口。 */
    private static final int BACKFILL_DAYS = 30;

    /** 聚合昨日（及漏算日）并上报未传的 rollup；Tier 2 开时批量上报事件。全程静默失败。 */
    public void sync() {
        try {
            backfillMissingRollups();
            if (!settings.rollupEnabled()) return;
            uploadPendingRollups();
            if (settings.eventsEnabled()) {
                uploadRecentEvents();
            }
        } catch (Exception e) {
            log.debug("telemetry 上报轮次失败（静默）: {}", e.toString());
        }
    }

    /**
     * 这是一个不常驻 24/7 的桌面应用；此前每轮只补算"昨日"，应用关了几天没开
     * （长周末）中间跳过的那几天永远没人替它们算——events 表里有数据，
     * TelemetryDailyRollup 行永远不会出现，那几天的匿名统计静默永久丢失。
     *
     * <p>昨日无条件重算：临近午夜落库的事件可能在第二天才被处理，用旧值覆盖同一天
     * 是既有行为，不能因为加了补算就丢了这条。更早的日子只在完全没有 rollup 行时才
     * 补——rollupFor 本身是幂等 upsert，但没必要每天重新算 29 天前已经算对的数据。
     */
    private void backfillMissingRollups() {
        LocalDate today = LocalDate.now();
        rollupService.rollupFor(today.minusDays(1));
        for (int i = 2; i <= BACKFILL_DAYS; i++) {
            LocalDate day = today.minusDays(i);
            if (rollupRepository.findByDate(day).isEmpty()) {
                rollupService.rollupFor(day);
            }
        }
    }

    private void uploadPendingRollups() {
        List<TelemetryDailyRollup> pending =
                rollupRepository.findByUploadedFalseAndDateAfter(LocalDate.now().minusDays(30));
        for (TelemetryDailyRollup row : pending) {
            try {
                int status = transport.post(ingestBaseUrl + "/rollup", row.getPayload());
                if (status >= 200 && status < 300) {
                    row.setUploaded(true);
                    row.setUploadedAt(Instant.now());
                    rollupRepository.save(row);
                }
            } catch (Exception e) {
                log.debug("rollup 上报失败（下轮补传）: date={}, {}", row.getDate(), e.toString());
            }
        }
    }

    /** Tier 2：上报最近一天的脱敏事件流（本地已过白名单；不重传历史，粗粒度即可） */
    private void uploadRecentEvents() {
        try {
            List<TelemetryEvent> events =
                    eventRepository.findByTsAfter(Instant.now().minus(1, ChronoUnit.DAYS));
            if (events.isEmpty()) return;
            if (events.size() > EVENT_BATCH_LIMIT) {
                events = events.subList(0, EVENT_BATCH_LIMIT);
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (TelemetryEvent e : events) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ts", e.getTs().toString());
                item.put("eventName", e.getEventName());
                if (e.getConvKey() != null) item.put("convKey", e.getConvKey());
                if (e.getAttrs() != null) {
                    item.put("attrs", mapper.readValue(e.getAttrs(), Map.class));
                }
                items.add(item);
            }
            Map<String, Object> body = Map.of("installId", identity.installId(), "events", items);
            transport.post(ingestBaseUrl + "/events", mapper.writeValueAsString(body));
        } catch (Exception e) {
            log.debug("事件流上报失败（静默）: {}", e.toString());
        }
    }

}
