package com.checkba.repository;

import com.checkba.model.entity.UserFeedback;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserFeedbackRepository extends JpaRepository<UserFeedback, Long> {

    /** 优化者每轮取的就是这一批：待分诊 + 重试次数没到顶，最老的先处理。 */
    List<UserFeedback> findByStatusAndAttemptsLessThanOrderByIdAsc(String status, int maxAttempts, Pageable pageable);

    List<UserFeedback> findByStatusOrderByIdDesc(String status, Pageable pageable);

    List<UserFeedback> findByOrderByIdDesc(Pageable pageable);

    /** 用户自己提交过的反馈（浮窗「我的反馈」用），最新的在前。 */
    List<UserFeedback> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    long countByStatus(String status);

    /** 待上传到云端收件箱的本机反馈（只传本机自己产生的，收上来的不再转发）。 */
    List<UserFeedback> findByUploadedFalseAndSourceOrderByIdAsc(String source, Pageable pageable);

    /** 已上传、还在等云端处理结果的本机反馈（NEW/FAILED）：定时拉回执用。 */
    List<UserFeedback> findByUploadedTrueAndSourceAndStatusInOrderByIdAsc(
            String source, List<String> statuses, Pageable pageable);

    /** 幂等键：同一个安装的同一条反馈，重传不得在云端变成两条。 */
    Optional<UserFeedback> findByInstallIdAndClientRef(String installId, String clientRef);

    /**
     * 客户端凭 installId 自证身份查回执：鉴权就是 installId 本身，
     * 必须与 source=CLOUD 一起过滤，绝不能只按 clientRef 查（那样谁都能猜别人的编号）。
     */
    List<UserFeedback> findByInstallIdAndSourceAndClientRefIn(
            String installId, String source, List<String> clientRefs);

    long countByInstallIdAndCreatedAtAfter(String installId, LocalDateTime after);

    long countByCreatedAtAfter(LocalDateTime after);
}
