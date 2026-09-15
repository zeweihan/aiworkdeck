// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.AgentTodoList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentTodoListRepository extends JpaRepository<AgentTodoList, Long> {

    Optional<AgentTodoList> findByConversationId(String conversationId);

    /**
     * 定时清理用：捞出冷掉的清单。刻意不用 deleteByUpdatedAtBefore 直接删——
     * 清理时还要把对应会话的内存条目一起摘掉，需要 conversationId。
     */
    List<AgentTodoList> findByUpdatedAtBefore(LocalDateTime cutoff);
}
