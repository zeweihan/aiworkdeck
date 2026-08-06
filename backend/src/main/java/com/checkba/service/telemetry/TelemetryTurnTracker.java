package com.checkba.service.telemetry;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每轮 AI 对话的开始/终态配对器：把「发起时的上下文」与「终态 + 端到端耗时」
 * 合成一条 ai.turn 事件（设计 §5.4）。
 *
 * 语义：startTurn 开一个轮次上下文；第一个终态消费它（consume-once），
 * 后续无上下文的终态忽略——恢复/暂停等多次状态跳变不会重复计数。
 * 挂接点：AgentRunStateService.mark（全部终止分支的既有单点）。
 */
@Service
public class TelemetryTurnTracker {

    /** 视为「一轮结束」的状态：与 AgentRunStateService.RunStatus 字面量对齐 */
    private static final Set<String> TERMINAL =
            Set.of("FINISHED", "ERROR", "CANCELLED", "PAUSED", "AWAITING_APPROVAL");

    private record TurnCtx(long startMs, Map<String, Object> attrs) {}

    private final TelemetryService telemetry;
    private final Map<String, TurnCtx> open = new ConcurrentHashMap<>();

    public TelemetryTurnTracker(TelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    /** 轮次发起（handleUserMessage 入口调用）；attrs 已按 ai.turn 白名单字段准备 */
    public void startTurn(String conversationId, Map<String, Object> attrs) {
        if (conversationId == null) return;
        open.put(conversationId, new TurnCtx(System.currentTimeMillis(),
                attrs == null ? Map.of() : Map.copyOf(attrs)));
    }

    /** 状态跳变（AgentRunStateService.mark 调用）；非终态或无开启轮次时为 no-op */
    public void onStatus(String conversationId, String status) {
        if (conversationId == null || status == null || !TERMINAL.contains(status)) return;
        TurnCtx ctx = open.remove(conversationId);
        if (ctx == null) return;
        Map<String, Object> attrs = new HashMap<>(ctx.attrs());
        attrs.put("outcome", status);
        attrs.put("durationMs", System.currentTimeMillis() - ctx.startMs());
        telemetry.recordConv("ai.turn", conversationId, attrs);
    }
}
