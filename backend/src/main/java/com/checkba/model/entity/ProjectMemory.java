package com.checkba.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 项目记忆实体
 * 存储项目级别的长期记忆信息
 */
@Entity
@Table(name = "project_memory")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true) // 仅用 id，避免遍历 JSON 集合字段/未持久化时不稳
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * 项目ID（唯一）
     */
    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    /**
     * 项目名称
     */
    @Column(name = "project_name", length = 200)
    private String projectName;

    /**
     * 项目类型（重大资产重组/再融资等）
     */
    @Column(name = "project_type", length = 100)
    private String projectType;

    /**
     * 上市公司
     */
    @Column(name = "listed_company", length = 200)
    private String listedCompany;

    /**
     * 标的公司
     */
    @Column(name = "target_company", length = 200)
    private String targetCompany;

    /**
     * 交易结构描述
     */
    @Column(name = "transaction_structure", columnDefinition = "TEXT")
    private String transactionStructure;

    /**
     * 交易金额
     */
    @Column(name = "transaction_amount", precision = 20, scale = 2)
    private BigDecimal transactionAmount;

    /**
     * 关键日期（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_dates")
    private Map<String, String> keyDates;

    /**
     * 交易各方（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parties")
    private List<Map<String, String>> parties;

    /**
     * 关键变量（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_variables")
    private Map<String, String> keyVariables;

    /**
     * 法律引用（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "legal_refs")
    private List<String> legalRefs;

    /**
     * 核查结论（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "check_conclusions")
    private List<Map<String, String>> checkConclusions;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 转换为上下文字符串，用于注入到系统提示词
     */
    public String toCoreContext() {
        StringBuilder sb = new StringBuilder();
        
        if (projectName != null) {
            sb.append("- 项目名称: ").append(projectName).append("\n");
        }
        if (projectType != null) {
            sb.append("- 项目类型: ").append(projectType).append("\n");
        }
        if (listedCompany != null) {
            sb.append("- 上市公司: ").append(listedCompany).append("\n");
        }
        if (targetCompany != null) {
            sb.append("- 标的公司: ").append(targetCompany).append("\n");
        }
        if (transactionAmount != null) {
            sb.append("- 交易金额: ").append(transactionAmount).append("元\n");
        }
        if (transactionStructure != null) {
            sb.append("- 交易结构: ").append(transactionStructure).append("\n");
        }
        if (parties != null && !parties.isEmpty()) {
            sb.append("- 交易各方:\n");
            for (Map<String, String> party : parties) {
                sb.append("  - ").append(party.get("role")).append(": ").append(party.get("name")).append("\n");
            }
        }
        if (keyDates != null && !keyDates.isEmpty()) {
            sb.append("- 关键日期:\n");
            keyDates.forEach((k, v) -> sb.append("  - ").append(k).append(": ").append(v).append("\n"));
        }
        
        return sb.toString();
    }
}

