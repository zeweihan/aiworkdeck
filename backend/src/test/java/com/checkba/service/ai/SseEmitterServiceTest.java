// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * close(connectionId, epoch) 的代次校验：
 * 旧的单参 close(connectionId) 谁后写入 map 就删谁，会把"本轮收尾"和"用户并发重连"两件事
 * 搅在一起——迟到的旧一轮 close() 会把刚刚重连、什么错都没有的新 emitter 一并杀掉。
 *
 * <p>Spring 的 ResponseBodyEmitter/SseEmitter 在没有真实 Servlet 请求（handler 未 initialize）
 * 时仍然维护 complete 标志：complete() 之后再 send() 必抛 IllegalStateException（Assert.state
 * 先于 handler null 检查），因此这里不需要起真实 HTTP 请求也能观察"这个 emitter 是否被误杀"。
 */
class SseEmitterServiceTest {

    @Test
    void staleEpochCloseDoesNotKillReconnectedEmitter() throws Exception {
        SseEmitterService svc = new SseEmitterService();
        String id = "conv-epoch-1";

        // 首次建连；调用方（Agent 轮次）在"本轮开始时"记下这一刻的代次
        svc.createConnection(id);
        long staleEpoch = svc.currentEpoch(id);

        // 期间用户刷新页面/开新标签重连：产生一个全新 emitter，代次自增
        SseEmitter reconnected = svc.createConnection(id);

        // 旧一轮此刻才收尾，带着已经过期的代次调用 close —— 不该动到重连后的新连接
        svc.close(id, staleEpoch);

        // 新连接必须还活着：能正常 send 而不抛 IllegalStateException("已 complete")
        assertDoesNotThrow(() -> reconnected.send("still-alive"),
                "旧一轮的 close() 用过期 epoch 时不应该 complete 掉重连后的新 emitter");
    }

    @Test
    void matchingEpochCloseStillCompletesEmitter() throws Exception {
        SseEmitterService svc = new SseEmitterService();
        String id = "conv-epoch-2";

        SseEmitter emitter = svc.createConnection(id);

        // 没有发生重连：代次没变，close 应该照常生效（不能把 bug 修成"永远不关"）
        svc.close(id, svc.currentEpoch(id));

        assertThrows(IllegalStateException.class, () -> emitter.send("dead"),
                "epoch 与当前一致时 close() 必须照常 complete 掉这个 emitter");
    }

    /**
     * 断线空档里的事件必须进补发缓冲，而不是被静默丢弃（dev-board#287）。
     *
     * <p>还原病灶：2026-08-29 生产实证——两个任务窗格抢同一条 SSE 通道互相顶掉，
     * 期间 send() 对"当前没有 emitter"的会话只打一行 log 就把事件扔了。用户拿到的是
     * 一个标着"已完成 · 111 秒"的空白气泡；落在空档里的 client_action 还会让
     * OfficeBridgeService 实打实空等满 30 秒再报"操作超时"。
     */
    @Test
    void eventsSentWhileDisconnectedAreBufferedForReplay() {
        SseEmitterService svc = new SseEmitterService();
        String id = "conv-replay-1";

        // 建连 → 拿到 id=1 的 text_delta（connected 不进缓冲）
        svc.createConnection(id, "paneA");
        svc.send(id, "text_delta", "{\"content\":\"甲\"}");

        // 断线：emitter 没了，但这一轮还在跑，事件继续产生
        svc.close(id, svc.currentEpoch(id));
        svc.send(id, "text_delta", "{\"content\":\"方\"}");
        svc.send(id, "client_action", "{\"tool\":\"office_command\"}");
        svc.send(id, "bubble_end", "{\"status\":\"completed\"}");

        // 断线期间的三条都得留着——尤其 client_action，丢了就是 30 秒空等
        assertEquals(java.util.List.of("text_delta", "client_action", "bubble_end"),
                svc.bufferedEventNamesSince(id, 1),
                "断线空档里的事件必须进补发缓冲");
    }

    @Test
    void reconnectWithLastEventIdReplaysOnlyTheMissedEvents() {
        SseEmitterService svc = new SseEmitterService();
        String id = "conv-replay-2";

        svc.createConnection(id, "paneA");
        svc.send(id, "text_delta", "a");   // id=1，客户端收到了
        svc.close(id, svc.currentEpoch(id));
        svc.send(id, "text_delta", "b");   // id=2，断线期间
        svc.send(id, "bubble_end", "c");   // id=3，断线期间

        // 同一个窗格带着游标 1 重连：只补 2、3 两条，不重发已经渲染过的 1
        svc.createConnection(id, "paneA", "1");
        assertEquals(2, svc.lastReplayCount(id), "只应补发游标之后的事件");
    }

    @Test
    void heartbeatsAreNotBuffered() {
        SseEmitterService svc = new SseEmitterService();
        String id = "conv-replay-3";
        svc.createConnection(id, "paneA");
        for (int i = 0; i < 50; i++) svc.send(id, "heartbeat", "{\"ts\":1}");
        svc.send(id, "text_delta", "real");
        // 心跳补发没有意义，只会把真正需要补的内容从窗口里挤出去
        assertEquals(java.util.List.of("text_delta"), svc.bufferedEventNamesSince(id, 0));
    }

    @Test
    void heartbeatSweepReachesEveryLiveConnection() {
        // 心跳是前端区分「连接活着、模型还在想」与「连接断了」的唯一依据（dev-board#364）。
        // 调度器每 15s 调一次 heartbeatSweep；这里直接调，不等真实间隔。
        SseEmitterService svc = new SseEmitterService();
        svc.createConnection("conv-hb-1", "paneA");
        svc.createConnection("conv-hb-2", "paneA");
        assertEquals(2, svc.heartbeatSweep(), "每个在线连接都要收到一次 heartbeat");
        // 前端 useAgentStream 的 HEARTBEAT_STALE_MS=45000 按「3 个心跳周期」设：改间隔要同步那边
        assertEquals(15L, SseEmitterService.heartbeatIntervalSeconds());
    }

    @Test
    void heartbeatStopsOnceTheRunClosesItsConnection() {
        // v0.49.0 BUG-16：每轮收尾后端主动关流是设计内行为，关掉的连接必须同时退出心跳名单——
        // 否则 15s 后的心跳会往一个已 complete 的 emitter 上写，日志里就是一条
        // AsyncRequestNotUsableException / removing emitter，看着像异常断线。
        SseEmitterService svc = new SseEmitterService();
        svc.createConnection("conv-hb-done", "paneA");
        svc.createConnection("conv-hb-idle", "paneA");
        svc.close("conv-hb-done", svc.currentEpoch("conv-hb-done"));
        assertEquals(1, svc.heartbeatSweep(), "本轮已收尾关流的连接不该再收心跳，只剩另一条在线连接");
    }

    @Test
    void noLastEventIdReplaysNothing() {
        SseEmitterService svc = new SseEmitterService();
        String id = "conv-replay-4";
        svc.createConnection(id, "paneA");
        svc.send(id, "text_delta", "a");
        svc.close(id, svc.currentEpoch(id));
        // 旧版插件与桌面端不带这个头：**已经送达过的**一条都不许重发，
        // 否则用户会在刷新后看到重复正文（dev-board#812 C-04 之后判据从
        // 「没游标就什么都不补」收紧成「没游标就只补没送达过的」，这一条的结果不变）
        svc.createConnection(id, "paneA", null);
        assertEquals(0, svc.lastReplayCount(id));
    }

    // ==================== C-04：建连与 POST 并行之后的首批事件 ====================

    @Test
    void eventsEmittedBeforeAnyEmitterAttachesAreReplayedOnTheFirstConnect() {
        // 前端不再等建连完成就发 POST /chat，于是本轮最早的几个事件真有可能在
        // emitter 挂上之前就发出去了。**一条会话的第一轮没有任何 Last-Event-ID 可带**——
        // 旧判据（空游标一律返回 0）会把它们永远留在缓冲里，
        // 用户看到的是一个连 bubble_start 都没有、开头缺字的气泡。
        SseEmitterService svc = new SseEmitterService();
        String id = "conv-c04-race";

        // POST 先到：这几条发出去时还没有任何 emitter
        svc.send(id, "bubble_start", "{\"bubbleId\":\"b1\"}");
        svc.send(id, "text_delta", "{\"content\":\"不可\"}");
        svc.send(id, "text_delta", "{\"content\":\"抗力\"}");
        assertEquals(java.util.List.of("bubble_start", "text_delta", "text_delta"),
                svc.bufferedEventNamesSince(id, 0), "没有 emitter 时事件必须进缓冲");

        // 建连稍后才完成，且这条会话从来没有过游标
        svc.createConnection(id, "paneA", null);
        assertEquals(3, svc.lastReplayCount(id),
                "首批事件（含 bubble_start 与头几条 text_delta）一条都不能丢");
    }

    @Test
    void theSecondTurnGetsItsEarlyEventsBackEvenWithoutACursor() {
        // 更接近真实的形态：第一轮正常收完、后端收尾关流，第二轮又是「POST 先于 connect」。
        // 第一轮那些已经送达的绝不能重发，第二轮那些没送达的一条都不能少。
        SseEmitterService svc = new SseEmitterService();
        String id = "conv-c04-turn2";

        svc.createConnection(id, "paneA");
        svc.send(id, "text_delta", "{\"content\":\"第一轮\"}");     // 送达
        svc.send(id, "bubble_end", "{\"status\":\"finished\"}");    // 送达
        svc.close(id, svc.currentEpoch(id));                            // 后端每轮收尾关流

        svc.send(id, "bubble_start", "{\"bubbleId\":\"b2\"}");      // 第二轮，没有 emitter
        svc.send(id, "text_delta", "{\"content\":\"第二轮\"}");     // 第二轮，没有 emitter

        svc.createConnection(id, "paneA", null);
        assertEquals(2, svc.lastReplayCount(id),
                "只补第二轮那两条；第一轮已经渲染过的重发就是重复正文");
    }


    // ==================== 客户端实例身份与「移交」判定（dev-board#803 / #821 K40） ====================
    //
    // 桌面端从 v0.47 起也上送 X-Client-Instance（此前只有 Office 任务窗格上送）。
    // 这条判定错一边就是一种确定性故障：
    //   · 把「同一个窗口断线重连」误判成移交 → 给自己发一条 superseded，前端随即停止重连，
    //     用户看到的是一条再也接不上的会话；
    //   · 把「另一个窗口来抢」漏判成普通重连 → 旧窗口只看到流断了，1 秒后重连回来把新窗口顶掉，
    //     两边 1 Hz 无限互顶（dev-board#285 的生产实证）。
    // superseded 不进补发缓冲，所以这里用一个记录 send 调用的子类来观察决定本身。

    private static final class RecordingSseEmitterService extends SseEmitterService {
        final java.util.List<String> events = new java.util.ArrayList<>();

        @Override
        public void send(String connectionId, String eventName, Object data) {
            events.add(eventName + "=" + data);
            super.send(connectionId, eventName, data);
        }

        java.util.List<String> supersededPayloads() {
            return events.stream().filter(e -> e.startsWith("superseded=")).toList();
        }
    }

    @Test
    void reconnectFromTheSameClientInstanceIsNotATakeover() {
        RecordingSseEmitterService svc = new RecordingSseEmitterService();
        String id = "conv-client-same";

        svc.createConnection(id, "pane-A");
        // 后端每轮收尾都会关流，桌面端随即退避重连——同一个窗口、同一个实例 id
        svc.close(id, svc.currentEpoch(id));
        svc.createConnection(id, "pane-A");
        svc.createConnection(id, "pane-A");

        assertEquals(java.util.List.of(), svc.supersededPayloads(),
                "同一个客户端实例的重连是常态（每轮收尾都会关流），绝不能当成被另一个窗口接管");
    }

    @Test
    void aDifferentClientInstanceTakesOverAndTheOldConnectionIsToldWhy() {
        RecordingSseEmitterService svc = new RecordingSseEmitterService();
        String id = "conv-client-swap";

        svc.createConnection(id, "pane-A");
        svc.createConnection(id, "pane-B");

        assertEquals(java.util.List.of("superseded={\"reason\":\"another_pane\"}"),
                svc.supersededPayloads(),
                "换了客户端实例必须先给旧连接交代一句再关，否则旧窗口会立刻重连回来无限互顶");
    }

    @Test
    void clientsThatSendNoInstanceHeaderAreNeverSupersededEitherWay() {
        RecordingSseEmitterService svc = new RecordingSseEmitterService();
        String id = "conv-client-none";

        // 不上送 X-Client-Instance 的客户端（旧版插件、v0.47 之前的桌面端）
        svc.createConnection(id);
        svc.createConnection(id);
        // 缺省值不许污染登记簿：后面真有另一个窗格带着 id 连上来时，
        // 它是这条会话上第一个报出身份的，同样不算「换了实例」
        svc.createConnection(id, "pane-A");

        assertEquals(java.util.List.of(), svc.supersededPayloads(),
                "身份缺省时行为必须与改造前完全一致：不判移交");
    }

}
