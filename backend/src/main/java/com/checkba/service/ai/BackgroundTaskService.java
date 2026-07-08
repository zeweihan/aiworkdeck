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

    public BackgroundTaskService(SseEmitterService sseEmitterService) {
        this.sseEmitterService = sseEmitterService;
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
        conversationTasks.computeIfAbsent(conversationId, k -> new CopyOnWriteArrayList<>()).add(taskId);
        userTasks.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(taskId);
        
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
     * Cancel a task.
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
        Instant cutoff = Instant.now().minusSeconds(30 * 60); // 30 minutes
        
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
    
    private void cleanupTaskReferences(String taskId, TaskInfo task) {
        if (task.getConversationId() != null) {
            List<String> convTasks = conversationTasks.get(task.getConversationId());
            if (convTasks != null) {
                convTasks.remove(taskId);
            }
        }
        if (task.getUserId() != null) {
            List<String> uTasks = userTasks.get(task.getUserId());
            if (uTasks != null) {
                uTasks.remove(taskId);
            }
        }
    }
}
