// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.TokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TokenUsageRepository extends JpaRepository<TokenUsage, Long> {
    List<TokenUsage> findByUserId(Long userId);
    List<TokenUsage> findByProjectId(Long projectId);
    List<TokenUsage> findByConversationId(String conversationId);
    List<TokenUsage> findByCreatedAtAfter(java.time.LocalDateTime after);
}
