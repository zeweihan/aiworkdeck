// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 尽调清单项实体
 * 代表清单中的具体一项文件要求（如：营业执照）
 */
@Entity
@Table(name = "dd_item")
public class DdItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属清单 ID
     */
    @Column(nullable = false)
    private Long ddRequestId;

    /**
     * 标题（如：营业执照副本）
     */
    @Column(length = 512, nullable = false)
    private String title;

    /**
     * 描述/说明（如：请提供最新年检版本）
     */
    @Column(length = 1024)
    private String description;

    /**
     * 状态
     * - PENDING: 待上传
     * - UPLOADED: 已上传（待审核）
     * - APPROVED: 已确认（律师通过）
     * - REJECTED: 需重传（律师驳回）
     */
    @Column(length = 32, nullable = false)
    private String status = "PENDING";

    /**
     * 排序号
     */
    @Column(nullable = false)
    private Integer sortOrder;

    /**
     * 父项 ID (null 表示根节点)
     */
    @Column
    private Long parentId;

    /**
     * 层级 (0 表示根节点)
     */
    @Column(nullable = false)
    private Integer level = 0;

    /**
     * 示例文件 ID（关联 ProjectFile，可选）
     */
    @Column
    private Long exampleFileId;

    /**
     * 客户上传的文件 ID（关联 ProjectFile，上传后非空）
     */
    @Column
    private Long uploadedFileId;

    /**
     * 客户上传时间
     */
    @Column
    private LocalDateTime uploadedAt;

    /**
     * 客户上传人 ID
     */
    @Column
    private Long uploadedBy;

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

    public Long getDdRequestId() {
        return ddRequestId;
    }

    public void setDdRequestId(Long ddRequestId) {
        this.ddRequestId = ddRequestId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Long getExampleFileId() {
        return exampleFileId;
    }

    public void setExampleFileId(Long exampleFileId) {
        this.exampleFileId = exampleFileId;
    }

    public Long getUploadedFileId() {
        return uploadedFileId;
    }

    public void setUploadedFileId(Long uploadedFileId) {
        this.uploadedFileId = uploadedFileId;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
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
        DdItem ddItem = (DdItem) o;
        return Objects.equals(id, ddItem.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
