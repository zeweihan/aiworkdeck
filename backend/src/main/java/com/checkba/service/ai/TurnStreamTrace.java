// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 一个助手段（一条 ASSISTANT 消息）在流式期间「已经给用户看过的一切」（dev-board#1060）。
 *
 * <p>两份记录，用途不同：
 * <ul>
 *   <li><b>回放日志</b>（{@link #recoveryPayload}）：按发生顺序记下正文增量（模型 token +
 *       编排器补发的工具过程标签）与思考增量，每段带服务端时间戳。断线重连 / 切走再切回时，
 *       前端按原顺序把它重放一遍——思考卡、过程卡、正文都回到断开前的样子，
 *       思考计时按后端时间续算，而不是从 0 重来。有上限，超了从最早的一段丢起。</li>
 *   <li><b>思考块</b>（{@link #reasoningJson}）：随消息落库的思考全文，每轮一块，
 *       {@code anchor} = 这一块开始时本段已经执行过的工具过程数（执行日志里的
 *       {@code <process} 个数）。历史回放据此把思考挂回「对应那个过程卡之后」，
 *       与实时 appendReasoning 的落点同口径。</li>
 * </ul>
 *
 * <p>思考文本<b>永远不进正文</b>、不回喂模型（契约 D）：它只落在
 * {@code ProjectAiMessage.reasoning} 这一列，ContextAssembler 不读它。
 *
 * <p>写在流式回调线程、读在 /connect 线程与收尾线程，所有方法同步。
 */
final class TurnStreamTrace {

    /** 回放日志的字符上限，与旧的正文快照同一量级。 */
    static final int REPLAY_LIMIT = 256 * 1024;
    /** 单条消息落库的思考全文上限；超过的部分不再追加，最后一块标 truncated。 */
    static final int REASONING_PERSIST_LIMIT = 200_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class Entry {
        final String type;
        final StringBuilder content = new StringBuilder();
        final long at;
        long lastAt;

        Entry(String type, long at) {
            this.type = type;
            this.at = at;
            this.lastAt = at;
        }
    }

    private static final class Block {
        final StringBuilder text = new StringBuilder();
        final long startedAt;
        long endedAt;
        final int anchor;
        boolean truncated;

        Block(long startedAt, int anchor) {
            this.startedAt = startedAt;
            this.endedAt = startedAt;
            this.anchor = anchor;
        }
    }

    private final LongSupplier clock;
    private final List<Entry> replay = new ArrayList<>();
    private long replayChars;
    private final List<Block> blocks = new ArrayList<>();
    private long reasoningChars;
    /** 下一段思考必须另起一块（新一轮 LLM 调用开始 / 中间出现过正文或工具过程）。 */
    private boolean reasoningBoundary = true;
    private long segmentStartedAt;
    /** 本段的执行日志（runLoop 的 executionLog），只读来数工具过程个数。 */
    private StringBuilder executionLog;

    TurnStreamTrace() {
        this(System::currentTimeMillis);
    }

    TurnStreamTrace(LongSupplier clock) {
        this.clock = clock;
        this.segmentStartedAt = clock.getAsLong();
    }

    /** 每次 LLM 调用开始前调：记下本段的执行日志，并让下一段思考另起一块。 */
    synchronized void beginModelRound(StringBuilder executionLog) {
        this.executionLog = executionLog;
        this.reasoningBoundary = true;
    }

    /** 正文增量：模型 token，或编排器补发给前端的工具过程标签。 */
    synchronized void recordText(String text) {
        if (text == null || text.isEmpty()) return;
        record("text", text);
        reasoningBoundary = true;
    }

    /** 思考增量。 */
    synchronized void recordReasoning(String text) {
        if (text == null || text.isEmpty()) return;
        long now = record("reasoning", text);
        Block block = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
        if (block == null || reasoningBoundary) {
            block = new Block(now, processCount());
            blocks.add(block);
            reasoningBoundary = false;
        }
        block.endedAt = now;
        if (block.truncated) return;
        long room = REASONING_PERSIST_LIMIT - reasoningChars;
        if (room <= 0) {
            block.truncated = true;
            return;
        }
        String kept = text.length() > room ? text.substring(0, (int) room) : text;
        block.text.append(kept);
        reasoningChars += kept.length();
        if (kept.length() < text.length()) block.truncated = true;
    }

    /** 新的助手段开始（插话把当前段落库之后）：两份记录都从空开始。 */
    synchronized void resetSegment() {
        replay.clear();
        replayChars = 0;
        blocks.clear();
        reasoningChars = 0;
        reasoningBoundary = true;
        segmentStartedAt = clock.getAsLong();
    }

    synchronized boolean isEmpty() {
        return replay.isEmpty();
    }

    synchronized int reasoningLength() {
        int n = 0;
        for (Entry e : replay) if ("reasoning".equals(e.type)) n += e.content.length();
        return n;
    }

    /**
     * 随消息落库的思考记录：{@code [{"text","startedAt","endedAt","anchor"[,"truncated"]}]}。
     * 一段思考都没有时返回 null（落库保持 null，老行为不变）。
     */
    synchronized String reasoningJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Block b : blocks) {
            if (b.text.length() == 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", b.text.toString());
            m.put("startedAt", b.startedAt);
            m.put("endedAt", b.endedAt);
            m.put("anchor", b.anchor);
            if (b.truncated) m.put("truncated", true);
            out.add(m);
        }
        if (out.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * state_recovery 的载荷。{@code content} 保持原义（只有模型 token，Office/WPS 任务窗格读它），
     * 新增 {@code events} / {@code startedAt} / {@code serverNow} 给能按序重放的客户端。
     */
    synchronized String recoveryPayload(String legacyContent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", legacyContent == null ? "" : legacyContent);
        body.put("startedAt", segmentStartedAt);
        body.put("serverNow", clock.getAsLong());
        List<Map<String, Object>> events = new ArrayList<>(replay.size());
        for (Entry e : replay) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.type);
            m.put("content", e.content.toString());
            m.put("at", e.at);
            if ("reasoning".equals(e.type)) m.put("lastAt", e.lastAt);
            events.add(m);
        }
        body.put("events", events);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"content\":\"\"}";
        }
    }

    private long record(String type, String text) {
        long now = clock.getAsLong();
        Entry last = replay.isEmpty() ? null : replay.get(replay.size() - 1);
        if (last == null || !last.type.equals(type)) {
            last = new Entry(type, now);
            replay.add(last);
        }
        last.content.append(text);
        last.lastAt = now;
        replayChars += text.length();
        // 超限从最早的一整段丢起；只剩一段还超就截它的开头
        while (replayChars > REPLAY_LIMIT && replay.size() > 1) {
            replayChars -= replay.remove(0).content.length();
        }
        if (replayChars > REPLAY_LIMIT) {
            Entry only = replay.get(0);
            int drop = (int) (replayChars - REPLAY_LIMIT);
            only.content.delete(0, drop);
            replayChars -= drop;
        }
        return now;
    }

    private int processCount() {
        StringBuilder log = executionLog;
        if (log == null) return 0;
        String s;
        try {
            s = log.toString();
        } catch (Exception e) {
            return 0;
        }
        int n = 0;
        for (int i = s.indexOf("<process"); i >= 0; i = s.indexOf("<process", i + 8)) n++;
        return n;
    }
}
