// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.ProjectVariable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectVariableRepository extends JpaRepository<ProjectVariable, Long> {
    List<ProjectVariable> findTop500ByProjectIdOrderByUpdatedAtDescIdDesc(Long projectId);

    List<ProjectVariable> findByProjectId(Long projectId);
    Optional<ProjectVariable> findByProjectIdAndName(Long projectId, String name);
}

