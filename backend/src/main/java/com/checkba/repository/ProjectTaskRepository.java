// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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

    /**
     * 单项目 + 文件过滤（dev-board#895）：旧列 file_id 命中或关联表 project_task_file 命中任一即算。
     * 单开一个方法而不是在上面那条里加「:fileId IS NULL OR …」——Long 型空参在 PostgreSQL
     * 上推不出类型，容易炸「could not determine data type of parameter」。
     */
    @Query("SELECT t FROM ProjectTask t WHERE t.projectId = :projectId "
            + "AND (t.fileId = :fileId OR t.id IN (SELECT f.taskId FROM ProjectTaskFile f WHERE f.fileId = :fileId)) "
            + "AND (:from IS NULL OR t.dueDate >= :from) AND (:to IS NULL OR t.dueDate <= :to) "
            + "ORDER BY t.dueDate ASC, t.dueTime ASC NULLS FIRST")
    List<ProjectTask> findByProjectIdAndFileIdAndDueDateRange(
            @Param("projectId") Long projectId,
            @Param("fileId") Long fileId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** 概览计数用（dev-board#895 summary）：若干项目内指定状态的事项，排序同上。 */
    @Query("SELECT t FROM ProjectTask t WHERE t.projectId IN :projectIds AND t.status = :status "
            + "ORDER BY t.dueDate ASC, t.dueTime ASC NULLS FIRST")
    List<ProjectTask> findByProjectIdInAndStatus(
            @Param("projectIds") List<Long> projectIds,
            @Param("status") String status);
}
