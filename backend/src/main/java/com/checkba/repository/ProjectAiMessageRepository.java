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
     * 上下文组装专用的历史投影（dev-board#811 K31，审查 C-12）。
     *
     * <p>{@code ContextAssemblerService} 每一轮都要把整条会话读一遍，而它只用到 role 与 content
     * 两个字段（契约 D：模型永远只看 content）。走 {@code findByConversationIdOrderByCreatedAtAsc}
     * 会把 displayContent / conversationTitle / clientRequestId / sourceChannel 一起拖回来，
     * 再由服务层顺手补一次附件查询——三样东西都不参与上下文组装，而这一整段就卡在用户等待
     * 首 token 的关键路径上。
     *
     * <p><b>刻意不加行数上限。</b> 看上去「只取最近 N 条」更省，但 {@code ContextCompressor} 的
     * 第二到第五层（去冗余 / 压工具结果 / 摘要旧消息 / 激进压缩）读的是整条历史，砍掉前面的行
     * 会静默改变摘要内容——省下的那点读取远不值得让模型「忘掉」会话开头。要做行数上限，
     * 得先把压缩器改成按游标回溯，那是另一张卡。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT new com.checkba.repository.ProjectAiMessageRepository$HistoryLine(m.role, m.content) " +
            "FROM ProjectAiMessage m WHERE m.conversationId = :conversationId ORDER BY m.createdAt ASC, m.id ASC")
    List<HistoryLine> findHistoryForAssembly(@org.springframework.data.repository.query.Param("conversationId") String conversationId);

    /** 上下文组装要的两列。 */
    record HistoryLine(String role, String content) {
    }

    /**
     * 历史分页（dev-board#811 K31，审查 C-12）：取 id 小于游标的最近 limit 条。
     *
     * <p>倒序取、调用方反转。按 id 而不是 createdAt 走游标：同一轮里插入的几条消息
     * createdAt 可能同毫秒（MySQL 还会按秒截断），用时间戳当游标会永久丢条——与项目级会话
     * 列表那条复合游标同一个教训，只是这里同一会话内 id 单调，单列就够。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT m FROM ProjectAiMessage m WHERE m.conversationId = :conversationId " +
            "AND (:before IS NULL OR m.id < :before) ORDER BY m.id DESC")
    List<ProjectAiMessage> findPageBefore(@org.springframework.data.repository.query.Param("conversationId") String conversationId,
                                          @org.springframework.data.repository.query.Param("before") Long before,
                                          org.springframework.data.domain.Pageable pageable);

    /**
     * 获取会话列表，包含 conversationTitle 和用户第一条消息
     * Returns: [conversationId, updatedAt, lastContent, conversationTitle, firstUserMessage, sourceChannel,
     *           pinned, parentConversationId]
     * sourceChannel 取首条消息的（镜像导入的会话在首条上带 office-word 等值，dev-board#298）。
     * pinned 同理取「首条非空值」（与 conversationTitle 同一存储位，dev-board#796）；
     * 置顶排序在 Java 层做——这里再套一层同样的标量子查询只为排序不划算。
     * parentConversationId 同样取首条（会话级元数据一律挂首行，本仓没有 ai_conversation 表），
     * 「从此分叉」的产物才非空，前端据此渲染「分支自 …」角标（dev-board#779 K18）。
     * <b>加列只许追加在尾部</b>：服务层按下标取值。
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT m.conversationId, MAX(m.createdAt), " +
        "(SELECT m2.content FROM ProjectAiMessage m2 WHERE m2.conversationId = m.conversationId ORDER BY m2.createdAt DESC LIMIT 1), " +
        "(SELECT m3.conversationTitle FROM ProjectAiMessage m3 WHERE m3.conversationId = m.conversationId AND m3.conversationTitle IS NOT NULL ORDER BY m3.createdAt ASC LIMIT 1), " +
        "(SELECT m4.content FROM ProjectAiMessage m4 WHERE m4.conversationId = m.conversationId AND m4.role = 'USER' ORDER BY m4.createdAt ASC LIMIT 1), " +
        "(SELECT m6.sourceChannel FROM ProjectAiMessage m6 WHERE m6.conversationId = m.conversationId ORDER BY m6.createdAt ASC LIMIT 1), " +
        "(SELECT m7.conversationPinned FROM ProjectAiMessage m7 WHERE m7.conversationId = m.conversationId AND m7.conversationPinned IS NOT NULL ORDER BY m7.createdAt ASC LIMIT 1), " +
        "(SELECT m8.parentConversationId FROM ProjectAiMessage m8 WHERE m8.conversationId = m.conversationId ORDER BY m8.createdAt ASC LIMIT 1) " +
        "FROM ProjectAiMessage m WHERE m.projectId = :projectId AND m.userId = :userId " +
        "GROUP BY m.conversationId ORDER BY MAX(m.createdAt) DESC")
    List<Object[]> findConversationSummaries(@org.springframework.data.repository.query.Param("projectId") Long projectId, @org.springframework.data.repository.query.Param("userId") Long userId);

    /**
     * 一批会话的标题素材（dev-board#779 K18）：给「分支自 &lt;父标题&gt;」角标解析父会话的标题。
     *
     * <p>为什么要单独一条：父会话不一定在当前这页列表里（可能是别人发起的、可能已经滚出视野），
     * 而逐条 findFirstByConversationId 是 N+1。这条一次把这一页用到的父会话全查回来。
     * 空集合别调它——JPQL 的 {@code IN ()} 在多数方言上是语法错误，调用方负责先判空。
     *
     * Returns: [conversationId, conversationTitle（首个非空）, firstUserMessage]
     * 两列都给是为了与 forkConversation 取标题同口径：storedTitle 优先，没有就用用户第一问
     * （取最后一条通常是助手整段回答，截断后连后缀都看不见）。
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT m.conversationId, " +
        "(SELECT m2.conversationTitle FROM ProjectAiMessage m2 WHERE m2.conversationId = m.conversationId AND m2.conversationTitle IS NOT NULL ORDER BY m2.createdAt ASC LIMIT 1), " +
        "(SELECT m3.content FROM ProjectAiMessage m3 WHERE m3.conversationId = m.conversationId AND m3.role = 'USER' ORDER BY m3.createdAt ASC LIMIT 1) " +
        "FROM ProjectAiMessage m WHERE m.conversationId IN :conversationIds GROUP BY m.conversationId")
    List<Object[]> findConversationTitleCandidates(
            @org.springframework.data.repository.query.Param("conversationIds") java.util.Collection<String> conversationIds);

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


