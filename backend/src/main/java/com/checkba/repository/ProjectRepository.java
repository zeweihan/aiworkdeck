// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE Project p SET p.uid = :uid WHERE p.id = :id AND ((:previous IS NULL AND p.uid IS NULL) OR p.uid = :previous)")
    int assignCatalogUidIfUnchanged(Long id, String previous, String uid);

    @org.springframework.data.jpa.repository.Query("SELECT p.uid FROM Project p WHERE p.id = :id")
    String readCatalogUid(Long id);

    /**
     * 根据用户 ID 查询项目列表，按创建时间倒序
     */
    List<Project> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 查询所有 userId 为 null 的项目
     */
    List<Project> findByUserIdIsNull();

    /**
     * 某用户名下的项目数（本机身份候选的「数据量」信号之一，见 LocalIdentityService）
     */
    long countByUserId(Long userId);

    /**
     * 按本地文件夹根目录查项目（IDE 化本地文件夹项目，路径存入前已 normalize）
     */
    java.util.Optional<Project> findByLocalRoot(String localRoot);

    /**
     * 所有已绑定本地文件夹的项目（用于嵌套校验）
     */
    List<Project> findByLocalRootIsNotNull();
}


