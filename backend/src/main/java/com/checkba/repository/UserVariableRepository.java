// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.UserVariable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserVariableRepository extends JpaRepository<UserVariable, Long> {
    List<UserVariable> findTop500ByUserIdOrderByUpdatedAtDescIdDesc(Long userId);

    List<UserVariable> findByUserId(Long userId);
    Optional<UserVariable> findByUserIdAndName(Long userId, String name);
}

