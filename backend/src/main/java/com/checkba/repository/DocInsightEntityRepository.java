package com.checkba.repository;

import com.checkba.model.entity.DocInsightEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface DocInsightEntityRepository extends JpaRepository<DocInsightEntity, Long> {

    List<DocInsightEntity> findByRunIdOrderByIdAsc(Long runId);

    /**
     * 7 天缓存命中：同项目、同类、同归一键的最近一次<b>成功</b>检索。
     * 只认 OK——把 UNAVAILABLE 也缓存起来会让「法宝续了点数」之后一周内都不再尝试。
     */
    List<DocInsightEntity> findTop1ByProjectIdAndKindAndNormKeyAndRetrievalStatusAndFetchedAtAfterOrderByFetchedAtDesc(
            Long projectId, String kind, String normKey, String retrievalStatus, LocalDateTime after);
}
