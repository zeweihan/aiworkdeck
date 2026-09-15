// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 网页/截图摘录收藏（项目内 + 我的收藏）
 */
@Entity
@Table(name = "web_favorite")
public class WebFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /**
     * 关联项目（可为空：我的收藏里也可以有不绑定项目的）
     */
    @Column
    private Long projectId;

    /**
     * 来源 URL（可为空，例如纯截图）
     */
    @Column(columnDefinition = "TEXT")
    private String sourceUrl;

    /**
     * 标题（可为空）
     */
    @Column(length = 256)
    private String title;

    /**
     * 摘录文本（OCR 或复制的文本）
     */
    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * 截图存储路径（可为空）
     */
    @Column(length = 512)
    private String imagePath;

    /**
     * 扩展元信息（JSON 字符串）：
     * - 用于网核证据：抓取时间、页面 HTML 快照摘要、selector 等
     */
    @Column(columnDefinition = "TEXT")
    private String meta;

    @Column
    private LocalDateTime createdAt;
    
    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public String getMeta() {
        return meta;
    }

    public void setMeta(String meta) {
        this.meta = meta;
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
        WebFavorite that = (WebFavorite) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
