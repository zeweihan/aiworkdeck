package com.checkba.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户记忆实体
 * 存储用户级别的偏好和习惯
 */
@Entity
@Table(name = "user_memory")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true) // 仅用 id，避免遍历 JSON 集合字段/未持久化时不稳
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * 用户ID（唯一）
     */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /**
     * 用户偏好（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferences")
    private Map<String, String> preferences;

    /**
     * 常用表达（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "frequent_phrases")
    private List<String> frequentPhrases;

    /**
     * 工具使用统计（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_usage_stats")
    private Map<String, Integer> toolUsageStats;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

