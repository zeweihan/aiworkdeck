package com.checkba.repository;

import com.checkba.model.entity.ProjectAiMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectAiMessageRepository extends JpaRepository<ProjectAiMessage, Long> {

    List<ProjectAiMessage> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    List<ProjectAiMessage> findByProjectIdAndUserIdOrderByCreatedAtAsc(Long projectId, Long userId);

    List<ProjectAiMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    /**
     * 获取会话列表，包含 conversationTitle 和用户第一条消息
     * Returns: [conversationId, updatedAt, lastContent, conversationTitle, firstUserMessage]
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT m.conversationId, MAX(m.createdAt), " +
        "(SELECT m2.content FROM ProjectAiMessage m2 WHERE m2.conversationId = m.conversationId ORDER BY m2.createdAt DESC LIMIT 1), " +
        "(SELECT m3.conversationTitle FROM ProjectAiMessage m3 WHERE m3.conversationId = m.conversationId AND m3.conversationTitle IS NOT NULL ORDER BY m3.createdAt ASC LIMIT 1), " +
        "(SELECT m4.content FROM ProjectAiMessage m4 WHERE m4.conversationId = m.conversationId AND m4.role = 'USER' ORDER BY m4.createdAt ASC LIMIT 1) " +
        "FROM ProjectAiMessage m WHERE m.projectId = :projectId AND m.userId = :userId " +
        "GROUP BY m.conversationId ORDER BY MAX(m.createdAt) DESC")
    List<Object[]> findConversationSummaries(@org.springframework.data.repository.query.Param("projectId") Long projectId, @org.springframework.data.repository.query.Param("userId") Long userId);

    void deleteByConversationIdAndCreatedAtAfter(String conversationId, java.time.LocalDateTime timestamp);
    
    /**
     * 根据 conversationId 查找第一条消息
     */
    @org.springframework.data.jpa.repository.Query("SELECT m FROM ProjectAiMessage m WHERE m.conversationId = :conversationId ORDER BY m.createdAt ASC LIMIT 1")
    java.util.Optional<ProjectAiMessage> findFirstByConversationId(@org.springframework.data.repository.query.Param("conversationId") String conversationId);
}


