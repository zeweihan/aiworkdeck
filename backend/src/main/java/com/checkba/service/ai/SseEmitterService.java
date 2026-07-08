package com.checkba.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 SSE 连接服务。
 * 负责维护客户端的长连接，并提供向指定会话推送事件的能力。
 */
@Service
public class SseEmitterService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SseEmitterService.class);

    // Key: conversationId (or sessionId) -> SseEmitter
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 创建连接
     * @param connectionId 会话ID或用户ID
     * @return SseEmitter 实例
     */
    public SseEmitter createConnection(String connectionId) {
        // 设置超时时间，0表示不过期 (由客户端重连或业务逻辑控制断开)
        // Spring Boot 默认可能是 30s，这里设为 30分钟
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        emitter.onCompletion(() -> {
            emitters.remove(connectionId);
            log.debug("SSE connection completed: {}", connectionId);
        });

        emitter.onTimeout(() -> {
            emitters.remove(connectionId);
            log.debug("SSE connection timed out: {}", connectionId);
        });

        emitter.onError((e) -> {
            emitters.remove(connectionId);
            log.warn("SSE connection error: {}", connectionId, e);
        });

        emitters.put(connectionId, emitter);
        log.info("SSE connection established: {}", connectionId);
        
        // Send immediate initial event to confirm connection
        send(connectionId, "connected", "Connection established");
        
        return emitter;
    }

    /**
     * 推送事件
     * @param connectionId 目标ID
     * @param eventName 事件名 (e.g. bubble_start, text_delta)
     * @param data 数据对象 (会被转为 JSON)
     */
    public void send(String connectionId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(connectionId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                // IOException：客户端断开；IllegalStateException：emitter 已 complete（与 close() 竞态）。
                // 两种情况该 emitter 都已失效，一并丢弃，避免异常逃逸打断推事件的调用线程。
                log.warn("Failed to send SSE event to {} ({}), removing emitter.", connectionId, e.getClass().getSimpleName());
                emitters.remove(connectionId);
            }
        } else {
            log.trace("Skipping SSE send. No emitter for {}", connectionId);
        }
    }
    
    /**
     * 关闭连接
     */
    public void close(String connectionId) {
        SseEmitter emitter = emitters.remove(connectionId);
        if (emitter != null) {
            emitter.complete();
        }
    }
}
