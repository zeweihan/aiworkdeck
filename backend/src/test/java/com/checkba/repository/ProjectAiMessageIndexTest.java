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
 * 钉死 project_ai_message 的索引定义。
 *
 * 背景：这张表长期零 @Index，而四个 profile 全是 ddl-auto: update、无 flyway/liquibase、
 * 无 schema.sql —— 线上只有主键索引，概览页按 projectId 铺全项目会话是全表扫描。
 * 索引被谁顺手删掉不会有任何报错，只会悄悄变慢，所以用测试钉住。
 *
 * 环境：内存 H2（MODE=PostgreSQL）+ NON_KEYWORDS=VALUE，表结构交给 Hibernate ddl-auto 建。
 * H2 里未加引号的标识符一律存成大写，故断言用大写字面量。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-message-index-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectAiMessageIndexTest {

    @Autowired
    private TestEntityManager em;

    @SuppressWarnings("unchecked")
    private List<String> indexNames() {
        List<Object> rows = em.getEntityManager().createNativeQuery(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME) = 'PROJECT_AI_MESSAGE'")
                .getResultList();
        return rows.stream().map(String::valueOf)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    @Test
    void 按项目铺会话的复合索引存在() {
        List<String> names = indexNames();
        assertTrue(names.contains("IDX_AI_MESSAGE_PROJECT_CREATED"),
                "缺 (project_id, created_at) 索引，项目级会话汇总会退化成全表扫描；实际索引=" + names);
    }

    @Test
    void 按会话取正文与标量子查询的复合索引存在() {
        List<String> names = indexNames();
        assertTrue(names.contains("IDX_AI_MESSAGE_CONVERSATION_CREATED"),
                "缺 (conversation_id, created_at) 索引，四个标量子查询与历史回放都会全表扫描；实际索引=" + names);
    }
}
