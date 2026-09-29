// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dev-board#1060：TurnStreamTrace 的回放日志与思考记录。 */
class TurnStreamTraceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("回放按序、同类合并、带时间戳；思考按轮分块，anchor 取执行日志里的过程数")
    void recordsInOrderWithTimestampsAndAnchors() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        TurnStreamTrace trace = new TurnStreamTrace(clock::get);
        StringBuilder log = new StringBuilder();
        trace.beginModelRound(log);
        clock.set(2000);
        trace.recordReasoning("想");
        clock.set(3000);
        trace.recordReasoning("一想");
        log.append("<process name=\"读\"><tool_code>x()</tool_code></process>\n");
        clock.set(4000);
        trace.recordText("<process name=\"读\">");
        trace.beginModelRound(log);
        clock.set(5000);
        trace.recordReasoning("再想");

        JsonNode payload = mapper.readTree(trace.recoveryPayload("tok"));
        assertEquals("tok", payload.get("content").asText());
        assertEquals(1000, payload.get("startedAt").asLong());
        assertEquals(5000, payload.get("serverNow").asLong());
        JsonNode events = payload.get("events");
        assertEquals(3, events.size());
        assertEquals("想一想", events.get(0).get("content").asText());
        assertEquals(2000, events.get(0).get("at").asLong());
        assertEquals(3000, events.get(0).get("lastAt").asLong());
        assertEquals("text", events.get(1).get("type").asText());
        assertEquals("再想", events.get(2).get("content").asText());

        JsonNode blocks = mapper.readTree(trace.reasoningJson());
        assertEquals(2, blocks.size());
        assertEquals(0, blocks.get(0).get("anchor").asInt());
        assertEquals(2000, blocks.get(0).get("startedAt").asLong());
        assertEquals(3000, blocks.get(0).get("endedAt").asLong());
        assertEquals(1, blocks.get(1).get("anchor").asInt());
        assertEquals(5, trace.reasoningLength());
    }

    @Test
    @DisplayName("没有思考时落库记录为 null；新段开始后两份记录都清空")
    void emptyAndReset() throws Exception {
        TurnStreamTrace trace = new TurnStreamTrace();
        trace.recordText("正文");
        assertNull(trace.reasoningJson(), "没有思考就落 null，存量行为不变");
        trace.recordReasoning("想");
        assertTrue(trace.reasoningJson() != null);
        trace.resetSegment();
        assertNull(trace.reasoningJson());
        assertTrue(trace.isEmpty());
        assertEquals(0, mapper.readTree(trace.recoveryPayload("")).get("events").size());
    }

    @Test
    @DisplayName("回放超限从最早一段丢起；思考落库超限截断并标 truncated")
    void bounded() throws Exception {
        TurnStreamTrace trace = new TurnStreamTrace();
        String big = "a".repeat(TurnStreamTrace.REPLAY_LIMIT - 10);
        trace.recordText(big);
        trace.recordReasoning("b".repeat(TurnStreamTrace.REASONING_PERSIST_LIMIT + 5));
        JsonNode events = mapper.readTree(trace.recoveryPayload("")).get("events");
        long total = 0;
        for (JsonNode e : events) total += e.get("content").asText().length();
        assertTrue(total <= TurnStreamTrace.REPLAY_LIMIT, "回放总量不超限：" + total);
        assertEquals("reasoning", events.get(events.size() - 1).get("type").asText(), "最新的一段保留");
        JsonNode blocks = mapper.readTree(trace.reasoningJson());
        assertEquals(TurnStreamTrace.REASONING_PERSIST_LIMIT, blocks.get(0).get("text").asText().length());
        assertTrue(blocks.get(0).get("truncated").asBoolean());
    }
}
