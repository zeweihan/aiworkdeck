package com.checkba.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 项目标签实体
 */
@Entity
@Table(name = "project_tag", uniqueConstraints = {
    // 同项目内标签名唯一：应用层 existsByProjectIdAndName 是"先查后插"，并发下仍可能重复；
    // DB 唯一约束兜底，避免重复标签导致 findByProjectIdAndName 抛 NonUniqueResultException。
    @UniqueConstraint(columnNames = {"projectId", "name"})
})
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true) // 仅用 id，与仓库其余实体一致，避免可变字段/未持久化时的 hashCode 陷阱
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * 所属项目 ID
     */
    @Column(nullable = false)
    private Long projectId;

    /**
     * 标签名称
     */
    @Column(nullable = false, length = 64)
    private String name;

    /**
     * 标签颜色 (Hex Code, e.g., #FF5733)
     */
    @Column(length = 7)
    private String color;

    /**
     * 是否为系统自动生成的标签（不可随意修改名称，但可以删除）
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean isSystem = false;
    
    /**
     * 描述
     */
    @Column(length = 512)
    private String description;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
