// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 计划审阅记录（dev-board#1022）：用户对一份项目文件（AI 产出的计划 md）进入审阅态时建一条，
 * 记下进入时的全文作为基线，前端据此画改动标记，「放弃修改」据此把文件写回。
 *
 * <p>一个文件同一时刻至多一条 {@code status=open} 的记录，由 {@code FileReviewService.open}
 * 的幂等语义保证（库里不加唯一约束：submitted / discarded 的历史行可以有多条）。
 */
@Entity
@Table(name = "project_file_review", indexes = {
        @Index(name = "idx_file_review_file", columnList = "file_id"),
        @Index(name = "idx_file_review_status", columnList = "file_id,status")
})
@Data
public class ProjectFileReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "conversation_id", length = 128)
    private String conversationId;

    @Column(name = "artifact_id", length = 128)
    private String artifactId;

    /** 进入审阅态那一刻的文件全文（UTF-8 文本）。 */
    // 不用 @Lob：PG 方言下 @Lob String 走 oid 大对象访问会炸，LONGVARCHAR + TEXT 双方言通吃（同 ProjectFileTextCache / MeetingRecording 的约定）
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "baseline_text", columnDefinition = "TEXT")
    private String baselineText;

    /** open | submitted | discarded */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
