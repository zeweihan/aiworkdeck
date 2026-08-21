package com.checkba.service.ai;

import com.checkba.model.ai.TaskInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 后台任务的会话内取消（长任务可控）。
 *
 * 背景：cancelTask 早就实现完整，但全仓一个调用方都没有——进度卡只能干等到超时。
 * 接端点时补上会话归属校验，否则拿自己的会话 ID + 猜到的 taskId 就能掐别人的任务。
 */
@DisplayName("后台任务取消")
class BackgroundTaskServiceTest {

    private SseEmitterService sse;
    private BackgroundTaskService service;

    @BeforeEach
    void setUp() {
        sse = mock(SseEmitterService.class);
        service = new BackgroundTaskService(sse);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // 清理任务的调度线程（每个实例一个），别在测试 JVM 里堆着
        service.shutdown();
    }

    @Test
    @DisplayName("本会话可停：任务转为 CANCELLED 并广播一次 background_task_complete")
    void cancelsOwnConversationTask() {
        String taskId = service.registerTask("conv-1", 7L, TaskInfo.TaskType.PPTX_GENERATE, 900);

        assertTrue(service.cancelTask(taskId, "conv-1"));
        assertEquals(TaskInfo.TaskStatus.CANCELLED, service.getTask(taskId).getStatus());
        verify(sse).send(eq("conv-1"), eq("background_task_complete"), any());
        assertTrue(service.getActiveTasksForConversation("conv-1").isEmpty());
    }

    @Test
    @DisplayName("跨会话停不动：任务照常在跑，也不广播结束事件")
    void refusesForeignConversation() {
        String taskId = service.registerTask("conv-1", 7L, TaskInfo.TaskType.PPTX_GENERATE, 900);

        assertFalse(service.cancelTask(taskId, "conv-other"));
        assertTrue(service.getTask(taskId).isActive());
        verify(sse, never()).send(any(), eq("background_task_complete"), any());
    }

    @Test
    @DisplayName("不存在/已结束/参数缺失一律返回 false（端点据此回 404，不谎报已停止）")
    void refusesUnknownOrFinished() {
        assertFalse(service.cancelTask("no-such-task", "conv-1"));
        assertFalse(service.cancelTask(null, "conv-1"));

        String taskId = service.registerTask("conv-1", 7L, TaskInfo.TaskType.PPTX_GENERATE, 900);
        assertFalse(service.cancelTask(taskId, null));
        assertTrue(service.cancelTask(taskId, "conv-1"));
        assertFalse(service.cancelTask(taskId, "conv-1"), "已取消的任务不能再取消一次");
    }

    // ==== 卡死 RUNNING 任务的兜底回收 ====
    // 背景：cleanupOldTasks 此前的 removeIf 条件是 !task.isActive()，RUNNING 永远
    // isActive()==true 故永不回收。触发场景：AI 调 pptx_generate，pptx-service 网络失败，
    // PptxTools 里 registerTask 之后的异常路径直接 return 不碰 taskId——三张登记表永久留一条
    // RUNNING，hasActiveTasks 恒为 true，前端进度卡永远转下去。

    @Test
    @DisplayName("修复：卡死超过阈值的 RUNNING 任务被 cleanupOldTasks 强制终态化并广播")
    void reclaimsStuckRunningTaskAfterTimeout() {
        String taskId = service.registerTask("conv-1", 7L, TaskInfo.TaskType.PPTX_GENERATE, 900);
        assertTrue(service.hasActiveTasks("conv-1"));

        // 模拟卡死：既不 complete 也不 fail，只把服务内部的时间源推进到远超回收阈值
        // （registerTask 落的 startedAt/lastUpdatedAt 用的是真实系统时钟，早于推进后的"现在"）
        service.setClock(java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofHours(2)));

        service.cleanupOldTasks();

        assertFalse(service.hasActiveTasks("conv-1"), "卡死超过阈值的 RUNNING 任务应被回收，不再计入活跃任务");

        // 用 SSE 广播的载荷断言终态是 FAILED，而不是读 getTask(taskId) 之后的状态——
        // 注入的时钟被推远到 2 小时后，failTask 内部用真实 Instant.now() 落的 lastUpdatedAt
        // 相对推远后的"现在"也早已超过下面清理已终态任务的 30 分钟保留期，条目可能在同一次
        // cleanupOldTasks 调用里被连带摘除；广播内容才是不依赖这个时序细节的稳定断言点。
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sse).send(eq("conv-1"), eq("background_task_complete"), payload.capture());
        assertTrue(payload.getValue().contains("\"FAILED\""),
                "应以失败终态广播通知前端，而不是让进度卡无声挂起：" + payload.getValue());
    }

    @Test
    @DisplayName("未超阈值的 RUNNING 任务不受影响：cleanupOldTasks 不会误杀正常在跑的任务")
    void doesNotReclaimFreshRunningTask() {
        String taskId = service.registerTask("conv-1", 7L, TaskInfo.TaskType.PPTX_GENERATE, 900);

        service.cleanupOldTasks();

        assertTrue(service.hasActiveTasks("conv-1"), "刚注册、未超时的任务不应被误杀");
        assertEquals(TaskInfo.TaskStatus.RUNNING, service.getTask(taskId).getStatus());
    }

    // ==== conversationTasks / userTasks 外层 map 的无界增长 ====
    // 背景：cleanupTaskReferences 此前只 list.remove(taskId) 摘空内层列表，从不摘外层 map
    // 的 key——每个处理过至少一个后台任务的会话/用户都在这两张表里永久占一条，进程越久攒得越多。

    @Test
    @DisplayName("修复：任务清空后，conversationTasks/userTasks 的外层 key 要随之摘除，不能只剩空列表")
    void cleanupRemovesEmptyOuterMapEntry() {
        String taskId = service.registerTask("conv-1", 7L, TaskInfo.TaskType.PPTX_GENERATE, 900);
        service.completeTask(taskId, "done");

        // 走 cleanupOldTasks 的"已终态超过 30 分钟保留期"分支，而不是 completeTask 内部
        // 5 分钟后才触发的异步 scheduleCleanup——后者要等真实墙钟，单测等不起。
        service.setClock(java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofHours(1)));
        service.cleanupOldTasks();

        assertTrue(service.getActiveTasksForConversation("conv-1").isEmpty());
        assertFalse(service.hasConversationTaskMapEntry("conv-1"),
                "任务清空后 conversationTasks 里 conv-1 这个 key 应随之摘除，不能只留一个空列表");
        assertFalse(service.hasUserTaskMapEntry(7L),
                "任务清空后 userTasks 里 userId=7 这个 key 应随之摘除，不能只留一个空列表");
    }
}
