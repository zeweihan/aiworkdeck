package com.checkba.repository;

import com.checkba.model.entity.ProjectRemote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRemoteRepository extends JpaRepository<ProjectRemote, Long> {
    Optional<ProjectRemote> findByProjectId(Long projectId);
    List<ProjectRemote> findByConnectionId(Long connectionId);
}
