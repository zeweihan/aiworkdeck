package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股东大会核查会话：一家公司一次股东会的见证核查。
 * 关联的材料（通知/决议/投票结果/模板）均为项目文件树里的 ProjectFile 引用；
 * 「开始核查」时会在文件树建底稿夹并把材料复制进去。
 */
@Data
@Entity
@Table(name = "shareholder_meeting_check")
public class ShareholderMeetingCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    /** 公司名称（简称或全称） */
    @Column(length = 256, nullable = false)
    private String companyName;

    /** 股票代码（6 位，可空：非上市/未填） */
    @Column(length = 16)
    private String stockCode;

    /** 届次名称，如「2026年第一次临时股东会」 */
    @Column(length = 256, nullable = false)
    private String meetingName;

    /** 股东会召开日期 */
    private LocalDate meetingDate;

    /**
     * 状态：DRAFT（建档中）→ READY（已开始核查，底稿夹就绪）→ RUNNING（AI 执行中）→ DONE（完成）
     * RUNNING/DONE 由前端在发送与会话结束时回写。
     */
    @Column(length = 32, nullable = false)
    private String status = "DRAFT";

    /** 股东大会通知 文件 ID */
    private Long noticeFileId;

    /** 董事会决议公告 文件 ID */
    private Long resolutionFileId;

    /** 投票结果文件 ID 列表（JSON 数组，如 "[12,34]"） */
    @Column(length = 1024)
    private String voteResultFileIds;

    /** 本所意见书模板/会前初稿 文件 ID（可选） */
    private Long templateFileId;

    /** 其他材料文件 ID 列表（JSON 数组） */
    @Column(length = 1024)
    private String otherFileIds;

    /** 核查执行绑定的 AI 会话 ID */
    @Column(length = 128)
    private String conversationId;

    /** 底稿夹（文件树文件夹）ID */
    private Long workpaperFolderId;

    @Column(nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
