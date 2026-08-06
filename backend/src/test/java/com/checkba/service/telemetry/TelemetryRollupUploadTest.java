package com.checkba.service.telemetry;

import com.checkba.model.entity.TelemetryDailyRollup;
import com.checkba.model.entity.TelemetryEvent;
import com.checkba.model.entity.TokenUsage;
import com.checkba.repository.TelemetryDailyRollupRepository;
import com.checkba.repository.TelemetryEventRepository;
import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.SystemSettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 锁定：rollup 聚合口径（含 costSource 分口径）、上报开关语义（关=零外发）、
 * 失败静默与补传标记。
 */
class TelemetryRollupUploadTest {

    @TempDir
    Path dir;

    TelemetryEventRepository eventRepo;
    TelemetryDailyRollupRepository rollupRepo;
    TokenUsageRepository tokenRepo;
    SystemSettingService settingStore;
    TelemetryRollupService rollupService;
    InstallIdentityService identity;
    ObjectMapper mapper = new ObjectMapper();

    LocalDate date = LocalDate.of(2026, 8, 5);

    @BeforeEach
    void setup() {
        eventRepo = mock(TelemetryEventRepository.class);
        rollupRepo = mock(TelemetryDailyRollupRepository.class);
        tokenRepo = mock(TokenUsageRepository.class);
        settingStore = mock(SystemSettingService.class);
        identity = new InstallIdentityService(dir.toString());
        TelemetryService telemetry = new TelemetryService(eventRepo, identity, "1.2.3");
        rollupService = new TelemetryRollupService(eventRepo, rollupRepo, tokenRepo, identity, telemetry);
    }

    private TelemetryEvent ev(String name, String attrs, int minuteOffset) {
        TelemetryEvent e = new TelemetryEvent();
        e.setTs(date.atStartOfDay(ZoneId.systemDefault()).toInstant().plusSeconds(minuteOffset * 60L));
        e.setEventName(name);
        e.setAttrs(attrs);
        e.setAppVersion("1.2.3");
        return e;
    }

    @Test
    void rollupAggregatesCountersDistributionsAndTokensBySource() throws Exception {
        List<TelemetryEvent> events = new ArrayList<>(List.of(
                ev("ai.turn", "{\"outcome\":\"FINISHED\"}", 0),
                ev("ai.turn", "{\"outcome\":\"ERROR\"}", 1),
                ev("ai.tool", "{\"toolName\":\"doc_replace_text\"}", 1),
                ev("ai.tool", "{\"toolName\":\"doc_replace_text\"}", 2),
                ev("ai.model", "{\"provider\":\"OPENROUTER\",\"targetModel\":\"m1\"}", 2),
                ev("skill.activated", "{\"skillId\":\"listing-pathway\"}", 3),
                ev("matter.classified", "{\"category\":\"资本市场证券\"}", 3),
                ev("editor.action", "{\"agent\":true}", 4),
                ev("file.changed", null, 4)));
        when(eventRepo.findByTsBetween(any(), any())).thenReturn(events);

        TokenUsage platform = new TokenUsage();
        platform.setCostSource("platform");
        platform.setPromptTokens(100);
        platform.setCompletionTokens(50);
        platform.setTotalTokens(150);
        platform.setCost(new BigDecimal("0.010000"));
        platform.setCreatedAt(date.atTime(10, 0));
        platform.setModel("m1");
        TokenUsage estimate = new TokenUsage();
        estimate.setCostSource("estimate");
        estimate.setPromptTokens(10);
        estimate.setCompletionTokens(5);
        estimate.setTotalTokens(15);
        estimate.setCreatedAt(date.atTime(11, 0));
        estimate.setModel("m1");
        when(tokenRepo.findByCreatedAtAfter(any())).thenReturn(List.of(platform, estimate));
        when(rollupRepo.findByDate(date)).thenReturn(Optional.empty());

        rollupService.rollupFor(date);

        ArgumentCaptor<TelemetryDailyRollup> cap = ArgumentCaptor.forClass(TelemetryDailyRollup.class);
        verify(rollupRepo).save(cap.capture());
        JsonNode p = mapper.readTree(cap.getValue().getPayload());

        assertEquals(identity.installId(), p.get("installId").asText());
        assertEquals("2026-08-05", p.get("date").asText());
        assertEquals(2, p.get("counters").get("ai.turn").asInt());
        assertEquals(1, p.get("counters").get("ai.turn.finished").asInt());
        assertEquals(1, p.get("counters").get("ai.turn.error").asInt());
        assertEquals(1, p.get("counters").get("editor.action.agent").asInt());
        assertEquals(2, p.get("byTool").get("doc_replace_text").asInt());
        assertEquals(1, p.get("bySkill").get("listing-pathway").asInt());
        assertEquals(1, p.get("byMatterCategory").get("资本市场证券").asInt());
        assertEquals(1, p.get("byProvider").get("OPENROUTER").asInt());
        // costSource 两套口径不得合并
        assertEquals(100, p.get("tokens").get("platform").get("promptTokens").asInt());
        assertEquals(10, p.get("tokens").get("estimate").get("promptTokens").asInt());
        // 5 个不同分钟
        assertEquals(5, p.get("activeMinutes").asInt());
        assertFalse(cap.getValue().isUploaded());
    }

    @Test
    void emptyDayProducesNoRollup() {
        when(eventRepo.findByTsBetween(any(), any())).thenReturn(List.of());
        rollupService.rollupFor(date);
        verify(rollupRepo, never()).save(any());
    }

    private TelemetryUploadService uploader(String rollupEnabled, String eventsEnabled,
                                            TelemetryUploadService.HttpTransport transport) {
        when(settingStore.get(eq(TelemetrySettings.KEY_ROLLUP), anyString())).thenReturn(rollupEnabled);
        when(settingStore.get(eq(TelemetrySettings.KEY_EVENTS), anyString())).thenReturn(eventsEnabled);
        TelemetryRollupService noopRollup = mock(TelemetryRollupService.class);
        return new TelemetryUploadService(rollupRepo, eventRepo, noopRollup,
                new TelemetrySettings(settingStore), identity, "http://ingest.test/api/telemetry", transport);
    }

    @Test
    void disabledRollupSwitchMeansZeroOutboundRequests() {
        List<String> calls = new ArrayList<>();
        TelemetryUploadService svc = uploader("false", "true", (url, body) -> {
            calls.add(url);
            return 200;
        });
        svc.sync();
        assertTrue(calls.isEmpty());
    }

    @Test
    void pendingRollupIsUploadedAndMarked() {
        TelemetryDailyRollup row = new TelemetryDailyRollup();
        row.setDate(date);
        row.setPayload("{\"installId\":\"x\"}");
        when(rollupRepo.findByUploadedFalseAndDateAfter(any())).thenReturn(List.of(row));
        List<String> calls = new ArrayList<>();
        TelemetryUploadService svc = uploader("true", "false", (url, body) -> {
            calls.add(url);
            return 200;
        });
        svc.sync();
        assertEquals(List.of("http://ingest.test/api/telemetry/rollup"), calls);
        assertTrue(row.isUploaded());
        assertNotNull(row.getUploadedAt());
    }

    @Test
    void serverFailureKeepsUploadedFalseAndNeverThrows() {
        TelemetryDailyRollup row = new TelemetryDailyRollup();
        row.setDate(date);
        row.setPayload("{}");
        when(rollupRepo.findByUploadedFalseAndDateAfter(any())).thenReturn(List.of(row));
        TelemetryUploadService svc = uploader("true", "false", (url, body) -> {
            throw new RuntimeException("connection refused");
        });
        assertDoesNotThrow(svc::sync);
        assertFalse(row.isUploaded());
        verify(rollupRepo, never()).save(row);
    }

    @Test
    void tier2UploadsEventsBatchWhenEnabled() {
        when(rollupRepo.findByUploadedFalseAndDateAfter(any())).thenReturn(List.of());
        when(eventRepo.findByTsAfter(any())).thenReturn(List.of(
                ev("ui.nav", "{\"page\":\"admin\"}", 0)));
        List<String> bodies = new ArrayList<>();
        TelemetryUploadService svc = uploader("true", "true", (url, body) -> {
            if (url.endsWith("/events")) bodies.add(body);
            return 200;
        });
        svc.sync();
        assertEquals(1, bodies.size());
        assertTrue(bodies.get(0).contains("ui.nav"));
        assertTrue(bodies.get(0).contains(identity.installId()));
    }
}
