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

    /** 锚定文件（ProjectFile.id）；项目级事件为 null */
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
