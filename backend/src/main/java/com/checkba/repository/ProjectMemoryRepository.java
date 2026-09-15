// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.ProjectMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 项目记忆仓库
 */
@Repository
public interface ProjectMemoryRepository extends JpaRepository<ProjectMemory, Long> {

    /**
     * 根据项目ID查找项目记忆
     */
    Optional<ProjectMemory> findByProjectId(Long projectId);

    /**
     * 检查项目是否有记忆
     */
    boolean existsByProjectId(Long projectId);

    /**
     * 删除项目记忆
     */
    void deleteByProjectId(Long projectId);
}

