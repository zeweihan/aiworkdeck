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

    // Key: connectionId -> 当前连接代次，每次 createConnection 建连自增。
    // close(connectionId, epoch) 靠它判断"要关的是不是这一次连接"，见 close() 的注释。
    private final Map<String, Long> epochs = new ConcurrentHashMap<>();

    // Key: connectionId -> 当前持有连接的客户端实例 id（任务窗格每次载入生成一个）。
    // 用来区分"同一个窗格断线重连"与"另一个窗格来抢同一个会话"，见 createConnection。
    private final Map<String, String> clientByConnection = new ConcurrentHashMap<>();

    /** 代次表的清理阈值：远大于任何真实并发会话数，触到才动手，正常运行期间等于不清理。 */
    private static final int EPOCH_PURGE_THRESHOLD = 10_000;

    // 心跳广播：15s 一次，双重作用——(a) 长工具执行期间通道零字节流动，企业网关/
    // 代理的空闲回收窗口（常见 60~120s）会静默掐连接，心跳保活穿透；
    // (b) 前端据 lastHeartbeat 判定连接死活，超时触发自动重连（F-04/F-05）。
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;
    private final java.util.concurrent.ScheduledExecutorService heartbeatScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    @jakarta.annotation.PostConstruct
    void startHeartbeat() {
        heartbeatScheduler.scheduleWithFixedDelay(() -> {
            // 整个任务体必须吞掉 Throwable：scheduleWithFixedDelay 的语义是"任务抛出即
            // 永久取消后续执行"。心跳一旦停摆，全体在线客户端 40 秒后同时判死连接、
            // 齐刷刷重连，而且直到进程重启都好不了——单点故障放大成全局故障。
            // send() 内部只捕获 Exception，Error（OOM/StackOverflow）会漏出来。
            try {
                for (String id : emitters.keySet()) {
                    try {
                        send(id, "heartbeat", "{\"ts\":" + System.currentTimeMillis() + "}");
                    } catch (Throwable t) {
                        // 单个会话的心跳失败不许连累其余会话
                        log.debug("Heartbeat failed for {}", id);
                    }
                }
            } catch (Throwable t) {
                log.warn("Heartbeat sweep failed, continuing", t);
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    @jakarta.annotation.PreDestroy
    void stopHeartbeat() {
        heartbeatScheduler.shutdownNow();
    }

    /**
     * 创建连接
     * @param connectionId 会话ID或用户ID
     * @return SseEmitter 实例
     */
    public SseEmitter createConnection(String connectionId) {
        return createConnection(connectionId, null);
    }

    /**
     * 创建连接（带客户端实例身份）。
     *
     * <p><b>为什么需要 clientId</b>（dev-board#285，2026-08-29 生产实测）：emitter 表只按
     * conversationId 索引，同 ID 后来者无声顶掉先来者。当两个任务窗格（WPS 文字 + WPS 演示）
     * 因为共用 localStorage 而拿到同一个 conversationId 时，就形成了互顶循环——
     * A 被顶掉 → 1 秒后重连 → 顶掉 B → B 重连 → ……实测稳定 1 Hz 持续 9 分钟，
     * 期间这一轮的 text_delta 有一半落进了另一个窗格、另一半落进了没有 emitter 的空档
     * （send() 对无 emitter 的会话是静默丢弃），用户拿到一个标着"已完成"的空白气泡。
     *
     * <p>客户端侧已按宿主拆开会话存储键，正常不会再撞；这里是兜底：认出"换了一个窗格"时
     * 先给旧连接发一个 {@code superseded} 事件再关掉它，把无限互顶变成一次性移交——
     * 旧窗格收到后停止重连并明确告诉用户"这场对话已在另一个窗格继续"。
     * 同一个 clientId 的重连（含 clientId 缺失的旧版插件）行为与改造前完全一致。
     */
    public SseEmitter createConnection(String connectionId, String clientId) {
        // 代次自增：本次建连之后，任何持有旧代次的 close() 调用都不再匹配当前连接
        epochs.merge(connectionId, 1L, Long::sum);
        purgeEpochsIfOversized();

        // 设置超时时间，0表示不过期 (由客户端重连或业务逻辑控制断开)
        // Spring Boot 默认可能是 30s，这里设为 30分钟
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        // 两参 remove：只在映射仍指向本 emitter 时移除。同 ID 重连时旧 emitter 的
        // complete 回调不能把刚放进去的新 emitter 一并摘掉
        emitter.onCompletion(() -> {
            emitters.remove(connectionId, emitter);
            log.debug("SSE connection completed: {}", connectionId);
        });

        emitter.onTimeout(() -> {
            emitters.remove(connectionId, emitter);
            log.debug("SSE connection timed out: {}", connectionId);
        });

        emitter.onError((e) -> {
            emitters.remove(connectionId, emitter);
            log.warn("SSE connection error: {}", connectionId, e);
        });

        // 换了客户端实例（不是同一个窗格重连）时，先向旧连接交代一句再关，
        // 否则旧窗格只看到"流断了"，会立刻重连回来，两边无限互顶。
        String previousClient = clientId == null || clientId.isBlank()
                ? clientByConnection.get(connectionId)
                : clientByConnection.put(connectionId, clientId);
        boolean takenOverFromOther = clientId != null && !clientId.isBlank()
                && previousClient != null && !previousClient.equals(clientId);
        if (takenOverFromOther) {
            log.info("SSE connection taken over for {}: client {} -> {}", connectionId, previousClient, clientId);
            send(connectionId, "superseded", "{\"reason\":\"another_pane\"}");
        }

        // 同 ID 重复建连（断线重连）：先 complete 旧 emitter 释放其 Tomcat 异步上下文，
        // 否则旧连接悬空挂到 30 分钟超时才释放（F-12）
        SseEmitter previous = emitters.put(connectionId, emitter);
        if (previous != null) {
            try { previous.complete(); } catch (Exception e) { /* 已失效，忽略 */ }
        }
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
                emitters.remove(connectionId, emitter);
            }
        } else {
            log.trace("Skipping SSE send. No emitter for {}", connectionId);
        }
    }
    
    /**
     * 调用方（一轮 Agent 运行）应在本轮开始时取一次当前代次存起来，收尾调用 {@link #close}
     * 时原样带回——用来分辨"现在映射的这个 emitter 还是不是本轮建连的那个"。
     */
    /**
     * 代次表不能在 close() 时随手删掉——删了之后新连接的代次会从 1 重新开始，
     * 一条迟到的、手里攥着旧代次 1 的 close() 就会误判成"匹配"，把新连接掐掉，
     * 正是这次要修的那个病。所以只能按规模兜底清理：超过阈值时，
     * 把当前没有活连接的那些键清掉。
     *
     * <p>清掉之后若真有迟到的 close 找上门，它看到的当前代次是 0、与自己手里的对不上，
     * 于是**跳过关闭**——落在安全的一侧（最坏是某个 emitter 没被显式收尾，
     * 由 30 分钟超时或下一次重连顶掉），不会误关活连接。
     */
    private void purgeEpochsIfOversized() {
        if (epochs.size() <= EPOCH_PURGE_THRESHOLD) return;
        epochs.keySet().removeIf(id -> !emitters.containsKey(id));
        // 客户端归属表与代次表同生命周期，一起按规模兜底清理
        clientByConnection.keySet().removeIf(id -> !emitters.containsKey(id));
    }

    public long currentEpoch(String connectionId) {
        return epochs.getOrDefault(connectionId, 0L);
    }

    /**
     * 关闭连接。
     *
     * <p>close() 和 createConnection() 各自跑在不同线程上：后台的 Agent 轮次结束时调用本方法，
     * 与此同时用户可能因为刷新页面/开新标签/断线重连而并发调用 createConnection() 建立了一个
     * 全新的 emitter。旧单参版本 {@code emitters.remove(connectionId)} 不认这个区别，谁后写入
     * map 就删谁，会把刚重连、什么错都没有的新连接一并掐掉。
     *
     * <p>epoch 只在与当前代次一致时才真正 complete；重连已经把代次往前推了的话，这次 close
     * 就是"迟到的旧一轮善后"，原地跳过——新连接自己的生命周期不受影响。
     *
     * @param connectionId 会话ID或用户ID
     * @param epoch 调用方在本轮开始时通过 {@link #currentEpoch} 记下的代次
     */
    public void close(String connectionId, long epoch) {
        if (epochs.getOrDefault(connectionId, 0L) != epoch) {
            log.debug("Skip close for {}: stale epoch {} (current {}), a newer connection already took over",
                    connectionId, epoch, epochs.get(connectionId));
            return;
        }
        SseEmitter emitter = emitters.remove(connectionId);
        if (emitter != null) {
            emitter.complete();
        }
    }
}
