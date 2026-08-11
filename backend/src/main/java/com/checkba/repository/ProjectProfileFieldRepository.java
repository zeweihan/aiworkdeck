package com.checkba.repository;

import com.checkba.model.entity.ProjectProfileField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 项目档案字段仓储。
 *
 * 只此两个方法：服务层按固定五键顺序在内存里组装响应，不靠 SQL 排序。
 * 「删项目连带清档案」的 deleteByProjectId 属 Plan 3，本期不预加。
 */
public interface ProjectProfileFieldRepository extends JpaRepository<ProjectProfileField, Long> {

    List<ProjectProfileField> findByProjectId(Long projectId);

    Optional<ProjectProfileField> findByProjectIdAndFieldKey(Long projectId, String fieldKey);
}
