// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 尽调清单请求实体
 * 代表律师发起的一次文件收集请求（如：第一轮尽调清单）
 */
@Entity
@Table(name = "dd_request")
public class DdRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属项目 ID
     */
    @Column(nullable = false)
    private Long projectId;

    /**
     * 清单名称（如：初步尽调清单、补充清单）
     */
    @Column(length = 256, nullable = false)
    private String name;

    /**
     * 状态
     * - DRAFT: 草稿（律师编辑中）
     * - PUBLISHED: 已发布（客户可见，开始上传）
     * - COMPLETED: 已完成（归档）
     */
    @Column(length = 32, nullable = false)
    private String status = "DRAFT";

    /**
     * 创建者 ID
     */
    @Column(nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DdRequest ddRequest = (DdRequest) o;
        return Objects.equals(id, ddRequest.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
