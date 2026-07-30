package com.checkba.repository;

import com.checkba.model.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    Optional<DeviceToken> findByTokenHash(String tokenHash);
    List<DeviceToken> findByUserIdOrderByCreatedAtDesc(Long userId);
}
