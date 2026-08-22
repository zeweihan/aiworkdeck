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
import java.util.Set;

/**
 * 证据链接子表：一条 = 一个底稿位置（id 即 filelink&t= 里的 t）。
 * 应用层随 {@link EvidenceLink} 级联删除；file_id 必须属于同项目（IDOR 校验在 Service）。
 */
@Entity
@Table(name = "evidence_link_target", indexes = {
        @Index(name = "idx_evt_link", columnList = "link_id"),
        @Index(name = "idx_evt_file", columnList = "file_id"),
        @Index(name = "idx_evt_dedupe", columnList = "link_id,file_id,locator_hash", unique = true)
})
@Getter
@Setter
public class EvidenceLinkTarget {

    public static final String RELATION_SUPPORTS = "supports";
    public static final Set<String> RELATIONS = Set.of("supports", "contradicts", "partial");
    public static final Set<String> METHODS = Set.of("written_review", "written_statement", "web_check", "third_party", "interview");
    /** 空 locator 的 locator_hash 占位值。 */
    public static final String EMPTY_LOCATOR_HASH = "-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_id", nullable = false)
    private Long linkId;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    /** 分型 locator（type=pdf/docx/image/media/web/sheet），可空 = 整个文件。 */
    @Column(name = "locator_json", columnDefinition = "TEXT")
    private String locatorJson;

    /** sha256(canonical locatorJson)，空 locator 记 "-"；只为唯一索引服务。 */
    @Column(name = "locator_hash", length = 64, nullable = false)
    private String locatorHash = EMPTY_LOCATOR_HASH;

    /** supports / contradicts / partial。 */
    @Column(length = 16, nullable = false)
    private String relation = RELATION_SUPPORTS;

    /**
     * written_review / written_statement / web_check / third_party / interview，可空。
     *
     * <p>长度 32 不是随手写的：`written_statement` 有 17 个字符，原来的 length=16 让这一档**永远存不进去**
     * （真实起草跑分里报 "Value too long for column method"，凡是「书面确认」型的链接全部落库失败）。
     * 枚举里最长的值决定列宽，改枚举时一并核对。
     */
    @Column(length = 32)
    private String method;

    /** 0-100，人工建 = null。 */
    @Column
    private Short confidence;

    @Column(length = 512)
    private String note;

    /**
     * 勾稽核查（P2，dev-board#116）的结构化 findings：
     * {@code {checkedAt, verdict, checks:[{kind, expected, found, ok, note}], priorRelation}}。
     * 由 {@link com.checkba.service.evidence.EvidenceVerifyService} 整条覆盖写，幂等可重跑；
     * 没核查过为 null。verdict 只影响本列与 relation/confidence，<b>不参与 EvidenceLink 状态机</b>。
     */
    @Column(name = "verify_json", columnDefinition = "TEXT")
    private String verifyJson;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "created_by_kind", length = 8)
    private String createdByKind;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
