package com.checkba.repository;

import com.checkba.model.entity.ProjectFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectFileRepository extends JpaRepository<ProjectFile, Long> {
    /**
     * 根据项目 ID 查询所有文件（包含已删除），用于文件树清单采集
     */
    List<ProjectFile> findByProjectId(Long projectId);

    /**
     * 根据项目 ID 和父文件夹 ID 查询文件列表（按排序序号排序，排除已删除）
     */
    List<ProjectFile> findByProjectIdAndParentIdAndIsDeletedFalseOrderBySortOrderAsc(Long projectId, Long parentId);
    
    // Legacy support: alias to above (implicit filter)
    // FIX: Use IS NULL check for null parentId, otherwise = comparison
    @org.springframework.data.jpa.repository.Query("SELECT pf FROM ProjectFile pf WHERE pf.projectId = :projectId AND (:parentId IS NULL AND pf.parentId IS NULL OR pf.parentId = :parentId) AND pf.isDeleted = false ORDER BY pf.sortOrder ASC")
    List<ProjectFile> findByProjectIdAndParentIdOrderBySortOrderAsc(Long projectId, Long parentId);

    /**
     * 根据项目 ID 和父文件夹 ID 查询文件列表（按排序序号排序，包含已删除）
     * 用于彻底删除和还原时查找所有子文件
     */
    @org.springframework.data.jpa.repository.Query("SELECT pf FROM ProjectFile pf WHERE pf.projectId = :projectId AND (:parentId IS NULL AND pf.parentId IS NULL OR pf.parentId = :parentId) ORDER BY pf.sortOrder ASC")
    List<ProjectFile> findByProjectIdAndParentId(Long projectId, Long parentId);

    /**
     * 根据项目 ID 查询所有文件（排除已删除）
     */
    List<ProjectFile> findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(Long projectId);
    
    @org.springframework.data.jpa.repository.Query("SELECT pf FROM ProjectFile pf WHERE pf.projectId = :projectId AND pf.isDeleted = false ORDER BY pf.sortOrder ASC")
    List<ProjectFile> findByProjectIdOrderBySortOrderAsc(Long projectId);

    /**
     * 根据父文件夹 ID 查询子文件数量（排除已删除）
     */
    long countByParentIdAndIsDeletedFalse(Long parentId);
    
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(pf) FROM ProjectFile pf WHERE pf.parentId = :parentId AND pf.isDeleted = false")
    long countByParentId(Long parentId);

    /**
     * 检查同一父文件夹下是否存在同名文件/文件夹（排除已删除）
     */
    boolean existsByProjectIdAndParentIdAndNameAndIdNotAndIsDeletedFalse(Long projectId, Long parentId, String name, Long excludeId);
    
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(pf) > 0 FROM ProjectFile pf WHERE pf.projectId = :projectId AND (:parentId IS NULL AND pf.parentId IS NULL OR pf.parentId = :parentId) AND pf.name = :name AND pf.id <> :excludeId AND pf.isDeleted = false")
    boolean existsByProjectIdAndParentIdAndNameAndIdNot(Long projectId, Long parentId, String name, Long excludeId);

    /**
     * 根据 WPS 文件 ID 查询文件
     */
    List<ProjectFile> findByWpsFileId(String wpsFileId);
    
    /**
     * 根据项目 ID、父文件夹 ID 和名称查询文件 (排除已删除)
     */
    Optional<ProjectFile> findByProjectIdAndParentIdAndNameAndIsDeletedFalse(Long projectId, Long parentId, String name);
    
    @org.springframework.data.jpa.repository.Query("SELECT pf FROM ProjectFile pf WHERE pf.projectId = :projectId AND (:parentId IS NULL AND pf.parentId IS NULL OR pf.parentId = :parentId) AND pf.name = :name AND pf.isDeleted = false")
    Optional<ProjectFile> findByProjectIdAndParentIdAndName(Long projectId, Long parentId, String name);

    /**
     * 计算项目文件总大小（排除已删除）
     */
    @org.springframework.data.jpa.repository.Query("SELECT SUM(pf.fileSize) FROM ProjectFile pf WHERE pf.projectId = :projectId AND pf.isDeleted = false")
    Long sumSizeByProjectId(Long projectId);

    /**
     * 根据物理文件路径查询
     */
    Optional<ProjectFile> findByFilePath(String filePath);

    /**
     * 查询回收站文件（已删除）
     */
    List<ProjectFile> findByProjectIdAndIsDeletedTrueOrderByDeletedAtDesc(Long projectId);
}

