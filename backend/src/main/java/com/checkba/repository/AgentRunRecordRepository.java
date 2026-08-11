package com.checkba.repository;

import com.checkba.model.entity.AgentRunRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentRunRecordRepository extends JpaRepository<AgentRunRecord, Long> {

    Optional<AgentRunRecord> findByConversationId(String conversationId);

    /** 启动回收用：捞出上次进程没跑完就被杀掉的会话。 */
    List<AgentRunRecord> findByStatus(String status);

    /**
     * 概览页统计条的「后台 AI 任务」：按项目取运行记录，最近更新的在前。
     * 刻意读表不读 AgentRunStateService 的内存 Map——那份状态进程重启即清零，
     * 概览页把历史铺开时会整片显示无状态。服务层再 limit，不在 SQL 里限条数。
     */
    List<AgentRunRecord> findByProjectIdOrderByUpdatedAtDesc(Long projectId);

    /** 概览页会话列表批量取运行状态：一次查完，避免每个会话一次 findByConversationId 的 N+1。 */
    List<AgentRunRecord> findByConversationIdIn(java.util.Collection<String> conversationIds);
}
