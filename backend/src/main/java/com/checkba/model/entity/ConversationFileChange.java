// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 记录每次对话中的文件变动（新增/修改）
 */
@Entity
@Table(name = "conversation_file_change")
public class ConversationFileChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的会话ID
     */
    @Column(nullable = false, length = 64)
    private String conversationId;

    /**
     * 文件名
     */
    @Column(nullable = false)
    private String fileName;

    /**
     * 变动类型：ADDED / MODIFIED
     */
    @Column(nullable = false, length = 16)
    private String changeType;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public ConversationFileChange() {}

    public ConversationFileChange(String conversationId, String fileName, String changeType) {
        this.conversationId = conversationId;
        this.fileName = fileName;
        this.changeType = changeType;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConversationFileChange that = (ConversationFileChange) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
