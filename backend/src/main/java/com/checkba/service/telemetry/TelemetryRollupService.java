package com.checkba.service.telemetry;

import com.checkba.model.entity.TelemetryDailyRollup;
import com.checkba.model.entity.TelemetryEvent;
import com.checkba.model.entity.TokenUsage;
import com.checkba.repository.TelemetryDailyRollupRepository;
import com.checkba.repository.TelemetryEventRepository;
import com.checkba.repository.TokenUsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日聚合（设计 §5.5）：把本地明细账本压成每日一条的匿名计数负载。
 * 这是 Tier 1 唯一出本机的数据形状——只有计数与枚举分布，无任何明细。
 */
@Slf4j
@Service
public class TelemetryRollupService {

    private final TelemetryEventRepository eventRepository;
    private final TelemetryDailyRollupRepository rollupRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final InstallIdentityService identity;
    private final TelemetryService telemetryService;
    private final ObjectMapper mapper = new ObjectMapper();

    public TelemetryRollupService(TelemetryEventRepository eventRepository,
                                  TelemetryDailyRollupRepository rollupRepository,
                                  TokenUsageRepository tokenUsageRepository,
                                  InstallIdentityService identity,
                                  TelemetryService telemetryService) {
        this.eventRepository = eventRepository;
        this.rollupRepository = rollupRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.identity = identity;
        this.telemetryService = telemetryService;
    }

    /** 聚合指定日期并 upsert 本地 rollup 表；当日无事件时不产生条目。幂等。 */
    @Transactional
    public void rollupFor(LocalDate date) {
        try {
            ZoneId zone = ZoneId.systemDefault();
            Instant from = date.atStartOfDay(zone).toInstant();
            Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
            List<TelemetryEvent> events = eventRepository.findByTsBetween(from, to);
            if (events.isEmpty()) return;

            Map<String, Long> counters = new LinkedHashMap<>();
            Map<String, Long> byProvider = new LinkedHashMap<>();
            Map<String, Long> byModel = new LinkedHashMap<>();
            Map<String, Long> byTool = new LinkedHashMap<>();
            Map<String, Long> bySkill = new LinkedHashMap<>();
            Map<String, Long> byMatter = new LinkedHashMap<>();
            Set<Long> activeMinutes = new HashSet<>();
            String appVersion = telemetryService.appVersion();

            for (TelemetryEvent e : events) {
                counters.merge(e.getEventName(), 1L, Long::sum);
                activeMinutes.add(e.getTs().getEpochSecond() / 60);
                if (e.getAppVersion() != null) appVersion = e.getAppVersion();
                Map<String, Object> attrs = parseAttrs(e.getAttrs());
                switch (e.getEventName()) {
                    case "ai.turn" -> {
                        Object outcome = attrs.get("outcome");
                        if (outcome instanceof String s) counters.merge("ai.turn." + s.toLowerCase(), 1L, Long::sum);
                    }
                    case "ai.model" -> {
                        inc(byProvider, attrs.get("provider"));
                        inc(byModel, attrs.get("targetModel"));
                    }
                    case "ai.tool" -> inc(byTool, attrs.get("toolName"));
                    case "skill.activated" -> inc(bySkill, attrs.get("skillId"));
                    case "matter.classified" -> inc(byMatter, attrs.get("category"));
                    case "editor.action" -> {
                        if (Boolean.TRUE.equals(attrs.get("agent"))) {
                            counters.merge("editor.action.agent", 1L, Long::sum);
                        }
                    }
                    default -> { }
                }
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("installId", identity.installId());
            payload.put("date", date.toString());
            payload.put("appVersion", appVersion);
            payload.put("platform", platform());
            payload.put("counters", counters);
            payload.put("byProvider", byProvider);
            payload.put("byModel", byModel);
            payload.put("byTool", byTool);
            payload.put("bySkill", bySkill);
            payload.put("byMatterCategory", byMatter);
            payload.put("tokens", tokensFor(date, zone));
            payload.put("activeMinutes", activeMinutes.size());

            String json = mapper.writeValueAsString(payload);
            TelemetryDailyRollup row = rollupRepository.findByDate(date)
                    .orElseGet(TelemetryDailyRollup::new);
            // 重算覆盖旧值并复位上传标记（当日追加的事件要重新上报）
            row.setDate(date);
            row.setPayload(json);
            row.setUploaded(false);
            row.setUploadedAt(null);
            rollupRepository.save(row);
        } catch (Exception e) {
            log.warn("telemetry 日聚合失败: date={}, {}", date, e.toString());
        }
    }

    /** token 用量按 costSource 分口径（platform/estimate 两套口径不得合并的既有契约） */
    private Map<String, Map<String, Object>> tokensFor(LocalDate date, ZoneId zone) {
        Map<String, Map<String, Object>> bySource = new LinkedHashMap<>();
        LocalDateTime from = date.atStartOfDay();
        for (TokenUsage u : tokenUsageRepository.findByCreatedAtAfter(from)) {
            if (u.getCreatedAt() == null || !u.getCreatedAt().toLocalDate().equals(date)) continue;
            String src = u.getCostSource() == null ? "estimate" : u.getCostSource();
            Map<String, Object> agg = bySource.computeIfAbsent(src, k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("promptTokens", 0L);
                m.put("completionTokens", 0L);
                m.put("cost", BigDecimal.ZERO);
                return m;
            });
            agg.put("promptTokens", (Long) agg.get("promptTokens")
                    + (u.getPromptTokens() == null ? 0 : u.getPromptTokens()));
            agg.put("completionTokens", (Long) agg.get("completionTokens")
                    + (u.getCompletionTokens() == null ? 0 : u.getCompletionTokens()));
            if (u.getCost() != null) {
                agg.put("cost", ((BigDecimal) agg.get("cost")).add(u.getCost()));
            }
        }
        return bySource;
    }

    private Map<String, Object> parseAttrs(String json) {
        if (json == null || json.isEmpty()) return Map.of();
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructMapType(HashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void inc(Map<String, Long> m, Object key) {
        if (key instanceof String s && !s.isEmpty()) m.merge(s, 1L, Long::sum);
    }

    private static String platform() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        String norm = os.contains("mac") ? "darwin" : os.contains("win") ? "win32"
                : os.contains("linux") ? "linux" : "other";
        return norm + "-" + System.getProperty("os.arch", "unknown");
    }
}
