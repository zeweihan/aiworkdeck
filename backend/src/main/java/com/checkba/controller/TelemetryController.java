package com.checkba.controller;

import com.checkba.model.entity.TelemetryEvent;
import com.checkba.repository.TelemetryEventRepository;
import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.telemetry.TelemetryService;
import com.checkba.service.telemetry.TelemetrySettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 埋点设置 / 前端事件入口 / 本地使用统计（设计 §5.4、§5.7）。
 *
 * /event 只放行前端产生的事件名（editor.action / ui.nav / app.start），
 * 服务端事件不经 HTTP，防止伪造污染。鉴权同 ActivityLogController：
 * X-Session-Id required=false，身份判定交给 getUserIdFromSession。
 */
@RestController
@RequestMapping("/api/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private static final Set<String> FRONTEND_EVENTS = Set.of("editor.action", "ui.nav", "app.start");

    private final TelemetrySettings settings;
    private final TelemetryService telemetry;
    private final TelemetryEventRepository eventRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 0);
        r.put("rollupEnabled", settings.rollupEnabled());
        r.put("eventsEnabled", settings.eventsEnabled());
        return r;
    }

    @PostMapping("/settings")
    public Map<String, Object> updateSettings(
            @RequestBody SettingsRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (request.getRollupEnabled() != null) settings.setRollupEnabled(request.getRollupEnabled());
        if (request.getEventsEnabled() != null) settings.setEventsEnabled(request.getEventsEnabled());
        return getSettings();
    }

    @PostMapping("/event")
    public Map<String, Object> logEvent(
            @RequestBody EventRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        Map<String, Object> r = new HashMap<>();
        r.put("code", 0);
        if (request.getEventName() == null || !FRONTEND_EVENTS.contains(request.getEventName())) {
            r.put("accepted", false);
            return r;
        }
        telemetry.record(request.getEventName(), request.getAttrs());
        r.put("accepted", true);
        return r;
    }

    /** 本地使用统计页数据源：全部来自本机账本与 token_usage，与上报开关无关。 */
    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(value = "days", defaultValue = "30") int days,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        int window = Math.max(1, Math.min(days, 365));
        Instant from = Instant.now().minus(window, ChronoUnit.DAYS);

        List<TelemetryEvent> events = eventRepository.findByTsAfter(from);
        Map<String, Long> counters = events.stream()
                .collect(Collectors.groupingBy(TelemetryEvent::getEventName, Collectors.counting()));

        Map<String, Long> byTool = new HashMap<>();
        Map<String, Long> bySkill = new HashMap<>();
        Map<String, Long> byMatter = new HashMap<>();
        Map<String, Long> byOutcome = new HashMap<>();
        long agentEditorActions = 0, humanEditorActions = 0;
        for (TelemetryEvent e : events) {
            Map<String, Object> attrs = parseAttrs(e.getAttrs());
            switch (e.getEventName()) {
                case "ai.tool" -> inc(byTool, str(attrs.get("toolName")));
                case "skill.activated" -> inc(bySkill, str(attrs.get("skillId")));
                case "matter.classified" -> inc(byMatter, str(attrs.get("category")));
                case "ai.turn" -> inc(byOutcome, str(attrs.get("outcome")));
                case "editor.action" -> {
                    if (Boolean.TRUE.equals(attrs.get("agent"))) agentEditorActions++;
                    else humanEditorActions++;
                }
                default -> { }
            }
        }

        Map<String, Map<String, Object>> tokensBySource = new HashMap<>();
        tokenUsageRepository.findByCreatedAtAfter(LocalDateTime.now().minusDays(window)).forEach(u -> {
            String src = u.getCostSource() == null ? "estimate" : u.getCostSource();
            Map<String, Object> agg = tokensBySource.computeIfAbsent(src, k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("totalTokens", 0L);
                m.put("cost", BigDecimal.ZERO);
                return m;
            });
            agg.put("totalTokens", (Long) agg.get("totalTokens")
                    + (u.getTotalTokens() == null ? 0 : u.getTotalTokens()));
            if (u.getCost() != null) {
                agg.put("cost", ((BigDecimal) agg.get("cost")).add(u.getCost()));
            }
        });

        Map<String, Object> r = new HashMap<>();
        r.put("code", 0);
        r.put("days", window);
        r.put("counters", counters);
        r.put("byTool", topN(byTool, 15));
        r.put("bySkill", topN(bySkill, 15));
        r.put("byMatterCategory", topN(byMatter, 15));
        r.put("byOutcome", byOutcome);
        r.put("editorActions", Map.of("agent", agentEditorActions, "human", humanEditorActions));
        r.put("tokens", tokensBySource);
        return r;
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

    private static void inc(Map<String, Long> m, String key) {
        if (key != null) m.merge(key, 1L, Long::sum);
    }

    private static String str(Object o) {
        return o instanceof String s && !s.isEmpty() ? s : null;
    }

    private static List<Map<String, Object>> topN(Map<String, Long> m, int n) {
        return m.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(n)
                .map(e -> Map.<String, Object>of("name", e.getKey(), "count", e.getValue()))
                .collect(Collectors.toList());
    }

    @Data
    public static class SettingsRequest {
        private Boolean rollupEnabled;
        private Boolean eventsEnabled;
    }

    @Data
    public static class EventRequest {
        private String eventName;
        private Map<String, Object> attrs;
    }
}
