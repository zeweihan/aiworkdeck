// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.AgentInboxItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentInboxItemRepository extends JpaRepository<AgentInboxItem, String> {
    List<AgentInboxItem> findByConversationIdOrderByPositionAscCreatedAtAsc(String conversationId);
    List<AgentInboxItem> findByConversationIdAndStateOrderByPositionAscCreatedAtAsc(
            String conversationId, String state);
    Optional<AgentInboxItem> findByConversationIdAndUserIdAndClientRequestId(
            String conversationId, Long userId, String clientRequestId);
    List<AgentInboxItem> findByRunIdAndState(String runId, String state);
}
