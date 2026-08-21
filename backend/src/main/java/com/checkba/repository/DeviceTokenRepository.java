package com.checkba.repository;

import com.checkba.model.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    Optional<DeviceToken> findByTokenHash(String tokenHash);
    List<DeviceToken> findByUserIdOrderByCreatedAtDesc(Long userId);

    // 空转设备令牌清理用：见 DeviceTokenService.purgeIdleTokens。lastUsedAt 从未用过时为
    // null（issue() 不写它），此时按 createdAt 兜底判定，否则永久发出一次就再也不清理。
    @Modifying
    @Query("DELETE FROM DeviceToken t WHERE " +
            "(t.lastUsedAt IS NOT NULL AND t.lastUsedAt < :cutoff) OR " +
            "(t.lastUsedAt IS NULL AND t.createdAt < :cutoff)")
    int deleteIdleBefore(@Param("cutoff") LocalDateTime cutoff);
}
