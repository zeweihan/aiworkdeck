package com.checkba.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 插件后台任务（插件规范 v2.4 §11 Jobs）。id 是 26 位 ULID（宿主生成），
 * 状态 queued / running / done / failed / cancelled 只前进不回退。
 * 进度字段由 {@code PluginJobService} 节流落库，内存态才是实时值。
 */
@Entity
@Table(name = "plugin_job", indexes = {
        @Index(name = "idx_plugin_job_project", columnList = "project_id,created_at"),
        @Index(name = "idx_plugin_job_status", columnList = "status")
})
@Getter
@Setter
public class PluginJob {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    @Id
    @Column(length = 26, nullable = false)
    private String id;

    @Column(name = "plugin_id", length = 64, nullable = false)
    private String pluginId;

    @Column(length = 64, nullable = false)
    private String kind;

    @Column(length = 256)
    private String title;

    @Column(length = 16, nullable = false)
    private String status = STATUS_QUEUED;

    @Column(nullable = false)
    private long done;

    @Column(nullable = false)
    private long total;

    @Column(length = 512)
    private String message;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isTerminal() {
        return STATUS_DONE.equals(status) || STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status);
    }
}
