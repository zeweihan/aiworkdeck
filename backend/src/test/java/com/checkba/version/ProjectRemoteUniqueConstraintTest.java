package com.checkba.version;

import com.checkba.model.entity.ProjectRemote;
import com.checkba.repository.ProjectRemoteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同一个远端案卷在本机只能有一份绑定（dev-board#439 第 5 环的兜底）。
 * 服务层的查重是第一道，唯一约束是第二道——并发点两下「取到本机」时，
 * 先查后插不是原子的，没有这条约束就会真的造出两个本机项目。
 *
 * H2 约定同 WorkSessionRepositoryTest（就地 @TestPropertySource，不放 classpath schema.sql）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-remote-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectRemoteUniqueConstraintTest {

    @Autowired
    private ProjectRemoteRepository repository;


    private ProjectRemote remote(long projectId, long connectionId, String remoteProjectId) {
        ProjectRemote r = new ProjectRemote();
        r.setProjectId(projectId);
        r.setConnectionId(connectionId);
        r.setRemoteProjectId(remoteProjectId);
        r.setPendingUpload(false);
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    /**
     * 两条记录的 project_id **必须不同**，两个方法用的 project_id 段也必须不相交：
     * 否则撞上的会是 project_id 那条既有的唯一约束，本用例就算把复合约束整个删掉
     * 也照样「通过」。
     */
    @Test
    void theSameRemoteCaseFileCannotBeBoundTwice() {
        repository.saveAndFlush(remote(101L, 3L, "5"));

        assertThrows(Exception.class, () -> repository.saveAndFlush(remote(102L, 3L, "5")));
    }

    /** 不同案件库里恰好同号的案卷互不影响。 */
    @Test
    void theSameRemoteIdInADifferentLibraryIsFine() {
        repository.saveAndFlush(remote(201L, 13L, "5"));

        assertDoesNotThrow(() -> repository.saveAndFlush(remote(202L, 14L, "5")));
    }
}
