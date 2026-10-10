// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.model.entity;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Durable idempotency receipt; survives relay cleanup and file movement/deletion. */
@Entity
@Table(name="mobile_cloud_receipt", uniqueConstraints=@UniqueConstraint(columnNames={"user_id", "client_media_id"}))
@Data
public class MobileCloudReceipt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long userId;
    @Column(nullable=false, length=36) private String clientMediaId;
    @Column(nullable=false) private Long projectId;
    @Column(length=36) private String projectUid;
    @Column(nullable=false) private Long fileId;
    @Column(nullable=false, length=36) private String fileUid;
    @Column(nullable=false) private LocalDateTime createdAt;
}
