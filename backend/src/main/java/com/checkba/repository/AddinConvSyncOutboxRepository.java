package com.checkba.repository;

import com.checkba.model.entity.AddinConvSyncOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AddinConvSyncOutboxRepository extends JpaRepository<AddinConvSyncOutbox, Long> {

    Optional<AddinConvSyncOutbox> findByUserIdAndSourceMessageId(Long userId, Long sourceMessageId);

    List<AddinConvSyncOutbox> findTop200ByUserIdAndDeviceIdOrderByIdAsc(Long userId, String deviceId);

    List<AddinConvSyncOutbox> findByUserIdAndIdIn(Long userId, List<Long> ids);

    List<AddinConvSyncOutbox> findByCreatedAtBefore(LocalDateTime cutoff);
}
