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
}
