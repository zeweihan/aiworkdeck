package com.checkba.repository;

import com.checkba.model.entity.DocInsightFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocInsightFindingRepository extends JpaRepository<DocInsightFinding, Long> {

    List<DocInsightFinding> findByRunIdOrderByIdAsc(Long runId);
}
