package com.checkba.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
