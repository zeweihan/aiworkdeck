package com.checkba.service.telemetry;

import com.checkba.model.entity.TelemetryDailyRollup;
import com.checkba.repository.TelemetryDailyRollupRepository;
import com.checkba.repository.TelemetryEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 病灶：sync() 每轮只补算"昨日"一天。这是一个不常驻 24/7 的桌面应用——用户关了几天
 * 没开（长周末），再打开时 onStartup()/scheduledSync() 只算回前一天，中间那几天
 * telemetry_event 表里明明有数据，却永远不会有对应的 TelemetryDailyRollup 行，
 * 那几天的匿名统计永久静默丢失，没有任何日志或错误信号。
 */
class TelemetryUploadServiceTest {

    TelemetryDailyRollupRepository rollupRepository;
    TelemetryEventRepository eventRepository;
    TelemetryRollupService rollupService;
    TelemetrySettings settings;
    InstallIdentityService identity;
    TelemetryUploadService service;

    @BeforeEach
    void setUp() {
        rollupRepository = mock(TelemetryDailyRollupRepository.class);
        eventRepository = mock(TelemetryEventRepository.class);
        rollupService = mock(TelemetryRollupService.class);
        settings = mock(TelemetrySettings.class);
        identity = mock(InstallIdentityService.class);
        // rollup 上报关掉，sync() 补算完当天缺口后直接返回，不需要再打桩上传链路
        when(settings.rollupEnabled()).thenReturn(false);
        // 默认每天都已经有 rollup 行——测试各自按需覆盖成"缺失"
        when(rollupRepository.findByDate(any())).thenAnswer(inv ->
                Optional.of(new TelemetryDailyRollup()));

        service = new TelemetryUploadService(rollupRepository, eventRepository, rollupService,
                settings, identity, "https://example.invalid/telemetry", (url, body) -> 200);
    }

    @Test
    @DisplayName("昨日无条件重算（late-arriving 事件在临近午夜才落库）")
    void yesterdayIsAlwaysRolledUpUnconditionally() {
        service.sync();

        verify(rollupService).rollupFor(LocalDate.now().minusDays(1));
    }

    @Test
    @DisplayName("应用关了几天没开：中间跳过的日子在下次启动时被补算")
    void gapDaysWithoutAnyRollupRowAreBackfilled() {
        LocalDate today = LocalDate.now();
        LocalDate gapDay = today.minusDays(3); // 长周末跳过的那天，events 表有数据但从没算过
        when(rollupRepository.findByDate(gapDay)).thenReturn(Optional.empty());

        service.sync();

        verify(rollupService).rollupFor(gapDay);
    }

    @Test
    @DisplayName("已经有 rollup 行的老日子不重复计算（只补真正缺失的）")
    void alreadyRolledUpOlderDaysAreNotRecomputed() {
        LocalDate twoDaysAgo = LocalDate.now().minusDays(2);
        // 默认桩已经让 findByDate 一律返回"存在"

        service.sync();

        verify(rollupService, never()).rollupFor(twoDaysAgo);
    }

    @Test
    @DisplayName("回溯窗口有界：不会无限往前找缺口")
    void backfillWindowIsBounded() {
        when(rollupRepository.findByDate(any())).thenReturn(Optional.empty()); // 全部"缺失"

        service.sync();

        // 30 天窗口（与 uploadPendingRollups 的补传窗口口径一致）：昨天 + 更早 29 天 = 30 次
        verify(rollupService, times(30)).rollupFor(any());
    }
}
