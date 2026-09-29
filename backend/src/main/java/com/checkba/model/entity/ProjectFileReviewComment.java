// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 计划审阅里的一条批注（dev-board#1022）。锚点 = 行号区间 + 引用原文；
 * 行号只是提示，文档改动后前端以 {@code quotedText} 重定位。
 *
 * <p>长文本列用 {@code columnDefinition = "TEXT"} 而不是 {@code @Lob}：PG 方言下 {@code @Lob String}
 * 走 oid 大对象访问会炸，LONGVARCHAR + TEXT 在 H2 与 PG 双方言通吃（同 ProjectFileTextCache / MeetingRecording 的约定）。
 */
@Entity
@Table(name = "project_file_review_comment", indexes = {
        @Index(name = "idx_file_review_comment_review", columnList = "review_id")
})
@Data
public class ProjectFileReviewComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "from_line")
    private Integer fromLine;

    @Column(name = "to_line")
    private Integer toLine;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "quoted_text", columnDefinition = "TEXT")
    private String quotedText;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
