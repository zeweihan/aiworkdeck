// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.ProjectTaskFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 事项 ↔ 文件关联仓储（dev-board#895）。
 */
public interface ProjectTaskFileRepository extends JpaRepository<ProjectTaskFile, Long> {

    /** 批量取若干事项的关联行，按 id 升序（= 写入顺序，第一个即 ProjectTask.fileId）。 */
    List<ProjectTaskFile> findByTaskIdInOrderByIdAsc(Collection<Long> taskIds);

    /**
     * 删一个事项的全部关联行。
     *
     * 刻意用批量 JPQL 而不是派生 deleteByTaskId：派生删除走 em.remove 排队，Hibernate 的
     * flush 顺序是先 insert 后 delete，整体替换时重新加入同一文件会先撞 (task_id, file_id)
     * 唯一索引。批量语句立即执行，没有这个顺序问题。
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM ProjectTaskFile f WHERE f.taskId = :taskId")
    int deleteAllByTaskId(@Param("taskId") Long taskId);
}
