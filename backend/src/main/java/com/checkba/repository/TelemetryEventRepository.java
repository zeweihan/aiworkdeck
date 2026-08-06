package com.checkba.repository;

import com.checkba.model.entity.TelemetryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TelemetryEventRepository extends JpaRepository<TelemetryEvent, Long> {

    List<TelemetryEvent> findByTsBetween(Instant from, Instant to);

    List<TelemetryEvent> findByTsAfter(Instant from);

    @Modifying
    @Query("DELETE FROM TelemetryEvent e WHERE e.ts < :before")
    int deleteByTsBefore(@Param("before") Instant before);
}
