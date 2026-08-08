package com.checkba.repository;

import com.checkba.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    Optional<User> findByPhone(String phone);

    /** 按已验证邮箱定位账号（登录身份）。资料字段 email 不唯一，不可用于此。 */
    Optional<User> findByVerifiedEmail(String verifiedEmail);
}

