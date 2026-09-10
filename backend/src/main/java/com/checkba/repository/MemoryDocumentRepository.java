// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.MemoryDocument;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface MemoryDocumentRepository extends JpaRepository<MemoryDocument, Long> {
    Optional<MemoryDocument> findBySpaceIdAndPath(String spaceId, String path);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from MemoryDocument d where d.spaceId = :spaceId and d.path = :path")
    Optional<MemoryDocument> findLockedBySpaceIdAndPath(@Param("spaceId") String spaceId,
                                                         @Param("path") String path);

    List<MemoryDocument> findBySpaceIdAndDeletedFalseOrderByPathAsc(String spaceId);

    Optional<MemoryDocument> findBySourceMemoryUid(String sourceMemoryUid);
}
