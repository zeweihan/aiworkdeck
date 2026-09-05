package com.checkba.repository;

import com.checkba.model.entity.ProjectRemote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRemoteRepository extends JpaRepository<ProjectRemote, Long> {
    Optional<ProjectRemote> findByProjectId(Long projectId);
    /** 换机器取回的查重键：这个案件库里的这份案卷，本机是不是已经有了。 */
    Optional<ProjectRemote> findByConnectionIdAndRemoteProjectId(Long connectionId, String remoteProjectId);
    List<ProjectRemote> findByConnectionId(Long connectionId);
}
