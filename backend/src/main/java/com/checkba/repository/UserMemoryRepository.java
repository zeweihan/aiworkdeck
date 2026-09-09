// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.UserMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户记忆仓库
 */
@Repository
public interface UserMemoryRepository extends JpaRepository<UserMemory, Long> {

    /**
     * 根据用户ID查找用户记忆
     */
    Optional<UserMemory> findByUserId(Long userId);

    /**
     * 检查用户是否有记忆
     */
    boolean existsByUserId(Long userId);

    /**
     * 删除用户记忆
     */
    void deleteByUserId(Long userId);
}

