// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.AddinGitRepoLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 插件关联的 git 仓库（dev-board#720）。
 *
 * <p>三个方法全部带 userId：关联行里有那个人的访问令牌，按 id 单独查是一条越权读的口子。
 */
public interface AddinGitRepoLinkRepository extends JpaRepository<AddinGitRepoLink, Long> {

    List<AddinGitRepoLink> findByUserIdAndCloudProjectId(Long userId, Long cloudProjectId);

    Optional<AddinGitRepoLink> findByIdAndUserId(Long id, Long userId);

    Optional<AddinGitRepoLink> findByUserIdAndCloudProjectIdAndProviderAndOwnerAndRepo(
            Long userId, Long cloudProjectId, String provider, String owner, String repo);
}
