package com.checkba.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * WPS 文档选区超链接 -> 项目文件关联
 *
 * 设计要点：
 * - 文档里写入超链接地址：checkba://filelink?k=xxx&projectId=yyy
 * - 点击超链接时前端拦截，通过 linkKey 查询关联的 fileIds
 * - fileId 是稳定主键，因此文件移动/重命名后仍可正确打开，无需额外监听路径变化
 */
@Entity
@Table(name = "doc_file_link", indexes = {
        @Index(name = "idx_doc_file_link_project_key", columnList = "project_id,link_key", unique = true)
})
public class DocFileLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long userId;

    /**
     * 关联所在文档（WPS 文件 ID）
     */
    @Column(length = 256, nullable = false)
    private String docWpsFileId;

    /**
     * 超链接的 key（写入文档链接里），项目内唯一
     */
    @Column(length = 128, nullable = false)
    private String linkKey;

    /**
     * 选区文本（仅用于展示/排查）
     */
    @Column(length = 512)
    private String anchorText;

    /**
     * 最初创建时的 Range（仅用于排查；文档编辑后会变化，不作为定位依据）
     */
    @Column
    private Integer rangeStart;

    @Column
    private Integer rangeEnd;

    /**
     * 关联文件 ID 列表（JSON 数组），例如：[1,2,3]
     */
    @Column(columnDefinition = "TEXT")
    private String fileIdsJson;

    private LocalDateTime createdAt;
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getDocWpsFileId() {
        return docWpsFileId;
    }

    public void setDocWpsFileId(String docWpsFileId) {
        this.docWpsFileId = docWpsFileId;
    }

    public String getLinkKey() {
        return linkKey;
    }

    public void setLinkKey(String linkKey) {
        this.linkKey = linkKey;
    }

    public String getAnchorText() {
        return anchorText;
    }

    public void setAnchorText(String anchorText) {
        this.anchorText = anchorText;
    }

    public Integer getRangeStart() {
        return rangeStart;
    }

    public void setRangeStart(Integer rangeStart) {
        this.rangeStart = rangeStart;
    }

    public Integer getRangeEnd() {
        return rangeEnd;
    }

    public void setRangeEnd(Integer rangeEnd) {
        this.rangeEnd = rangeEnd;
    }

    public String getFileIdsJson() {
        return fileIdsJson;
    }

    public void setFileIdsJson(String fileIdsJson) {
        this.fileIdsJson = fileIdsJson;
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
        DocFileLink that = (DocFileLink) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
