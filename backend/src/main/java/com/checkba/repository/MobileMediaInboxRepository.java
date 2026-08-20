package com.checkba.repository;

import com.checkba.model.entity.MobileMediaInbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MobileMediaInboxRepository extends JpaRepository<MobileMediaInbox, Long> {

    Optional<MobileMediaInbox> findByUserIdAndClientMediaId(Long userId, String clientMediaId);

    List<MobileMediaInbox> findByUserIdAndDeviceIdAndDeliveredAtIsNullOrderByCreatedAtAsc(
            Long userId, String deviceId);

    List<MobileMediaInbox> findByUserIdAndClientMediaIdIn(Long userId, List<String> clientMediaIds);

    List<MobileMediaInbox> findByCreatedAtBefore(LocalDateTime cutoff);
}
