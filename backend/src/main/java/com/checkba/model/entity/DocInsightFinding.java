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
 * 文档内部一致性校验的一条发现（dev-board#182）：正文写 58 项、附表只有 39 项这类前后矛盾。
 *
 * <p><b>detailJson 是给前端做「一键修改」的数据，不只是给人看的说明</b>——形状与不变式
 * 见 {@code service/insight/DocInsightChecks}。核心是每个 claim 的 {@code numberText}
 * 必须是 {@code quote} 里的<b>逐字子串</b>，前端据此做 {@code quote.replace(numberText, 新值)}；
 * 校不过就只给 {@code fixable:false}（仍展示，只是不给一键修改按钮），绝不给一个对不上的串。
 */
@Entity
@Table(name = "doc_insight_finding", indexes = {
        @Index(name = "idx_dif_run", columnList = "run_id"),
        @Index(name = "idx_dif_project_doc", columnList = "project_id,doc_file_id")
})
@Getter
@Setter
public class DocInsightFinding {

    /** 同一主体同一指标出现两个及以上不同数值。 */
    public static final String KIND_COUNT_MISMATCH = "COUNT_MISMATCH";
    /** 统一社会信用代码校验位不符（陈述自身的硬错，与外部库无关）。 */
    public static final String KIND_USCC_INVALID = "USCC_INVALID";
    /** 引用的法条条号在北大法宝检索不到（引用校验步，见 DocInsightChecks 的形状注释）。 */
    public static final String KIND_CITATION_NOT_FOUND = "CITATION_NOT_FOUND";
    /** 按引文内容定位到的条文与引用的条号对不上（同上；条文可能被重编号，永不给一键修改）。 */
    public static final String KIND_CITATION_MISMATCH = "CITATION_MISMATCH";

    public static final String SEVERITY_WARN = "warn";
    public static final String SEVERITY_ERROR = "error";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "doc_file_id", nullable = false)
    private Long docFileId;

    @Column(name = "kind", length = 32, nullable = false)
    private String kind;

    @Column(name = "severity", length = 16, nullable = false)
    private String severity = SEVERITY_WARN;

    /** 一句话说清是什么矛盾，列表直接显示。 */
    @Column(name = "title", length = 500, nullable = false)
    private String title;

    /** 结构化明细，含定位用 quote 与替换用 numberText。 */
    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
