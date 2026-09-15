// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 云端协作的长期设备令牌。库里只存 SHA-256，明文只在发放时返回一次。 */
@Entity
@Table(name = "device_token",
        uniqueConstraints = @UniqueConstraint(columnNames = {"token_hash"}))
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(length = 128)
    private String name;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;
}
