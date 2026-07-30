package com.checkba.version;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存 H2（MODE=PostgreSQL）+ NON_KEYWORDS=VALUE，约定同 IdorAuthIntegrationTest /
 * DesktopContextSmokeTest。数据源覆盖用 @TestPropertySource 就地指定，只作用于本类的
 * ApplicationContext 缓存键，不影响模块内其他 @DataJpaTest；表结构交给 Hibernate
 * ddl-auto 自动建，不依赖任何 schema.sql。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:work-session-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class WorkSessionRepositoryTest {

    @Autowired
    private WorkSessionRepository repository;

    private WorkSession session(Long projectId, WorkSession.Status status, String branch) {
        WorkSession s = new WorkSession();
        s.setProjectId(projectId);
        s.setBranchName(branch);
        s.setStartedAt(LocalDateTime.now());
        s.setStatus(status);
        s.setUserId(1L);
        return s;
    }

    @Test
    void findsOnlyTheActiveSessionForAProject() {
        repository.save(session(7L, WorkSession.Status.MERGED, "work/1"));
        repository.save(session(7L, WorkSession.Status.ACTIVE, "work/2"));
        repository.save(session(8L, WorkSession.Status.ACTIVE, "work/3"));

        var found = repository.findFirstByProjectIdAndStatus(7L, WorkSession.Status.ACTIVE);

        assertTrue(found.isPresent());
        assertEquals("work/2", found.get().getBranchName());
    }
}
