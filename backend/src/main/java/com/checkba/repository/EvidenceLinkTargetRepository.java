package com.checkba.repository;

import com.checkba.model.entity.EvidenceLinkTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EvidenceLinkTargetRepository extends JpaRepository<EvidenceLinkTarget, Long> {
    List<EvidenceLinkTarget> findByLinkIdOrderBySortOrderAscIdAsc(Long linkId);
    List<EvidenceLinkTarget> findByLinkIdInOrderBySortOrderAscIdAsc(Collection<Long> linkIds);
    List<EvidenceLinkTarget> findByFileId(Long fileId);
    List<EvidenceLinkTarget> findByFileIdIn(Collection<Long> fileIds);
    boolean existsByLinkIdAndFileIdAndLocatorHash(Long linkId, Long fileId, String locatorHash);
    void deleteByLinkId(Long linkId);

    /** 每行 {fileId, count}。 */
    @Query("select t.fileId, count(t) from EvidenceLinkTarget t where t.fileId in :fileIds group by t.fileId")
    List<Object[]> countByFileIds(@Param("fileIds") Collection<Long> fileIds);
}
