// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.slf4j.Logger;

/**
 * 一轮对话在「首 token 之前」那段准备工作的分段计时。
 *
 * <p>存在的理由：用户感知的响应速度里，{@code Stream TTFT} 只覆盖「请求发出 → 首字回来」，
 * 而在那之前还有落库、skill 激活、上下文组装、记忆检索、历史压缩、工具规格准备一整串同步工作，
 * 全部串在用户的等待里。没有这条分段，排障时只能在「模型慢」和「我们慢」之间靠猜。
 *
 * <p><b>默认零开销、零输出</b>：{@link #start} 在目标 logger 的 DEBUG 关闭时返回共享的
 * 空操作实例，{@code mark}/{@code done} 不分配、不取时间、不打日志。要看分段就临时把
 * {@code com.checkba.service.ai} 的日志级别调到 DEBUG。
 *
 * <p>刻意不做成埋点事件：这是排障工具，不是产品指标；在线指标仍是 {@code ai.turn}
 * 的 rounds / promptTokensFirstRound（dev-board#729 ⑥）。
 */
public final class TurnTimings {

    private static final TurnTimings DISABLED = new TurnTimings(null, null, false);

    private final String label;
    private final String conversationId;
    private final boolean enabled;
    private final long startNanos;
    private long lastNanos;
    private final StringBuilder marks;

    private TurnTimings(String label, String conversationId, boolean enabled) {
        this.label = label;
        this.conversationId = conversationId;
        this.enabled = enabled;
        this.startNanos = enabled ? System.nanoTime() : 0L;
        this.lastNanos = this.startNanos;
        this.marks = enabled ? new StringBuilder() : null;
    }

    /** DEBUG 关闭时返回空操作实例——调用方不需要判空，也不会付任何代价。 */
    public static TurnTimings start(Logger log, String label, String conversationId) {
        if (log == null || !log.isDebugEnabled()) return DISABLED;
        return new TurnTimings(label, conversationId, true);
    }

    /** 记一段：从上一个 mark（或起点）到现在的毫秒数。 */
    public void mark(String name) {
        if (!enabled) return;
        long now = System.nanoTime();
        marks.append(' ').append(name).append('=').append((now - lastNanos) / 1_000_000L);
        lastNanos = now;
    }

    /** 记一段并附带一个计数（条目数、工具数等），便于把耗时和规模对上。 */
    public void mark(String name, long count) {
        if (!enabled) return;
        long now = System.nanoTime();
        marks.append(' ').append(name).append('=').append((now - lastNanos) / 1_000_000L)
                .append('(').append(count).append(')');
        lastNanos = now;
    }

    /** 打一条汇总行；DEBUG 关闭时什么都不做。 */
    public void done(Logger log) {
        if (!enabled) return;
        log.debug("[Timing] {} conv={} total={}ms{}",
                label, conversationId, (System.nanoTime() - startNanos) / 1_000_000L, marks);
    }
}
