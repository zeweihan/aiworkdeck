// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.FileTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FileTagRepository extends JpaRepository<FileTag, Long> {
    
    /**
     * Finds all file-tag associations for a specific file.
     */
    List<FileTag> findByFileId(Long fileId);

    /**
     * Finds all file-tag associations for a list of files.
     */
    List<FileTag> findByFileIdIn(List<Long> fileIds);
    
    /**
     * Finds all file-tag associations for a specific tag.
     */
    List<FileTag> findByTagId(Long tagId);

    /**
     * Finds all file-tag associations for a list of tags.
     */
    List<FileTag> findByTagIdIn(List<Long> tagIds);

    /**
     * Deletes relation by fileId and tagId.
     */
    void deleteByFileIdAndTagId(Long fileId, Long tagId);
    
    /**
     * Checks if relation exists.
     */
    boolean existsByFileIdAndTagId(Long fileId, Long tagId);
    
    /**
     * Delete all tags for a file (e.g., when file is deleted).
     */
    void deleteByFileId(Long fileId);
}
