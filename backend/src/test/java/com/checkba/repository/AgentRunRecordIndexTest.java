package com.checkba.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死 agent_run_record 的索引定义。
 *
 * 背景：这张表长期零 @Index；本分支新开了按 projectId 取后台 AI 任务的查询形态
 * （findTop5ByProjectIdOrderByUpdatedAtDesc，供 /overview/stats 与会话列表用），云后端上
 * 它是全租户共库表，缺索引就是全表扫描 + filesort。索引被谁顺手删掉不会有任何报错，
 * 只会悄悄变慢，所以照 ProjectAiMessageIndexTest 的样子用测试钉住。
 *
 * 环境同 ProjectAiMessageIndexTest：内存 H2（MODE=PostgreSQL）+ NON_KEYWORDS=VALUE，
 * 表结构交给 Hibernate ddl-auto 建。H2 里未加引号的标识符一律存成大写，故断言用大写字面量。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-run-record-index-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AgentRunRecordIndexTest {

    @Autowired
    private TestEntityManager em;

    @SuppressWarnings("unchecked")
    private List<String> indexNames() {
        List<Object> rows = em.getEntityManager().createNativeQuery(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME) = 'AGENT_RUN_RECORD'")
                .getResultList();
        return rows.stream().map(String::valueOf)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    @Test
    void 按项目取后台任务的复合索引存在() {
        List<String> names = indexNames();
        assertTrue(names.contains("IDX_AGENT_RUN_PROJECT_UPDATED"),
                "缺 (project_id, updated_at) 索引，概览页/会话列表的后台 AI 任务查询会退化成全表扫描；实际索引=" + names);
    }
}
