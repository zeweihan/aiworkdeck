// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.CompletionEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface CompletionEntryRepository extends JpaRepository<CompletionEntry, Long> {
    Optional<CompletionEntry> findByScopeKeyAndText(String scopeKey, String text);
    List<CompletionEntry> findByScopeKeyOrderByLastUsedAtDescIdDesc(String scopeKey);
    long deleteByScopeKey(String scopeKey);
    /** 列表不从数据库搬运工商/法规全文。 */
    interface Summary {
        Long getId();
        String getText();
        String getKind();
        long getUses();
        boolean getHasDetail();
    }

    @Query("select e.id as id, e.text as text, e.kind as kind, e.uses as uses, "
            + "case when e.detailJson is not null then true else false end as hasDetail "
            + "from CompletionEntry e where e.scopeKey = :scopeKey order by e.lastUsedAt desc, e.id desc")
    List<Summary> findSummaries(@Param("scopeKey") String scopeKey, Pageable pageable);

    @Query("select e.id from CompletionEntry e where e.scopeKey = :scopeKey order by e.lastUsedAt desc, e.id desc")
    List<Long> findIds(@Param("scopeKey") String scopeKey, Pageable pageable);
}
