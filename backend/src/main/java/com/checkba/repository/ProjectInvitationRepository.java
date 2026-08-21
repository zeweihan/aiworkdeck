package com.checkba.repository;

import com.checkba.model.entity.ProjectInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectInvitationRepository extends JpaRepository<ProjectInvitation, Long> {
    Optional<ProjectInvitation> findByAccessCode(String accessCode);
    /**
     * 返回 List 而不是 Optional：(project_id, type) 上没有、也不该有唯一约束——
     * CLIENT_NAMED 天然一个项目多行。而 inviteClient 是先查后建，两个并发的
     * 「生成访问码」会各插一行 CLIENT_GENERIC；声明成 Optional 就走 getSingleResult，
     * 之后每次查都抛 IncorrectResultSizeDataAccessException，这个项目的邀请链接功能
     * 从此永久 500，没有重试也没有自愈，只能有人手工去库里删行。
     * 调用方按 id 取最早的一行，重复时行为稳定。
     */
    List<ProjectInvitation> findByProjectIdAndType(Long projectId, String type);
    Optional<ProjectInvitation> findByProjectIdAndRelatedUserId(Long projectId, Long relatedUserId);
}