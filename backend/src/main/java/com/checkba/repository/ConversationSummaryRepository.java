// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.ConversationSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 对话摘要仓库
 */
@Repository
public interface ConversationSummaryRepository extends JpaRepository<ConversationSummary, Long> {

    /**
     * 根据对话ID查找摘要
     */
    Optional<ConversationSummary> findByConversationId(String conversationId);

    /**
     * 根据项目ID查找所有摘要
     */
    List<ConversationSummary> findByProjectIdOrderByUpdatedAtDesc(Long projectId);

    /**
     * 根据用户ID查找所有摘要
     */
    List<ConversationSummary> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * 根据项目ID和用户ID查找摘要
     */
    List<ConversationSummary> findByProjectIdAndUserIdOrderByUpdatedAtDesc(Long projectId, Long userId);

    /**
     * 删除对话摘要
     */
    void deleteByConversationId(String conversationId);

    /**
     * 检查对话是否有摘要
     */
    boolean existsByConversationId(String conversationId);
}

