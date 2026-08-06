package com.checkba.repository;

import com.checkba.model.entity.MemoryRemote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 记忆仓库远端绑定。 */
@Repository
public interface MemoryRemoteRepository extends JpaRepository<MemoryRemote, Long> {

    Optional<MemoryRemote> findByRepoKey(String repoKey);
}
