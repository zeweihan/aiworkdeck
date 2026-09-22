// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.ProjectFileTextCache;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectFileTextCacheRepository extends JpaRepository<ProjectFileTextCache, Long> {

    Optional<ProjectFileTextCache> findByFileId(Long fileId);

    void deleteByFileId(Long fileId);

    /**
     * 总量超限时的淘汰候选：最旧的若干行。
     *
     * <p>刻意不按「最近使用」淘汰——那要在每次命中时写一次 last_accessed_at，
     * 而命中恰恰发生在首 token 之前的关键路径上（记忆检索的 touchLastAccessedAt 就是这个毛病）。
     * 缓存重建的代价是重抽一次，不值得为它在读路径上加一次写。
     */
    @Query("SELECT c FROM ProjectFileTextCache c ORDER BY c.createdAt ASC, c.id ASC")
    List<ProjectFileTextCache> findOldest(Pageable pageable);
}
