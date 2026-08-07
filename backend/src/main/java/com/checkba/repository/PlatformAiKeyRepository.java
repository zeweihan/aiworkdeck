package com.checkba.repository;

import com.checkba.model.entity.PlatformAiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformAiKeyRepository extends JpaRepository<PlatformAiKey, Long> {

    Optional<PlatformAiKey> findByUserId(Long userId);
}
