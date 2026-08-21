package com.checkba.service.plugin;

import com.checkba.model.entity.PluginJob;
import com.checkba.plugin.api.JobBody;
import com.checkba.plugin.api.JobContext;
import com.checkba.plugin.api.JobHandle;
import com.checkba.plugin.api.JobStatus;
import com.checkba.plugin.api.ToolCall;
import com.checkba.repository.PluginJobRepository;
import com.checkba.service.LangText;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.PlatformAiUserScope;
import com.checkba.service.evidence.Ulid;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 插件后台任务（插件规范 v2.4 §11 Jobs）。
 *
 * <p>每个插件一份 2 线程池（第 3 个任务排队），任务体在 {@link PlatformAiUserScope} 里跑，
 * 使其中的 LLM/OCR 调用落在发起用户的 Credits 上。进度写库按 {@code progressThrottleMs} 节流，
 * 内存态（{@link #status}）永远是实时值；终态必落库。有 conversationId 时每次落库同时经
 * SSE {@code client_action: plugin_job_progress} 推给对话（前端 BackgroundTaskIndicator 消费）。
 *
 * <p>宿主重启后库里还停在 queued/running 的任务没有人会再推进，启动时统一标 failed（宿主重启）。
 */
@Service
@Slf4j
public class PluginJobService {

    public static final String CLIENT_ACTION = "plugin_job_progress";
    static final int PER_PLUGIN_CONCURRENCY = 2;

    private final PluginJobRepository repo;
    /** 可为 null（测试直接 new 时）；生产由 Spring 注入。 */
    private final EditorBridgeService editorBridge;
    private final long progressThrottleMs;

    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    @Autowired
    public PluginJobService(PluginJobRepository repo, EditorBridgeService editorBridge) {
        this(repo, editorBridge, 500);
    }

    PluginJobService(PluginJobRepository repo, EditorBridgeService editorBridge, long progressThrottleMs) {
        this.repo = repo;
        this.editorBridge = editorBridge;
        this.progressThrottleMs = progressThrottleMs;
    }

    /** 一个在本进程生命周期内的任务：实体 + 取消标记 + 提交句柄。 */
    private static final class JobState {
        final PluginJob job;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean finished = new AtomicBoolean();
        volatile Future<?> future;
        /** 上一次 progress 落库的时刻（0 = 还没落过，首次 progress 必写）；状态切换的落库不计入节流时钟。 */
        volatile long lastProgressPersistMs;
        volatile String resultJson;

        JobState(PluginJob job) { this.job = job; }
    }

    @PostConstruct
    void recoverOrphans() {
        try {
            List<PluginJob> orphans = repo.findByStatusIn(List.of(PluginJob.STATUS_QUEUED, PluginJob.STATUS_RUNNING));
            for (PluginJob j : orphans) {
                j.setStatus(PluginJob.STATUS_FAILED);
                j.setError(LangText.of("宿主重启，任务中断", "Host restarted; job interrupted"));
                j.setUpdatedAt(LocalDateTime.now());
                repo.save(j);
            }
            if (!orphans.isEmpty()) {
                log.warn("PluginJobService: marked {} orphan job(s) as failed after host restart", orphans.size());
            }
        } catch (Exception e) {
            log.warn("PluginJobService: orphan recovery skipped: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        for (ExecutorService ex : executors.values()) {
            ex.shutdownNow();
        }
    }

    public JobHandle start(String pluginId, String kind, String title, ToolCall call, JobBody body) {
        if (pluginId == null || pluginId.isBlank()) throw new IllegalArgumentException("pluginId required");
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("kind required");
        if (body == null) throw new IllegalArgumentException("body required");

        LocalDateTime now = LocalDateTime.now();
        PluginJob job = new PluginJob();
        job.setId(Ulid.next());
        job.setPluginId(pluginId);
        job.setKind(kind);
        job.setTitle(title);
        job.setStatus(PluginJob.STATUS_QUEUED);
        if (call != null) {
            job.setProjectId(call.projectId());
            job.setUserId(call.userId());
            job.setConversationId(call.conversationId());
        }
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        JobState state = new JobState(job);
        jobs.put(job.getId(), state);
        persist(state);

        ToolCall snapshot = call != null ? call : new ToolCall(null, null, null, null);
        state.future = executorFor(pluginId).submit(() -> run(state, snapshot, body));
        return new JobHandle(job.getId());
    }

    public JobStatus status(String jobId) {
        if (jobId == null) return null;
        JobState s = jobs.get(jobId);
        if (s != null) return toStatus(s.job);
        return repo.findById(jobId).map(PluginJobService::toStatus).orElse(null);
    }

    /** 实体视图（REST 用）：内存优先，其次数据库。 */
    public PluginJob get(String jobId) {
        if (jobId == null) return null;
        JobState s = jobs.get(jobId);
        if (s != null) return s.job;
        return repo.findById(jobId).orElse(null);
    }

    public List<PluginJob> listByProject(Long projectId) {
        List<PluginJob> fromDb = repo.findByProjectIdOrderByCreatedAtDesc(projectId);
        // 库里的进度是节流后的旧值，能用内存态的就换成内存态
        return fromDb.stream().map(j -> {
            JobState s = jobs.get(j.getId());
            return s != null ? s.job : j;
        }).toList();
    }

    public void cancel(String jobId) {
        JobState s = jobs.get(jobId);
        if (s == null) {
            // 不在本进程内存里：要么已结束，要么是孤儿；孤儿由启动恢复处理，这里不动
            return;
        }
        s.cancelled.set(true);
        // queued→cancelled 与 run() 里的 queued→running 互斥：不锁的话 run() 刚过守卫、
        // 这里把它标成 cancelled 并移出内存，run() 接着又把状态写回 running，终态永远落不下来
        synchronized (s) {
            if (PluginJob.STATUS_QUEUED.equals(s.job.getStatus())) {
                if (s.future != null) s.future.cancel(false);
                finish(s, PluginJob.STATUS_CANCELLED, null);
                return;
            }
        }
        if (s.future != null) s.future.cancel(true);
    }

    // ------------------------------------------------------------------ internals

    private ExecutorService executorFor(String pluginId) {
        return executors.computeIfAbsent(pluginId, id -> {
            AtomicInteger n = new AtomicInteger();
            return new ThreadPoolExecutor(PER_PLUGIN_CONCURRENCY, PER_PLUGIN_CONCURRENCY, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "plugin-job-" + id + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        });
    }

    private void run(JobState s, ToolCall call, JobBody body) {
        synchronized (s) {
            if (s.finished.get() || s.cancelled.get()) {
                if (!s.finished.get()) finish(s, PluginJob.STATUS_CANCELLED, null);
                return;
            }
            s.job.setStatus(PluginJob.STATUS_RUNNING);
            persist(s);
        }
        JobContext ctx = new Ctx(s, call);
        try {
            Long userId = call.userId();
            if (userId != null) {
                PlatformAiUserScope.call(userId, () -> {
                    try {
                        body.run(ctx);
                        return null;
                    } catch (Exception e) {
                        throw new BodyException(e);
                    }
                });
            } else {
                body.run(ctx);
            }
            finish(s, s.cancelled.get() ? PluginJob.STATUS_CANCELLED : PluginJob.STATUS_DONE, null);
        } catch (Throwable t) {
            Throwable cause = t instanceof BodyException ? t.getCause() : t;
            if (s.cancelled.get() || cause instanceof InterruptedException) {
                finish(s, PluginJob.STATUS_CANCELLED, null);
            } else {
                log.warn("Plugin job {} ({}/{}) failed", s.job.getId(), s.job.getPluginId(), s.job.getKind(), cause);
                finish(s, PluginJob.STATUS_FAILED, cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        } finally {
            Thread.interrupted();
        }
    }

    /** 把任务体的受检异常穿过 Supplier 边界。 */
    private static final class BodyException extends RuntimeException {
        BodyException(Throwable cause) { super(cause); }
    }

    private void finish(JobState s, String status, String error) {
        if (!s.finished.compareAndSet(false, true)) return;
        s.job.setStatus(status);
        s.job.setError(error);
        if (PluginJob.STATUS_DONE.equals(status)) {
            s.job.setResultJson(s.resultJson);
            if (s.job.getTotal() > 0) s.job.setDone(s.job.getTotal());
        }
        persist(s);
        // 终态留在内存里（status()/get() 不必回库，库里的 save 也可能还没提交），
        // 只在条目过多时按创建时间淘汰已结束的
        evictTerminalIfOversized();
    }

    private static final int MEMORY_CAP = 500;

    private void evictTerminalIfOversized() {
        if (jobs.size() <= MEMORY_CAP) return;
        jobs.values().stream()
                .filter(st -> st.finished.get())
                .sorted(java.util.Comparator.comparing(st -> st.job.getCreatedAt()))
                .limit(Math.max(1, jobs.size() - MEMORY_CAP))
                .forEach(st -> jobs.remove(st.job.getId()));
    }

    private void persist(JobState s) {
        s.job.setUpdatedAt(LocalDateTime.now());
        try {
            repo.save(s.job);
        } catch (Exception e) {
            log.warn("Plugin job {} persist failed: {}", s.job.getId(), e.getMessage());
        }
        push(s.job);
    }

    private void push(PluginJob job) {
        if (editorBridge == null || job.getConversationId() == null) return;
        Map<String, Object> fields = new HashMap<>();
        fields.put("jobId", job.getId());
        fields.put("pluginId", job.getPluginId());
        fields.put("kind", job.getKind());
        fields.put("title", job.getTitle());
        fields.put("status", job.getStatus());
        fields.put("done", job.getDone());
        fields.put("total", job.getTotal());
        fields.put("message", job.getMessage());
        fields.put("error", job.getError());
        fields.put("conversationId", job.getConversationId());
        try {
            editorBridge.sendClientAction(CLIENT_ACTION, job.getConversationId(), fields);
        } catch (Exception e) {
            log.debug("Plugin job {} SSE push failed: {}", job.getId(), e.getMessage());
        }
    }

    private static JobStatus toStatus(PluginJob j) {
        return new JobStatus(j.getId(), j.getKind(), j.getTitle(), j.getStatus(), j.getDone(), j.getTotal(),
                j.getMessage(), j.getResultJson(), j.getError());
    }

    /** 任务体看到的上下文。 */
    private final class Ctx implements JobContext {
        private final JobState s;
        private final ToolCall call;

        Ctx(JobState s, ToolCall call) {
            this.s = s;
            this.call = call;
        }

        @Override
        public void progress(long done, long total, String message) {
            s.job.setDone(done);
            s.job.setTotal(total);
            s.job.setMessage(message != null && message.length() > 512 ? message.substring(0, 512) : message);
            long now = System.currentTimeMillis();
            if (s.lastProgressPersistMs == 0 || now - s.lastProgressPersistMs >= progressThrottleMs) {
                s.lastProgressPersistMs = now;
                persist(s);
            }
        }

        @Override
        public void checkCancelled() throws InterruptedException {
            if (s.cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException(LangText.of("任务已取消", "Job cancelled"));
            }
        }

        @Override
        public ToolCall call() {
            return call;
        }

        @Override
        public void result(String resultJson) {
            s.resultJson = resultJson;
        }
    }
}
