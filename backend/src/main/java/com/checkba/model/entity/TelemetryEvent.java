package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 产品埋点本地明细账本（设计见 docs/ANALYTICS_TELEMETRY_DESIGN.md）。
 *
 * 与 user_activity_log（律师工时计费功能）严格分离，互不复用。
 * attrs 只允许 TelemetryAttrWhitelist 放行的枚举/数值/布尔字段，
 * 永不存放文件名、路径、消息文本等用户内容。
 * 本地保留 90 天滚动清理；默认仅日聚合计数出本机（见 TelemetryUploadService）。
 */
@Getter
@Setter
@Entity
@Table(name = "telemetry_event", indexes = {
        @Index(name = "idx_telemetry_event_ts", columnList = "ts")
})
public class TelemetryEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant ts;

    @Column(name = "event_name", length = 64, nullable = false)
    private String eventName;

    /** 白名单过滤后的属性 JSON */
    @Column(columnDefinition = "TEXT")
    private String attrs;

    /** HMAC 派生的会话关联键（16 hex），可空；绝不存原始 conversationId */
    @Column(name = "conv_key", length = 16)
    private String convKey;

    @Column(name = "app_version", length = 32)
    private String appVersion;
}
