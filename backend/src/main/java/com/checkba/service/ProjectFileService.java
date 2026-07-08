package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.storage.StorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目文件服务
 */
@Service
public class ProjectFileService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProjectFileService.class);

    private final ProjectFileRepository projectFileRepository;
    private final ProjectRagService projectRagService;
    private final StorageServiceFactory storageServiceFactory;

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectFileService(ProjectFileRepository projectFileRepository,
                              ProjectRagService projectRagService,
                              StorageServiceFactory storageServiceFactory) {
        this.projectFileRepository = projectFileRepository;
        this.projectRagService = projectRagService;
        this.storageServiceFactory = storageServiceFactory;
    }

    /**
     * 获取项目的文件树（指定父文件夹下的文件列表）
     */
    public List<ProjectFile> getFilesByParent(Long projectId, Long parentId) {
        if (projectId == null) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        return projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(projectId, parentId);
    }

    /**
     * 获取项目的完整文件树（递归获取所有文件和文件夹）
     */
    public List<ProjectFile> getFileTree(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        return projectFileRepository.findByProjectIdOrderBySortOrderAsc(projectId);
    }

    /**
     * 创建文件夹
     */
    @Transactional
    public ProjectFile createFolder(Long projectId, Long parentId, String name, Long userId) {
        if (projectId == null) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("文件夹名称不能为空");
        }
        validateNodeName(name);
        if (userId == null) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }

        // 检查同名文件夹是否存在
        if (projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(projectId, parentId, name, -1L)) {
            throw new IllegalArgumentException("该文件夹下已存在同名文件夹: " + name);
        }

        // 获取当前父文件夹下的最大排序序号
        List<ProjectFile> siblings = projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(projectId, parentId);
        int maxSortOrder = siblings.stream()
                .mapToInt(ProjectFile::getSortOrder)
                .max()
                .orElse(-1);

        ProjectFile folder = new ProjectFile();
        folder.setProjectId(projectId);
        folder.setParentId(parentId);
        folder.setIsFolder(true);
        folder.setName(name.trim());
        folder.setSortOrder(maxSortOrder + 1);
        folder.setUserId(userId);
        folder.setCreatedAt(LocalDateTime.now());
        folder.setUpdatedAt(LocalDateTime.now());

        return projectFileRepository.save(folder);
    }

    /**
     * 校验文件/文件夹名合法性：不得含路径分隔符或为 "." / ".."，
     * 否则该名字会被 buildPhysicalPath 逐级拼进物理路径，导致文件写到项目目录之外。
     */
    private void validateNodeName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.contains("/") || n.contains("\\") || ".".equals(n) || "..".equals(n)) {
            throw new IllegalArgumentException("名称包含非法字符: " + name);
        }
    }

    /**
     * 构建文件的物理存储路径
     * 格式: projects/{projectId}/{logical_path}/{fileName}
     */
    private String buildPhysicalPath(Long projectId, Long parentId, String fileName) {
        StringBuilder pathBuilder = new StringBuilder();
        
        // 构建逻辑路径
        Long currentParentId = parentId;
        int depth = 0;
        // 防止无限循环
        while (currentParentId != null && depth < 20) {
            Optional<ProjectFile> parentOpt = projectFileRepository.findById(currentParentId);
            if (parentOpt.isPresent()) {
                ProjectFile parent = parentOpt.get();
                if (pathBuilder.length() > 0) {
                    pathBuilder.insert(0, "/");
                }
                pathBuilder.insert(0, parent.getName());
                currentParentId = parent.getParentId();
            } else {
                break;
            }
            depth++;
        }
        
        // 构建完整路径
        // 格式: projects/{projectId}/{logicalPath}/{fileName}
        // 物理根目录配置为 data，所以最终路径为 data/projects/{projectId}/{logicalPath}/{fileName}
        String logicalPath = pathBuilder.toString();
        String safeName = fileName;
        
        if (StringUtils.hasText(logicalPath)) {
            return String.format("projects/%d/%s/%s", projectId, logicalPath, safeName);
        } else {
            return String.format("projects/%d/%s", projectId, safeName);
        }
    }

    /**
     * 创建或更新文件 (如果存在则更新元数据)
     */
    @Transactional
    public ProjectFile createOrUpdateFile(Long projectId, Long parentId, String name, String fileType, 
                                  Long fileSize, String filePath, String wpsFileId, Long userId) {
        
        Optional<ProjectFile> existing = projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, parentId, name);
        if (existing.isPresent()) {
            ProjectFile f = existing.get();
            f.setFileSize(fileSize);
            f.setUpdatedAt(LocalDateTime.now());
            // If wpsFileId is provided, update it (or keep existing if new is null?? usually overwrite)
            if (StringUtils.hasText(wpsFileId)) {
                f.setWpsFileId(wpsFileId);
            }
            // Ensure path is consistent if changed?
            if (StringUtils.hasText(filePath)) {
                f.setFilePath(filePath);
            }
            log.info("Updating existing file in DB: {}", name);
            return projectFileRepository.save(f);
        } else {
            return createFile(projectId, parentId, name, fileType, fileSize, filePath, wpsFileId, userId);
        }
    }

    /**
     * 创建文件
     */
    @Transactional
    public ProjectFile createFile(Long projectId, Long parentId, String name, String fileType, 
                                  Long fileSize, String filePath, String wpsFileId, Long userId) {
        if (projectId == null) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (userId == null) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }

        // 检查同名文件是否存在
        if (projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(projectId, parentId, name, -1L)) {
            throw new IllegalArgumentException("该文件夹下已存在同名文件: " + name);
        }

        // 获取当前父文件夹下的最大排序序号
        List<ProjectFile> siblings = projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(projectId, parentId);
        int maxSortOrder = siblings.stream()
                .mapToInt(ProjectFile::getSortOrder)
                .max()
                .orElse(-1);

        // 如果未提供 filePath，则自动构建
        if (!StringUtils.hasText(filePath)) {
            filePath = buildPhysicalPath(projectId, parentId, name);
        }

        ProjectFile file = new ProjectFile();
        file.setProjectId(projectId);
        file.setParentId(parentId);
        file.setIsFolder(false);
        file.setName(name.trim());
        file.setFileType(fileType);
        file.setFileSize(fileSize);
        file.setFilePath(filePath);
        file.setWpsFileId(wpsFileId);
        file.setSortOrder(maxSortOrder + 1);
        file.setUserId(userId);
        file.setCreatedAt(LocalDateTime.now());
        file.setUpdatedAt(LocalDateTime.now());

        ProjectFile savedFile = projectFileRepository.save(file);
        
        // 尝试创建物理文件（从模板复制）
        try {
            storageServiceFactory.getStorageService().load(filePath);
            log.info("物理文件创建成功: {}", filePath);
        } catch (Exception e) {
            log.warn("物理文件创建可能失败 (如果是第一次访问会自动创建): {}", filePath, e);
        }
        
        return savedFile;
    }

    /**
     * 重命名文件或文件夹
     */
    @Transactional
    public ProjectFile rename(Long fileId, String newName, Long userId) {
        if (fileId == null) {
            throw new IllegalArgumentException("文件 ID 不能为空");
        }
        if (!StringUtils.hasText(newName)) {
            throw new IllegalArgumentException("新名称不能为空");
        }
        validateNodeName(newName);

        ProjectFile file = projectFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + fileId));

        // 权限检查已移至 Controller 层，这里不再检查创建者身份

        // 检查同名文件是否存在
        if (projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(
                file.getProjectId(), file.getParentId(), newName.trim(), fileId)) {
            throw new IllegalArgumentException("该文件夹下已存在同名文件/文件夹: " + newName);
        }

        String oldName = file.getName();
        String oldFilePath = file.getFilePath();
        
        // 处理文件名：如果是文件，确保保留文件后缀
        String finalNewName = newName.trim();
        if (!file.getIsFolder()) {
            // 检查原文件名是否有后缀
            String oldExtension = "";
            int lastDotIndex = oldName.lastIndexOf('.');
            if (lastDotIndex > 0 && lastDotIndex < oldName.length() - 1) {
                oldExtension = oldName.substring(lastDotIndex);
            }
            
            // 如果新名称不包含后缀，但原名称有后缀，则保留原后缀
            if (!finalNewName.contains(".") && StringUtils.hasText(oldExtension)) {
                finalNewName = finalNewName + oldExtension;
            }
            // 如果新名称不包含后缀，且原名称也没有后缀，但 fileType 存在，则添加 fileType 后缀
            else if (!finalNewName.contains(".") && !StringUtils.hasText(oldExtension) 
                    && StringUtils.hasText(file.getFileType())) {
                finalNewName = finalNewName + "." + file.getFileType();
            }
        }
        
        // 更新文件名
        file.setName(finalNewName);
        file.setUpdatedAt(LocalDateTime.now());

        // 如果是文件（非文件夹），需要更新 filePath 并重命名物理文件
        if (!file.getIsFolder() && StringUtils.hasText(oldFilePath)) {
            // 构建新的文件路径
            String newFilePath = buildPhysicalPath(file.getProjectId(), file.getParentId(), finalNewName);
            file.setFilePath(newFilePath);
            
            // 重命名物理文件
            try {
                movePhysicalFile(oldFilePath, newFilePath);
                log.info("物理文件重命名成功: fileId={}, oldPath={}, newPath={}", fileId, oldFilePath, newFilePath);
            } catch (Exception e) {
                log.warn("物理文件重命名失败，继续更新数据库记录: fileId={}, oldPath={}, newPath={}", 
                    fileId, oldFilePath, newFilePath, e);
                // 如果物理文件重命名失败，回滚 filePath
                file.setFilePath(oldFilePath);
            }
        }

        return projectFileRepository.save(file);
    }

    /**
     * 软删除文件或文件夹（移入回收站）
     */
    @Transactional
    public void delete(Long fileId, Long userId) {
        if (fileId == null) {
            throw new IllegalArgumentException("文件 ID 不能为空");
        }

        ProjectFile file = projectFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + fileId));

        // 权限检查已移至 Controller 层，这里不再检查创建者身份

        // 递归软删除
        softDeleteRecursive(file);
    }

    private void softDeleteRecursive(ProjectFile file) {
        // 如果是文件夹，递归软删除子文件
        if (Boolean.TRUE.equals(file.getIsFolder())) {
            List<ProjectFile> children = projectFileRepository.findByProjectIdAndParentIdAndIsDeletedFalseOrderBySortOrderAsc(
                    file.getProjectId(), file.getId());
            for (ProjectFile child : children) {
                softDeleteRecursive(child);
            }
        }
        
        // 标记为已删除
        file.setIsDeleted(true);
        file.setDeletedAt(LocalDateTime.now());
        projectFileRepository.save(file);
    }

    /**
     * 彻底删除文件或文件夹（物理删除 + 数据库删除）
     */
    @Transactional
    public void permDelete(Long fileId, Long userId) {
        if (fileId == null) {
            throw new IllegalArgumentException("文件 ID 不能为空");
        }

        ProjectFile file = projectFileRepository.findById(fileId)
                // 如果文件找不到，可能已经被删除了，这是幂等操作，可以直接返回，但为了明确反馈，这里还是查一下
                // 注意：findById 默认查所有（包括 isDeleted=true）
                .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + fileId));

        // 权限检查已移至 Controller 层，这里不再检查创建者身份

        // 如果是文件夹，递归彻底删除所有子文件
        if (Boolean.TRUE.equals(file.getIsFolder())) {
            // 这里要查出所有子文件（包括已软删除的，否则删不干净）
            // 使用自定义查询查所有 parentId = id 的
             List<ProjectFile> children = getAllChildrenIncludingDeleted(file.getProjectId(), fileId);
            for (ProjectFile child : children) {
                permDelete(child.getId(), userId);
            }
        }

        // 记录文件路径用于向量库刷新和物理文件删除
        String filePath = file.getFilePath();
        
        // 删除物理文件
        // 1. 如果是文件夹，构建物理路径并尝试删除
        if (Boolean.TRUE.equals(file.getIsFolder())) {
             filePath = buildPhysicalPath(file.getProjectId(), file.getParentId(), file.getName());
        }

        if (StringUtils.hasText(filePath)) {
            try {
                storageServiceFactory.getStorageService().delete(filePath);
                log.info("物理文件/文件夹彻底删除成功: fileId={}, path={}", fileId, filePath);
            } catch (Exception e) {
                log.warn("物理文件/文件夹彻底删除失败，继续删除数据库记录: fileId={}, path={}", fileId, filePath, e);
            }
        }
        
        // 删除数据库记录
        projectFileRepository.deleteById(fileId);
        
        // 触发向量库增量刷新（文件删除）
        if (file.getProjectId() != null && filePath != null) {
            projectRagService.refreshProjectKnowledgeIncremental(
                    String.valueOf(file.getProjectId()), filePath);
        }
    }
    
    private List<ProjectFile> getAllChildrenIncludingDeleted(Long projectId, Long parentId) {
        return projectFileRepository.findByProjectIdAndParentId(projectId, parentId);
    }
    
    /**
     * 还原文件
     */
    @Transactional
    public void restore(Long fileId, Long userId) {
         ProjectFile file = projectFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + fileId));
         
         // 权限检查已移至 Controller 层，这里不再检查创建者身份
         
         restoreRecursive(file);
    }
    
    private void restoreRecursive(ProjectFile file) {
        file.setIsDeleted(false);
        file.setDeletedAt(null);
        projectFileRepository.save(file);
        
        // 递归还原所有子文件
        if (Boolean.TRUE.equals(file.getIsFolder())) {
            List<ProjectFile> children = getAllChildrenIncludingDeleted(file.getProjectId(), file.getId());
            for (ProjectFile child : children) {
                // 注意：如果只还原文件夹，但不想还原之前的子文件？
                // 现在的逻辑是：软删除时，递归删除了所有子文件。
                // 还原时，如果递归还原，那就全都回来了。
                // 这是一个简单的策略。
                // 为了避免还原出"本来就是已删除"的文件（比如在文件夹删除之前就删除了的文件），
                // 我们可能需要更复杂的逻辑（比如 deleteTransactionId）。
                // 但当前 MVP，递归还原即可。
                restoreRecursive(child);
            }
        }
    }
    
    /**
     * 获取回收站文件列表
     */
    public List<ProjectFile> getRecycleBinFiles(Long projectId) {
         return projectFileRepository.findByProjectIdAndIsDeletedTrueOrderByDeletedAtDesc(projectId);
    }

    /**
     * 移动文件或文件夹（拖拽排序）
     */
    @Transactional
    public ProjectFile move(Long fileId, Long newParentId, Integer newSortOrder, Long userId) {
        if (fileId == null) {
            throw new IllegalArgumentException("文件 ID 不能为空");
        }

        ProjectFile file = projectFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + fileId));

        // 权限检查已移至 Controller 层，这里不再检查创建者身份

        // 检查不能移动到自己的子文件夹中
        if (file.getIsFolder() && newParentId != null) {
            if (isDescendant(fileId, newParentId)) {
                throw new IllegalArgumentException("不能将文件夹移动到自己的子文件夹中");
            }
        }

        // 检查目标文件夹下是否存在同名文件
        // 注意：newParentId 为 null 表示移动到根目录
        Long targetParentId = newParentId;
        if (projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(
                file.getProjectId(), targetParentId, file.getName(), fileId)) {
            throw new IllegalArgumentException("目标文件夹下已存在同名文件/文件夹: " + file.getName());
        }

        String oldFilePath = file.getFilePath();
        Long oldParentId = file.getParentId();
        
        // 更新父文件夹和排序序号
        file.setParentId(newParentId);
        if (newSortOrder != null) {
            file.setSortOrder(newSortOrder);
        }
        file.setUpdatedAt(LocalDateTime.now());

        // 如果是文件（非文件夹），需要更新 filePath 并移动物理文件
        if (!file.getIsFolder()) {
            return moveSingleFileWithPhysical(file, oldFilePath, oldParentId);
        }

        // 文件夹移动：需要同步更新子文件的 filePath，并移动所有子文件的物理文件
        ProjectFile savedFolder = projectFileRepository.save(file);
        try {
            moveFolderDescendantPhysicalFiles(savedFolder);
        } catch (Exception e) {
            log.warn("文件夹移动：同步移动子文件物理文件失败（数据库已更新 parentId）: folderId={}", savedFolder.getId(), e);
        }
        return savedFolder;
    }

    /**
     * 批量删除（支持文件夹递归删除）
     */
    @Transactional
    public void batchDelete(Long projectId, com.checkba.model.dto.ProjectFileBatchRequest request, Long userId) {
        if (request == null || request.getFileIds() == null || request.getFileIds().isEmpty()) {
            throw new IllegalArgumentException("fileIds 不能为空");
        }
        for (Long id : request.getFileIds()) {
            if (id == null) continue;
            // 归属校验：防止越权批量删除他人项目的文件（delete(id,...) 本身不校验 projectId）
            ProjectFile owned = projectFileRepository.findById(id).orElse(null);
            if (owned != null && !Objects.equals(owned.getProjectId(), projectId)) {
                throw new IllegalArgumentException("跨项目删除不支持: " + id);
            }
            try {
                delete(id, userId);
            } catch (IllegalArgumentException e) {
                // 忽略文件不存在的错误，实现幂等删除
                if (e.getMessage() != null && e.getMessage().contains("文件不存在")) {
                    log.warn("批量删除时忽略不存在的文件: {}", id);
                    continue;
                }
                throw e;
            } catch (Exception e) {
                log.error("删除文件失败: " + id, e);
                throw e;
            }
        }
    }

    /**
     * 批量移动（支持文件夹递归移动）
     */
    @Transactional
    public List<ProjectFile> batchMove(Long projectId, com.checkba.model.dto.ProjectFileBatchRequest request, Long userId) {
        if (request == null || request.getFileIds() == null || request.getFileIds().isEmpty()) {
            throw new IllegalArgumentException("fileIds 不能为空");
        }
        List<ProjectFile> result = new ArrayList<>();
        for (Long id : request.getFileIds()) {
            if (id == null) continue;
            // 归属校验：防止越权批量移动他人项目的文件（move(id,...) 本身不校验 projectId）
            ProjectFile owned = projectFileRepository.findById(id).orElse(null);
            if (owned != null && !Objects.equals(owned.getProjectId(), projectId)) {
                throw new IllegalArgumentException("跨项目移动不支持: " + id);
            }
            result.add(move(id, request.getTargetParentId(), null, userId));
        }
        return result;
    }

    /**
     * 批量复制（支持文件夹递归复制）
     */
    @Transactional
    public List<ProjectFile> batchCopy(Long projectId, com.checkba.model.dto.ProjectFileBatchRequest request, Long userId) {
        if (request == null || request.getFileIds() == null || request.getFileIds().isEmpty()) {
            throw new IllegalArgumentException("fileIds 不能为空");
        }
        if (request.getTargetParentId() == null && projectId == null) {
            // 防御性校验
            throw new IllegalArgumentException("projectId 不能为空");
        }
        List<ProjectFile> createdRoots = new ArrayList<>();
        for (Long id : request.getFileIds()) {
            if (id == null) continue;
            ProjectFile source = projectFileRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + id));
            if (!Objects.equals(source.getProjectId(), projectId)) {
                throw new IllegalArgumentException("跨项目复制不支持");
            }
            createdRoots.add(copyRecursive(projectId, source, request.getTargetParentId(), userId));
        }
        return createdRoots;
    }

    private ProjectFile moveSingleFileWithPhysical(ProjectFile file, String oldFilePath, Long oldParentId) {
        if (StringUtils.hasText(oldFilePath)) {
            String newFilePath = buildPhysicalPath(file.getProjectId(), file.getParentId(), file.getName());
            file.setFilePath(newFilePath);
            try {
                movePhysicalFile(oldFilePath, newFilePath);
                log.info("物理文件移动成功: fileId={}, oldPath={}, newPath={}", file.getId(), oldFilePath, newFilePath);
            } catch (Exception e) {
                log.warn("物理文件移动失败，继续更新数据库记录: fileId={}, oldPath={}, newPath={}",
                        file.getId(), oldFilePath, newFilePath, e);
                file.setFilePath(oldFilePath);
                file.setParentId(oldParentId);
            }
        }
        return projectFileRepository.save(file);
    }

    /**
     * 文件夹移动后：递归更新其子孙文件的 filePath，并移动物理文件
     */
    private void moveFolderDescendantPhysicalFiles(ProjectFile folder) throws Exception {
        if (folder == null || !Boolean.TRUE.equals(folder.getIsFolder())) return;
        List<ProjectFile> children = projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(folder.getProjectId(), folder.getId());
        for (ProjectFile child : children) {
            if (Boolean.TRUE.equals(child.getIsFolder())) {
                moveFolderDescendantPhysicalFiles(child);
                continue;
            }
            String oldPath = child.getFilePath();
            if (!StringUtils.hasText(oldPath)) continue;
            String newPath = buildPhysicalPath(child.getProjectId(), child.getParentId(), child.getName());
            if (oldPath.equals(newPath)) continue;
            try {
                movePhysicalFile(oldPath, newPath);
                child.setFilePath(newPath);
                child.setUpdatedAt(LocalDateTime.now());
                projectFileRepository.save(child);
            } catch (Exception e) {
                log.warn("移动子文件物理文件失败: fileId={}, oldPath={}, newPath={}", child.getId(), oldPath, newPath, e);
            }
        }
    }

    /**
     * 递归复制文件/文件夹
     */
    private ProjectFile copyRecursive(Long projectId, ProjectFile source, Long targetParentId, Long userId) {
        if (Boolean.TRUE.equals(source.getIsFolder())) {
            String desiredName = source.getName();
            if (Objects.equals(source.getParentId(), targetParentId)) {
                desiredName = "【副本】" + desiredName;
            }
            String newFolderName = resolveUniqueName(projectId, targetParentId, desiredName);
            ProjectFile newFolder = new ProjectFile();
            newFolder.setProjectId(projectId);
            newFolder.setParentId(targetParentId);
            newFolder.setIsFolder(true);
            newFolder.setName(newFolderName);
            newFolder.setSortOrder(0);
            newFolder.setUserId(userId);
            newFolder.setCreatedAt(LocalDateTime.now());
            newFolder.setUpdatedAt(LocalDateTime.now());
            ProjectFile savedFolder = projectFileRepository.save(newFolder);

            List<ProjectFile> children = projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(projectId, source.getId());
            for (ProjectFile child : children) {
                copyRecursive(projectId, child, savedFolder.getId(), userId);
            }
            return savedFolder;
        }

        // file
        String desiredName = source.getName();
        if (Objects.equals(source.getParentId(), targetParentId)) {
            desiredName = "【副本】" + desiredName;
        }
        String newName = resolveUniqueName(projectId, targetParentId, desiredName);
        ProjectFile newFile = new ProjectFile();
        newFile.setProjectId(projectId);
        newFile.setParentId(targetParentId);
        newFile.setIsFolder(false);
        newFile.setName(newName);
        newFile.setFileType(source.getFileType());
        newFile.setFileSize(source.getFileSize());
        String newPath = buildPhysicalPath(projectId, targetParentId, newName);
        newFile.setFilePath(newPath);
        newFile.setWpsFileId(generateWpsFileId(projectId));
        newFile.setSortOrder(0);
        newFile.setUserId(userId);
        newFile.setCreatedAt(LocalDateTime.now());
        newFile.setUpdatedAt(LocalDateTime.now());
        ProjectFile saved = projectFileRepository.save(newFile);

        // copy physical
        if (StringUtils.hasText(source.getFilePath())) {
            try {
                copyPhysicalFile(source.getFilePath(), newPath);
            } catch (Exception e) {
                log.warn("复制物理文件失败: sourcePath={}, targetPath={}", source.getFilePath(), newPath, e);
            }
        }
        return saved;
    }

    private String resolveUniqueName(Long projectId, Long parentId, String desiredName) {
        if (!StringUtils.hasText(desiredName)) return desiredName;
        String base = desiredName.trim();
        String ext = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0 && dot < base.length() - 1) {
            ext = base.substring(dot);
            base = base.substring(0, dot);
        }
        String candidate = base + ext;
        int i = 1;
        while (projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(projectId, parentId, candidate, -1L)) {
            candidate = base + " (" + i + ")" + ext;
            i++;
            if (i > 1000) {
                candidate = base + "-" + UUID.randomUUID() + ext;
                break;
            }
        }
        return candidate;
    }

    private String generateWpsFileId(Long projectId) {
        // 与前端生成规则保持一致的风格（无需完全一致，但确保全局唯一）
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return String.format("project_%d_doc_%d_%s", projectId, System.currentTimeMillis(), rand);
    }

    private void copyPhysicalFile(String sourcePath, String targetPath) throws Exception {
        var resource = storageServiceFactory.getStorageService().load(sourcePath);
        try (var inputStream = resource.getInputStream()) {
            storageServiceFactory.getStorageService().save(targetPath, inputStream);
        }
    }
    
    /**
     * 移动/重命名物理文件
     * 通过读取旧文件、写入新位置、删除旧文件来实现
     */
    private void movePhysicalFile(String oldPath, String newPath) throws Exception {
        try {
            // 读取旧文件
            var resource = storageServiceFactory.getStorageService().load(oldPath);
            
            // 写入新位置
            try (var inputStream = resource.getInputStream()) {
                storageServiceFactory.getStorageService().save(newPath, inputStream);
            }
            
            // 删除旧文件
            storageServiceFactory.getStorageService().delete(oldPath);
            
            log.info("物理文件移动/重命名成功: {} -> {}", oldPath, newPath);
        } catch (Exception e) {
            log.error("物理文件移动/重命名失败: {} -> {}", oldPath, newPath, e);
            throw e;
        }
    }

    /**
     * 检查 targetId 是否是 sourceId 的后代
     */
    private boolean isDescendant(Long sourceId, Long targetId) {
        if (targetId == null) {
            return false;
        }
        ProjectFile target = projectFileRepository.findById(targetId).orElse(null);
        if (target == null) {
            return false;
        }
        if (target.getParentId() == null) {
            return false;
        }
        if (target.getParentId().equals(sourceId)) {
            return true;
        }
        return isDescendant(sourceId, target.getParentId());
    }

    /**
     * 获取文件详情
     */
    public ProjectFile getFile(Long fileId) {
        return projectFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + fileId));
    }

    /**
     * 读取文件字节内容 (支持 Local/OSS)
     */
    public byte[] getFileBytes(Long fileId) throws java.io.IOException {
        ProjectFile file = getFile(fileId);
        if (file == null || !StringUtils.hasText(file.getFilePath())) {
            return null;
        }
        var resource = storageServiceFactory.getStorageService().load(file.getFilePath());
        try (var inputStream = resource.getInputStream()) {
            return inputStream.readAllBytes();
        }
    }
    /**
     * 保存 Artifact 文件 (AI 助手工作计划/任务清单)
     * 路径: /AI Assistant Files/{conversationId}/{filename}.md
     * 若 conversationId 对应的文件夹不存在，则创建（并将 wpsFileId 设为 conversationId 以便后续查找）
     */
    @Transactional
    public ProjectFile saveArtifactFile(Long projectId, String conversationId, String filename, String content, Long userId) {
        if (projectId == null || !StringUtils.hasText(filename)) {
            return null;
        }

        // 1. 确保 "AI Assistant Files" 根目录存在
        ProjectFile rootFolder = ensureFolder(projectId, null, "AI Assistant Files", userId);

        // 2. 确保 conversationId 子目录存在
        // 查找逻辑: 先按 wpsFileId = conversationId 查 (支持已重命名的情况)
        // 如果没找到，再按 Name = conversationId 查 (旧数据兼容)
        // 如果都没找到，创建新的，name=conversationId, wpsFileId=conversationId
        ProjectFile convFolder = ensureConversationFolder(projectId, rootFolder.getId(), conversationId, userId);

        // 3. 保存文件
        String finalName = filename.endsWith(".md") ? filename : filename + ".md";
        
        // 检查是否存在
        Optional<ProjectFile> existing = projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(
            projectId, convFolder.getId(), finalName
        );
        
        if (existing.isPresent()) {
            // 更新内容
            ProjectFile file = existing.get();
            try {
                // 更新物理文件
                storageServiceFactory.getStorageService().save(file.getFilePath(), new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                file.setFileSize((long) content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                file.setUpdatedAt(LocalDateTime.now());
                return projectFileRepository.save(file);
            } catch (Exception e) {
                log.error("更新 Artifact 文件失败: " + file.getFilePath(), e);
                throw new RuntimeException("Save artifact failed", e);
            }
        } else {
            // 创建新文件
            String logicalPath = "AI Assistant Files/" + convFolder.getName() + "/" + finalName;
            String physicalPath = buildPhysicalPath(projectId, convFolder.getId(), finalName);
            
            ProjectFile file = new ProjectFile();
            file.setProjectId(projectId);
            file.setParentId(convFolder.getId());
            file.setIsFolder(false);
            file.setName(finalName);
            file.setFileType("md");
            file.setFileSize((long) content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            file.setFilePath(physicalPath);
            file.setWpsFileId(generateWpsFileId(projectId));
            file.setSortOrder(0);
            file.setUserId(userId);
            file.setCreatedAt(LocalDateTime.now());
            file.setUpdatedAt(LocalDateTime.now());
            
            ProjectFile saved = projectFileRepository.save(file);
            
            try {
                storageServiceFactory.getStorageService().save(physicalPath, new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (Exception e) {
                log.error("创建 Artifact 物理文件失败: " + physicalPath, e);
                 // 稍微清理一下
                 projectFileRepository.delete(saved);
                 throw new RuntimeException("Create artifact file failed", e);
            }
            return saved;
        }
    }

    private ProjectFile ensureFolder(Long projectId, Long parentId, String name, Long userId) {
        Optional<ProjectFile> folderOpt = projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, parentId, name);
        if (folderOpt.isPresent()) {
            return folderOpt.get();
        }
        return createFolder(projectId, parentId, name, userId);
    }

    private ProjectFile ensureConversationFolder(Long projectId, Long parentId, String conversationId, Long userId) {
        // 1. Try by wpsFileId (Robust lookup)
        Optional<ProjectFile> byWpsId = projectFileRepository.findByWpsFileId(conversationId).stream().findFirst();
        if (byWpsId.isPresent() && !Boolean.TRUE.equals(byWpsId.get().getIsDeleted())) {
            return byWpsId.get();
        }
        
        // 2. Try by Name (Fallback / Initial creation if wpsId wasn't set)
        Optional<ProjectFile> byName = projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, parentId, conversationId);
        if (byName.isPresent()) {
            // Found by name. Let's update wpsFileId for future robustness if empty
            ProjectFile f = byName.get();
            if (!StringUtils.hasText(f.getWpsFileId())) {
                f.setWpsFileId(conversationId);
                projectFileRepository.save(f);
            }
            return f;
        }
        
        // 3. Create New
        // Reuse createFolder logic but set wpsFileId manually
        ProjectFile folder = new ProjectFile();
        folder.setProjectId(projectId);
        folder.setParentId(parentId);
        folder.setIsFolder(true);
        folder.setName(conversationId); // Default name is ID
        folder.setSortOrder(0);
        folder.setUserId(userId);
        folder.setCreatedAt(LocalDateTime.now());
        folder.setUpdatedAt(LocalDateTime.now());
        folder.setWpsFileId(conversationId); // CRITICAL: Link ID to Folder
        
        return projectFileRepository.save(folder);
    }
    
    @Transactional
    public void renameConversationFolder(String conversationId, String newTitle, Long userId) {
        if (!StringUtils.hasText(newTitle)) return;
        
        // Find folder by wpsFileId = conversationId
        Optional<ProjectFile> folderOpt = projectFileRepository.findByWpsFileId(conversationId).stream().findFirst();
        if (folderOpt.isPresent()) {
            ProjectFile folder = folderOpt.get();
            // Only rename if different
            if (!folder.getName().equals(newTitle)) {
                try {
                    rename(folder.getId(), newTitle, userId); // Re-use standard rename logic
                } catch (Exception e) {
                    log.error("Failed to rename conversation folder {} to {}", conversationId, newTitle, e);
                    // Fallback: force update DB Name if physical rename fails? 
                    // rename() already handles DB update even if physical fails (partially)
                }
            }
        }
    }
}

