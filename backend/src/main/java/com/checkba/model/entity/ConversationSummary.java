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
 * 对话摘要实体（Episode 情景记忆）
 * 存储压缩后的对话历史，用于中期记忆
 * 
 * 借鉴 EverMemOS 的 Episode 概念，增强为结构化情景记忆：
 * - 支持事件级摘要
 * - 记录参与者信息
 * - 包含时间线
 * - 支持情景分类
 */
@Entity
@Table(name = "conversation_summary")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true) // 仅用 id：字段型 equals/hashCode 会遍历 List/Map JSON 字段且未持久化时不稳
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * 对话ID（唯一）
     */
    @Column(name = "conversation_id", nullable = false, unique = true, length = 100)
    private String conversationId;

    /**
     * 项目ID
     */
    @Column(name = "project_id")
    private Long projectId;

    /**
     * 用户ID
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 对话摘要文本
     */
    @Column(name = "summary_text", columnDefinition = "TEXT", nullable = false)
    private String summaryText;

    /**
     * 关键点列表（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_points")
    private List<String> keyPoints;

    /**
     * 法律引用列表（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "legal_references")
    private List<String> legalReferences;

    /**
     * 提及的实体（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mentioned_entities")
    private List<String> mentionedEntities;

    /**
     * 待办事项（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_tasks")
    private List<String> pendingTasks;

    /**
     * 摘要的 Token 数量
     */
    @Column(name = "token_count")
    private Integer tokenCount;

    /**
     * 压缩前的消息数量
     */
    @Column(name = "message_count")
    private Integer messageCount;

    /**
     * 最后一条被压缩的消息ID
     */
    @Column(name = "last_message_id")
    private Long lastMessageId;

    // ==================== Episode 结构化字段（借鉴 EverMemOS）====================

    /**
     * 情景类型
     * discussion - 讨论/问答
     * decision - 决策过程
     * review - 核查/审查
     * research - 法律研究
     * drafting - 文书起草
     */
    @Column(name = "episode_type", length = 50)
    @Builder.Default
    private String episodeType = "discussion";

    /**
     * 事件列表（JSON格式）
     * 每个事件包含: timestamp, actor, action, content
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "events")
    private List<Map<String, Object>> events;

    /**
     * 参与者列表（JSON格式）
     * 包含用户和AI助手的标识
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "participants")
    private List<Map<String, String>> participants;

    /**
     * 时间线摘要（JSON格式）
     * 按时间顺序记录关键节点
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "timeline")
    private List<Map<String, Object>> timeline;

    /**
     * 情景主题/标题
     */
    @Column(name = "episode_title", length = 200)
    private String episodeTitle;

    /**
     * 情景结果/结论
     */
    @Column(name = "episode_outcome", columnDefinition = "TEXT")
    private String episodeOutcome;

    /**
     * 关联的 MemCell ID 列表
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "related_memcell_ids")
    private List<Long> relatedMemCellIds;

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

