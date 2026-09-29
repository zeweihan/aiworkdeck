// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.ProjectFileReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectFileReviewRepository extends JpaRepository<ProjectFileReview, Long> {

    /** 当前审阅态：一个文件至多一条 open 记录（服务层保证）。 */
    Optional<ProjectFileReview> findFirstByFileIdAndStatus(Long fileId, String status);
}
