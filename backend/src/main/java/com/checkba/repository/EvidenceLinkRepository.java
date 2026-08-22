package com.checkba.repository;

import com.checkba.model.entity.EvidenceLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
    /** 缺口清单用：项目里全部处于某状态（如 orphan）的 link，不按单份报告限定。 */
    List<EvidenceLink> findByProjectIdAndStatusOrderByIdAsc(Long projectId, String status);
    /** dd_export 工具在 docFileId 缺省时用来判断「项目里是不是只有一份带底稿关联的报告」。 */
    @Query("select distinct e.docFileId from EvidenceLink e where e.projectId = :projectId")
    List<Long> findDistinctDocFileIdsByProjectId(@Param("projectId") Long projectId);
}
