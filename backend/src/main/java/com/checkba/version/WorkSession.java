package com.checkba.version;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 一次工作。律师第一次改动任何东西时隐式创建，结束时合并回主线。
 * 分支名对用户不可见——界面上只有「本次工作」。
 */
@Entity
@Table(name = "work_session")
public class WorkSession {

    public enum Status { ACTIVE, MERGED, DISCARDED }

    /** WORK = 普通工作段（30 分钟空闲自动结束、可自动合并）；DRAFT = 另起一稿的长命分支，不受二者管辖。 */
    public enum SessionType { WORK, DRAFT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 128)
    private String branchName;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /** 结束时律师给这段工作起的名字；未命名则由服务端生成。 */
    @Column(length = 256)
    private String title;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, columnDefinition = "varchar(8) default 'WORK'")
    private SessionType sessionType = SessionType.WORK;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public SessionType getSessionType() { return sessionType; }
    public void setSessionType(SessionType sessionType) { this.sessionType = sessionType; }
}
