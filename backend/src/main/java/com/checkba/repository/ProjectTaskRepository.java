package com.checkba.repository;

import com.checkba.model.entity.ProjectTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 任务仓储（dev-board #49）。
 *
 * 排序恒为 dueDate asc, dueTime asc nulls first（全天事项排在有具体时刻的事项前面）。
 * from/to 为 null 时对应条件跳过，同一条 JPQL 既服务「查全部」又服务「区间过滤」，
 * 不必为两种调用各开一个方法。
 */
public interface ProjectTaskRepository extends JpaRepository<ProjectTask, Long> {

    /** 单项目任务列表，供 ProjectOverviewController /tasks 与 TaskSchedule.vue 用。 */
    @Query("SELECT t FROM ProjectTask t WHERE t.projectId = :projectId "
            + "AND (:from IS NULL OR t.dueDate >= :from) AND (:to IS NULL OR t.dueDate <= :to) "
            + "ORDER BY t.dueDate ASC, t.dueTime ASC NULLS FIRST")
    List<ProjectTask> findByProjectIdAndDueDateRange(
            @Param("projectId") Long projectId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** 跨项目聚合，供 CalendarController 用（当前用户可见的全部项目）。 */
    @Query("SELECT t FROM ProjectTask t WHERE t.projectId IN :projectIds "
            + "AND (:from IS NULL OR t.dueDate >= :from) AND (:to IS NULL OR t.dueDate <= :to) "
            + "ORDER BY t.dueDate ASC, t.dueTime ASC NULLS FIRST")
    List<ProjectTask> findByProjectIdInAndDueDateRange(
            @Param("projectIds") List<Long> projectIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
