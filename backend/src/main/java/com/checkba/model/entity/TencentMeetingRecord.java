// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 腾讯会议记录实体：保存从腾讯会议 CLI (tmeet) 同步的会议元数据、逐字稿与智能纪要。
 */
@Data
@Entity
@Table(name = "tencent_meeting_record", indexes = {
        @Index(name = "idx_tmeet_user_start", columnList = "userId, startTime"),
        @Index(name = "idx_tmeet_project", columnList = "projectId"),
        @Index(name = "idx_tmeet_record_file", columnList = "recordFileId")
})
public class TencentMeetingRecord {

    public static final String STATUS_SYNCED = "SYNCED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的 AI WorkDeck 项目 ID（可选，未关联时仅在用户空间展示） */
    private Long projectId;

    /** 所属系统用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 腾讯会议内部 ID（敏感字段，不对终端用户直接展示） */
    @Column(length = 64, nullable = false)
    private String meetingId;

    /** 会议号（公开展示给用户的会议码，如 379959971） */
    @Column(length = 64)
    private String meetingCode;

    /** 录制文件 ID（tmeet record_file_id，作为去重主键之一） */
    @Column(length = 64, nullable = false)
    private String recordFileId;

    /** 会议主题 */
    @Column(length = 256, nullable = false)
    private String subject;

    /** 会议类型（普通会议 / 周期性会议等） */
    @Column(length = 64)
    private String meetingType;

    /** 周期性会议的子会议 ID */
    @Column(length = 64)
    private String subMeetingId;

    /** 开会时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 会议时长字符串（如 21:05 或 01:39:09） */
    @Column(length = 32)
    private String duration;

    /** 发言人列表 JSON 数组：["张三", "李四"] */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(columnDefinition = "TEXT")
    private String speakersJson;

    /**
     * 逐字稿段落 JSON 数组：
     * [{"pid":"0","startTime":"01:23","endTime":"01:31","speaker":{"user_name":"张三","avatar_url":""},"text":"..."}]
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(columnDefinition = "TEXT")
    private String transcriptJson;

    /** 格式化后的智能纪要 Markdown 文本 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(columnDefinition = "TEXT")
    private String smartMinutesText;

    /** 腾讯会议返回的原始智能纪要 JSON 数据（包含 todos / chapters 等） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(columnDefinition = "TEXT")
    private String smartMinutesJson;

    /** 同步状态：SYNCED / FAILED */
    @Column(length = 32, nullable = false)
    private String status = STATUS_SYNCED;

    /** 同步完成时间 */
    private LocalDateTime syncedAt;

    /** 错误信息（若拉取逐字稿失败） */
    @Column(length = 1024)
    private String error;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
