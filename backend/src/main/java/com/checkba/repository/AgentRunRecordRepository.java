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
     * 概览页统计条的「后台 AI 任务」：按项目取最近更新的 5 条运行记录。
     * 刻意读表不读 AgentRunStateService 的内存 Map——那份状态进程重启即清零，
     * 概览页把历史铺开时会整片显示无状态。Top5 直接在 SQL 里限量：这张表在云后端上是
     * 全租户共库表，先前无索引 + 无上限查询按 project_id 是全表扫描 + filesort
     * （见 idx_agent_run_project_updated）。
     */
    List<AgentRunRecord> findTop5ByProjectIdOrderByUpdatedAtDesc(Long projectId);

    /** 概览页会话列表批量取运行状态：一次查完，避免每个会话一次 findByConversationId 的 N+1。 */
    List<AgentRunRecord> findByConversationIdIn(java.util.Collection<String> conversationIds);
}
