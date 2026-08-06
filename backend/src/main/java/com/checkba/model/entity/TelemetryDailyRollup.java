package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 埋点日聚合（每日一条）。payload 即上报负载 JSON（设计 §5.5），
 * uploaded 标记支撑「失败静默、下轮补传」（最多回溯 30 天）。
 */
@Getter
@Setter
@Entity
@Table(name = "telemetry_daily_rollup", uniqueConstraints = {
        @UniqueConstraint(name = "uk_telemetry_rollup_date", columnNames = "rollup_date")
})
public class TelemetryDailyRollup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rollup_date", nullable = false)
    private LocalDate date;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(nullable = false)
    private boolean uploaded = false;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;
}
