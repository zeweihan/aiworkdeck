// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * 系统级配置项（键值对），用于后台管理可编辑的配置。
 *
 * 说明：
 * - key 建议使用类似 external.qichacha.key / ai.systemPrompt / ai.activeProvider 的形式
 * - value 统一使用字符串存储，必要时前后端约定为 JSON 再解析
 *
 * 注意：
 * - MySQL 中 KEY 是保留字，这里物理列名使用 config_key，避免 SQL 语法冲突
 */
@Entity
@Table(name = "system_setting")
public class SystemSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 配置键（全局唯一）
     */
    @Column(name = "config_key", length = 128, nullable = false, unique = true)
    private String key;

    /**
     * 配置值（统一为字符串，必要时存 JSON）
     */
    @Column(columnDefinition = "TEXT")
    private String value;

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SystemSetting that = (SystemSetting) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return "SystemSetting{" +
                "id=" + id +
                ", key='" + key + '\'' +
                '}';
    }
}
