package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目内 AI 对话消息，用于后续做历史聊天记录 / 上下文管理。
 */
@Entity
@Table(name = "project_ai_message")
public class ProjectAiMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 项目 ID
     */
    @Column(nullable = false)
    private Long projectId;

    /**
     * 用户 ID
     */
    @Column
    private Long userId;

    /**
     * 消息角色：USER / ASSISTANT
     */
    @Column(length = 16, nullable = false)
    private String role;

    /**
     * 消息内容（支持 markdown）
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 可选：给用户看的正文（「发送内容 ≠ 显示内容」通道）。空 = 与本字段不存在时完全一致。
     *
     * <p><b>语义红线：模型永远只看 {@link #content}，用户看本字段、为空则回退 content。</b>
     * 上下文组装（ContextAssemblerService 的历史栈）一律读 content，一个字都不许改成读本字段——
     * 否则模型会丢掉它真正需要的细节：计划审批卡回喂的「已修订 N 处 + 修订版全文」、
     * PPT 生成结果里的 fileId 与 PPTX 服务项目 ID。
     *
     * <p>由来：点一个选项/按钮时，用户气泡里不该出现一整句代拟的机器口吻文字
     * （病灶是计划审批卡把「我已修订计划（共 N 处改动…）」当用户消息发出去）。
     */
    @Column(columnDefinition = "TEXT")
    private String displayContent;

    /**
     * 关联的会话分组 ID（预留，便于以后做多会话）
     */
    @Column(length = 64)
    private String conversationId;

    /**
     * 对话的标题（由 LLM 生成，基于第一条消息）
     */
    @Column(length = 100)
    private String conversationTitle;

    @Column
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getDisplayContent() { return displayContent; }
    public void setDisplayContent(String displayContent) { this.displayContent = displayContent; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getConversationTitle() { return conversationTitle; }
    public void setConversationTitle(String conversationTitle) { this.conversationTitle = conversationTitle; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectAiMessage that = (ProjectAiMessage) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}


