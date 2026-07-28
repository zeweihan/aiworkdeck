package com.checkba.version;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
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
