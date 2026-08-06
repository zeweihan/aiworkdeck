package com.checkba.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 一个记忆仓库（repoKey）与远端 Git 仓库的绑定（自填 remote 模式，spec Phase A 第 7 条）。
 * url 是任意标准 Git remote（官方 GitHttpController 的 /git/{key}.git，或律所自建
 * gitea/gitlab 等）；凭据本地保存，只用于 fetch/push。
 * pendingUpload：有一次推送没成（离线/被拒后重试仍被拒），等下一轮同步再推。
 */
@Entity
@Table(name = "memory_remote", indexes = {
    @Index(name = "idx_memory_remote_key", columnList = "repo_key")
})
public class MemoryRemote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 本机领域标识：user-{userId}-memory / project-{projectId}-memory。 */
    @Column(name = "repo_key", nullable = false, length = 64, unique = true)
    private String repoKey;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "username", length = 100)
    private String username;

    /** 远端凭据（设备令牌或 Git 服务的访问令牌），本地保存。 */
    @Column(name = "secret", length = 300)
    private String secret;

    @Column(name = "pending_upload")
    private Boolean pendingUpload = false;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRepoKey() { return repoKey; }
    public void setRepoKey(String repoKey) { this.repoKey = repoKey; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public Boolean getPendingUpload() { return pendingUpload; }
    public void setPendingUpload(Boolean pendingUpload) { this.pendingUpload = pendingUpload; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
