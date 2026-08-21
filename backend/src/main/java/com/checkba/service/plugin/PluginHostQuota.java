package com.checkba.service.plugin;

import com.checkba.plugin.api.HostQuotaException;
import com.checkba.service.LangText;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每插件每分钟宿主调用次数上限（滑动窗口）。防 runaway 插件把宿主打满，不是计费单位——
 * LLM/OCR 的钱按用户 Credits 在平台网关算。
 */
public class PluginHostQuota {

    public static final int DEFAULT_LIMIT_PER_MINUTE = 60;
    private static final long WINDOW_MS = 60_000L;

    private final int limit;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public PluginHostQuota() {
        this(DEFAULT_LIMIT_PER_MINUTE);
    }

    public PluginHostQuota(int limit) {
        this.limit = limit;
    }

    /** 记一次调用；超限抛 {@link HostQuotaException}（不记入窗口，超限期间重试不会把窗口越推越满）。 */
    public void acquire(String pluginId) {
        Deque<Long> q = windows.computeIfAbsent(pluginId, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                throw new HostQuotaException(LangText.of(
                        "插件 " + pluginId + " 宿主调用超过每分钟 " + limit + " 次上限",
                        "Plugin " + pluginId + " exceeded the host call quota of " + limit + " per minute"));
            }
            q.addLast(now);
        }
    }
}
