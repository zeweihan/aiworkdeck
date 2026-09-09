// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for task progress SSE events.
 * Sent during long-running operations to update the frontend on progress.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskProgressEvent {
    /**
     * Unique identifier for the task
     */
    private String taskId;
    
    /**
     * Type of task (e.g., "PPTX_GENERATE", "FILE_PROCESS")
     */
    private String taskType;
    
    /**
     * Source of the progress update: "LLM_LOOP" or "PPTX_SERVICE"
     */
    private String source;
    
    /**
     * Progress percentage (0-100)
     */
    private int progress;
    
    /**
     * Human-readable progress message
     */
    private String message;
    
    /**
     * Estimated remaining time in seconds (null if unknown)
     */
    private Integer estimatedRemainingSec;
    
    /**
     * Current stage of the operation (e.g., "generating_outline", "creating_images")
     */
    private String stage;
    
    /**
     * Timestamp when this event was generated
     */
    private long timestamp;
    
    public static TaskProgressEvent of(String taskId, String taskType, String source, int progress, String message) {
        return TaskProgressEvent.builder()
                .taskId(taskId)
                .taskType(taskType)
                .source(source)
                .progress(progress)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
