// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for background task lifecycle SSE events.
 * Sent when a background task starts or completes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackgroundTaskEvent {
    
    public enum EventType {
        STARTED,
        COMPLETED,
        FAILED,
        CANCELLED
    }
    
    /**
     * Unique identifier for the task
     */
    private String taskId;
    
    /**
     * Type of task (e.g., "PPTX_GENERATE", "FILE_PROCESS")
     */
    private String taskType;
    
    /**
     * Event type (started, completed, failed, cancelled)
     */
    private EventType eventType;
    
    /**
     * Conversation ID this task belongs to
     */
    private String conversationId;
    
    /**
     * Estimated duration in seconds (for STARTED events)
     */
    private Integer estimatedDurationSec;
    
    /**
     * Result data (for COMPLETED events)
     */
    private Object result;
    
    /**
     * Error message (for FAILED events)
     */
    private String error;
    
    /**
     * Success status (for COMPLETED events)
     */
    private Boolean success;
    
    /**
     * Timestamp when this event was generated
     */
    private long timestamp;
    
    public static BackgroundTaskEvent started(String taskId, String taskType, String conversationId, Integer estimatedDurationSec) {
        return BackgroundTaskEvent.builder()
                .taskId(taskId)
                .taskType(taskType)
                .eventType(EventType.STARTED)
                .conversationId(conversationId)
                .estimatedDurationSec(estimatedDurationSec)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    public static BackgroundTaskEvent completed(String taskId, String taskType, Object result) {
        return BackgroundTaskEvent.builder()
                .taskId(taskId)
                .taskType(taskType)
                .eventType(EventType.COMPLETED)
                .result(result)
                .success(true)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    public static BackgroundTaskEvent failed(String taskId, String taskType, String error) {
        return BackgroundTaskEvent.builder()
                .taskId(taskId)
                .taskType(taskType)
                .eventType(EventType.FAILED)
                .error(error)
                .success(false)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    public static BackgroundTaskEvent cancelled(String taskId, String taskType) {
        return BackgroundTaskEvent.builder()
                .taskId(taskId)
                .taskType(taskType)
                .eventType(EventType.CANCELLED)
                .success(false)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
