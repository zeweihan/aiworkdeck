// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 腾讯会议定时同步配置实体：按用户存储自动同步开关、同步周期、范围与过滤词。
 */
@Data
@Entity
@Table(name = "tencent_meeting_sync_config", indexes = {
        @Index(name = "idx_tmeet_cfg_user", columnList = "user_id", unique = true)
})
public class TencentMeetingSyncConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 ID（每个用户独立维护一套同步配置） */
    @Column(nullable = false, unique = true)
    private Long userId;

    /** 是否开启后台定时自动同步 */
    @Column(nullable = false)
    private Boolean autoSync = true;

    /** 自动同步周期（分钟，默认 60 分钟） */
    @Column(nullable = false)
    private Integer syncIntervalMinutes = 60;

    /** 同步历史时间范围（天，默认最近 7 天） */
    @Column(nullable = false)
    private Integer syncDays = 7;

    /** 排除主题关键词（逗号分隔，如 "面试,招聘,背调"） */
    @Column(length = 1024)
    private String excludeKeywords;

    /** 上次同步开始/完成时间 */
    private LocalDateTime lastSyncAt;

    /** 上次同步状态：SUCCESS / FAILED / RUNNING */
    @Column(length = 32)
    private String lastSyncStatus;

    /** 上次同步结果摘要或错误信息 */
    @Column(length = 1024)
    private String lastSyncMessage;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
