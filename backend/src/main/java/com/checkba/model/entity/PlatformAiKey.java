package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 每用户一把的平台 AI 通道密钥（server 模式多租户）。
 *
 * <p>为什么不挂在 {@link AccountBinding} 上：那张表是纯身份映射、每次桥接登录都要读，
 * 把密文塞进去会改变它的安全等级；分表还让「吊销即删行」不碰身份数据。
 *
 * <p>{@code keyEnc} 是 OpenRouter runtime key 明文的 AES-256-GCM 密文
 * （{@link com.checkba.service.ai.PlatformAiKeyCipher}）。awdk_ 账户 Key 明文
 * <b>永不落库</b>——本表存的是它换来的、额度受 OpenRouter 侧 limit 强制的 runtime key。
 */
@Entity
@Table(name = "platform_ai_key",
        uniqueConstraints = @UniqueConstraint(columnNames = {"userId"}))
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PlatformAiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 密文，格式 v1:<ivB64>:<tagB64>:<cipherB64>。 */
    @Column(nullable = false, length = 1024)
    private String keyEnc;

    /** SHA-256(明文) 前 12 位十六进制：模型实例缓存键与对账 baseline 的分桶键。 */
    @Column(nullable = false, length = 32)
    private String keyFingerprint;

    /** 官网返回的额度上限（美元），展示用；官网未给时为 null。 */
    private Double limitUsd;

    @Column(nullable = false)
    private LocalDateTime fetchedAt;

    /**
     * 最近一次向 OpenRouter 验证成功的时间。超过 30 天未成功验证即判为过期
     * （与 EntitlementService/LicenseService 的 OFFLINE_GRACE 同口径：
     * 永久离线不能等于永久可用）。
     */
    @Column(nullable = false)
    private LocalDateTime lastVerifiedAt;
}
