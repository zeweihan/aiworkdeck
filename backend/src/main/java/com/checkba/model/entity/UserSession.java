package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 浏览器登录会话（DB 落库形态）。库里只存 SHA-256，明文只在登录时返回一次。
 * 与 DeviceToken（awdt_ 长期设备令牌）是两种凭据：会话按 lastUsedAt 滑动过期，
 * 设备令牌长期有效、由用户显式吊销。
 */
@Entity
@Table(name = "user_session",
        uniqueConstraints = @UniqueConstraint(columnNames = {"token_hash"}))
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastUsedAt;
}
