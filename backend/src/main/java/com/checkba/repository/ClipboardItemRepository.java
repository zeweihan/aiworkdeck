package com.checkba.repository;

import com.checkba.model.entity.ClipboardItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ClipboardItemRepository extends JpaRepository<ClipboardItem, Long> {

    List<ClipboardItem> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query("select c from ClipboardItem c where c.userId = :userId and (c.text like concat('%', :q, '%') or c.meta like concat('%', :q, '%')) order by c.createdAt desc")
    List<ClipboardItem> search(@Param("userId") Long userId, @Param("q") String q, Pageable pageable);
}


