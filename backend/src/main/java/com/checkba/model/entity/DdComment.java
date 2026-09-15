// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 尽调项评论实体
 * 用于律师和客户针对某一项文件进行沟通
 */
@Entity
@Table(name = "dd_comment")
public class DdComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属清单项 ID
     */
    @Column(nullable = false)
    private Long ddItemId;

    /**
     * 发言人 ID
     */
    @Column(nullable = false)
    private Long userId;

    /**
     * 评论内容
     */
    @Column(length = 2048, nullable = false)
    private String content;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDdItemId() {
        return ddItemId;
    }

    public void setDdItemId(Long ddItemId) {
        this.ddItemId = ddItemId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DdComment ddComment = (DdComment) o;
        return Objects.equals(id, ddComment.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
