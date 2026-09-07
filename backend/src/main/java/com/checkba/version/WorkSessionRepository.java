package com.checkba.version;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WorkSessionRepository extends JpaRepository<WorkSession, Long> {

    Optional<WorkSession> findFirstByProjectIdAndStatus(Long projectId, WorkSession.Status status);

    List<WorkSession> findByProjectIdOrderByStartedAtDesc(Long projectId);

    Optional<WorkSession> findFirstByProjectIdAndStatusAndSessionType(
            Long projectId, WorkSession.Status status, WorkSession.SessionType sessionType);

    List<WorkSession> findByProjectIdAndStatusAndSessionTypeOrderByStartedAtDesc(
            Long projectId, WorkSession.Status status, WorkSession.SessionType sessionType);

    /**
     * 团队使用统计的日聚合取数（dev-board#496）。按 <b>startedAt 落日</b>——跨零点的工作段
     * 整段算在开始那天，与「这一天干了什么」的直觉一致，也免得为几分钟的尾巴做跨日切分。
     *
     * <p>状态与段类型的过滤放在调用方（{@code TeamUsageRollupService}）而不是这里：
     * 那两条判据（排除 ACTIVE 未结束段、排除 DRAFT 长命分支）是统计口径的一部分，
     * 摆在聚合逻辑旁边才看得见，藏进 finder 名字里迟早被下一个人绕过去。
     */
    List<WorkSession> findByUserIdAndStartedAtBetween(
            Long userId, LocalDateTime from, LocalDateTime to);
}
