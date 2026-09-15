// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Durable user input waiting to start or steer an Agent run. */
@Entity
@Table(name = "agent_inbox_item", indexes = {
        @Index(name = "idx_agent_inbox_pending", columnList = "conversation_id, state, position")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_agent_inbox_request",
                columnNames = {"conversation_id", "user_id", "client_request_id"})
})
public class AgentInboxItem {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "client_request_id", length = 128)
    private String clientRequestId;

    @Column(name = "submission_mode", nullable = false, length = 16)
    private String submissionMode;

    @Column(nullable = false, length = 24)
    private String state;

    @Column(nullable = false)
    private Long position;

    @Column(nullable = false)
    private Long revision;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(name = "display_text", columnDefinition = "TEXT")
    private String displayText;

    /** Complete request, including attachment/context metadata, for durable restart/drain. */
    @Column(name = "request_json", columnDefinition = "TEXT", nullable = false)
    private String requestJson;

    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(name = "applied_sequence")
    private Long appliedSequence;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public String getSubmissionMode() { return submissionMode; }
    public void setSubmissionMode(String submissionMode) { this.submissionMode = submissionMode; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Long getPosition() { return position; }
    public void setPosition(Long position) { this.position = position; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getDisplayText() { return displayText; }
    public void setDisplayText(String displayText) { this.displayText = displayText; }
    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String requestJson) { this.requestJson = requestJson; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Long getAppliedSequence() { return appliedSequence; }
    public void setAppliedSequence(Long appliedSequence) { this.appliedSequence = appliedSequence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
