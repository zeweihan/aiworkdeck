// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计划审阅里的一条批注（dev-board#1022）。锚点 = 行号区间 + 引用原文；
 * 行号只是提示，文档改动后前端以 {@code quotedText} 重定位。
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

    @Lob
    @Column(name = "quoted_text")
    private String quotedText;

    @Lob
    @Column(name = "body")
    private String body;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
