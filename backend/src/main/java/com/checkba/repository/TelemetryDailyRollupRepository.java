package com.checkba.repository;

import com.checkba.model.entity.TelemetryDailyRollup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TelemetryDailyRollupRepository extends JpaRepository<TelemetryDailyRollup, Long> {

    Optional<TelemetryDailyRollup> findByDate(LocalDate date);

    List<TelemetryDailyRollup> findByUploadedFalseAndDateAfter(LocalDate after);
}
