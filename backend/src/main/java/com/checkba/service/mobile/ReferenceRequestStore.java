// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.mobile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 桌面端参考读取请求的内存登记簿（dev-board#718，spec 第 6 节）。
 *
 * <p>云端 AI 要读桌面端项目里的文件时：{@link #submit} 登记一条 LIST / READ / OPEN 请求并拿到
 * future → 门铃叫桌面端 → 桌面端 {@code GET /api/mobile/ref/requests} 经 {@link #take} 取件 →
 * 处理完 {@code POST /api/mobile/ref/{id}/result} 经 {@link #complete} 交回结果、完成 future。
 *
 * <p>红线：不落库、不落盘、不计费——结果只进 future，完成即从登记簿里摘掉；日志只记 id 与种类，
 * 不记文件路径，更不记正文。单 JVM 进程内存（spec 第 2 节：单区域单实例）。
 * TTL 60 秒：过期请求既不再下发、也不再接受迟到的结果，由 {@link #sweep} 以失败结果收尾，
 * 等待方拿到一句可以直接转述给用户的原因。
 */
@Service
@Slf4j
public class ReferenceRequestStore {

    public static final long TTL_MS = 60_000;

    /** 等待方（DesktopSource）超时与清扫收尾用同一句话，模型看到的原因一致。 */
    public static final String TIMEOUT_MESSAGE = "桌面端 60 秒内未响应，可稍后重试。";

    public enum Outcome { OK, STALE, FORBIDDEN }

    public record Pending(String id, CompletableFuture<Map<String, Object>> future) {
    }

    private static final class Request {
        final String id;
        final Long userId;
        final String deviceId;
        final String kind;
        final String projectKey;
        final String path;
        final String keyword;
        final long createdAt;
        final long seq;
        final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        final AtomicBoolean dispatched = new AtomicBoolean();

        Request(String id, Long userId, String deviceId, String kind, String projectKey, String path,
                String keyword, long createdAt, long seq) {
            this.id = id;
            this.userId = userId;
            this.deviceId = deviceId;
            this.kind = kind;
            this.projectKey = projectKey;
            this.path = path;
            this.keyword = keyword;
            this.createdAt = createdAt;
            this.seq = seq;
        }

        Map<String, Object> toWire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("kind", kind);
            m.put("projectKey", projectKey);
            if (path != null) m.put("path", path);
            if (keyword != null) m.put("keyword", keyword);
            return m;
        }
    }

    private final LongSupplier clock;
    private final Map<String, Request> requests = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public ReferenceRequestStore() {
        this(System::currentTimeMillis);
    }

    ReferenceRequestStore(LongSupplier clock) {
        this.clock = clock;
    }

    /** 登记一条请求。path 只有 READ / OPEN 带，keyword 只有 LIST 带（都可为 null）。 */
    public Pending submit(Long userId, String deviceId, String kind, String projectKey, String path, String keyword) {
        Request r = new Request(UUID.randomUUID().toString(), userId, deviceId, kind, projectKey, path, keyword,
                clock.getAsLong(), seq.incrementAndGet());
        requests.put(r.id, r);
        log.debug("参考请求登记：id={}, kind={}", r.id, kind);
        return new Pending(r.id, r.future);
    }

    /** 取出该 (userId, deviceId) 尚未下发的请求并标记为已下发：同一条只下发一次。按登记顺序。 */
    public List<Map<String, Object>> take(Long userId, String deviceId) {
        if (userId == null || deviceId == null) return List.of();
        List<Request> mine = new ArrayList<>();
        for (Request r : requests.values()) {
            if (userId.equals(r.userId) && deviceId.equals(r.deviceId) && !expired(r) && !r.dispatched.get()) {
                mine.add(r);
            }
        }
        mine.sort(Comparator.comparingLong(r -> r.seq));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Request r : mine) {
            if (r.dispatched.compareAndSet(false, true)) {
                out.add(r.toWire());
            }
        }
        return out;
    }

    /**
     * 交回结果。请求不存在（已完成 / 已过期被清扫 / 从来没有）→ STALE；不属于该用户 → FORBIDDEN，
     * 且请求原样留着等真正的属主；过期但还没被清扫 → 以超时收尾并回 STALE。
     */
    public Outcome complete(Long userId, String id, Map<String, Object> result) {
        if (id == null) return Outcome.STALE;
        Request r = requests.get(id);
        if (r == null) return Outcome.STALE;
        if (userId == null || !userId.equals(r.userId)) return Outcome.FORBIDDEN;
        if (!requests.remove(id, r)) return Outcome.STALE;
        if (expired(r)) {
            r.future.complete(timeout());
            return Outcome.STALE;
        }
        r.future.complete(result != null ? result : Map.of("ok", false, "error", "桌面端没有返回结果，可稍后重试。"));
        return Outcome.OK;
    }

    /** 过期清扫：摘掉超过 TTL 的请求，以失败结果完成它们的 future（等待方不会一直挂着）。 */
    @Scheduled(fixedDelay = 10_000)
    public void sweep() {
        int n = 0;
        for (Request r : requests.values()) {
            if (expired(r) && requests.remove(r.id, r)) {
                r.future.complete(timeout());
                n++;
            }
        }
        if (n > 0) {
            log.info("参考请求过期清扫 {} 条", n);
        }
    }

    private boolean expired(Request r) {
        return clock.getAsLong() - r.createdAt > TTL_MS;
    }

    private static Map<String, Object> timeout() {
        return Map.of("ok", false, "error", TIMEOUT_MESSAGE);
    }
}
