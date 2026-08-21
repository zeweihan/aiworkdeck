package com.checkba.service.ai;

import com.checkba.repository.AgentRunRecordRepository;
import com.checkba.service.telemetry.TelemetryTurnTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计条目：「AgentRunStateService.states map grows unboundedly for the life of the process」。
 * 每个在本进程内跑过至少一轮的 conversationId 永久占一条，从不摘除。修法是给每条记录的更新时刻
 * 配一个每日一次的惰性过期扫描（对齐 TodoListService.purgeStaleLists 的既有先例）。
 */
@DisplayName("AgentRunStateService：states 无界增长")
class AgentRunStateServiceTest {

    private AgentRunStateService service;

    @BeforeEach
    void setUp() {
        AgentRunRecordRepository recordRepository = mock(AgentRunRecordRepository.class);
        when(recordRepository.findByConversationId(anyString())).thenReturn(Optional.empty());
        TelemetryTurnTracker turnTracker = mock(TelemetryTurnTracker.class);
        service = new AgentRunStateService(recordRepository, turnTracker);
    }

    @Test
    @DisplayName("修复：超过 7 天未再更新的会话状态，purgeStaleStates 应把内存条目清掉")
    void purgeStaleStatesRemovesOldEntries() {
        long[] now = {1_000_000L};
        service.setClockMillis(() -> now[0]);

        service.mark("conv-old", AgentRunStateService.RunStatus.FINISHED);
        assertEquals(1, service.statesSize());

        now[0] += Duration.ofDays(8).toMillis();
        service.purgeStaleStates();

        assertEquals(0, service.statesSize(), "超过 7 天未更新的记录应被清掉，不能无限期占着内存");
        assertNull(service.get("conv-old"), "过期后应回到「本进程内从未跑过」的既有语义");
    }

    @Test
    @DisplayName("未超过 7 天的记录不受影响：purgeStaleStates 不会误删刚更新的状态")
    void purgeStaleStatesKeepsFreshEntries() {
        long[] now = {1_000_000L};
        service.setClockMillis(() -> now[0]);

        service.mark("conv-fresh", AgentRunStateService.RunStatus.RUNNING);

        now[0] += Duration.ofDays(1).toMillis();
        service.purgeStaleStates();

        assertEquals(1, service.statesSize(), "未超过过期窗口的记录不该被误删");
        assertEquals(AgentRunStateService.RunStatus.RUNNING.name(), service.statusName("conv-fresh"));
    }
}
