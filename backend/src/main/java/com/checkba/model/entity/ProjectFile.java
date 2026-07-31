package com.checkba.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 项目文件/文件夹实体
 * 
 * 说明：
 * - 用于存储项目中的文件和文件夹结构
 * - 支持树形结构（通过 parentId 实现）
 * - 文件和文件夹共用一张表，通过 isFolder 字段区分
 */
@Entity
@Table(name = "project_file")
public class ProjectFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属项目 ID
     */
    @Column(nullable = false)
    private Long projectId;

    /**
     * 父文件夹 ID（null 表示根目录）
     */
    @Column
    private Long parentId;

    /**
     * 是否为文件夹（true=文件夹，false=文件）
     */
    @Column(nullable = false)
    private Boolean isFolder;

    /**
     * 文件名或文件夹名
     */
    @Column(length = 256, nullable = false)
    private String name;

    /**
     * 文件类型（仅文件有效，如 docx, pdf, xlsx 等）
     */
    @Column(length = 32)
    private String fileType;

    /**
     * 文件大小（字节，仅文件有效）
     */
    @Column
    private Long fileSize;

    /**
     * 文件存储路径（仅文件有效，相对于存储根目录）
     * 例如：project_1/doc_1.docx
     */
    @Column(length = 512)
    private String filePath;

    /**
     * WPS 文件 ID（仅文件有效，用于 WPS 在线编辑）
     * 例如：project_1_doc_1_v1234567890
     */
    @Column(length = 256)
    private String wpsFileId;

    /**
     * 排序序号（用于拖拽排序）
     */
    @Column(nullable = false)
    private Integer sortOrder;

    /**
     * 创建者用户 ID
     */
    @Column(nullable = false)
    private Long userId;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最近更新时间
     */
    private LocalDateTime updatedAt;
    /**
     * 是否已删除（软删除标记）
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean isDeleted = false;

    /** 跨机器稳定身份（清单 v2）。本机新建行由 capture 回填，克隆行来自云端清单。 */
    @Column(length = 36)
    private String uid;

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }

    /**
     * 删除时间
     */
    private LocalDateTime deletedAt;

    /**
     * 文件关联的标签（非持久化字段）
     */
    @jakarta.persistence.Transient
    private java.util.List<Tag> tags;
    
    // Getters and Setters

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

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Boolean getIsFolder() {
        return isFolder;
    }

    public void setIsFolder(Boolean isFolder) {
        this.isFolder = isFolder;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getWpsFileId() {
        return wpsFileId;
    }

    public void setWpsFileId(String wpsFileId) {
        this.wpsFileId = wpsFileId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public Boolean getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Boolean isDeleted) {
        this.isDeleted = isDeleted;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public java.util.List<Tag> getTags() {
        return tags;
    }

    public void setTags(java.util.List<Tag> tags) {
        this.tags = tags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectFile that = (ProjectFile) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
