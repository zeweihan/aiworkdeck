// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.CompanyMirror;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyMirrorRepository extends JpaRepository<CompanyMirror, Long> {

    List<CompanyMirror> findByRoleOrderByUpdatedAtDesc(String role);

    Optional<CompanyMirror> findFirstByRoleAndStockCode(String role, String stockCode);

    Optional<CompanyMirror> findFirstByRoleAndName(String role, String name);
}


