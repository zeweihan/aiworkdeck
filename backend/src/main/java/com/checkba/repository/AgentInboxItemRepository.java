// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.AgentInboxItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentInboxItemRepository extends JpaRepository<AgentInboxItem, String> {
    @Query("select i.conversationId from AgentInboxItem i where i.id = :id")
    Optional<String> findConversationIdById(@Param("id") String id);
    List<AgentInboxItem> findByConversationIdOrderByPositionAscCreatedAtAsc(String conversationId);
    List<AgentInboxItem> findByConversationIdAndStateOrderByPositionAscCreatedAtAsc(
            String conversationId, String state);
    Optional<AgentInboxItem> findByConversationIdAndUserIdAndClientRequestId(
            String conversationId, Long userId, String clientRequestId);
    List<AgentInboxItem> findByRunIdAndState(String runId, String state);
}
