package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectMember;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.storage.ProjectStorageResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * IDE 化本地文件夹项目：「打开文件夹 / 新建项目文件夹 / 打开文件」的业务入口。
 *
 * 语义与 IDE 对齐：
 * - 打开同一个文件夹永远回到同一个项目（按 localRoot 查重复用，不重复建项目）；
 * - 打开即导入：扫描文件夹现有内容登记进数据库文件树（幂等，重复打开等于对账一次）；
 * - 删除项目永远只删数据库记录，绝不碰用户文件夹（契约，勿改）。
 */
@Service
public class LocalProjectService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalProjectService.class);

    /** 扫描上限：防止误选超大目录（如整个用户主目录）把数据库灌爆。 */
    static final int MAX_IMPORT_ENTRIES = 3000;
    static final int MAX_IMPORT_DEPTH = 20;

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectFileService projectFileService;
    private final ProjectMemberService projectMemberService;
    private final ProjectStorageResolver storageResolver;

    public LocalProjectService(ProjectRepository projectRepository,
                               ProjectMemberRepository projectMemberRepository,
                               ProjectFileRepository projectFileRepository,
                               ProjectFileService projectFileService,
                               ProjectMemberService projectMemberService,
                               ProjectStorageResolver storageResolver) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectFileRepository = projectFileRepository;
        this.projectFileService = projectFileService;
        this.projectMemberService = projectMemberService;
        this.storageResolver = storageResolver;
    }

    public record OpenLocalResult(Project project, boolean reused, Long openFileId,
                                  int importedCount, boolean truncated) {}

    @Transactional
    public OpenLocalResult openLocalFolder(String localRootRaw, boolean createFolder,
                                           String name, String openFileName, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        Path root = validateLocalRoot(localRootRaw, createFolder);
        String canonical = root.toString();

        Optional<Project> existing = projectRepository.findByLocalRoot(canonical);
        Project project;
        boolean reused;
        if (existing.isPresent()) {
            project = existing.get();
            if (!projectMemberService.hasReadPermission(project.getId(), userId)) {
                throw new IllegalArgumentException("该文件夹已被其他账号的项目使用");
            }
            reused = true;
        } else {
            rejectNestedRoot(root);
            project = new Project();
            project.setName(StringUtils.hasText(name) ? name.trim() : folderDisplayName(root));
            project.setProjectType("BLANK");
            project.setListedCompanyName("");
            project.setTargetCompanyName("");
            project.setUserId(userId);
            project.setLocalRoot(canonical);
            LocalDateTime now = LocalDateTime.now();
            project.setCreatedAt(now);
            project.setUpdatedAt(now);
            project = projectRepository.save(project);

            ProjectMember member = new ProjectMember();
            member.setProjectId(project.getId());
            member.setUserId(userId);
            member.setRole("ADMIN");
            projectMemberRepository.save(member);

            storageResolver.invalidate(project.getId());
            reused = false;
        }

        ImportStats stats = importFolder(project.getId(), root, userId);

        Long openFileId = null;
        if (StringUtils.hasText(openFileName)) {
            openFileId = projectFileRepository
                    .findByProjectIdAndParentIdAndNameAndIsDeletedFalse(project.getId(), null, openFileName)
                    .map(ProjectFile::getId)
                    .orElse(null);
        }
        log.info("打开本地文件夹项目: project={}, root={}, reused={}, imported={}, truncated={}",
                project.getId(), canonical, reused, stats.imported, stats.truncated);
        return new OpenLocalResult(project, reused, openFileId, stats.imported, stats.truncated);
    }

    /** 校验并规范化用户选择的文件夹。 */
    private Path validateLocalRoot(String raw, boolean createFolder) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("请选择一个文件夹");
        }
        Path p = Paths.get(raw.trim());
        if (!p.isAbsolute()) {
            throw new IllegalArgumentException("文件夹路径必须是绝对路径");
        }
        p = p.normalize();
        if (p.getParent() == null) {
            throw new IllegalArgumentException("不能把整个磁盘根目录作为项目");
        }
        Path global = storageResolver.globalRoot();
        if (p.startsWith(global)) {
            throw new IllegalArgumentException("该位置是软件内部数据目录，请选择其他文件夹");
        }
        if (createFolder) {
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new IllegalArgumentException("创建文件夹失败: " + e.getMessage());
            }
        }
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("文件夹不存在或不是目录: " + p);
        }
        return p;
    }

    /** 拒绝与已有本地文件夹项目互相嵌套（父子重叠会让两边的文件树互相踩踏）。 */
    private void rejectNestedRoot(Path root) {
        for (Project other : projectRepository.findByLocalRootIsNotNull()) {
            if (!StringUtils.hasText(other.getLocalRoot())) continue;
            Path otherRoot = Paths.get(other.getLocalRoot());
            if (root.startsWith(otherRoot) || otherRoot.startsWith(root)) {
                throw new IllegalArgumentException(
                        "该文件夹与已有项目「" + other.getName() + "」的文件夹嵌套，请选择互不包含的文件夹");
            }
        }
    }

    private String folderDisplayName(Path root) {
        Path fn = root.getFileName();
        return fn != null ? fn.toString() : root.toString();
    }

    private static final class ImportStats {
        int imported;
        boolean truncated;
    }

    /**
     * 把文件夹现有内容登记进数据库文件树。幂等：已有同名记录只更新元数据。
     * 跳过点开头的隐藏项（.git/.awd/.DS_Store 等一并覆盖）。
     */
    private ImportStats importFolder(Long projectId, Path root, Long userId) {
        ImportStats stats = new ImportStats();
        Map<Path, Long> dirIds = new HashMap<>();
        dirIds.put(root, null);
        try {
            Files.walkFileTree(root, java.util.Collections.emptySet(), MAX_IMPORT_DEPTH,
                    new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    if (dir.getFileName().toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    if (stats.imported >= MAX_IMPORT_ENTRIES) {
                        stats.truncated = true;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Long parentId = dirIds.get(dir.getParent());
                    String dirName = dir.getFileName().toString();
                    Long folderId = projectFileRepository
                            .findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, parentId, dirName)
                            .filter(f -> Boolean.TRUE.equals(f.getIsFolder()))
                            .map(ProjectFile::getId)
                            .orElse(null);
                    if (folderId == null) {
                        try {
                            folderId = projectFileService.createFolder(projectId, parentId, dirName, userId).getId();
                            stats.imported++;
                        } catch (Exception e) {
                            log.warn("导入文件夹失败，跳过子树: {} ({})", dir, e.getMessage());
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    dirIds.put(dir, folderId);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (fileName.startsWith(".")) return FileVisitResult.CONTINUE;
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    if (stats.imported >= MAX_IMPORT_ENTRIES) {
                        stats.truncated = true;
                        return FileVisitResult.TERMINATE;
                    }
                    Long parentId = dirIds.get(file.getParent());
                    if (parentId == null && !file.getParent().equals(root)) {
                        return FileVisitResult.CONTINUE; // 父目录导入失败被跳过
                    }
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    String logicalPath = "projects/" + projectId + "/" + rel;
                    int dot = fileName.lastIndexOf('.');
                    String ext = dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
                    try {
                        projectFileService.createOrUpdateFile(projectId, parentId, fileName, ext,
                                attrs.size(), logicalPath, null, userId);
                        stats.imported++;
                    } catch (Exception e) {
                        log.warn("导入文件失败，跳过: {} ({})", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("导入时无法访问，跳过: {} ({})", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("扫描文件夹失败（已导入 {} 项）: {}", stats.imported, e.getMessage());
        }
        return stats;
    }
}
