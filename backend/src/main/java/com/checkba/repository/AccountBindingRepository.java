// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.AccountBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountBindingRepository extends JpaRepository<AccountBinding, Long> {
    Optional<AccountBinding> findByExternalAccountId(String externalAccountId);

    Optional<AccountBinding> findByUserId(Long userId);

    long deleteByUserId(Long userId);
}
