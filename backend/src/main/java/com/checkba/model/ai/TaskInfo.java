// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Tracks information about a background task.
 * Used for in-memory task tracking across conversations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskInfo {
    
    public enum TaskStatus {
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
    
    public enum TaskType {
        PPTX_GENERATE,
        PPTX_MODIFY,
        FILE_PROCESS,
        WEB_FETCH,
        OTHER
    }
    
    /**
     * Unique identifier for the task
     */
    private String taskId;
    
    /**
     * Conversation ID this task belongs to
     */
    private String conversationId;
    
    /**
     * User ID who initiated the task
     */
    private Long userId;
    
    /**
     * Type of task
     */
    private TaskType taskType;
    
    /**
     * Current progress (0-100)
     */
    private int progress;
    
    /**
     * Human-readable progress message
     */
    private String message;
    
    /**
     * When the task started
     */
    private Instant startedAt;
    
    /**
     * Estimated duration in seconds (null if unknown)
     */
    private Integer estimatedDurationSec;
    
    /**
     * Current status of the task
     */
    private TaskStatus status;
    
    /**
     * Result data (set on completion)
     */
    private Object result;
    
    /**
     * Error message (set on failure)
     */
    private String error;
    
    /**
     * When the task was last updated
     */
    private Instant lastUpdatedAt;
    
    public static TaskInfo create(String taskId, String conversationId, Long userId, TaskType taskType, Integer estimatedDurationSec) {
        Instant now = Instant.now();
        return TaskInfo.builder()
                .taskId(taskId)
                .conversationId(conversationId)
                .userId(userId)
                .taskType(taskType)
                .progress(0)
                .message("Task started")
                .startedAt(now)
                .lastUpdatedAt(now)
                .estimatedDurationSec(estimatedDurationSec)
                .status(TaskStatus.RUNNING)
                .build();
    }
    
    public void updateProgress(int progress, String message) {
        this.progress = progress;
        this.message = message;
        this.lastUpdatedAt = Instant.now();
    }
    
    public void complete(Object result) {
        this.status = TaskStatus.COMPLETED;
        this.progress = 100;
        this.result = result;
        this.lastUpdatedAt = Instant.now();
    }
    
    public void fail(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.lastUpdatedAt = Instant.now();
    }
    
    public void cancel() {
        this.status = TaskStatus.CANCELLED;
        this.lastUpdatedAt = Instant.now();
    }
    
    public boolean isActive() {
        return status == TaskStatus.RUNNING;
    }
}
