// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 剪贴板记录（类似 Paste）：仅记录本应用可感知的剪贴板行为（如 OCR/粘贴事件/复制按钮）。
 */
@Entity
@Table(name = "clipboard_item")
public class ClipboardItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /**
     * TEXT / FILE
     */
    @Column(length = 16, nullable = false)
    private String type;

    /**
     * 文本内容（TEXT）
     */
    @Column(columnDefinition = "TEXT")
    private String text;

    /**
     * 扩展元信息（JSON 字符串，FILE 等场景）
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
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
        ClipboardItem that = (ClipboardItem) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ClipboardItem{" +
                "id=" + id +
                ", userId=" + userId +
                ", type='" + type + '\'' +
                '}';
    }
}
