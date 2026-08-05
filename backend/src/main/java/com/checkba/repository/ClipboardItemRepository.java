package com.checkba.repository;

import com.checkba.model.entity.ClipboardItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ClipboardItemRepository extends JpaRepository<ClipboardItem, Long> {

    List<ClipboardItem> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query("select c from ClipboardItem c where c.userId = :userId and (c.text like concat('%', :q, '%') or c.meta like concat('%', :q, '%')) order by c.createdAt desc")
    List<ClipboardItem> search(@Param("userId") Long userId, @Param("q") String q, Pageable pageable);

    // ==================== 免费额度统计（PR-C） ====================
    // 免费版剪贴板只**显示**最近 20 条且 3 天内的记录，超出部分留在库里不可见。
    // 下面四个计数用来算 hiddenCount——「有多少条因为额度而看不见」，
    // 与分页 limit 造成的不可见是两回事，故必须按同一 query 作用域分别统计。

    long countByUserId(Long userId);

    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime cutoff);

    @Query("select count(c) from ClipboardItem c where c.userId = :userId and (c.text like concat('%', :q, '%') or c.meta like concat('%', :q, '%'))")
    long countSearch(@Param("userId") Long userId, @Param("q") String q);

    @Query("select count(c) from ClipboardItem c where c.userId = :userId and c.createdAt > :cutoff and (c.text like concat('%', :q, '%') or c.meta like concat('%', :q, '%'))")
    long countSearchAfter(@Param("userId") Long userId, @Param("q") String q, @Param("cutoff") LocalDateTime cutoff);
}
