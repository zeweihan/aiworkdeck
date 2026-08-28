package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 跨设备文件传输的一件请求（dev-board#251，spec 见
 * docs/superpowers/specs/2026-08-28-cross-device-transfer.md 二、2.1）。
 *
 * <p>三种 kind：LIST（拉清单）/ PULL（拉文件）/ PUSH（投送文件）。状态机：
 * <ul>
 * <li>LIST：PENDING →(B 回清单) DONE ｜ →(B 报错) FAILED ｜ →(10 分钟) EXPIRED。不扣费。</li>
 * <li>PULL：PENDING（已扣费，等 B 上传）→(B 上传) STAGED →(A save-to-project) DELIVERED
 *     ｜ →(B 报错 / A cancel) FAILED+退款 ｜ PENDING 24h / STAGED 7 天 → EXPIRED+退款。</li>
 * <li>PUSH：创建即扣费并把云项目文件字节复制入 blob → STAGED →(B 落盘+ack) DELIVERED
 *     ｜ STAGED 30 天 → EXPIRED+退款+删 blob。</li>
 * </ul>
 *
 * <p>storagePath 与 {@link MobileMediaInbox} 同一套约定：非空 = 占用中转区配额
 * （{@code MobileRelayStoreService.QUOTA_BYTES} 与影像中转共用同一份 3GB），
 * 投递/失败/过期后置空。chargedCredits/chargeLedgerId/refundedAt 是退款账本，
 * 已扣未退才需要退——退款失败只 log.error，由每小时的 TTL 清扫兜底重试。
 */
@Entity
@Table(name = "mobile_transfer_request",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "request_id"}))
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MobileTransferRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** client 生成的 UUID，幂等键。 */
    @Column(nullable = false, length = 64)
    private String requestId;

    /** LIST / PULL / PUSH。 */
    @Column(nullable = false, length = 8)
    private String kind;

    /** PENDING / STAGED / DONE / DELIVERED / FAILED / EXPIRED。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 涉事远程设备：PULL/LIST=来源 B；PUSH=目标 B。 */
    @Column(nullable = false, length = 64)
    private String deviceId;

    /** B 机项目 key（跨机同号不同物，见既有契约）。 */
    @Column(nullable = false, length = 64)
    private String projectKey;

    /** PULL：B 机 project_file 行 id（字符串透传）。 */
    @Column(length = 128)
    private String remoteFileId;

    @Column(length = 512)
    private String fileName;

    /** 声明大小；upload 后以实际字节覆盖。 */
    private Long fileSize;

    /** blob 定位符；非空 = 占配额，投递/失败/过期后置空。 */
    @Column(length = 1024)
    private String storagePath;

    /** LIST 结果 files 数组 JSON（服务端截到 2000 条）。 */
    @Column(columnDefinition = "TEXT")
    private String payloadJson;

    /** FAILED 原因（用户可读）。 */
    @Column(length = 1024)
    private String errorMessage;

    private Integer chargedCredits;

    /** 官网流水 id，退款要用。 */
    @Column(length = 64)
    private String chargeLedgerId;

    private LocalDateTime refundedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
