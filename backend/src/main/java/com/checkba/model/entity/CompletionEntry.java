// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 本地学习词库。项目资料与个人输入分开存放，绝不借用用户变量。 */
@Entity
@Table(name = "completion_entry", uniqueConstraints = {
        @UniqueConstraint(name = "uk_completion_scope_text", columnNames = {"scope_key", "text"})
}, indexes = {
        @Index(name = "idx_completion_scope_recent", columnList = "scope_key,last_used_at,id")
})
@Getter
@Setter
public class CompletionEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** p:<projectId> 或 u:<userId>；只由鉴权后的服务端构造。 */
    @Column(name = "scope_key", nullable = false, length = 64)
    private String scopeKey;

    @Column(nullable = false, length = 160)
    private String text;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false)
    private long uses;

    /** 用户明确查询成功后保存的 EntityView JSON；候选列表不下发正文。 */
    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;

    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt;
}
