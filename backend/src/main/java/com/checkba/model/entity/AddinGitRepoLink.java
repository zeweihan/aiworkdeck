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
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 插件里关联的 GitHub / Gitee 仓库（dev-board#720，spec 2026-09-18 §7.2）：
 * 让不装桌面端的用户也有一个「权威源」可供 AI 参考。
 *
 * <p>关联挂在**云端项目 + 人**两个维度上：令牌是某个人的，只有他自己的会话读得到——
 * 同一个项目里的另一个成员看不见这一行，也就借不走这把令牌。
 *
 * <p>只读（D 决策）：AI 只从这些仓库读文件，从不提交、不推送。
 *
 * <p>{@code tokenEnc} 是 {@link com.checkba.service.addin.GitTokenCipher} 的密文；明文只在
 * 调用瞬间解密，不回显、不写日志。{@code tokenLast4} 只为在界面上认出「填的是哪一把」。
 */
@Entity
@Table(name = "addin_git_repo_link",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"user_id", "cloud_project_id", "provider", "owner", "repo"}),
        indexes = @Index(name = "idx_addin_git_link_user_project", columnList = "user_id,cloud_project_id"))
public class AddinGitRepoLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 云端项目 id（本库 Project）。 */
    @Column(name = "cloud_project_id", nullable = false)
    private Long cloudProjectId;

    /** github 或 gitee。 */
    @Column(nullable = false, length = 16)
    private String provider;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(nullable = false, length = 128)
    private String repo;

    /** 关联时解析好的具体分支（用户留空时是仓库的默认分支），读取时直接用，不再二次解析。 */
    @Column(nullable = false, length = 128)
    private String branch;

    /** 访问令牌密文；公开仓库可以不填令牌，这一列即为空。 */
    @Column(name = "token_enc", length = 512)
    private String tokenEnc;

    @Column(name = "token_last4", length = 8)
    private String tokenLast4;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 最近一次成功读取的时刻。 */
    @Column(name = "last_ok_at")
    private LocalDateTime lastOkAt;

    /** 最近一次失败的原因（令牌失效居多）。界面上常驻显示，不然用户只会看到 AI「找不到文件」。 */
    @Column(name = "last_error", length = 512)
    private String lastError;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getCloudProjectId() { return cloudProjectId; }
    public void setCloudProjectId(Long cloudProjectId) { this.cloudProjectId = cloudProjectId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getTokenEnc() { return tokenEnc; }
    public void setTokenEnc(String tokenEnc) { this.tokenEnc = tokenEnc; }
    public String getTokenLast4() { return tokenLast4; }
    public void setTokenLast4(String tokenLast4) { this.tokenLast4 = tokenLast4; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastOkAt() { return lastOkAt; }
    public void setLastOkAt(LocalDateTime lastOkAt) { this.lastOkAt = lastOkAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
