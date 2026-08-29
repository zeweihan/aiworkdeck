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
 *
 * <p>两个窗口（dev-board#109，单元 L 实跑反馈）：工具调用线程 60 次/分钟；
 * <b>后台任务线程</b>（JobContext 绑定期间）1200 次/分钟——批量入库每个文件至少两次宿主调用，
 * 60 是硬瓶颈。两个窗口按 (pluginId, 模式) 分开计数。
 */
public class PluginHostQuota {

    public static final int DEFAULT_LIMIT_PER_MINUTE = 60;
    public static final int DEFAULT_JOB_LIMIT_PER_MINUTE = 1200;
    /** Web 插件桥 ai.request 的独立窗口（规范 v2.7 P2）：烧的是用户 Credits，限得比宿主调用紧得多 */
    public static final int DEFAULT_AI_LIMIT_PER_MINUTE = 10;
    private static final long WINDOW_MS = 60_000L;

    private final int toolLimit;
    private final int jobLimit;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public PluginHostQuota() {
        this(DEFAULT_LIMIT_PER_MINUTE, DEFAULT_JOB_LIMIT_PER_MINUTE);
    }

    public PluginHostQuota(int toolLimit, int jobLimit) {
        this.toolLimit = toolLimit;
        this.jobLimit = jobLimit;
    }

    /** 工具调用线程的窗口。 */
    public void acquire(String pluginId) {
        acquire(pluginId, false);
    }

    /**
     * 桥 ai.request 的窗口（规范 v2.7 P2，独立 key {@code <pluginId>#ai}，10 次/分钟）。
     * 超限抛 {@link HostQuotaException}，与既有两窗口同语义（超限不记入窗口）。
     */
    public void acquireAi(String pluginId) {
        Deque<Long> q = windows.computeIfAbsent(pluginId + "#ai", k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= DEFAULT_AI_LIMIT_PER_MINUTE) {
                throw new HostQuotaException(LangText.of(
                        "插件 " + pluginId + " 的 AI 请求超过每分钟 " + DEFAULT_AI_LIMIT_PER_MINUTE + " 次上限",
                        "Plugin " + pluginId + " exceeded the AI request quota of "
                                + DEFAULT_AI_LIMIT_PER_MINUTE + " per minute"));
            }
            q.addLast(now);
        }
    }

    /**
     * 记一次调用；超限抛 {@link HostQuotaException}（不记入窗口，超限期间重试不会把窗口越推越满）。
     *
     * @param inJob 是否在后台任务线程（JobContext 绑定期间）——用 1200 次/分钟的大窗口
     */
    public void acquire(String pluginId, boolean inJob) {
        int limit = inJob ? jobLimit : toolLimit;
        Deque<Long> q = windows.computeIfAbsent(pluginId + (inJob ? "#job" : "#tool"), k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                throw new HostQuotaException(LangText.of(
                        "插件 " + pluginId + " 宿主调用超过每分钟 " + limit + " 次上限" + (inJob ? "（后台任务）" : ""),
                        "Plugin " + pluginId + " exceeded the host call quota of " + limit + " per minute"
                                + (inJob ? " (background job)" : "")));
            }
            q.addLast(now);
        }
    }
}
