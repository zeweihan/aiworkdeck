// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.ProjectAiMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectAiMessageRepository extends JpaRepository<ProjectAiMessage, Long> {

    List<ProjectAiMessage> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    List<ProjectAiMessage> findByProjectIdAndUserIdOrderByCreatedAtAsc(Long projectId, Long userId);

    List<ProjectAiMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    /**
     * 会话消息条数（dev-board#729 ⑤）。
     *
     * <p>编排器的「这是不是首轮」判断此前是 {@code listByConversationId(...).size() <= 1}——
     * 把整条会话的全部消息（含每条几千到几万字符的正文与 executionLog）从库里读出来、
     * 映射成实体，只为了拿一个数字；而 {@code ContextAssemblerService} 紧接着还要再全量读一次。
     * 长会话里这是两次可观的往返，且都在用户等待首 token 的关键路径上。
     */
    long countByConversationId(String conversationId);

    /**
     * 获取会话列表，包含 conversationTitle 和用户第一条消息
     * Returns: [conversationId, updatedAt, lastContent, conversationTitle, firstUserMessage, sourceChannel, pinned]
     * sourceChannel 取首条消息的（镜像导入的会话在首条上带 office-word 等值，dev-board#298）。
     * pinned 同理取「首条非空值」（与 conversationTitle 同一存储位，dev-board#796）；
     * 置顶排序在 Java 层做——这里再套一层同样的标量子查询只为排序不划算。
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT m.conversationId, MAX(m.createdAt), " +
        "(SELECT m2.content FROM ProjectAiMessage m2 WHERE m2.conversationId = m.conversationId ORDER BY m2.createdAt DESC LIMIT 1), " +
        "(SELECT m3.conversationTitle FROM ProjectAiMessage m3 WHERE m3.conversationId = m.conversationId AND m3.conversationTitle IS NOT NULL ORDER BY m3.createdAt ASC LIMIT 1), " +
        "(SELECT m4.content FROM ProjectAiMessage m4 WHERE m4.conversationId = m.conversationId AND m4.role = 'USER' ORDER BY m4.createdAt ASC LIMIT 1), " +
        "(SELECT m6.sourceChannel FROM ProjectAiMessage m6 WHERE m6.conversationId = m.conversationId ORDER BY m6.createdAt ASC LIMIT 1), " +
        "(SELECT m7.conversationPinned FROM ProjectAiMessage m7 WHERE m7.conversationId = m.conversationId AND m7.conversationPinned IS NOT NULL ORDER BY m7.createdAt ASC LIMIT 1) " +
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

    /**
     * 回退（edit-and-resend）：删掉目标消息<b>及其之后</b>的全部消息。
     *
     * <p>判据是显示顺序 (createdAt, id) 的字典序，不是单独的 createdAt。老实现用
     * {@code deleteByConversationIdAndCreatedAtAfter}（严格大于）有两处毛病：目标自己不删
     * （与前端「回填输入框改了重发」的语义相反，库里会留下两条连着的 USER 行），
     * 以及与目标同刻的那条助手回复会幸存下来——同毫秒落库、MySQL 秒级截断都造得出这种行。
     * 只按 {@code id >=} 也不行：那假设 id 与时间同序，fork/镜像导入的行不保证。
     */
    // 批量 JPQL 删除绕过一级缓存：flushAutomatically 保证同一事务里先落地的写（回退前的
    // 存档就是一批 insert）已经发出去，clearAutomatically 保证删完之后没人从缓存里
    // 读到已经删掉的行（OSIV 下持久化上下文一直活到响应写完）。
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
        "DELETE FROM ProjectAiMessage m WHERE m.conversationId = :conversationId " +
        "AND (m.createdAt > :createdAt OR (m.createdAt = :createdAt AND m.id >= :messageId))")
    void deleteFromMessageOnwards(
            @org.springframework.data.repository.query.Param("conversationId") String conversationId,
            @org.springframework.data.repository.query.Param("createdAt") java.time.LocalDateTime createdAt,
            @org.springframework.data.repository.query.Param("messageId") Long messageId);

    /** 回退定位键（见 ProjectAiMessage#clientRequestId）：同会话内按客户端幂等键取最早一条。 */
    java.util.Optional<ProjectAiMessage> findFirstByConversationIdAndClientRequestIdOrderByCreatedAtAscIdAsc(
            String conversationId, String clientRequestId);

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


