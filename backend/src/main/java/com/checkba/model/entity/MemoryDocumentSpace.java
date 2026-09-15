// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "memory_document_space",
        uniqueConstraints = @UniqueConstraint(columnNames = {"scope", "owner_key"}))
public class MemoryDocumentSpace {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 16)
    private String scope;

    @Column(name = "owner_key", nullable = false, length = 160)
    private String ownerKey;

    @Column(nullable = false, length = 256)
    private String label;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getOwnerKey() { return ownerKey; }
    public void setOwnerKey(String ownerKey) { this.ownerKey = ownerKey; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
