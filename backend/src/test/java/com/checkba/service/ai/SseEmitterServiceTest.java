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
    void noLastEventIdReplaysNothing() {
        SseEmitterService svc = new SseEmitterService();
        String id = "conv-replay-4";
        svc.createConnection(id, "paneA");
        svc.send(id, "text_delta", "a");
        svc.close(id, svc.currentEpoch(id));
        // 旧版插件与桌面端不带这个头：行为必须与改造前逐字一致（什么都不补）
        svc.createConnection(id, "paneA", null);
        assertEquals(0, svc.lastReplayCount(id));
    }
}
