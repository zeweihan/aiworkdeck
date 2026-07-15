package com.checkba.repository;

import com.checkba.model.entity.MemoryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆条目仓库
 */
@Repository
public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, Long> {

    /**
     * 根据项目ID查找记忆
     */
    List<MemoryEntry> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /**
     * 根据项目ID和类型查找记忆
     */
    List<MemoryEntry> findByProjectIdAndMemoryTypeOrderByImportanceScoreDesc(Long projectId, String memoryType);

    /**
     * 根据项目ID查找受保护的记忆
     */
    List<MemoryEntry> findByProjectIdAndIsProtectedTrue(Long projectId);

    /**
     * 根据对话ID查找记忆
     */
    List<MemoryEntry> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    /**
     * 根据关键词模糊搜索记忆
     */
    @Query("SELECT m FROM MemoryEntry m WHERE m.projectId = :projectId " +
           "AND (LOWER(m.memoryKey) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(m.memoryValue) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY m.importanceScore DESC")
    List<MemoryEntry> searchByKeyword(@Param("projectId") Long projectId, 
                                       @Param("keyword") String keyword,
                                       Pageable pageable);

    /**
     * 根据项目ID和类型搜索记忆
     */
    @Query("SELECT m FROM MemoryEntry m WHERE m.projectId = :projectId " +
           "AND (:memoryType IS NULL OR m.memoryType = :memoryType) " +
           "AND (LOWER(m.memoryKey) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(m.memoryValue) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY m.importanceScore DESC")
    List<MemoryEntry> searchByKeywordAndType(@Param("projectId") Long projectId,
                                              @Param("keyword") String keyword,
                                              @Param("memoryType") String memoryType,
                                              Pageable pageable);

    /**
     * 获取项目最重要的记忆
     */
    @Query("SELECT m FROM MemoryEntry m WHERE m.projectId = :projectId " +
           "ORDER BY m.importanceScore DESC, m.createdAt DESC")
    List<MemoryEntry> findTopImportantMemories(@Param("projectId") Long projectId, Pageable pageable);

    /**
     * 删除过期的记忆
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM MemoryEntry m WHERE m.expiresAt IS NOT NULL AND m.expiresAt < :now AND m.isProtected = false")
    void deleteExpiredMemories(@Param("now") LocalDateTime now);

    /**
     * 批量更新检索命中时间与命中次数（时间衰减 + 复述效应依据；
     * JPQL 批量更新绕过 @PreUpdate，不影响 updatedAt）
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE MemoryEntry m SET m.lastAccessedAt = :now, m.accessCount = COALESCE(m.accessCount, 0) + 1 WHERE m.id IN :ids")
    void touchLastAccessedAt(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /**
     * 根据项目ID和用户ID查找记忆
     */
    List<MemoryEntry> findByProjectIdAndUserIdOrderByCreatedAtDesc(Long projectId, Long userId);

    /**
     * 按作用域查找用户级记忆（跨项目，如用户偏好）
     */
    @Query("SELECT m FROM MemoryEntry m WHERE m.userId = :userId AND m.scope = :scope " +
           "ORDER BY m.importanceScore DESC, m.updatedAt DESC")
    List<MemoryEntry> findByUserIdAndScope(@Param("userId") Long userId,
                                            @Param("scope") String scope,
                                            Pageable pageable);

    /**
     * 按作用域查找通用知识记忆（跨用户跨项目）
     */
    @Query("SELECT m FROM MemoryEntry m WHERE m.scope = :scope " +
           "AND (LOWER(m.memoryKey) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(m.memoryValue) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY m.importanceScore DESC")
    List<MemoryEntry> searchByScopeAndKeyword(@Param("scope") String scope,
                                               @Param("keyword") String keyword,
                                               Pageable pageable);

    /**
     * 按来源文件查找文件级记忆
     */
    List<MemoryEntry> findBySourceFileIdOrderByImportanceScoreDesc(Long sourceFileId);

    /**
     * 按项目与一组 memoryKey 查找记忆（证据账本的"已被更新"信号检测用）
     */
    List<MemoryEntry> findByProjectIdAndMemoryKeyIn(Long projectId, java.util.Collection<String> memoryKeys);

    /**
     * 统计项目的记忆数量
     */
    long countByProjectId(Long projectId);

    /**
     * 统计项目各类型记忆数量
     */
    @Query("SELECT m.memoryType, COUNT(m) FROM MemoryEntry m WHERE m.projectId = :projectId GROUP BY m.memoryType")
    List<Object[]> countByProjectIdGroupByType(@Param("projectId") Long projectId);
}

