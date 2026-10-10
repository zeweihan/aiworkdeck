// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 试用计量 v0.1.1 · 每用户一行的试用余额（规格 §3.1 {@code trial_balance}）。
 *
 * <p>口径：14 天或 80 次 AI 调用，先到为准（{@code policy = min_of_days_or_calls}）；
 * 14 天时钟自 {@code trial_grant} 起算（= {@link #trialStartedAt}），与首次 AI 调用无关。
 *
 * <p>本 spike 只落存储与只读余额：{@link #callsUsed} 目前没有任何扣次路径会改它
 * （扣次走后续 {@code ai_turn_ledger}，仅 completed + 可展示结果计 1 次）。
 * 与插件试用码 {@code TrialCodeVerifier} 无关，勿混用。
 *
 * <p>建表走全仓同一套 {@code spring.jpa.hibernate.ddl-auto=update}（本仓无 Flyway/Liquibase）。
 */
@Entity
@Table(name = "trial_balance",
        uniqueConstraints = @UniqueConstraint(name = "uk_trial_balance_user", columnNames = {"user_id"}))
public class TrialBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 账户站缓存归属；旧行为空，不能作为任何账户的展示兜底。 */
    @Column(length = 12)
    private String accountFingerprint;

    /** 账户站最后确认的完整余额；离线展示不按本机时间重新推算。 */
    @Column(columnDefinition = "TEXT")
    private String accountSnapshot;

    /** = trial_grant 时刻 */
    @Column(nullable = false)
    private Instant trialStartedAt;

    @Column(nullable = false)
    private Instant trialEndsAt;

    @Column(nullable = false)
    private Integer callsQuota;

    @Column(nullable = false)
    private Integer callsUsed = 0;

    /** 落库状态：active / exhausted_calls / expired_days / converted（none = 无行，不落库） */
    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false, length = 32)
    private String policy;

    /** cn / intl（站点），可空 */
    @Column(length = 8)
    private String region;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAccountFingerprint() { return accountFingerprint; }
    public void setAccountFingerprint(String accountFingerprint) { this.accountFingerprint = accountFingerprint; }
    public String getAccountSnapshot() { return accountSnapshot; }
    public void setAccountSnapshot(String accountSnapshot) { this.accountSnapshot = accountSnapshot; }
    public Instant getTrialStartedAt() { return trialStartedAt; }
    public void setTrialStartedAt(Instant trialStartedAt) { this.trialStartedAt = trialStartedAt; }
    public Instant getTrialEndsAt() { return trialEndsAt; }
    public void setTrialEndsAt(Instant trialEndsAt) { this.trialEndsAt = trialEndsAt; }
    public Integer getCallsQuota() { return callsQuota; }
    public void setCallsQuota(Integer callsQuota) { this.callsQuota = callsQuota; }
    public Integer getCallsUsed() { return callsUsed; }
    public void setCallsUsed(Integer callsUsed) { this.callsUsed = callsUsed; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
