package com.checkba.repository;

import com.checkba.model.entity.ProjectInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectInvitationRepository extends JpaRepository<ProjectInvitation, Long> {
    Optional<ProjectInvitation> findByAccessCode(String accessCode);
    Optional<ProjectInvitation> findByProjectIdAndType(Long projectId, String type);
    Optional<ProjectInvitation> findByProjectIdAndRelatedUserId(Long projectId, Long relatedUserId);
}