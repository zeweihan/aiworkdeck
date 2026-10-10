// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.model.entity;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Metadata only: the last successful desktop LIST, never file bytes. */
@Entity
@Table(name = "mobile_project_manifest", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_id", "project_key"}))
@Data
public class MobileProjectManifest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false, length = 64) private String deviceId;
    @Column(nullable = false, length = 64) private String projectKey;
    @Column(nullable = false, columnDefinition = "TEXT") private String payloadJson;
    @Column(nullable = false) private LocalDateTime updatedAt;
}
