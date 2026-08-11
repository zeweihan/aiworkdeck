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
 * 文件-标签关联实体
 */
@Entity
@Table(name = "project_file_tag", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"file_id", "tag_id"})
})
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true) // 仅用 id：join 实体最易被放进 Set 去重，字段型 hashCode 在 save 前后会变
public class FileTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * 文件 ID
     */
    @Column(nullable = false)
    private Long fileId;

    /**
     * 标签 ID
     */
    @Column(nullable = false)
    private Long tagId;

    /**
     * 创建者（打标签的人，系统打标则为 null 或特定系统 ID）
     */
    @Column
    private Long createdBy;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
