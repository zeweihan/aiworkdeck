package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 手机端云中转的设备心跳落点（dev-board#250 起，spec 见
 * docs/superpowers/specs/2026-08-28-cross-device-transfer.md 一、1.1）。
 *
 * <p>每 (userId, deviceId) 一行，记最近一次心跳时刻。deviceName 起初刻意不存（目录行
 * {@link MobileProjectDir} 里有，联查即可），但线上出现过「设备有心跳、目录行却为 0」的
 * 形态（同机第二个后端实例复用 relay 身份、用空清单顶掉了目录），这种设备也要能在
 * listDevices 里露脸并带名字，所以补了 nullable 的 device_name（PUT /projects 的
 * touchDevice 顺带更新）。在线判定是 {@code MobileRelayStoreService.ONLINE_WINDOW}
 * 窗口内是否有心跳，不是本表自己算的字段。
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

    /** 设备自报的名字（目录行为 0 时 listDevices 的唯一名字来源）。nullable：存量行没有。 */
    @Column(length = 128)
    private String deviceName;
}
