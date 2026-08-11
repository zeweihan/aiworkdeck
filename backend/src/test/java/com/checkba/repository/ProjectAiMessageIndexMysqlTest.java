package com.checkba.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
 * 与 ProjectAiMessageIndexTest 同一条断言，换到 MySQL8 上再验一次。
 *
 * 为什么必须验两次：桌面壳开发态默认跑 prod profile（MySQL8），打包态才跑 desktop
 * （H2 file, MODE=PostgreSQL）—— 本机改 schema 的验证环境和线上不是同一种库。
 * 且四个 profile 全是 ddl-auto: update、无迁移体系，索引能不能被 update 模式补出来
 * 只有在真 MySQL 上跑一遍才知道。
 *
 * 默认跳过。跑法见本任务 Step 5（起一个一次性 docker MySQL8，再带
 * AWD_MYSQL_SCHEMA_CHECK=1 跑本类）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "AWD_MYSQL_SCHEMA_CHECK", matches = "1")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13306/checkba_schema_check?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root",
        "spring.datasource.password=checkba123",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        // 与线上 prod profile 完全一致：update，不是 create-drop
        "spring.jpa.hibernate.ddl-auto=update"
})
class ProjectAiMessageIndexMysqlTest {

    @Autowired
    private TestEntityManager em;

    @Test
    @SuppressWarnings("unchecked")
    void ddl_auto_update_在MySQL8上也把两条索引建出来() {
        List<Object> rows = em.getEntityManager().createNativeQuery(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_ai_message'")
                .getResultList();
        List<String> names = rows.stream().map(String::valueOf)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toList());

        assertTrue(names.contains("IDX_AI_MESSAGE_PROJECT_CREATED"),
                "MySQL8 上 ddl-auto=update 没建出 (project_id, created_at) 索引；实际索引=" + names);
        assertTrue(names.contains("IDX_AI_MESSAGE_CONVERSATION_CREATED"),
                "MySQL8 上 ddl-auto=update 没建出 (conversation_id, created_at) 索引；实际索引=" + names);
    }
}
