// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.mobile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 桌面端与云端常连的「门铃」流（dev-board#719，spec 第 5 节）。
 *
 * <p>桌面端持有 {@code GET /api/mobile/desktop/stream}：连上即收 {@code ready}，每 15 秒
 * {@code ping}，有待办时收 {@code nudge}（data 只有 {@code {"kind":"ref"}} 或
 * {@code {"kind":"transfer"}}，不含任何内容），收到后立刻去取件。门铃按不响无害——桌面端的
 * 60 秒轮询照旧兜底。
 *
 * <p>同一 (userId, deviceId) 只保留一条连接：后连顶掉先连，先连收到 {@code superseded} 后关闭
 * （同机多个后端实例共用 relay 身份时不会无限互顶，见 mobile-sync 地雷 8）。
 * 在线判定 = 流在连；参考读取用它，跨设备传输的 180 秒心跳窗口不动。单 JVM 进程内存。
 */
@Service
@Slf4j
public class DesktopStreamService {

    /**
     * 「最后在线」保留窗口：断线超过它就不再留着。deviceId 是客户端自带的，
     * 一个已登录用户反复换 deviceId 连流就能把这张表撑大，只写不清不行。
     */
    static final long LAST_SEEN_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    /** lastSeen 的硬上限：超过就从最老的开始淘汰（当前连着的键不淘汰）。 */
    static final int MAX_LAST_SEEN = 1000;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    /** 最后一次成功送达（建连 / ping / nudge）的时刻；断线后保留，用于「最后在线」。 */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public DesktopStreamService() {
        this(System::currentTimeMillis);
    }

    DesktopStreamService(LongSupplier clock) {
        this.clock = clock;
    }

    /** 建一条门铃流（不超时，靠 ping 探活）。先连的同键连接收到 superseded 后关闭。 */
    public SseEmitter connect(Long userId, String deviceId) {
        String key = key(userId, deviceId);
        // 定时清扫只在 ping 那一轮跑；建连这里再收一次口，免得高频换 deviceId 在两轮之间撑爆
        if (lastSeen.size() >= MAX_LAST_SEEN) pruneLastSeen();
        SseEmitter emitter = new SseEmitter(0L);
        // 两参 remove：只在映射仍指向本连接时摘掉——被顶掉的旧连接收尾时不能把新连接一起摘了
        emitter.onCompletion(() -> emitters.remove(key, emitter));
        emitter.onTimeout(() -> emitters.remove(key, emitter));
        emitter.onError(e -> emitters.remove(key, emitter));

        SseEmitter previous = emitters.put(key, emitter);
        if (previous != null) {
            log.info("桌面端门铃流被新连接顶替：userId={}, deviceId={}", userId, deviceId);
            try {
                previous.send(SseEmitter.event().name("superseded").data("{\"reason\":\"another_connection\"}"));
            } catch (Exception ignored) {
                // 旧连接可能早已断开，照样收尾
            }
            try {
                previous.complete();
            } catch (Exception ignored) {
                // 已失效
            }
        } else {
            log.info("桌面端门铃流已连接：userId={}, deviceId={}", userId, deviceId);
        }
        send(key, emitter, "ready", "{}");
        return emitter;
    }

    /** 按门铃。返回是否送达（不在线或写失败 → false，调用方靠桌面端轮询兜底）。 */
    public boolean nudge(Long userId, String deviceId, String kind) {
        if (kind == null || !kind.matches("[a-z]+")) {
            throw new IllegalArgumentException("unknown nudge kind");
        }
        String key = key(userId, deviceId);
        SseEmitter emitter = emitters.get(key);
        if (emitter == null) return false;
        return send(key, emitter, "nudge", "{\"kind\":\"" + kind + "\"}");
    }

    public boolean isOnline(Long userId, String deviceId) {
        return emitters.containsKey(key(userId, deviceId));
    }

    /** 最后一次确认连着的时刻（毫秒）；从没连过为空。 */
    public OptionalLong lastSeenMs(Long userId, String deviceId) {
        Long ms = lastSeen.get(key(userId, deviceId));
        return ms == null ? OptionalLong.empty() : OptionalLong.of(ms);
    }

    /**
     * 每 15 秒探活一次：既穿透代理的空闲回收，也把对端已断的连接及时摘掉（写失败即摘）。
     * 单个连接失败不许连累其余连接。
     */
    @Scheduled(fixedDelay = 15_000)
    public void ping() {
        for (Map.Entry<String, SseEmitter> e : emitters.entrySet()) {
            try {
                send(e.getKey(), e.getValue(), "ping", "{\"ts\":" + clock.getAsLong() + "}");
            } catch (Throwable t) {
                log.debug("门铃流 ping 失败：{}", t.getClass().getSimpleName());
            }
        }
        pruneLastSeen();
    }

    /**
     * 收「最后在线」表：先按 TTL 清掉早就不连的，还超上限就从最老的继续淘汰。
     * 当前连着的键一律不动——它们的在线判定与「最后在线」都还要用。
     */
    private void pruneLastSeen() {
        long now = clock.getAsLong();
        lastSeen.entrySet().removeIf(e ->
                now - e.getValue() > LAST_SEEN_TTL_MS && !emitters.containsKey(e.getKey()));
        int excess = lastSeen.size() - MAX_LAST_SEEN;
        if (excess <= 0) return;
        List<String> oldest = lastSeen.entrySet().stream()
                .filter(e -> !emitters.containsKey(e.getKey()))
                .sorted(Map.Entry.comparingByValue())
                .limit(excess)
                .map(Map.Entry::getKey)
                .toList();
        oldest.forEach(lastSeen::remove);
        if (!oldest.isEmpty()) {
            log.debug("门铃「最后在线」表超上限，淘汰 {} 条", oldest.size());
        }
    }

    /** 写一条事件；失败即摘掉该连接（只摘仍是它自己的那一条）并收尾，不抛给调用方。 */
    private boolean send(String key, SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            lastSeen.put(key, clock.getAsLong());
            return true;
        } catch (Exception ex) {
            // IOException：对端断开；IllegalStateException：连接已 complete
            if (emitters.remove(key, emitter)) {
                log.info("桌面端门铃流已断开（{}）：{}", ex.getClass().getSimpleName(), key);
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 已失效
            }
            return false;
        }
    }

    private static String key(Long userId, String deviceId) {
        return userId + "|" + deviceId;
    }
}
