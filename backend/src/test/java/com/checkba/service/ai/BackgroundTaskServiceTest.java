package com.checkba.service.ai;

import com.checkba.model.ai.TaskInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
