package com.checkba.service.ai;

import cn.hutool.json.JSONUtil;
import com.checkba.dto.ai.BackgroundTaskEvent;
import com.checkba.dto.ai.HeartbeatEvent;
import com.checkba.dto.ai.TaskProgressEvent;
import com.checkba.model.ai.TaskInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for tracking and managing background tasks.
 * Provides progress reporting, task lifecycle management, and SSE event emission.
 */
@Slf4j
@Service
public class BackgroundTaskService {
    
    private final SseEmitterService sseEmitterService;
    
    /**
     * Active tasks: taskId -> TaskInfo
     */
    private final Map<String, TaskInfo> activeTasks = new ConcurrentHashMap<>();
    
    /**
     * Conversation to tasks mapping: conversationId -> List<taskId>
     */
    private final Map<String, List<String>> conversationTasks = new ConcurrentHashMap<>();
    
    /**
     * User to tasks mapping: userId -> List<taskId>
     */
    private final Map<Long, List<String>> userTasks = new ConcurrentHashMap<>();
    
    /**
     * 共享的守护线程调度器，用于延迟清理已完成任务。
     * 取代原先逐任务 new Thread() 的做法（非守护线程会拖慢 JVM 关闭，且大量休眠线程堆积）。
     */
    private final ScheduledExecutorService cleanupScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bg-task-cleanup");
                t.setDaemon(true);
                return t;
            });

    /**
     * RUNNING 任务卡死回收的时间源。生产环境走真实系统时钟；测试用 {@link #setClock} 换成
     * 可控时钟——不这样做的话，"注册任务后不更新、等它被判定为卡死" 这条路径没法在单测里
     * 用秒级等待验证（真要卡死回收阈值那么久，测试根本跑不完）。
     */
    private volatile Clock clock = Clock.systemUTC();

    /**
     * RUNNING 状态卡死回收阈值（分钟）：超过这个时长仍未更新进度/心跳的 RUNNING 任务视为卡死。
     * 与下面"已终态任务保留多久供前端查询"的 30 分钟数值相同但语义不同，各自命名以免以后
     * 需要分别调整时混在一起。
     */
    private static final long STALE_RUNNING_TIMEOUT_MINUTES = 30;

    public BackgroundTaskService(SseEmitterService sseEmitterService) {
        this.sseEmitterService = sseEmitterService;
    }

    /** 供测试注入可控时钟（生产环境走真实系统时钟，见 {@link #clock} 字段注释）。 */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 供测试断言 conversationTasks 外层 map 是否还留着某个 key（不下沉成生产代码路径）。 */
    boolean hasConversationTaskMapEntry(String conversationId) {
        return conversationTasks.containsKey(conversationId);
    }

    /** 供测试断言 userTasks 外层 map 是否还留着某个 key（不下沉成生产代码路径）。 */
    boolean hasUserTaskMapEntry(Long userId) {
        return userTasks.containsKey(userId);
    }

    @PreDestroy
    public void shutdown() {
        cleanupScheduler.shutdownNow();
    }
    
    /**
     * Register a new background task.
     * 
     * @param conversationId The conversation this task belongs to
     * @param userId The user who initiated the task
     * @param taskType The type of task
     * @param estimatedDurationSec Estimated duration in seconds
     * @return The generated task ID
     */
    public String registerTask(String conversationId, Long userId, TaskInfo.TaskType taskType, Integer estimatedDurationSec) {
        String taskId = UUID.randomUUID().toString();
        TaskInfo taskInfo = TaskInfo.create(taskId, conversationId, userId, taskType, estimatedDurationSec);
        
        activeTasks.put(taskId, taskInfo);
        // 内层用 CopyOnWriteArrayList：注册线程、清理线程、查询线程并发 add/remove/遍历，
        // 普通 ArrayList 会抛 ConcurrentModificationException 或脏读（ConcurrentHashMap 只保护外层 map）。
        // 用 compute（而不是 computeIfAbsent(...).add(...) 两步）：注册与
        // cleanupTaskReferences 的"清空后摘除 key"都要经过同一个按 key 加锁的原子操作，
        // 否则会出现"cleanup 判定 list 为空、正要摘除 key 的同时，registerTask 恰好往同一个
        // 已存在但即将被摘除的 list 里塞了新 taskId"，新任务在摘除后就从外层 map 里凭空消失。
        conversationTasks.compute(conversationId, (k, list) -> {
            List<String> l = list != null ? list : new CopyOnWriteArrayList<>();
            l.add(taskId);
            return l;
        });
        userTasks.compute(userId, (k, list) -> {
            List<String> l = list != null ? list : new CopyOnWriteArrayList<>();
            l.add(taskId);
            return l;
        });
        
        // Send background_task_start event
        BackgroundTaskEvent event = BackgroundTaskEvent.started(taskId, taskType.name(), conversationId, estimatedDurationSec);
        sseEmitterService.send(conversationId, "background_task_start", JSONUtil.toJsonStr(event));
        
        log.info("Registered background task: {} (type: {}, conversation: {})", taskId, taskType, conversationId);
        return taskId;
    }
    
    /**
     * Update task progress and emit SSE event.
     * 
     * @param taskId The task ID
     * @param progress Progress percentage (0-100)
     * @param message Human-readable progress message
     * @param stage Current stage of the operation
     */
    public void updateProgress(String taskId, int progress, String message, String stage) {
        TaskInfo task = activeTasks.get(taskId);
        if (task == null) {
            log.warn("Cannot update progress for unknown task: {}", taskId);
            return;
        }
        
        task.updateProgress(progress, message);
        
        // Calculate estimated remaining time
        Integer estimatedRemaining = null;
        if (task.getEstimatedDurationSec() != null && progress > 0) {
            long elapsed = Instant.now().toEpochMilli() - task.getStartedAt().toEpochMilli();
            double progressRatio = progress / 100.0;
            if (progressRatio > 0) {
                long totalEstimated = (long) (elapsed / progressRatio);
                estimatedRemaining = (int) ((totalEstimated - elapsed) / 1000);
            }
        }
        
        // Determine source based on task type
        String source = task.getTaskType().name().startsWith("PPTX") ? "PPTX_SERVICE" : "LLM_LOOP";
        
        TaskProgressEvent event = TaskProgressEvent.builder()
                .taskId(taskId)
                .taskType(task.getTaskType().name())
                .source(source)
                .progress(progress)
                .message(message)
                .stage(stage)
                .estimatedRemainingSec(estimatedRemaining)
                .timestamp(System.currentTimeMillis())
                .build();
        
        sseEmitterService.send(task.getConversationId(), "task_progress", JSONUtil.toJsonStr(event));
        
        log.debug("Task {} progress: {}% - {}", taskId, progress, message);
    }
    
    /**
     * Mark a task as completed.
     * 
     * @param taskId The task ID
     * @param result The result data
     */
    public void completeTask(String taskId, Object result) {
        TaskInfo task = activeTasks.get(taskId);
        if (task == null) {
            log.warn("Cannot complete unknown task: {}", taskId);
            return;
        }
        
        task.complete(result);
        
        BackgroundTaskEvent event = BackgroundTaskEvent.completed(taskId, task.getTaskType().name(), result);
        sseEmitterService.send(task.getConversationId(), "background_task_complete", JSONUtil.toJsonStr(event));
        
        log.info("Task {} completed successfully", taskId);
        
        // Schedule cleanup after 5 minutes (allow frontend to query final status)
        scheduleCleanup(taskId, 5 * 60 * 1000);
    }
    
    /**
     * Mark a task as failed.
     * 
     * @param taskId The task ID
     * @param error Error message
     */
    public void failTask(String taskId, String error) {
        TaskInfo task = activeTasks.get(taskId);
        if (task == null) {
            log.warn("Cannot fail unknown task: {}", taskId);
            return;
        }
        
        task.fail(error);
        
        BackgroundTaskEvent event = BackgroundTaskEvent.failed(taskId, task.getTaskType().name(), error);
        sseEmitterService.send(task.getConversationId(), "background_task_complete", JSONUtil.toJsonStr(event));
        
        log.error("Task {} failed: {}", taskId, error);
        
        scheduleCleanup(taskId, 5 * 60 * 1000);
    }
    
    /**
     * 会话内取消一个后台任务。
     *
     * <p>控制器只能验证「调用方能用这个会话」，所以这里再验「这个任务属于这个会话」——
     * 两道合起来才挡得住「拿自己的会话 ID + 猜到的 taskId 去掐别人的任务」。
     * 任务 ID 是 UUID，猜中难，但把归属校验放进服务里可以避免调用方
     * 先 getTask 再 cancelTask 的竞态。
     *
     * @return true = 已请求停止；false = 无此活跃任务，或它不属于该会话
     */
    public boolean cancelTask(String taskId, String conversationId) {
        if (taskId == null || conversationId == null) {
            return false;
        }
        TaskInfo task = activeTasks.get(taskId);
        if (task == null || !conversationId.equals(task.getConversationId())) {
            return false;
        }
        return cancelTask(taskId);
    }

    /**
     * Cancel a task.
     *
     * <p>注意语义边界：本方法只改任务簿记并广播 background_task_complete(cancelled)，
     * <b>停不掉已经交给外部服务的活儿</b>（PPT 生成在 pptx-service 里继续跑到底、文件照样落盘）。
     * 因此面向用户的文案只能说「正在停止」，不能说「已停止」。
     *
     * @param taskId The task ID
     * @return true if task was cancelled, false if not found or already completed
     */
    public boolean cancelTask(String taskId) {
        TaskInfo task = activeTasks.get(taskId);
        if (task == null || !task.isActive()) {
            return false;
        }
        
        task.cancel();
        
        BackgroundTaskEvent event = BackgroundTaskEvent.cancelled(taskId, task.getTaskType().name());
        sseEmitterService.send(task.getConversationId(), "background_task_complete", JSONUtil.toJsonStr(event));
        
        log.info("Task {} cancelled", taskId);
        
        scheduleCleanup(taskId, 60 * 1000);
        return true;
    }
    
    /**
     * Send heartbeat for a task.
     * 
     * @param taskId The task ID
     * @param currentOperation Description of current operation
     */
    public void sendHeartbeat(String taskId, String currentOperation) {
        TaskInfo task = activeTasks.get(taskId);
        if (task == null || !task.isActive()) {
            return;
        }
        
        String source = task.getTaskType().name().startsWith("PPTX") ? "PPTX_SERVICE" : "LLM_LOOP";
        HeartbeatEvent event = HeartbeatEvent.builder()
                .source(source)
                .conversationId(task.getConversationId())
                .taskId(taskId)
                .currentOperation(currentOperation)
                .timestamp(System.currentTimeMillis())
                .build();
        
        sseEmitterService.send(task.getConversationId(), "heartbeat", JSONUtil.toJsonStr(event));
    }
    
    /**
     * Send heartbeat for a conversation (LLM loop).
     * 
     * @param conversationId The conversation ID
     */
    public void sendLlmLoopHeartbeat(String conversationId) {
        HeartbeatEvent event = HeartbeatEvent.llmLoop(conversationId);
        sseEmitterService.send(conversationId, "heartbeat", JSONUtil.toJsonStr(event));
    }
    
    /**
     * Get all active tasks for a conversation.
     */
    public List<TaskInfo> getActiveTasksForConversation(String conversationId) {
        List<String> taskIds = conversationTasks.getOrDefault(conversationId, List.of());
        return taskIds.stream()
                .map(activeTasks::get)
                .filter(t -> t != null && t.isActive())
                .collect(Collectors.toList());
    }
    
    /**
     * Get all active tasks for a user.
     */
    public List<TaskInfo> getActiveTasksForUser(Long userId) {
        List<String> taskIds = userTasks.getOrDefault(userId, List.of());
        return taskIds.stream()
                .map(activeTasks::get)
                .filter(t -> t != null && t.isActive())
                .collect(Collectors.toList());
    }
    
    /**
     * Get a specific task by ID.
     */
    public TaskInfo getTask(String taskId) {
        return activeTasks.get(taskId);
    }
    
    /**
     * Check if a conversation has any active tasks.
     */
    public boolean hasActiveTasks(String conversationId) {
        return !getActiveTasksForConversation(conversationId).isEmpty();
    }
    
    /**
     * Scheduled cleanup of old completed tasks.
     * Runs every 10 minutes.
     */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void cleanupOldTasks() {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minusSeconds(30 * 60); // 30 minutes
        Instant staleRunningCutoff = now.minusSeconds(STALE_RUNNING_TIMEOUT_MINUTES * 60);

        // 卡死的 RUNNING 任务：外部服务挂掉 / 调用方异常路径漏调 complete/failTask（PptxTools
        // 曾经就是这样——registerTask 之后两条异常分支直接 return，从不碰 taskId），此前下面的
        // removeIf 只认"非活跃"，RUNNING 永远 isActive()==true，三张登记表永久留着一条，
        // hasActiveTasks 恒为 true，前端进度卡永远转下去。转终态复用 failTask 的既有语义
        // （发 SSE 通知前端、scheduleCleanup 延迟摘除条目），不在这里另起一套清理逻辑。
        activeTasks.forEach((taskId, task) -> {
            if (task.isActive() && task.getLastUpdatedAt().isBefore(staleRunningCutoff)) {
                log.warn("Reclaiming stuck RUNNING task {} (type={}, no update since {})",
                        taskId, task.getTaskType(), task.getLastUpdatedAt());
                failTask(taskId, "任务长时间无响应，已自动终止");
            }
        });

        activeTasks.entrySet().removeIf(entry -> {
            TaskInfo task = entry.getValue();
            if (!task.isActive() && task.getLastUpdatedAt().isBefore(cutoff)) {
                cleanupTaskReferences(entry.getKey(), task);
                log.debug("Cleaned up old task: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
    
    private void scheduleCleanup(String taskId, long delayMs) {
        cleanupScheduler.schedule(() -> {
            TaskInfo task = activeTasks.get(taskId);
            if (task != null && !task.isActive()) {
                activeTasks.remove(taskId);
                cleanupTaskReferences(taskId, task);
                log.debug("Cleaned up completed task: {}", taskId);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 摘除任务在 conversationTasks/userTasks 里的引用。
     *
     * <p>此前只 {@code list.remove(taskId)} 摘空内层列表，外层的 conversationId/userId
     * 这个 key 永远留着一个空 {@link CopyOnWriteArrayList}——每个"处理过至少一个后台任务的
     * 会话/用户"都会在这两张表里永久占一条，进程不重启就一直涨（见审计条目）。
     * 用 {@code computeIfPresent} 把"摘元素"与"空了就摘 key"收进同一个按 key 加锁的原子操作，
     * 与 {@link #registerTask} 的 {@code compute} 互斥，避免"判定为空、正要摘 key"时
     * 恰好有新任务塞进同一个 list 却被一并摘掉的竞态。
     */
    private void cleanupTaskReferences(String taskId, TaskInfo task) {
        if (task.getConversationId() != null) {
            conversationTasks.computeIfPresent(task.getConversationId(), (id, list) -> {
                list.remove(taskId);
                return list.isEmpty() ? null : list;
            });
        }
        if (task.getUserId() != null) {
            userTasks.computeIfPresent(task.getUserId(), (id, list) -> {
                list.remove(taskId);
                return list.isEmpty() ? null : list;
            });
        }
    }
}
