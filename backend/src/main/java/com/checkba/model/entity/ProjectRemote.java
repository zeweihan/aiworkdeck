package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 一个项目与云端仓库的绑定关系。projectId 唯一——一个项目至多接一个云端。 */
@Entity
@Table(name = "project_remote",
        uniqueConstraints = @UniqueConstraint(columnNames = {"projectId"}))
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ProjectRemote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long connectionId;

    private String remoteProjectId;

    private String lastSyncSha;

    @Column(nullable = false)
    private Boolean pendingUpload = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
