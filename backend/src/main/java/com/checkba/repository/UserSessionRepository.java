package com.checkba.repository;

import com.checkba.model.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
    Optional<UserSession> findByTokenHash(String tokenHash);

    @Transactional
    void deleteByTokenHash(String tokenHash);

    @Transactional
    long deleteByLastUsedAtBefore(LocalDateTime cutoff);
}
