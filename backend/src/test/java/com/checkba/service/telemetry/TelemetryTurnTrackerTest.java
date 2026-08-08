package com.checkba.service.telemetry;

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
}
