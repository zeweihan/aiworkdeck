package com.checkba.repository;

import com.checkba.model.entity.MobileMediaInbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** 未投递 blob 的字节总和（storagePath 非空 = blob 还占着中转区），配额口径。 */
    @Query("select coalesce(sum(m.fileSize), 0) from MobileMediaInbox m"
            + " where m.userId = :userId and m.storagePath is not null")
    long sumPendingBytes(@Param("userId") Long userId);

    /** 账号注销时按用户清空（blob 由调用方先删，见 AccountDeletionService）。 */
    java.util.List<MobileMediaInbox> findByUserId(Long userId);
    long deleteByUserId(Long userId);
}
