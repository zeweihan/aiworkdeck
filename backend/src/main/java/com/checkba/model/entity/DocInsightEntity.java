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
 * 文档里抽出来的一个实体（企业 / 法规 / 案例）及其外部库检索结果（dev-board#182）。
 *
 * <p><b>检索状态四态</b>：PENDING（还没打上游）、OK（拿到结果）、UNAVAILABLE（通道不可用——
 * 未配置、点数耗尽、网关未开放；<b>不是查无此项</b>）、ERROR（打了但失败）。
 * UNAVAILABLE 与 ERROR 都必须把可读原因写进 {@link #retrievalNote}：窗格里显示
 * 「法宝检索本次不可用：账号点数耗尽」远好过一个空白格子。
 */
@Entity
@Table(name = "doc_insight_entity", indexes = {
        @Index(name = "idx_die_run", columnList = "run_id"),
        // 7 天缓存命中查询：同项目同类同归一键的最近一次成功检索
        @Index(name = "idx_die_cache", columnList = "project_id,kind,norm_key,fetched_at")
})
@Getter
@Setter
public class DocInsightEntity {

    public static final String KIND_COMPANY = "COMPANY";
    public static final String KIND_LAW = "LAW";
    public static final String KIND_CASE = "CASE";

    public static final String RETRIEVAL_PENDING = "PENDING";
    public static final String RETRIEVAL_OK = "OK";
    /** 通道不可用（未配置 / 点数耗尽 / 未开放）。与「查了但没查到」是两回事。 */
    public static final String RETRIEVAL_UNAVAILABLE = "UNAVAILABLE";
    public static final String RETRIEVAL_ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "doc_file_id", nullable = false)
    private Long docFileId;

    /** COMPANY / LAW / CASE。 */
    @Column(name = "kind", length = 16, nullable = false)
    private String kind;

    /** 展示名（企业全称 / 法规名+条号 / 案号或案件标题）。 */
    @Column(name = "name", length = 500, nullable = false)
    private String name;

    /** 归一键：去重与缓存命中都按它。 */
    @Column(name = "norm_key", length = 500, nullable = false)
    private String normKey;

    /** {@code [{"quote":"...","paragraph":null}]}，最多 20 条、每条 quote ≤ 120 字。 */
    @Column(name = "mentions_json", columnDefinition = "TEXT")
    private String mentionsJson;

    @Column(name = "retrieval_status", length = 16, nullable = false)
    private String retrievalStatus = RETRIEVAL_PENDING;

    /** 结果来自哪条通道：qichacha / qichacha-mcp / pkulaw-semantic / pkulaw-keyword / 案例 server 名。 */
    @Column(name = "retrieval_source", length = 64)
    private String retrievalSource;

    /** 检索结果 JSON 原文（企业档为裁过的工商摘要，法规/案例为上游返回正文）。 */
    @Column(name = "retrieval_json", columnDefinition = "TEXT")
    private String retrievalJson;

    /** 不可用/失败的可读原因，直接显示给用户。 */
    @Column(name = "retrieval_note", length = 1000)
    private String retrievalNote;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;
}
