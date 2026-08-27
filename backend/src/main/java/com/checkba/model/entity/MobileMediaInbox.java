package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 手机端现场影像中转区的一件待取件。
 *
 * <p>生命周期：手机上传（幂等键 user_id + client_media_id）→ 桌面端取件落盘后 ACK
 * （置 deliveredAt + <b>立即删除 blob</b>，行保留供手机端查「已抵达」）→ 30 天清理任务删行。
 * 删除由 ACK 触发、不由时间触发，TTL 只是兜底——两个机制不能混（spec 不变式 1）。
 */
@Entity
@Table(name = "mobile_media_inbox",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "client_media_id"}))
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MobileMediaInbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 目标桌面机（与 MobileProjectDir.deviceId 同源）。 */
    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(nullable = false, length = 64)
    private String projectKey;

    /** 手机侧生成的 UUID，幂等键。 */
    @Column(nullable = false, length = 64)
    private String clientMediaId;

    @Column(nullable = false, length = 512)
    private String fileName;

    /** image | video | audio */
    @Column(nullable = false, length = 16)
    private String mediaType;

    @Column(nullable = false)
    private Long fileSize;

    /** blob 的物理绝对路径；ACK 删 blob 后置空。 */
    @Column(length = 1024)
    private String storagePath;

    private LocalDateTime capturedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime deliveredAt;
}
