package com.checkba.repository;

import com.checkba.model.entity.ProjectProfileField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 项目档案字段仓储。
 *
 * 服务层按固定五键顺序在内存里组装响应，不靠 SQL 排序。
 * 「删项目连带清档案」的 deleteByProjectId 属 Plan 3，本期不预加。
 */
public interface ProjectProfileFieldRepository extends JpaRepository<ProjectProfileField, Long> {

    List<ProjectProfileField> findByProjectId(Long projectId);

    Optional<ProjectProfileField> findByProjectIdAndFieldKey(Long projectId, String fieldKey);

    /**
     * 一批项目的全部档案字段，给项目列表用（客户列 + 「详情」开关里那四项）。
     *
     * 概览页那条按项目逐个查的路径不动。这里换成一次 IN：每个项目最多五行，
     * 与其按键过滤再查第二次，不如一次取完在内存里分组——逐项目查会给已经
     * N+1 的列表页再加一层。
     */
    List<ProjectProfileField> findByProjectIdIn(List<Long> projectIds);
}
