package com.checkba.repository;

import com.checkba.model.entity.ProjectFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProjectFileRepository extends JpaRepository<ProjectFile, Long> {
    /**
     * 根据项目 ID 查询所有文件（包含已删除），用于文件树清单采集
     */
    List<ProjectFile> findByProjectId(Long projectId);

    /**
     * 悲观行锁读取（SELECT ... FOR UPDATE）。用于把「查当前用量 + 判断放行」钉进调用方
     * 已经开着的那个事务里：行锁与事务同生命周期，提交/回滚前不释放——不像进程内锁那样
     * 在方法返回时就提前释放，能正确堵住 check-then-act 竞态（见 StageQuotaService.checkAdmission
     * 的用法与注释）。三个受支持的数据库（H2/PostgreSQL/MySQL）都支持 FOR UPDATE。
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pf FROM ProjectFile pf WHERE pf.id = :id")
    Optional<ProjectFile> lockById(Long id);

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
     * 某用户名下未删除的文件数（本机身份候选的「数据量」信号之一，见 LocalIdentityService）
     */
    long countByUserIdAndIsDeletedFalse(Long userId);

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
     * 一批项目各自的「最近一次文件活动时间」（排除已删除），返回 [projectId, maxUpdatedAt]。
     *
     * 项目列表要显示「最近修改」，而 Project.updatedAt 只在建项目与改项目名时写过，
     * 拿它当修改时间会得到一个永远等于创建日期的列（假数据）。真正的活动落在文件行上：
     * 新建/改名/移动/删除/覆盖保存都会写 ProjectFile.updatedAt。
     *
     * 一次 group by 拿全部，不给已经是 N+1 的列表页再加一层每项一查。
     */
    @org.springframework.data.jpa.repository.Query("SELECT pf.projectId, MAX(pf.updatedAt) FROM ProjectFile pf WHERE pf.projectId IN :projectIds AND pf.isDeleted = false GROUP BY pf.projectId")
    List<Object[]> findLastActivityByProjectIds(List<Long> projectIds);

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

    /**
     * 项目文件树骨架（排除软删除）：只取统计需要的四列，不 hydrate 实体、不传文件内容。
     * 行形状 [id(Long), parentId(Long), isFolder(Boolean), name(String)]。
     *
     * 概览页统计条要剔除 __staging_area__ 与 AI Assistant Files 的整棵子树，
     * 派生 count 方法做不到递归，因此一次取回骨架、由服务层在内存里走一遍，
     * 对外仍然只发两个整数。不要拿它当文件树接口用。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT pf.id, pf.parentId, pf.isFolder, pf.name FROM ProjectFile pf "
            + "WHERE pf.projectId = :projectId AND pf.isDeleted = false")
    List<Object[]> findTreeSkeletonByProjectId(
            @org.springframework.data.repository.query.Param("projectId") Long projectId);
}

