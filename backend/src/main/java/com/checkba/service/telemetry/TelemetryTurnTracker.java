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

    /**
     * 视为「一轮结束」的状态：与 AgentRunStateService.RunStatus 字面量对齐。
     *
     * 「停机等人」的状态（PAUSED/AWAITING_APPROVAL/AWAITING_INPUT）都算轮次结束——
     * 用户的答复会作为**新一轮**用户消息重新 startTurn。漏加一个新的停机状态的后果是
     * 该轮的 TurnCtx 永远留在 open 里、ai.turn 永不闭合（既有教训）。
     */
    private static final Set<String> TERMINAL =
            Set.of("FINISHED", "ERROR", "CANCELLED", "PAUSED", "AWAITING_APPROVAL", "AWAITING_INPUT");

    private record TurnCtx(long startMs, Map<String, Object> attrs) {}

    private final TelemetryService telemetry;
    private final Map<String, TurnCtx> open = new ConcurrentHashMap<>();

    public TelemetryTurnTracker(TelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    /** 轮次发起（handleUserMessage 入口调用）；attrs 已按 ai.turn 白名单字段准备 */
    public void startTurn(String conversationId, Map<String, Object> attrs) {
        if (conversationId == null) return;
        // Map.copyOf 不收 null 值，而调用方的字段可为空（Office 插件不传 model，
        // 曾让整轮在这里 NPE 静默死掉）——空值字段直接丢弃，不让埋点炸掉业务主链。
        Map<String, Object> safe = Map.of();
        if (attrs != null) {
            safe = attrs.entrySet().stream()
                    .filter(e -> e.getKey() != null && e.getValue() != null)
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, Map.Entry::getValue));
        }
        open.put(conversationId, new TurnCtx(System.currentTimeMillis(), safe));
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
