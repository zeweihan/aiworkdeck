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
     * Returns: [conversationId, updatedAt, lastContent, conversationTitle, firstUserMessage, sourceChannel]
     * sourceChannel 取首条消息的（镜像导入的会话在首条上带 office-word 等值，dev-board#298）。
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT m.conversationId, MAX(m.createdAt), " +
        "(SELECT m2.content FROM ProjectAiMessage m2 WHERE m2.conversationId = m.conversationId ORDER BY m2.createdAt DESC LIMIT 1), " +
        "(SELECT m3.conversationTitle FROM ProjectAiMessage m3 WHERE m3.conversationId = m.conversationId AND m3.conversationTitle IS NOT NULL ORDER BY m3.createdAt ASC LIMIT 1), " +
        "(SELECT m4.content FROM ProjectAiMessage m4 WHERE m4.conversationId = m.conversationId AND m4.role = 'USER' ORDER BY m4.createdAt ASC LIMIT 1), " +
        "(SELECT m6.sourceChannel FROM ProjectAiMessage m6 WHERE m6.conversationId = m.conversationId ORDER BY m6.createdAt ASC LIMIT 1) " +
        "FROM ProjectAiMessage m WHERE m.projectId = :projectId AND m.userId = :userId " +
        "GROUP BY m.conversationId ORDER BY MAX(m.createdAt) DESC")
    List<Object[]> findConversationSummaries(@org.springframework.data.repository.query.Param("projectId") Long projectId, @org.springframework.data.repository.query.Param("userId") Long userId);

    /**
     * 项目级会话汇总（概览页用）：与上面的 findConversationSummaries 唯一的差别是
     * 去掉 userId 条件，改成「这个项目的全部会话」，并多回一列发起人 id。
     * 上面那条服务 /api/ai/conversations，一行都不改。
     *
     * 分页是游标不是 offset，且游标是 (MAX(createdAt), conversationId) 两维：
     * 只用时间一维时，两个会话最后活跃时间相同（同批导入 / 同毫秒落库 / MySQL 秒级截断）
     * 会在翻下一页时永久丢掉其中一条。beforeId 传 null 则第三个分支恒不成立，
     * 整条 HAVING 退化成严格小于（老行为，向后兼容）。
     *
     * limit 只能在 Java 层做 —— 这条 JPQL 有 4 个标量子查询 + GROUP BY + HAVING，
     * 套 Pageable 会逼出手写 countQuery 或改两段式。
     *
     * Returns: [conversationId, updatedAt, lastContent, conversationTitle, firstUserMessage, ownerUserId, sourceChannel]
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT m.conversationId, MAX(m.createdAt), " +
        "(SELECT m2.content FROM ProjectAiMessage m2 WHERE m2.conversationId = m.conversationId ORDER BY m2.createdAt DESC LIMIT 1), " +
        "(SELECT m3.conversationTitle FROM ProjectAiMessage m3 WHERE m3.conversationId = m.conversationId AND m3.conversationTitle IS NOT NULL ORDER BY m3.createdAt ASC LIMIT 1), " +
        "(SELECT m4.content FROM ProjectAiMessage m4 WHERE m4.conversationId = m.conversationId AND m4.role = 'USER' ORDER BY m4.createdAt ASC LIMIT 1), " +
        "(SELECT m5.userId FROM ProjectAiMessage m5 WHERE m5.conversationId = m.conversationId ORDER BY m5.createdAt ASC LIMIT 1), " +
        "(SELECT m6.sourceChannel FROM ProjectAiMessage m6 WHERE m6.conversationId = m.conversationId ORDER BY m6.createdAt ASC LIMIT 1) " +
        "FROM ProjectAiMessage m WHERE m.projectId = :projectId " +
        "GROUP BY m.conversationId " +
        "HAVING (:before IS NULL OR MAX(m.createdAt) < :before " +
        "        OR (MAX(m.createdAt) = :before AND m.conversationId < :beforeId)) " +
        "ORDER BY MAX(m.createdAt) DESC, m.conversationId DESC")
    List<Object[]> findProjectConversationSummaries(
            @org.springframework.data.repository.query.Param("projectId") Long projectId,
            @org.springframework.data.repository.query.Param("before") java.time.LocalDateTime before,
            @org.springframework.data.repository.query.Param("beforeId") String beforeId);

    void deleteByConversationIdAndCreatedAtAfter(String conversationId, java.time.LocalDateTime timestamp);

    void deleteByConversationId(String conversationId);
    
    /**
     * 根据 conversationId 查找第一条消息
     */
    @org.springframework.data.jpa.repository.Query("SELECT m FROM ProjectAiMessage m WHERE m.conversationId = :conversationId ORDER BY m.createdAt ASC LIMIT 1")
    java.util.Optional<ProjectAiMessage> findFirstByConversationId(@org.springframework.data.repository.query.Param("conversationId") String conversationId);

    /** 镜像导入的幂等查找（dev-board#298）。 */
    java.util.Optional<ProjectAiMessage> findByConversationIdAndSourceMessageId(String conversationId, Long sourceMessageId);

    /** 同会话当前最大 createdAt（镜像导入保序：新导入行的时间戳必须严格递增）。 */
    @org.springframework.data.jpa.repository.Query("SELECT MAX(m.createdAt) FROM ProjectAiMessage m WHERE m.conversationId = :conversationId")
    java.time.LocalDateTime maxCreatedAtByConversationId(@org.springframework.data.repository.query.Param("conversationId") String conversationId);
}


