// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.telemetry;

import com.checkba.service.ai.AgentRunStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 轮次埋点的健壮性：埋点绝不允许炸掉业务主链。
 * 真机事故（2026-08-07 云后端验收）：Office 插件的 chat 请求不带 model 字段，
 * turnAttrs 里 model=null，Map.copyOf 直接 NPE，整轮在编排器入口静默死掉——
 * 用户侧表现为 SSE 只有心跳、永远等不到回复。
 */
class TelemetryTurnTrackerTest {

    @Test
    @DisplayName("attrs 含 null 值（插件不传 model）：不抛异常，空值字段丢弃")
    void nullAttrValuesAreDroppedNotFatal() {
        TelemetryService telemetry = mock(TelemetryService.class);
        TelemetryTurnTracker tracker = new TelemetryTurnTracker(telemetry);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("mode", "AGENT");
        attrs.put("model", null); // Office 插件形态
        attrs.put("attachmentCount", 0);

        assertDoesNotThrow(() -> tracker.startTurn("conv-x", attrs));

        tracker.onStatus("conv-x", "FINISHED");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(telemetry).recordConv(eq("ai.turn"), eq("conv-x"), captor.capture());
        Map<String, Object> recorded = captor.getValue();
        assertEquals("AGENT", recorded.get("mode"));
        assertFalse(recorded.containsKey("model"), "null 值字段应被丢弃");
        assertEquals("FINISHED", recorded.get("outcome"));
    }

    @Test
    @DisplayName("attrs 为 null / conversationId 为 null：no-op 不抛")
    void nullInputsAreNoop() {
        TelemetryTurnTracker tracker = new TelemetryTurnTracker(mock(TelemetryService.class));
        assertDoesNotThrow(() -> tracker.startTurn(null, null));
        assertDoesNotThrow(() -> tracker.startTurn("conv-y", null));
        assertDoesNotThrow(() -> tracker.onStatus("conv-y", "FINISHED"));
    }

    @Test
    @DisplayName("反问停机 AWAITING_INPUT 闭合轮次：漏加进 TERMINAL 则 ai.turn 永不闭合")
    void awaitingInputClosesTurn() {
        TelemetryService telemetry = mock(TelemetryService.class);
        TelemetryTurnTracker tracker = new TelemetryTurnTracker(telemetry);

        tracker.startTurn("conv-q", Map.of("mode", "AGENT"));
        tracker.onStatus("conv-q", AgentRunStateService.RunStatus.AWAITING_INPUT.name());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(telemetry).recordConv(eq("ai.turn"), eq("conv-q"), captor.capture());
        assertEquals("AWAITING_INPUT", captor.getValue().get("outcome"));

        // consume-once：用户回答是新一轮消息，旧上下文不该被第二个终态重复计数
        tracker.onStatus("conv-q", "FINISHED");
        verify(telemetry, times(1)).recordConv(eq("ai.turn"), eq("conv-q"), any());
    }

    // ==== rounds / promptTokensFirstRound（dev-board#729 ⑥）====
    // 一条消息跑了几个 LLM 往返、首轮 prompt 有多大，是「慢在哪、贵在哪」的两个基本判据，
    // 此前账本里一个都没有——只知道总时长 80 秒，不知道是一轮慢还是跑了八轮。

    @Test
    @DisplayName("ai.turn 带上本轮 LLM 往返数与首轮 promptTokens")
    void turnCarriesRoundCountAndFirstRoundPromptTokens() {
        TelemetryService telemetry = mock(TelemetryService.class);
        TelemetryTurnTracker tracker = new TelemetryTurnTracker(telemetry);

        tracker.startTurn("conv-r", Map.of("mode", "AGENT"));
        tracker.noteRound("conv-r");
        tracker.notePromptTokens("conv-r", 59045);
        tracker.noteRound("conv-r");
        // 后续轮次的 promptTokens 更大（叠着工具结果），但只记第一次——
        // 混在一起就看不出固定前缀本身的体量了
        tracker.notePromptTokens("conv-r", 91000);
        tracker.noteRound("conv-r");
        tracker.onStatus("conv-r", "FINISHED");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(telemetry).recordConv(eq("ai.turn"), eq("conv-r"), captor.capture());
        assertEquals(3, captor.getValue().get("rounds"));
        assertEquals(59045, captor.getValue().get("promptTokensFirstRound"));
    }

    @Test
    @DisplayName("通道不回 usage（Ollama / 回放评测）时不写 promptTokensFirstRound，而不是写 0")
    void missingUsageLeavesTheFieldOutEntirely() {
        TelemetryService telemetry = mock(TelemetryService.class);
        TelemetryTurnTracker tracker = new TelemetryTurnTracker(telemetry);

        tracker.startTurn("conv-s", Map.of("mode", "AGENT"));
        tracker.noteRound("conv-s");
        tracker.onStatus("conv-s", "FINISHED");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(telemetry).recordConv(eq("ai.turn"), eq("conv-s"), captor.capture());
        assertEquals(1, captor.getValue().get("rounds"));
        assertFalse(captor.getValue().containsKey("promptTokensFirstRound"),
                "写 0 会让「拿不到」与「真的很小」在账本里长得一模一样");
    }

    @Test
    @DisplayName("没有开启轮次时 noteRound / notePromptTokens 是 no-op（子 Agent、辅助模型不该被计进来）")
    void countersOutsideATurnAreNoop() {
        TelemetryService telemetry = mock(TelemetryService.class);
        TelemetryTurnTracker tracker = new TelemetryTurnTracker(telemetry);

        assertDoesNotThrow(() -> tracker.noteRound("conv-none"));
        assertDoesNotThrow(() -> tracker.notePromptTokens("conv-none", 1234));
        assertDoesNotThrow(() -> tracker.noteRound(null));
        verifyNoInteractions(telemetry);
    }

    @Test
    @DisplayName("两个新字段都在 ai.turn 的白名单里（不在就整条被丢弃）")
    void newFieldsAreWhitelisted() {
        assertTrue(TelemetryAttrWhitelist.allowedAttrs("ai.turn").contains("rounds"));
        assertTrue(TelemetryAttrWhitelist.allowedAttrs("ai.turn").contains("promptTokensFirstRound"));
    }

    @Test
    @DisplayName("状态机全覆盖：除 RUNNING/INTERRUPTED 外每个 RunStatus 都必须闭合轮次")
    void everyStoppingStatusClosesTurn() {
        // 这条守的是「新增终止/停机分支忘了同步 TERMINAL」——后果不是报错而是
        // 该会话的 TurnCtx 永远留在 open 里，ai.turn 静默少一条。
        // 两个例外：RUNNING 是轮次起点；INTERRUPTED 只由启动回收在新进程里打，
        // 那时进程内没有未闭合轮次（restore 更是刻意不打点）。
        for (AgentRunStateService.RunStatus status : AgentRunStateService.RunStatus.values()) {
            boolean expectClose = status != AgentRunStateService.RunStatus.RUNNING
                    && status != AgentRunStateService.RunStatus.INTERRUPTED;
            String conv = "conv-" + status.name();

            TelemetryService telemetry = mock(TelemetryService.class);
            TelemetryTurnTracker tracker = new TelemetryTurnTracker(telemetry);
            tracker.startTurn(conv, Map.of("mode", "AGENT"));
            tracker.onStatus(conv, status.name());

            verify(telemetry, times(expectClose ? 1 : 0))
                    .recordConv(eq("ai.turn"), eq(conv), any());
        }
    }
}
