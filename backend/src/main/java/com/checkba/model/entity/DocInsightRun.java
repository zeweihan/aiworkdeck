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
 * 文档「解析」的一次运行（dev-board#181/#182）：一份文档 → 实体抽取 + 外部检索 + 一致性校验。
 *
 * <p>一次解析产出一行 run，外加若干 {@link DocInsightEntity} 与 {@link DocInsightFinding}。
 * 前端「依据」窗格按 (projectId, docFileId) 取最新一条 run 轮询展示。
 *
 * <p>状态机极简：RUNNING → DONE / FAILED，没有暂停与续跑。解析是一次性的批处理，
 * 中断了重跑一遍就行——不像对话轮次那样有「用户还在等一个答案」的语义。
 */
@Entity
@Table(name = "doc_insight_run", indexes = {
        // 窗格按 (项目, 文档) 取最新一条；单飞判定也走这条
        @Index(name = "idx_dir_project_doc", columnList = "project_id,doc_file_id,started_at")
})
@Getter
@Setter
public class DocInsightRun {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 被解析的 {@link ProjectFile#getId()}。 */
    @Column(name = "doc_file_id", nullable = false)
    private Long docFileId;

    @Column(name = "status", length = 16, nullable = false)
    private String status = STATUS_RUNNING;

    /** 可读进度短语（「抽取实体 2/5 段」「检索外部库 3/12」），前端直接显示，不做解析。 */
    @Column(name = "phase", length = 200)
    private String phase;

    /** 失败原因（可读中文），status=FAILED 时非空。 */
    @Column(name = "error", length = 1000)
    private String error;

    /** 本次抽取用的辅助模型 ID，排障与记账对账用。 */
    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
