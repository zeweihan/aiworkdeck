// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.MobileDeviceState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MobileDeviceStateRepository extends JpaRepository<MobileDeviceState, Long> {

    Optional<MobileDeviceState> findByUserIdAndDeviceId(Long userId, String deviceId);

    List<MobileDeviceState> findByUserIdAndDeviceIdIn(Long userId, Collection<String> deviceIds);

    List<MobileDeviceState> findByUserId(Long userId);

    long deleteByUserId(Long userId);
}
