package com.checkba.repository;

import com.checkba.model.entity.ProjectProfileField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL8 侧的建表验证。
 *
 * 为什么必须单独验一次：桌面壳开发态默认跑 prod profile（MySQL8，
 * application-prod.yml:6-16），打包态才跑 desktop（H2 file, MODE=PostgreSQL）——
 * 本机 H2 上建表成功，不代表线上 MySQL8 上也成立。只在 MySQL 上才暴露的三件事：
 *   1. @UniqueConstraint / @Index 的物理列名解析是否真的落成了约束与索引；
 *   2. utf8mb4 下 VARCHAR 的字节膨胀（1 字符最多 4 字节），本表 varchar 合计
 *      (64 + 2048 + 8 + 4000 + 2048 + 4000 + 36) * 4 = 48816 字节，逼近 InnoDB
 *      单行 65535 字节的硬限，再往上加长字段就会建表失败；
 *   3. 4000 个中文的 evidence 能不能真写进去（H2 上按字符算，MySQL 上按字节算）。
 *
 * 默认不跑：类级 @EnabledIfEnvironmentVariable 在加载 Spring 上下文之前就判定，
 * 没有 AWD_MYSQL_SCHEMA_CHECK=1 时整个类被跳过，不会去连一个不存在的 MySQL 而挂住。
 * 跑法见本任务 Step 5。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13306/checkba_schema_check?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root",
        "spring.datasource.password=checkba123",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQL8Dialect",
        "spring.jpa.hibernate.ddl-auto=update"
})
@EnabledIfEnvironmentVariable(named = "AWD_MYSQL_SCHEMA_CHECK", matches = "1")
class ProjectProfileFieldMysqlSchemaTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProjectProfileFieldRepository repository;

    private String showCreateTable() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW CREATE TABLE project_profile_field")) {
            assertTrue(rs.next(), "project_profile_field 表没有被建出来");
            return rs.getString(2);
        }
    }

    @Test
    void 唯一约束与索引在MySQL8上真的建出来了() throws Exception {
        String ddl = showCreateTable();

        assertTrue(ddl.contains("uk_profile_field_project_key"),
                "唯一约束名没落上，实际 DDL:\n" + ddl);
        assertTrue(ddl.contains("UNIQUE KEY"),
                "uk_profile_field_project_key 不是唯一约束，实际 DDL:\n" + ddl);
        assertTrue(ddl.contains("`project_id`,`field_key`") || ddl.contains("`project_id`, `field_key`"),
                "唯一约束的列不是 (project_id, field_key)——@UniqueConstraint 里必须写 snake_case 物理列名，实际 DDL:\n" + ddl);
        assertTrue(ddl.contains("idx_profile_field_project"),
                "project_id 索引没落上，实际 DDL:\n" + ddl);
        assertTrue(ddl.contains("utf8mb4"),
                "表字符集不是 utf8mb4，字节口径与线上不一致，实际 DDL:\n" + ddl);
    }

    @Test
    void 长字段长度未被截短且行长在InnoDB限内() throws Exception {
        Map<String, Long> charLen = new HashMap<>();
        long octetTotal = 0;
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COLUMN_NAME, CHARACTER_MAXIMUM_LENGTH, CHARACTER_OCTET_LENGTH "
                             + "FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_SCHEMA = 'checkba_schema_check' "
                             + "AND TABLE_NAME = 'project_profile_field' "
                             + "AND CHARACTER_MAXIMUM_LENGTH IS NOT NULL")) {
            while (rs.next()) {
                charLen.put(rs.getString(1), rs.getLong(2));
                octetTotal += rs.getLong(3);
            }
        }

        assertEquals(2048L, charLen.get("field_value"), "field_value 必须是 VARCHAR(2048)");
        assertEquals(4000L, charLen.get("evidence"), "evidence 必须是 VARCHAR(4000)");
        assertEquals(2048L, charLen.get("pending_value"), "pending_value 必须是 VARCHAR(2048)");
        assertEquals(4000L, charLen.get("pending_evidence"), "pending_evidence 必须是 VARCHAR(4000)");
        assertEquals(64L, charLen.get("field_key"));
        assertEquals(8L, charLen.get("source"));
        assertEquals(36L, charLen.get("uid"));

        // InnoDB 单行硬限 65535 字节。当前合计 48816，余量约 16KB——
        // 谁要再往这张表加长 VARCHAR，这条会先红。
        assertTrue(octetTotal < 65535L,
                "varchar 字节合计 " + octetTotal + " 已超 InnoDB 单行 65535 字节限");
    }

    @Test
    void 四千个中文证据能真写进MySQL8() {
        ProjectProfileField f = new ProjectProfileField();
        f.setProjectId(42L);
        f.setFieldKey("nextStep");
        f.setFieldValue("一".repeat(2048));
        f.setEvidence("证".repeat(4000));
        f.setSource("ai");
        f.setUid(UUID.randomUUID().toString());
        repository.saveAndFlush(f);

        ProjectProfileField loaded = repository.findByProjectIdAndFieldKey(42L, "nextStep").orElseThrow();
        assertEquals(2048, loaded.getFieldValue().length());
        assertEquals(4000, loaded.getEvidence().length());
    }
}
