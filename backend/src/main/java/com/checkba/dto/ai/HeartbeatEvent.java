// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for heartbeat SSE events.
 * Sent periodically to confirm the backend/LLM loop is still processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatEvent {
    
    /**
     * Source of the heartbeat: "LLM_LOOP" or "PPTX_SERVICE"
     */
    private String source;
    
    /**
     * Conversation ID this heartbeat belongs to
     */
    private String conversationId;
    
    /**
     * Task ID if a specific task is in progress
     */
    private String taskId;
    
    /**
     * Timestamp when this heartbeat was generated
     */
    private long timestamp;
    
    /**
     * Current operation being performed (optional detail)
     */
    private String currentOperation;
    
    public static HeartbeatEvent llmLoop(String conversationId) {
        return HeartbeatEvent.builder()
                .source("LLM_LOOP")
                .conversationId(conversationId)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    public static HeartbeatEvent pptxService(String conversationId, String taskId, String currentOperation) {
        return HeartbeatEvent.builder()
                .source("PPTX_SERVICE")
                .conversationId(conversationId)
                .taskId(taskId)
                .currentOperation(currentOperation)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
