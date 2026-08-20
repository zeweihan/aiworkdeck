package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 手机端项目目录镜像（云中转，spec 见 aiworkdeck_mobile docs/specs/2026-08-20-project-sync-relay.md）。
 *
 * <p>桌面端是项目的唯一权威源：本表只是各台桌面机推上来的 {key, name} 清单，
 * 供手机端「选择项目」页展示与影像中转寻址，<b>不是</b>云端 project 表的一部分——
 * projectKey 是那台桌面机本地库的项目 id，跨机同号不同物，所以必须连着 deviceId 一起用。
 */
@Entity
@Table(name = "mobile_project_dir",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_id", "project_key"}))
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MobileProjectDir {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 推送方桌面机的安装标识（UUID）。 */
    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(length = 128)
    private String deviceName;

    /** 那台桌面机本地库的项目 id（字符串形态，透传不解释）。 */
    @Column(nullable = false, length = 64)
    private String projectKey;

    @Column(nullable = false, length = 512)
    private String name;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
