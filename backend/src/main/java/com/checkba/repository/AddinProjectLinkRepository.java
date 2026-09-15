// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.AddinProjectLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AddinProjectLinkRepository extends JpaRepository<AddinProjectLink, Long> {

    Optional<AddinProjectLink> findByUserIdAndDeviceIdAndProjectKey(Long userId, String deviceId, String projectKey);

    List<AddinProjectLink> findByUserId(Long userId);

    Optional<AddinProjectLink> findByCloudProjectId(Long cloudProjectId);
}
