// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目内 AI 对话消息，用于后续做历史聊天记录 / 上下文管理。
 */
@Entity
@Table(name = "project_ai_message", indexes = {
        // 概览页按 projectId 铺全项目会话（GROUP BY conversationId + ORDER BY MAX(createdAt)）。
        // 加索引前线上只有主键索引，这条查询是全表扫描。
        @Index(name = "idx_ai_message_project_created", columnList = "project_id, created_at"),
        // 会话正文回放与四个按 conversationId 的标量子查询。
        @Index(name = "idx_ai_message_conversation_created", columnList = "conversation_id, created_at")
})
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

    /**
     * 会话置顶（dev-board#796，可空 = 未置顶，与本字段不存在时行为一致）。
     *
     * <p>与 {@link #conversationTitle} 同一存储位——挂在会话的首条消息上，读取时按
     * 「首条非空值」取。会话列表把置顶项排在最前；除排序外不影响任何行为。
     */
    @Column(name = "conversation_pinned")
    private Boolean conversationPinned;

    /**
     * 来源通道（dev-board#298，可空 = 本地产生）。插件对话镜像导入的会话在首条消息上带
     * office-word / wps-excel 等值，桌面端据此渲染来源角标并把会话置为只读（续聊走 fork）。
     */
    @Column(name = "source_channel", length = 32)
    private String sourceChannel;

    /**
     * 来源侧消息 id（可空 = 本地产生）：镜像导入的幂等键，同一条云端消息重复投递时按
     * (conversationId, sourceMessageId) upsert 而不是插重复行。
     */
    @Column(name = "source_message_id")
    private Long sourceMessageId;

    /**
     * 客户端为这次提交生成的幂等键（可空 = 该客户端没送，或是本字段上线前的存量行）。
     *
     * <p><b>它是「回退到这条消息」的定位键。</b>数据库主键在这里用不了：POST /api/agent/chat
     * 的回执由 {@code AgentInboxService.receipt} 在控制器线程上拼出，而这一行要等
     * {@code AgentOrchestrator.handleUserMessageInScope} 在 turnExecutor 线程上跑起来才落库——
     * 回执序列化的那一刻它还不存在；{@code input_applied} 同理（在 claim 里发，且发起的那条
     * POST 还显式压掉了它）。clientRequestId 反过来是<b>发之前就有</b>的，前端气泡从出生
     * 那一刻起就握着它，刷新后经 GET /api/ai/history 原样回来，live 与 replay 两种气泡
     * 因此共用同一个定位键。
     *
     * <p>同一会话内唯一：一个 inbox 条目对应一条 USER 行，而 inbox 本身按
     * (conversationId, userId, clientRequestId) 做幂等。解析时仍按最早一条取，宁可保守。
     */
    @Column(name = "client_request_id", length = 64)
    private String clientRequestId;

    /**
     * 这条会话是从哪条会话分叉出来的（可空 = 不是分叉产物）。与 conversationTitle /
     * sourceChannel 同款：<b>只写在会话首条消息上</b>——本仓没有 ai_conversation 表，
     * 会话级元数据一律挂首行。
     *
     * <p>回退前的自动存档也是一次 fork，所以存档会话同样带这两个字段。本批 UI 不展示，
     * 先落库是为了「数据模型先于 UI」：K18「从此分叉」要靠它们把分支串成树。
     */
    @Column(name = "parent_conversation_id", length = 64)
    private String parentConversationId;

    /**
     * 分叉点：父会话里的哪条消息（project_ai_message.id）。回退存档时 = 被回退掉的那条。
     * 可空 = 整条复制、没有特定分叉点（dev-board#298 的镜像会话 fork 就是这种）。
     */
    @Column(name = "branch_from_message_id")
    private Long branchFromMessageId;

    @Column
    private LocalDateTime createdAt;

    /**
     * 本条消息带走的附件（dev-board#793 K14 ④）。<b>不是表列</b>——
     * {@code GET /api/ai/history} 直接序列化本实体，加一个 {@code @Transient} 字段
     * 是「只增不改」地把附件清单带给前端的最小改动（响应形状纯追加，老客户端忽略即可）。
     *
     * <p>由 {@code ProjectAiMessageService.listByConversationId} 批量填充（一次查完，不 N+1）；
     * 其它读路径不填，此时为 null——前端一律按「空即无附件」处理。
     *
     * <p>刻意不做成 {@code @OneToMany}：那会给每一条历史消息挂一个懒加载代理，
     * 而历史接口是没有事务边界的 REST 序列化，代理在序列化时才初始化正是本仓 OSIV 那一串坑的来源。
     */
    @Transient
    private java.util.List<ProjectAiMessageAttachment> attachments;

    public java.util.List<ProjectAiMessageAttachment> getAttachments() { return attachments; }
    public void setAttachments(java.util.List<ProjectAiMessageAttachment> attachments) { this.attachments = attachments; }

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
    public Boolean getConversationPinned() { return conversationPinned; }
    public void setConversationPinned(Boolean conversationPinned) { this.conversationPinned = conversationPinned; }
    public String getSourceChannel() { return sourceChannel; }
    public void setSourceChannel(String sourceChannel) { this.sourceChannel = sourceChannel; }
    public Long getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Long sourceMessageId) { this.sourceMessageId = sourceMessageId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public String getParentConversationId() { return parentConversationId; }
    public void setParentConversationId(String parentConversationId) { this.parentConversationId = parentConversationId; }
    public Long getBranchFromMessageId() { return branchFromMessageId; }
    public void setBranchFromMessageId(Long branchFromMessageId) { this.branchFromMessageId = branchFromMessageId; }
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


