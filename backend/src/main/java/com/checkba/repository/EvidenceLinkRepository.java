package com.checkba.repository;

import com.checkba.model.entity.EvidenceLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EvidenceLinkRepository extends JpaRepository<EvidenceLink, Long> {
    Optional<EvidenceLink> findByProjectIdAndLinkKey(Long projectId, String linkKey);
    List<EvidenceLink> findByProjectIdAndDocFileIdOrderByIdAsc(Long projectId, Long docFileId);
    List<EvidenceLink> findByProjectIdAndDocFileIdAndStatusOrderByIdAsc(Long projectId, Long docFileId, String status);
    List<EvidenceLink> findByProjectIdAndDocFileIdAndSectionPathStartingWithOrderByIdAsc(Long projectId, Long docFileId, String prefix);
    List<EvidenceLink> findByProjectIdAndIdIn(Long projectId, Collection<Long> ids);
    long countByProjectId(Long projectId);
}
