package com.checkba.repository;

import com.checkba.model.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {
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


