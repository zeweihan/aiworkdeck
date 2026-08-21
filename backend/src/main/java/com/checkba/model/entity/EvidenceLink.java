package com.checkba.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 证据链接主表：一条 = 报告文档里一个书签锚点（link_key 即书签名）。
 * 底稿位置在子表 {@link EvidenceLinkTarget}。契约见 .claude/agents/ai-doc-bridge.md「EvidenceLink 契约」。
 *
 * 状态机：human/plugin 建链 → active；ai 建链 → unverified；worker 核对发现锚点文字变了 → stale；
 * 书签不在了（任何状态）→ orphan；用户「保留关联」→ active；「重新指定」（换 linkKey）→ active。
 * 状态只由 worker 核对结果与用户动作驱动，后端唯一例外是文件彻底删除后 target 清空 → orphan。
 */
@Entity
@Table(name = "evidence_link", indexes = {
        @Index(name = "idx_evl_project_key", columnList = "project_id,link_key", unique = true),
        @Index(name = "idx_evl_doc", columnList = "project_id,doc_file_id"),
        @Index(name = "idx_evl_section", columnList = "project_id,doc_file_id,section_path")
})
@Getter
@Setter
public class EvidenceLink {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_UNVERIFIED = "unverified";
    public static final String STATUS_STALE = "stale";
    public static final String STATUS_ORPHAN = "orphan";

    public static final String KIND_HUMAN = "human";
    public static final String KIND_AI = "ai";
    public static final String KIND_PLUGIN = "plugin";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 报告所在 ProjectFile.id（不再用 wpsFileId 软引用）。 */
    @Column(name = "doc_file_id", nullable = false)
    private Long docFileId;

    /** = 文档内书签名。新建 EVID_<26 位 ULID>；迁移行保留原 lk_*。 */
    @Column(name = "link_key", length = 64, nullable = false)
    private String linkKey;

    @Column(name = "anchor_text", length = 1000)
    private String anchorText;

    /** sha256(AnchorHash.normalize(anchorText))。 */
    @Column(name = "anchor_hash", length = 64)
    private String anchorHash;

    /** 书签所在标题链，如 一/（二）/3；worker 派生，不可靠时为空。 */
    @Column(name = "section_path", length = 512)
    private String sectionPath;

    @Column(name = "section_title", length = 512)
    private String sectionTitle;

    @Column(length = 16, nullable = false)
    private String status = STATUS_ACTIVE;

    @Column(name = "created_by_kind", length = 8, nullable = false)
    private String createdByKind = KIND_HUMAN;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 最近一次 worker 核对书签的时间。 */
    @Column(name = "checked_at")
    private LocalDateTime checkedAt;
}
