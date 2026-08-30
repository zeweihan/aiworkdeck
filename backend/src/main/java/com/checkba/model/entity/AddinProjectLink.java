package com.checkba.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 插件「归档绑定」：云端影子容器项目 ↔ 桌面项目 (deviceId, projectKey) 的映射
 * （spec：docs/superpowers/specs/2026-08-30-addin-project-binding-and-mirrors-design.md，dev-board#297）。
 *
 * <p>插件下拉里选中远程设备分组的桌面项目时，云端 find-or-create 一个影子项目承载会话与附件，
 * 本表记录映射。对话镜像（outbox）与文档镜像（media inbox）都按本表把云端产物路由回
 * (deviceId, projectKey)。deviceId/projectKey 与手机中转目录镜像同语义：
 * 服务端只存不解释，projectKey 是那台桌面机本地库的项目 id，跨机同号不同物。
 */
@Entity
@Table(name = "addin_project_link",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_id", "project_key"}),
        indexes = @Index(name = "idx_addin_link_cloud_project", columnList = "cloud_project_id"))
public class AddinProjectLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_id", length = 64, nullable = false)
    private String deviceId;

    /** 目标桌面项目在那台机器本地库里的 id（字符串透传，同 MobileProjectDir.key）。 */
    @Column(name = "project_key", length = 64, nullable = false)
    private String projectKey;

    /** 云端影子容器项目 id（本库 Project）。 */
    @Column(name = "cloud_project_id", nullable = false)
    private Long cloudProjectId;

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
    public Long getCloudProjectId() { return cloudProjectId; }
    public void setCloudProjectId(Long cloudProjectId) { this.cloudProjectId = cloudProjectId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
