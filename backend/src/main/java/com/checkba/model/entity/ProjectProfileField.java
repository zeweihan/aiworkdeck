package com.checkba.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 项目档案字段（一个字段一行——字段级来源标记要求行级粒度）。
 *
 * 固定五个 fieldKey：client / matterType / openedAt / nextStep / counterparty。
 *
 * source 库里只有两种取值：'ai'（AI 抽取）与 'user'（律师手填）。
 * 响应里可能出现的 'default' 是服务端为 openedAt 派生的（回落 Project.createdAt），
 * 永不落库。
 *
 * 核心不变式：source='user' 的字段锁定，AI 永不覆盖——AI 有新判断时写进同一行的
 * pending* 四列（唯一约束是 (projectId, fieldKey)，建议不能另起一行），律师采纳后才转正。
 *
 * 样板对齐 DdItem：手写 getter/setter，equals/hashCode 只比 id。
 * 不用 Lombok @Data——它生成覆盖全字段的 equals/hashCode，在 JPA 游离态与集合里行为不可预期。
 */
@Entity
@Table(
        name = "project_profile_field",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_profile_field_project_key",
                columnNames = {"project_id", "field_key"}),
        indexes = @Index(name = "idx_profile_field_project", columnList = "project_id")
)
public class ProjectProfileField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 */
    @Column(nullable = false)
    private Long projectId;

    /** client / matterType / openedAt / nextStep / counterparty */
    @Column(length = 64, nullable = false)
    private String fieldKey;

    /**
     * 档案值。
     * 用 VARCHAR 不用 TEXT/@Lob：@Lob 在 PostgreSQL 上映射成 OID 会炸，
     * columnDefinition="TEXT" 要在 H2/MySQL8/PG 三种库上各验；VARCHAR(2048) 三种库通吃。
     * ddl-auto=update 不会自动加宽，所以一次给够。
     */
    @Column(length = 2048)
    private String fieldValue;

    /** 库里只存 'ai' | 'user' */
    @Column(length = 8, nullable = false)
    private String source;

    /** AI 填时的置信度，user 填时 null */
    @Column
    private Double confidence;

    /** AI 是从哪份文件哪句话得出的 */
    @Column(length = 4000)
    private String evidence;

    /** Plan 2 的 AI 建议值，挂在同一行 */
    @Column(length = 2048)
    private String pendingValue;

    @Column
    private Double pendingConfidence;

    @Column(length = 4000)
    private String pendingEvidence;

    @Column
    private LocalDateTime pendingAt;

    /** UUID，跨机器身份，.awd/profile.json 同步只认它 */
    @Column(length = 36, nullable = false)
    private String uid;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** 字段级 LWW 的裁决依据 */
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getFieldKey() {
        return fieldKey;
    }

    public void setFieldKey(String fieldKey) {
        this.fieldKey = fieldKey;
    }

    public String getFieldValue() {
        return fieldValue;
    }

    public void setFieldValue(String fieldValue) {
        this.fieldValue = fieldValue;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public String getPendingValue() {
        return pendingValue;
    }

    public void setPendingValue(String pendingValue) {
        this.pendingValue = pendingValue;
    }

    public Double getPendingConfidence() {
        return pendingConfidence;
    }

    public void setPendingConfidence(Double pendingConfidence) {
        this.pendingConfidence = pendingConfidence;
    }

    public String getPendingEvidence() {
        return pendingEvidence;
    }

    public void setPendingEvidence(String pendingEvidence) {
        this.pendingEvidence = pendingEvidence;
    }

    public LocalDateTime getPendingAt() {
        return pendingAt;
    }

    public void setPendingAt(LocalDateTime pendingAt) {
        this.pendingAt = pendingAt;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectProfileField that = (ProjectProfileField) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
