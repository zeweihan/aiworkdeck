// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * text_delta 合流（dev-board#812 K32 ⑨，审查 C-14）。
 *
 * <p>通道层对每个模型 token 回调一次 {@code onNext}，一条 3000 token 的回答就是
 * 3000 次 JSON 转义 + 3000 次重放缓冲入队/裁剪 + 3000 个 HTTP chunk，
 * 前端再对应 3000 次事件分派与响应式写入。这是整条链路上被放大倍数最高的一段。
 *
 * <p>三条不能破的契约：
 * <ol>
 *   <li><b>正文一个字都不能少、顺序不能乱</b>——合并只改变分片，不改变内容；</li>
 *   <li><b>首字节不能变慢</b>——本轮第一段文本立即发，不进合并窗口；</li>
 *   <li><b>终态之前必须 flush</b>——否则会出现「气泡已经结束了，正文才姗姗来迟」。</li>
 * </ol>
 */
class AgentStreamTextCoalescingTest {

    private static AgentStreamHandler handler(SseEmitterService sse) {
        return new AgentStreamHandler(sse, "conv-coalesce",
                mock(TokenUsageService.class), "1", 1L, "deepseek/deepseek-v4-flash", 0L);
    }

    /** 按顺序取出所有 text_delta 的 content（去掉信封与转义）。 */
    private static List<String> textDeltas(SseEmitterService sse) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sse, atLeastOnce()).send(eq("conv-coalesce"), eq("text_delta"), payload.capture());
        return payload.getAllValues().stream()
                .map(String::valueOf)
                .map(s -> s.substring("{\"content\":\"".length(), s.length() - "\"}".length()))
                .collect(Collectors.toList());
    }

    private static String joined(SseEmitterService sse) {
        return String.join("", textDeltas(sse));
    }

    @Test
    @DisplayName("逐 token 喂入：正文逐字不变，但发出去的事件条数大幅下降")
    void manyTokensBecomeFewEventsWithoutLosingAnyText() {
        SseEmitterService sse = mock(SseEmitterService.class);
        AgentStreamHandler h = handler(sse);

        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            String token = "字" + (i % 10);
            expected.append(token);
            h.onNext(token);
        }
        h.onComplete(Response.from(AiMessage.from(expected.toString())));

        assertEquals(expected.toString(), joined(sse), "合并只改变分片，正文必须逐字不变");
        int events = textDeltas(sse).size();
        assertTrue(events < 120,
                "600 个 token 不该还发 600 条 text_delta，实际 " + events);
    }

    @Test
    @DisplayName("首字节立即发：第一个 token 不进合并窗口")
    void theFirstChunkIsSentImmediately() {
        SseEmitterService sse = mock(SseEmitterService.class);
        AgentStreamHandler h = handler(sse);

        h.onNext("第");

        // 还没有 onComplete、也没等任何窗口，就必须已经发出去了
        verify(sse).send(eq("conv-coalesce"), eq("text_delta"), anyString());
        assertEquals(List.of("第"), textDeltas(sse));
    }

    @Test
    @DisplayName("终态之前一定 flush：onComplete 之后缓冲里不许还剩字")
    void everythingIsFlushedBeforeTheTerminalEvent() {
        SseEmitterService sse = mock(SseEmitterService.class);
        AgentStreamHandler h = handler(sse);

        h.onNext("首");
        h.onNext("尾巴一小截");
        h.onComplete(Response.from(AiMessage.from("首尾巴一小截")));

        assertEquals("首尾巴一小截", joined(sse), "最后那一小截不能被扣在合并缓冲里");
    }

    @Test
    @DisplayName("SSE 重放缓冲的事件条数随之下降（断线重连要补发的量也跟着降）")
    void theReplayBufferHoldsFarFewerEvents() {
        // 走**真的** SseEmitterService：合并的收益有一半在它身上——
        // 每条 bufferable 事件都要自增序号、构造 BufferedEvent、在 synchronized 块里按条数裁剪，
        // 而重放缓冲是按**条数**裁的：条数降不下来，断线重连要补发的量就降不下来。
        SseEmitterService real = new SseEmitterService();
        String cid = "conv-replay-buffer";
        AgentStreamHandler h = new AgentStreamHandler(real, cid,
                mock(TokenUsageService.class), "1", 1L, "deepseek/deepseek-v4-flash", 0L);

        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            String token = "字" + (i % 10);
            expected.append(token);
            h.onNext(token);
        }
        h.onComplete(Response.from(AiMessage.from(expected.toString())));

        List<String> buffered = real.bufferedEventNamesSince(cid, 0);
        long textDeltas = buffered.stream().filter("text_delta"::equals).count();
        assertTrue(textDeltas < 120,
                "600 个 token 不该在重放缓冲里留下 600 条 text_delta，实际 " + textDeltas);
        assertTrue(textDeltas > 0, "也不能一条都不留——那说明正文根本没发出去");
    }

    @Test
    @DisplayName("攒够字符就先发，不必等满窗口")
    void aBigBurstIsFlushedOnSizeRatherThanWaitingForTheWindow() {
        SseEmitterService sse = mock(SseEmitterService.class);
        AgentStreamHandler h = handler(sse);

        h.onNext("首");                       // 立即发
        h.onNext("甲".repeat(300));            // 超过 200 字符阈值，应当就地发出

        assertEquals("首" + "甲".repeat(300), joined(sse));
        assertEquals(2, textDeltas(sse).size(), "大块不该被硬压成 16ms 一拍");
    }
}
