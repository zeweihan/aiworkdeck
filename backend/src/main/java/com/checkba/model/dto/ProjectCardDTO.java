package com.checkba.model.dto;

import java.time.LocalDateTime;
import java.util.Map;

public class ProjectCardDTO {
    private Long id;
    private String name;
    private String projectType;
    private String listedCompanyName;
    private String targetCompanyName;
    // We can omit bulky JSON fields if not needed for card display
    // private String listedCompanyInfoJson;
    // private String targetCompanyInfoJson;
    private Long userId; // Creator ID
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /**
     * 最近一次文件活动时间：项目下未删除文件的 MAX(updatedAt)，没有文件时回落到
     * {@link #updatedAt}。项目列表的「最近修改」列读这个。
     *
     * <p>为什么不直接用 {@code updatedAt}：那一列只在建项目与改项目名时写过，
     * 拿来当修改时间就是一个恒等于创建日期的假列。
     */
    private LocalDateTime lastActivityAt;
    /**
     * 项目档案（project_profile_field）里已填的字段，fieldKey → 值，**只含非空**。
     * 键就是 {@code ProjectProfileService.FIELD_KEYS}：
     * client / matterType / openedAt / nextStep / counterparty。
     *
     * <p>列表页把 {@code client} 当一等列，其余四项收在「详情」开关后面。
     * 没填过的键直接不出现——前端据此决定是回落到推断值（客户列）还是整条不渲染。
     *
     * <p>推断值绝不回写这里：档案字段的语义是「谁说的算」（写入即锁 source='user'，
     * AI 抽取只写 pending 永不覆盖），把猜的混进去会稀释掉那把锁。
     */
    private Map<String, String> profile;

    // Enhanced fields
    private String myRole; // OWNER, ADMIN, MEMBER, CLIENT...
    private Long managerId;
    private String managerName;
    private String managerAvatarUrl;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProjectType() {
        return projectType;
    }

    public void setProjectType(String projectType) {
        this.projectType = projectType;
    }

    public String getListedCompanyName() {
        return listedCompanyName;
    }

    public void setListedCompanyName(String listedCompanyName) {
        this.listedCompanyName = listedCompanyName;
    }

    public String getTargetCompanyName() {
        return targetCompanyName;
    }

    public void setTargetCompanyName(String targetCompanyName) {
        this.targetCompanyName = targetCompanyName;
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

    public LocalDateTime getLastActivityAt() {
        return lastActivityAt;
    }

    public void setLastActivityAt(LocalDateTime lastActivityAt) {
        this.lastActivityAt = lastActivityAt;
    }

    public Map<String, String> getProfile() {
        return profile;
    }

    public void setProfile(Map<String, String> profile) {
        this.profile = profile;
    }

    public String getMyRole() {
        return myRole;
    }

    public void setMyRole(String myRole) {
        this.myRole = myRole;
    }

    public Long getManagerId() {
        return managerId;
    }

    public void setManagerId(Long managerId) {
        this.managerId = managerId;
    }

    public String getManagerName() {
        return managerName;
    }

    public void setManagerName(String managerName) {
        this.managerName = managerName;
    }

    public String getManagerAvatarUrl() {
        return managerAvatarUrl;
    }

    public void setManagerAvatarUrl(String managerAvatarUrl) {
        this.managerAvatarUrl = managerAvatarUrl;
    }
}
