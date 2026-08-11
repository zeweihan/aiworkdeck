package com.checkba.repository;

import com.checkba.model.entity.ProjectProfileField;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 项目档案表的 schema 契约测试（H2 侧，MODE=PostgreSQL）。
 *
 * 本仓无 flyway/liquibase，四个 profile 全是 ddl-auto: update——新表零成本，
 * 但字段只增不减、不改类型、VARCHAR 不会自动加宽。因此这里把「唯一约束、
 * 长字段容量、pending 列建表即建」三条钉死，避免将来靠 ALTER 补救。
 *
 * H2 内存库配方照抄 WorkSessionRepositoryTest:19-28，只改库名——@TestPropertySource
 * 参与 ApplicationContext 缓存键，换个库名就不会与其他 @DataJpaTest 互相污染。
 *
 * MySQL8 侧的建表验证在 ProjectProfileFieldMysqlSchemaTest（需要 docker + 环境变量才跑）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:profile-field-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectProfileFieldRepositoryTest {

    @Autowired
    private ProjectProfileFieldRepository repository;

    private ProjectProfileField row(Long projectId, String fieldKey, String value, String source) {
        ProjectProfileField f = new ProjectProfileField();
        f.setProjectId(projectId);
        f.setFieldKey(fieldKey);
        f.setFieldValue(value);
        f.setSource(source);
        f.setUid(UUID.randomUUID().toString());
        return f;
    }

    @Test
    void 按项目与字段名取回单行() {
        repository.saveAndFlush(row(42L, "client", "北京某某科技有限公司", "user"));
        repository.saveAndFlush(row(42L, "counterparty", "上海某某贸易有限公司", "ai"));

        Optional<ProjectProfileField> found = repository.findByProjectIdAndFieldKey(42L, "client");
        assertTrue(found.isPresent());
        assertEquals("北京某某科技有限公司", found.get().getFieldValue());
        assertEquals("user", found.get().getSource());
        assertNotNull(found.get().getCreatedAt(), "@CreationTimestamp 应自动填充");
        assertNotNull(found.get().getUpdatedAt(), "@UpdateTimestamp 应自动填充");

        List<ProjectProfileField> all = repository.findByProjectId(42L);
        assertEquals(2, all.size());
        assertTrue(repository.findByProjectId(43L).isEmpty(), "不同项目之间不能串行");
    }

    @Test
    void 同一项目同一字段名只能有一行() {
        repository.saveAndFlush(row(42L, "client", "甲", "user"));
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(row(42L, "client", "乙", "ai")),
                "(projectId, fieldKey) 唯一约束必须生效——档案是一个字段一行，重复行会让 AI 建议另起一行");
    }

    @Test
    void 档案值可存2048字符且证据可存4000字符() {
        ProjectProfileField f = row(42L, "nextStep", "x".repeat(2048), "ai");
        f.setEvidence("y".repeat(4000));
        repository.saveAndFlush(f);

        ProjectProfileField loaded = repository.findByProjectIdAndFieldKey(42L, "nextStep").orElseThrow();
        assertEquals(2048, loaded.getFieldValue().length());
        assertEquals(4000, loaded.getEvidence().length());
    }

    @Test
    void pending四列建表即建_A期不写但可写() {
        ProjectProfileField f = row(42L, "matterType", "公司治理", "user");
        f.setPendingValue("并购交易");
        f.setPendingConfidence(0.82);
        f.setPendingEvidence("股权转让协议.docx 第 1 条");
        f.setPendingAt(LocalDateTime.of(2026, 8, 8, 10, 11, 12));
        repository.saveAndFlush(f);

        ProjectProfileField loaded = repository.findByProjectIdAndFieldKey(42L, "matterType").orElseThrow();
        assertEquals("并购交易", loaded.getPendingValue());
        assertEquals(0.82, loaded.getPendingConfidence(), 0.0001);
        assertEquals("股权转让协议.docx 第 1 条", loaded.getPendingEvidence());
        assertEquals(LocalDateTime.of(2026, 8, 8, 10, 11, 12), loaded.getPendingAt());
    }

    @Test
    void 相等性只看id() {
        ProjectProfileField a = new ProjectProfileField();
        a.setId(1L);
        a.setFieldValue("甲");
        ProjectProfileField b = new ProjectProfileField();
        b.setId(1L);
        b.setFieldValue("乙");
        ProjectProfileField c = new ProjectProfileField();
        c.setId(2L);

        assertEquals(a, b, "同 id 即同一实体——不能用全字段 equals");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
