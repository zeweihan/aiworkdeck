package com.checkba.repository;

import com.checkba.model.entity.UserFeedback;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserFeedbackRepository extends JpaRepository<UserFeedback, Long> {

    /** 优化者每轮取的就是这一批：待分诊 + 重试次数没到顶，最老的先处理。 */
    List<UserFeedback> findByStatusAndAttemptsLessThanOrderByIdAsc(String status, int maxAttempts, Pageable pageable);

    List<UserFeedback> findByStatusOrderByIdDesc(String status, Pageable pageable);

    List<UserFeedback> findByOrderByIdDesc(Pageable pageable);

    long countByStatus(String status);
}
