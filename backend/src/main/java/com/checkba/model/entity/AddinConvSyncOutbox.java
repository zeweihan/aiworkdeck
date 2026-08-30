package com.checkba.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 插件对话镜像 outbox（dev-board#298）：绑定项目（addin_project_link 有映射的影子项目）里
 * 每条落库消息在此排队，等目标桌面机拉取导入后 ACK 删行；30 天 TTL 兜底。
 *
 * <p>刷新语义：同一条消息（sourceMessageId）被 upsert 更新时，旧 outbox 行删除、插入新行
 * （新 id）——桌面端按行 id 列表 ACK，取件与 ACK 之间发生的更新因落在新行上而存活。
 */
@Entity
@Table(name = "addin_conv_sync_outbox",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "source_message_id"}),
        indexes = @Index(name = "idx_conv_sync_device", columnList = "user_id, device_id"))
public class AddinConvSyncOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_id", length = 64, nullable = false)
    private String deviceId;

    @Column(name = "project_key", length = 64, nullable = false)
    private String projectKey;

    @Column(name = "conversation_id", length = 64, nullable = false)
    private String conversationId;

    /** 云端 project_ai_message.id：桌面端导入的幂等键。 */
    @Column(name = "source_message_id", nullable = false)
    private Long sourceMessageId;

    @Column(length = 16, nullable = false)
    private String role;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String displayContent;

    /** 来源通道：office-word / office-excel / office-powerpoint / wps-word / wps-excel / wps-powerpoint。 */
    @Column(name = "source_channel", length = 32)
    private String sourceChannel;

    /** 消息在云端的原始落库时间（桌面端导入时保序用）。 */
    @Column(name = "message_created_at")
    private LocalDateTime messageCreatedAt;

    @Column
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public Long getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Long sourceMessageId) { this.sourceMessageId = sourceMessageId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getDisplayContent() { return displayContent; }
    public void setDisplayContent(String displayContent) { this.displayContent = displayContent; }
    public String getSourceChannel() { return sourceChannel; }
    public void setSourceChannel(String sourceChannel) { this.sourceChannel = sourceChannel; }
    public LocalDateTime getMessageCreatedAt() { return messageCreatedAt; }
    public void setMessageCreatedAt(LocalDateTime messageCreatedAt) { this.messageCreatedAt = messageCreatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
