// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> lockStorageOwner(Long id);

    Optional<User> findByUsername(String username);

    /** 同一访问码名下已建出的客户用户数（username 前缀 client_inv{invitationId}_，dev-board#1050）。 */
    long countByUsernameStartingWith(String prefix);

    Optional<User> findByPhone(String phone);

    /** 按已验证邮箱定位账号（登录身份）。资料字段 email 不唯一，不可用于此。 */
    Optional<User> findByVerifiedEmail(String verifiedEmail);
}

