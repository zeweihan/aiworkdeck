package com.checkba.repository;

import com.checkba.model.entity.DocInsightRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocInsightRunRepository extends JpaRepository<DocInsightRun, Long> {

    /** 窗格取「这份文档最近一次解析」。 */
    Optional<DocInsightRun> findFirstByProjectIdAndDocFileIdOrderByStartedAtDescIdDesc(Long projectId, Long docFileId);

    /** 单飞判定：这份文档还有没有在跑的解析（含崩溃留下的僵尸 RUNNING，由服务层按时限判活）。 */
    List<DocInsightRun> findByProjectIdAndDocFileIdAndStatus(Long projectId, Long docFileId, String status);
}
