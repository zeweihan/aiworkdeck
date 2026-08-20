package com.checkba.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 公司镜像实体
 *
 * 说明：
 * - 用于保存从企查查等外部服务拉取的公司基础信息快照；
 * - 既可承载上市公司，也可承载标的公司（通过 role 区分）；
 * - 列表类字段（股东、董监高等）暂以 JSON 字符串存储，后续有需要可以拆分子表。
 */
@Entity
@Table(name = "company_mirror")
public class CompanyMirror {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 公司角色：LISTED / TARGET 等
     */
    @Column(length = 32, nullable = false)
    private String role;

    /**
     * 公司名称 / 全称
     */
    @Column(length = 256, nullable = false)
    private String name;

    /**
     * 证券代码（如有）
     */
    @Column(length = 32)
    private String stockCode;

    /**
     * 公司全称
     */
    @Column(length = 256)
    private String fullName;

    /**
     * 公司简称
     */
    @Column(length = 128)
    private String shortName;

    /**
     * 所属板块
     */
    @Column(length = 128)
    private String board;

    /**
     * 股份总数（字符串形式保存，方便兼容“万股”等单位）
     */
    @Column(length = 128)
    private String totalShares;

    /**
     * 最新收盘价
     */
    @Column(length = 64)
    private String latestClosePrice;

    /**
     * 注册地址（标的公司为主）
     */
    @Column(length = 512)
    private String registeredAddress;

    /**
     * 注册资本
     */
    @Column(length = 128)
    private String registeredCapital;

    /**
     * 股权结构说明
     */
    @Column(length = 1024)
    private String equityStructureRemark;

    /**
     * 前十大股东列表 JSON。
     * 长文本不用 @Lob：PG 方言下 @Lob String 走 large-object（oid）读写会炸，
     * LONGVARCHAR + TEXT 在 H2（MODE=PostgreSQL）与 PG 双方言通吃（下两个 JSON 字段同理）。
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(columnDefinition = "TEXT")
    private String top10ShareholdersJson;

    /**
     * 董监高列表 JSON
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(columnDefinition = "TEXT")
    private String executivesJson;

    /**
     * 股东列表 JSON（标的公司）
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(columnDefinition = "TEXT")
    private String shareholdersJson;

    /**
     * 记录来源（例如：QICHACHA），便于后续扩展其他来源
     */
    @Column(length = 64)
    private String source;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最近更新时间
     */
    private LocalDateTime updatedAt;
    
    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = stockCode;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getBoard() {
        return board;
    }

    public void setBoard(String board) {
        this.board = board;
    }

    public String getTotalShares() {
        return totalShares;
    }

    public void setTotalShares(String totalShares) {
        this.totalShares = totalShares;
    }

    public String getLatestClosePrice() {
        return latestClosePrice;
    }

    public void setLatestClosePrice(String latestClosePrice) {
        this.latestClosePrice = latestClosePrice;
    }

    public String getRegisteredAddress() {
        return registeredAddress;
    }

    public void setRegisteredAddress(String registeredAddress) {
        this.registeredAddress = registeredAddress;
    }

    public String getRegisteredCapital() {
        return registeredCapital;
    }

    public void setRegisteredCapital(String registeredCapital) {
        this.registeredCapital = registeredCapital;
    }

    public String getEquityStructureRemark() {
        return equityStructureRemark;
    }

    public void setEquityStructureRemark(String equityStructureRemark) {
        this.equityStructureRemark = equityStructureRemark;
    }

    public String getTop10ShareholdersJson() {
        return top10ShareholdersJson;
    }

    public void setTop10ShareholdersJson(String top10ShareholdersJson) {
        this.top10ShareholdersJson = top10ShareholdersJson;
    }

    public String getExecutivesJson() {
        return executivesJson;
    }

    public void setExecutivesJson(String executivesJson) {
        this.executivesJson = executivesJson;
    }

    public String getShareholdersJson() {
        return shareholdersJson;
    }

    public void setShareholdersJson(String shareholdersJson) {
        this.shareholdersJson = shareholdersJson;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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
        CompanyMirror that = (CompanyMirror) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return "CompanyMirror{" +
                "id=" + id +
                ", role='" + role + '\'' +
                ", name='" + name + '\'' +
                '}';
    }
}
