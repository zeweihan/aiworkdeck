// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * 日历/任务系统的任务实体（dev-board #49）。
 *
 * 硬删除，无软删字段——与 ProjectFile 不同，任务没有回收站需求。
 * 文件被删除时任务保留：fileId 悬空（或指向已软删的 ProjectFile）时服务层按
 * fileName=null 处理展示，不联动删任务。
 *
 * 样板对齐 ProjectProfileField：手写 getter/setter，equals/hashCode 只比 id，
 * 不用 Lombok @Data。
 */
@Entity
@Table(
        name = "project_task",
        indexes = {
                @Index(name = "idx_project_task_project", columnList = "project_id"),
                @Index(name = "idx_project_task_project_due", columnList = "project_id, due_date")
        }
)
public class ProjectTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID，创建时生成，对齐 ProjectFile.uid 惯例，供未来云同步识别身份 */
    @Column(length = 36, nullable = false)
    private String uid;

    /** 所属项目 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /**
     * 锚定文件（ProjectFile.id）；项目级事件为 null。
     * dev-board#895 起关联文件改由 project_task_file 表承载多个，本列继续维护为「关联集合里的第一个」，
     * 供 FileTree 右键、旧客户端与 fileId 过滤向后兼容。
     */
    @Column(name = "file_id")
    private Long fileId;

    /** 事项标题 */
    @Column(length = 500, nullable = false)
    private String title;

    /** 截止日 */
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** 具体时刻（如开庭 09:30），null=全天 */
    @Column(name = "due_time")
    private LocalTime dueTime;

    /** "OPEN" / "DONE"，大写——前端 TaskSchedule.vue 已按大写渲染 */
    @Column(length = 20, nullable = false)
    private String status;

    /** "user" / "ai"（AI 建议的任务标 ai，界面可区分） */
    @Column(length = 20, nullable = false)
    private String source;

    /** 创建者 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 事项类型（dev-board#895）：DEADLINE 截止日 / HEARING 开庭 / MEETING 会议 / TODO 待办 / OTHER 其他。
     * 可空——加列前的旧行为 null，服务层按 DEADLINE 解释。
     */
    @Column(length = 20)
    private String type;

    /** 优先级：NORMAL / HIGH。可空，null 按 NORMAL 解释。 */
    @Column(length = 10)
    private String priority;

    /** 备注纯文本，可含「@名字」；结构化关系落 assigneeId 与关联文件表，不从文字里解析。 */
    @Column(length = 4000)
    private String notes;

    /** 负责人 userId，必须是项目成员或 owner；null=未指派。 */
    @Column(name = "assignee_id")
    private Long assigneeId;

    /** 提前多少分钟提醒；null=不提醒。全天事项以当天 09:00 为基准（前端计算，服务端只存）。 */
    @Column(name = "remind_before")
    private Integer remindBefore;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public LocalTime getDueTime() {
        return dueTime;
    }

    public void setDueTime(LocalTime dueTime) {
        this.dueTime = dueTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    public Integer getRemindBefore() {
        return remindBefore;
    }

    public void setRemindBefore(Integer remindBefore) {
        this.remindBefore = remindBefore;
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
        ProjectTask that = (ProjectTask) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
