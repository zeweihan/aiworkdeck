package com.checkba.version;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkSessionRepository extends JpaRepository<WorkSession, Long> {

    Optional<WorkSession> findFirstByProjectIdAndStatus(Long projectId, WorkSession.Status status);

    List<WorkSession> findByProjectIdOrderByStartedAtDesc(Long projectId);

    Optional<WorkSession> findFirstByProjectIdAndStatusAndSessionType(
            Long projectId, WorkSession.Status status, WorkSession.SessionType sessionType);

    List<WorkSession> findByProjectIdAndStatusAndSessionTypeOrderByStartedAtDesc(
            Long projectId, WorkSession.Status status, WorkSession.SessionType sessionType);
}
