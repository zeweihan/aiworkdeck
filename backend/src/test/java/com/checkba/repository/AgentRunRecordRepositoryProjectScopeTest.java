package com.checkba.repository;

import com.checkba.model.entity.AgentRunRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 概览页统计条的「后台 AI 任务」取自 agent_run_record 表（不是 AgentRunStateService
 * 的内存 Map——进程重启后内存态全是 null）。这里钉死按项目过滤 + 按 updatedAt 倒序。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-run-project-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AgentRunRecordRepositoryProjectScopeTest {

    @Autowired
    private AgentRunRecordRepository repository;

    private void record(String conversationId, Long projectId, String status, LocalDateTime updatedAt) {
        AgentRunRecord r = new AgentRunRecord();
        r.setConversationId(conversationId);
        r.setProjectId(projectId);
        r.setStatus(status);
        r.setUpdatedAt(updatedAt);
        repository.save(r);
    }

    @Test
    void returnsOnlyThisProjectsRunsNewestFirst() {
        LocalDateTime base = LocalDateTime.of(2026, 8, 8, 10, 0, 0);
        record("c-old", 7L, "DONE", base);
        record("c-new", 7L, "RUNNING", base.plusHours(2));
        record("c-other", 8L, "RUNNING", base.plusHours(3));

        List<AgentRunRecord> runs = repository.findByProjectIdOrderByUpdatedAtDesc(7L);

        assertEquals(2, runs.size());
        assertEquals("c-new", runs.get(0).getConversationId());
        assertEquals("RUNNING", runs.get(0).getStatus());
        assertEquals("c-old", runs.get(1).getConversationId());
    }
}
