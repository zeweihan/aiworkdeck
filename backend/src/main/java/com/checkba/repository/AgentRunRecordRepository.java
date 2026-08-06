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
}
