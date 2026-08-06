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
