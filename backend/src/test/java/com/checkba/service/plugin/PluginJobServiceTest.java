package com.checkba.service.plugin;

import com.checkba.model.entity.PluginJob;
import com.checkba.plugin.api.JobHandle;
import com.checkba.plugin.api.JobStatus;
import com.checkba.plugin.api.ToolCall;
import com.checkba.repository.PluginJobRepository;
import com.checkba.service.ai.EditorBridgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PluginJobService 契约（dev-board#109 单元 H2）：
 * 生命周期 queued→running→done、进度节流写库、取消经 checkCancelled 抛 InterruptedException、
 * 每插件并发 2、宿主重启把库里 running 的标 failed。Repository 与 EditorBridgeService 全 mock。
 */
class PluginJobServiceTest {

    PluginJobRepository repo = mock(PluginJobRepository.class);
    EditorBridgeService bridge = mock(EditorBridgeService.class);
    PluginJobService svc;

    /** 每次 save 的快照（status/done/error），因为 save 传入的是同一个可变实例，直接留引用看不到历史。 */
    final List<String[]> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(repo.save(any())).thenAnswer(inv -> {
            PluginJob j = inv.getArgument(0);
            synchronized (saved) {
                saved.add(new String[]{j.getStatus(), String.valueOf(j.getDone()), j.getError(), j.getResultJson()});
            }
            return j;
        });
        when(repo.findByStatusIn(anyList())).thenReturn(List.of());
        svc = new PluginJobService(repo, bridge, 500);
    }

    @AfterEach
    void tearDown() {
        svc.shutdown();
    }

    private static ToolCall call(String conversationId) {
        return new ToolCall(1L, conversationId, 9L, null);
    }

    private static JobStatus await(PluginJobService svc, String jobId, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JobStatus s = svc.status(jobId);
            if (s != null && status.equals(s.status())) return s;
            Thread.sleep(10);
        }
        throw new AssertionError("job " + jobId + " never reached " + status + ", last=" + svc.status(jobId));
    }

    @Test
    @DisplayName("start → running → done，resultJson 落库，进度节流：500ms 内第二次 progress 不写库")
    void lifecycleAndThrottle() throws Exception {
        CountDownLatch inBody = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobHandle h = svc.start("p1", "ingest", "入库", call(null), ctx -> {
            ctx.progress(1, 3, "第一步");
            ctx.progress(2, 3, "第二步");
            inBody.countDown();
            release.await(5, TimeUnit.SECONDS);
            ctx.result("{\"ok\":true}");
        });
        assertNotNull(h.jobId());
        assertTrue(inBody.await(5, TimeUnit.SECONDS));

        // 内存态永远是最新的
        JobStatus mid = svc.status(h.jobId());
        assertEquals("running", mid.status());
        assertEquals(2, mid.done());
        assertEquals("第二步", mid.message());
        // 库里只有第一次 progress（done=1），第二次在 500ms 节流窗口内没写
        synchronized (saved) {
            assertTrue(saved.stream().anyMatch(s -> "running".equals(s[0]) && "1".equals(s[1])), saved.toString());
            assertFalse(saved.stream().anyMatch(s -> "running".equals(s[0]) && "2".equals(s[1])), saved.toString());
        }

        release.countDown();
        JobStatus done = await(svc, h.jobId(), "done");
        assertEquals("{\"ok\":true}", done.resultJson());
        assertEquals(3, done.total());
        synchronized (saved) {
            assertTrue(saved.stream().anyMatch(s -> "done".equals(s[0]) && "{\"ok\":true}".equals(s[3])), saved.toString());
        }
    }

    @Test
    @DisplayName("有 conversationId 时每次落库同时经 SSE client_action plugin_job_progress 推给对话")
    void pushesSseWhenConversationPresent() throws Exception {
        JobHandle h = svc.start("p1", "ingest", "入库", call("conv-1"), ctx -> ctx.progress(1, 1, "完成"));
        await(svc, h.jobId(), "done");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(bridge, org.mockito.Mockito.atLeast(2))
                .sendClientAction(eq("plugin_job_progress"), eq("conv-1"), fields.capture());
        Map<String, Object> last = fields.getValue();
        assertEquals(h.jobId(), last.get("jobId"));
        assertEquals("done", last.get("status"));
        assertEquals("p1", last.get("pluginId"));
    }

    @Test
    @DisplayName("cancel：任务体的 checkCancelled 抛 InterruptedException，状态 cancelled")
    void cancelRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean sawInterrupt = new AtomicBoolean();
        JobHandle h = svc.start("p1", "ingest", "入库", call(null), ctx -> {
            started.countDown();
            try {
                for (int i = 0; i < 1000; i++) {
                    ctx.checkCancelled();
                    Thread.sleep(5);
                }
            } catch (InterruptedException e) {
                sawInterrupt.set(true);
                throw e;
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        svc.cancel(h.jobId());
        JobStatus s = await(svc, h.jobId(), "cancelled");
        assertEquals("cancelled", s.status());
        assertTrue(sawInterrupt.get(), "body must observe cancellation via checkCancelled");
    }

    @Test
    @DisplayName("任务体抛异常 → failed 并带 error")
    void failure() throws Exception {
        JobHandle h = svc.start("p1", "ingest", "入库", call(null), ctx -> {
            throw new IllegalStateException("boom");
        });
        JobStatus s = await(svc, h.jobId(), "failed");
        assertTrue(s.error().contains("boom"), s.error());
    }

    @Test
    @DisplayName("每插件并发 2：第 3 个排队 queued，前面放行后才跑；另一个插件不受影响")
    void perPluginConcurrency() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch twoRunning = new CountDownLatch(2);
        JobHandle a = svc.start("p1", "k", "a", call(null), ctx -> { twoRunning.countDown(); release.await(5, TimeUnit.SECONDS); });
        JobHandle b = svc.start("p1", "k", "b", call(null), ctx -> { twoRunning.countDown(); release.await(5, TimeUnit.SECONDS); });
        assertTrue(twoRunning.await(5, TimeUnit.SECONDS));
        JobHandle c = svc.start("p1", "k", "c", call(null), ctx -> { });
        Thread.sleep(150);
        assertEquals("queued", svc.status(c.jobId()).status());
        // 其他插件有自己的池
        JobHandle other = svc.start("p2", "k", "o", call(null), ctx -> { });
        await(svc, other.jobId(), "done");
        assertEquals("queued", svc.status(c.jobId()).status());

        release.countDown();
        await(svc, a.jobId(), "done");
        await(svc, b.jobId(), "done");
        await(svc, c.jobId(), "done");
    }

    @Test
    @DisplayName("取消排队中的任务：立即 cancelled，任务体不执行")
    void cancelQueued() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch twoRunning = new CountDownLatch(2);
        svc.start("p1", "k", "a", call(null), ctx -> { twoRunning.countDown(); release.await(5, TimeUnit.SECONDS); });
        svc.start("p1", "k", "b", call(null), ctx -> { twoRunning.countDown(); release.await(5, TimeUnit.SECONDS); });
        assertTrue(twoRunning.await(5, TimeUnit.SECONDS));
        AtomicBoolean ran = new AtomicBoolean();
        JobHandle c = svc.start("p1", "k", "c", call(null), ctx -> ran.set(true));
        svc.cancel(c.jobId());
        assertEquals("cancelled", svc.status(c.jobId()).status());
        release.countDown();
        Thread.sleep(100);
        assertFalse(ran.get());
    }

    @Test
    @DisplayName("启动时把库里 queued/running 的孤儿标 failed（宿主重启）")
    void recoverOrphansOnStartup() {
        PluginJob orphan = new PluginJob();
        orphan.setId("01ORPHAN");
        orphan.setPluginId("p1");
        orphan.setStatus("running");
        when(repo.findByStatusIn(anyList())).thenReturn(List.of(orphan));
        svc.recoverOrphans();
        assertEquals("failed", orphan.getStatus());
        assertNotNull(orphan.getError());
        assertTrue(orphan.getError().contains("宿主重启") || orphan.getError().toLowerCase().contains("restart"), orphan.getError());
        verify(repo).save(orphan);
    }

    @Test
    @DisplayName("status 对不在内存里的任务回读数据库；未知 id 返回 null")
    void statusFallsBackToDb() {
        PluginJob j = new PluginJob();
        j.setId("01DB");
        j.setPluginId("p1");
        j.setKind("k");
        j.setTitle("t");
        j.setStatus("done");
        j.setDone(3);
        j.setTotal(3);
        when(repo.findById("01DB")).thenReturn(Optional.of(j));
        when(repo.findById("nope")).thenReturn(Optional.empty());
        JobStatus s = svc.status("01DB");
        assertEquals("done", s.status());
        assertEquals(3, s.done());
        assertEquals(null, svc.status("nope"));
    }
}
