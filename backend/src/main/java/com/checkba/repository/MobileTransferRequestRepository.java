package com.checkba.repository;

import com.checkba.model.entity.MobileTransferRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MobileTransferRequestRepository extends JpaRepository<MobileTransferRequest, Long> {

    /** 幂等查找：requestId 撞既有行时用它取代重复扣费/重复建行。 */
    Optional<MobileTransferRequest> findByUserIdAndRequestId(Long userId, String requestId);

    /** B 侧 /commands 的候选集：该设备当前处于 PENDING/STAGED 的行，具体三态过滤在 service 层做
     * （LIST/PULL 看 PENDING、PUSH 看 STAGED，不是简单的笛卡尔积）。 */
    List<MobileTransferRequest> findByUserIdAndDeviceIdAndStatusIn(
            Long userId, String deviceId, List<String> statuses);

    /** TTL 清扫按 (kind, status, 创建时间早于 cutoff) 取一批过期行。 */
    List<MobileTransferRequest> findByKindAndStatusAndCreatedAtBefore(
            String kind, String status, LocalDateTime cutoff);

    /** 已扣未退的行（EXPIRED/FAILED 且退款失败过或还没退），TTL 清扫每轮兜底重试一次。 */
    List<MobileTransferRequest> findByStatusInAndChargedCreditsIsNotNullAndRefundedAtIsNull(
            List<String> statuses);

    /** 未投递 blob 的字节总和，与 MobileMediaInbox 同口径，两表之和才是配额共池的真实占用。 */
    @Query("select coalesce(sum(t.fileSize), 0) from MobileTransferRequest t"
            + " where t.userId = :userId and t.storagePath is not null")
    long sumPendingBytes(@Param("userId") Long userId);

    long deleteByUserId(Long userId);
}
