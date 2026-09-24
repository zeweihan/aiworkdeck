// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;

/**
 * 事项 ↔ 文件多对多关联（dev-board#895，spec：
 * docs/superpowers/specs/2026-09-25-task-calendar-redesign.md 第一节）。
 *
 * 裸 Long 外键，不做 JPA 关联、不靠 cascade：删事项时由 ProjectTaskService 显式删关联行，
 * 删项目时由 ProjectService.deleteProject 按 taskId 子查询级联。文件被删时关联行保留，
 * 展示为悬空（fileName=null），与 ProjectTask.fileId 的既有口径一致。
 *
 * 关联集合的顺序按 id 升序（即写入顺序），第一个同步写回 ProjectTask.fileId。
 */
@Entity
@Table(
        name = "project_task_file",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_task_file", columnNames = {"task_id", "file_id"}),
        indexes = {
                @Index(name = "idx_project_task_file_file", columnList = "file_id")
        }
)
public class ProjectTaskFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    public ProjectTaskFile() {
    }

    public ProjectTaskFile(Long taskId, Long fileId) {
        this.taskId = taskId;
        this.fileId = fileId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectTaskFile that = (ProjectTaskFile) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
