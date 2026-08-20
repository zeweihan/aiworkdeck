package com.checkba.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 用户活动日志实体
 * 用于记录用户行为，统计工作时间
 */
@Entity
@Table(name = "user_activity_log")
public class UserActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 行为类型
     * - LOGIN: 登录
     * - OPEN_FILE: 打开文件
     * - CLOSE_FILE: 关闭文件
     * - PAGE_VIEW: 页面访问
     * - OPEN_URL: 打开网页
     * - CLOSE_URL: 关闭网页
     */
    @Column(name = "action_type", length = 64, nullable = false)
    private String actionType;

    /**
     * 目标 ID (projectId 或 fileId)
     * 可为空，取决于 actionType
     */
    @Column(name = "target_id")
    private Long targetId;
    
    /**
     * 目标名称 (文件名或项目名)
     * 可为空，取决于 actionType
     */
    @Column(name = "target_name", length = 512)
    private String targetName;

    /**
     * 结构化项目归属。可为空——老数据没有这一列，前端归类为「未关联项目」。
     */
    @Column(name = "project_id")
    private Long projectId;

    /**
     * 项目名，按 projectId 批量查 Project 表回填，不落库。
     */
    @Transient
    private String projectName;

    /**
     * 发生时间
     */
    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime timestamp;

    /**
     * 持续时间（**毫秒**）
     * 仅对 CLOSE_FILE / PAGE_VIEW 等结束事件有效，表示该次会话的持续时长。
     * 唯一写入方是前端 utils/activityTracker.js（:207 effectiveDuration 是
     * Date.now() 差值，:223 原样经 logActivity 传上来），后端不做任何单位换算。
     * 旧注释写「秒」，与实际差 1000 倍——读这个字段做统计前先看清楚。
     */
    @Column
    private Long duration;
    
    /**
     * 附加信息 JSON
     */
    @Column(columnDefinition = "TEXT")
    private String metaInfo;

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

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Long getDuration() {
        return duration;
    }

    public void setDuration(Long duration) {
        this.duration = duration;
    }

    public String getMetaInfo() {
        return metaInfo;
    }

    public void setMetaInfo(String metaInfo) {
        this.metaInfo = metaInfo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserActivityLog that = (UserActivityLog) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
