package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 手机端云中转的设备心跳落点（dev-board#250 起，spec 见
 * docs/superpowers/specs/2026-08-28-cross-device-transfer.md 一、1.1）。
 *
 * <p>每 (userId, deviceId) 一行，只记最近一次心跳时刻——deviceName 不重复存，
 * 目录行 {@link MobileProjectDir} 里已经有，联查即可。在线判定是
 * {@code MobileRelayStoreService.ONLINE_WINDOW} 窗口内是否有心跳，不是本表自己算的字段。
 */
@Entity
@Table(name = "mobile_device_state",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_id"}))
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MobileDeviceState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;
}
