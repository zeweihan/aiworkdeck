package com.checkba.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 项目实体
 *
 * 说明：
 * - 用于持久化“创建项目”时录入的基础信息；
 * - 目前只包含项目级元数据和公司基础信息 JSON，后续可以在本实体上继续扩展。
 */
@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 项目名称
     */
    @Column(length = 256, nullable = false)
    private String name;

    /**
     * 项目类型，例如：MAJOR_ASSET_RESTRUCTURING
     */
    @Column(length = 64, nullable = false)
    private String projectType;

    /**
     * 上市公司名称
     */
    @Column(length = 256, nullable = false)
    private String listedCompanyName;

    /**
     * 标的公司名称
     */
    @Column(length = 256, nullable = false)
    private String targetCompanyName;

    /**
     * 上市公司从外部服务补全的基础信息 JSON
     */
    @Column(columnDefinition = "TEXT")
    private String listedCompanyInfoJson;

    /**
     * 标的公司从外部服务补全的基础信息 JSON
     */
    @Column(columnDefinition = "TEXT")
    private String targetCompanyInfoJson;

    /**
     * 项目创建者用户 ID
     */
    @Column(nullable = false)
    private Long userId;

    /**
     * IDE 化本地文件夹项目的物理根目录（绝对路径）。
     * 为空 = 存量托管项目，文件在 {globalRoot}/projects/{id} 下（见 ProjectStorageResolver）。
     */
    @Column(columnDefinition = "TEXT")
    private String localRoot;

    /**
     * 律师明确关掉过版本记录（dev-board#438）。为空 = 从没关过。
     * 关闭并删除历史之后，两个自动开启触发点都必须认这一列——否则下一次改动信号
     * 又把它开回来，「关掉」形同虚设。手动开启（POST /version/enable）会清掉它。
     */
    private Boolean versionOptOut;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最近更新时间
     */
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProjectType() {
        return projectType;
    }

    public void setProjectType(String projectType) {
        this.projectType = projectType;
    }

    public String getListedCompanyName() {
        return listedCompanyName;
    }

    public void setListedCompanyName(String listedCompanyName) {
        this.listedCompanyName = listedCompanyName;
    }

    public String getTargetCompanyName() {
        return targetCompanyName;
    }

    public void setTargetCompanyName(String targetCompanyName) {
        this.targetCompanyName = targetCompanyName;
    }

    public String getLocalRoot() {
        return localRoot;
    }

    public void setLocalRoot(String localRoot) {
        this.localRoot = localRoot;
    }

    public String getListedCompanyInfoJson() {
        return listedCompanyInfoJson;
    }

    public void setListedCompanyInfoJson(String listedCompanyInfoJson) {
        this.listedCompanyInfoJson = listedCompanyInfoJson;
    }

    public String getTargetCompanyInfoJson() {
        return targetCompanyInfoJson;
    }

    public void setTargetCompanyInfoJson(String targetCompanyInfoJson) {
        this.targetCompanyInfoJson = targetCompanyInfoJson;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Boolean getVersionOptOut() {
        return versionOptOut;
    }

    public void setVersionOptOut(Boolean versionOptOut) {
        this.versionOptOut = versionOptOut;
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
        Project project = (Project) o;
        return Objects.equals(id, project.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
