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
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final com.checkba.service.telemetry.TelemetryService telemetryService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public LocalProjectService(ProjectRepository projectRepository,
                               ProjectMemberRepository projectMemberRepository,
                               ProjectFileRepository projectFileRepository,
                               ProjectFileService projectFileService,
                               ProjectMemberService projectMemberService,
                               ProjectStorageResolver storageResolver,
                               org.springframework.context.ApplicationEventPublisher eventPublisher,
                               com.checkba.service.telemetry.TelemetryService telemetryService,
                               org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectFileRepository = projectFileRepository;
        this.projectFileService = projectFileService;
        this.projectMemberService = projectMemberService;
        this.storageResolver = storageResolver;
        this.eventPublisher = eventPublisher;
        this.telemetryService = telemetryService;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /** 本地文件夹项目被打开/创建（LocalRootWatchService 据此启动监听）。 */
    public record LocalProjectOpened(long projectId, String localRoot) {}

    public record OpenLocalResult(Project project, boolean reused, Long openFileId,
                                  int importedCount, boolean truncated) {}

    /**
     * 打开/复用本地文件夹项目。
     *
     * <p><b>刻意不带 @Transactional</b>，与 {@link #reconcileProject} 同理：方法里调的
     * {@code importFolder} 会逐条调 ProjectFileService 那三个 @Transactional 方法
     * （REQUIRED，会参与外层事务）。磁盘上出现与库中「活着」的文件同名的目录时
     * （用户把某个文件换成了同名文件夹），createFolder 的同名检查抛异常，
     * Spring 对参与型事务默认标 rollback-only；importFolder 的 catch 接住异常继续扫，
     * 却撤不回这个标记——方法体正常返回后，代理提交时抛 UnexpectedRollbackException。
     * 用户看到的是「打开失败」，而且**重开一次还是失败，项目从此打不开**。
     *
     * <p>项目行与成员行仍必须一起落（否则一次失败会留下没有成员的孤儿项目，
     * 之后 findByLocalRoot 命中它、权限检查又不过，这个文件夹就谁都用不了了），
     * 所以那一段单独用 TransactionTemplate 包起来，导入留在事务之外。
     */
    public OpenLocalResult openLocalFolder(String localRootRaw, boolean createFolder,
                                           String name, String openFileName, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        Path root = validateLocalRoot(localRootRaw, createFolder);
        String canonical = root.toString();

        ProjectResolution resolution = transactionTemplate.execute(status -> resolveProject(canonical, root, name, userId));
        Project project = resolution.project();
        boolean reused = resolution.reused();

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
        eventPublisher.publishEvent(new LocalProjectOpened(project.getId(), canonical));
        telemetryService.record("project.created", java.util.Map.of(
                "kind", "local", "reused", reused, "importedCount", stats.imported));
        return new OpenLocalResult(project, reused, openFileId, stats.imported, stats.truncated);
    }

    private record ProjectResolution(Project project, boolean reused) {}

    /** 复用既有项目或新建一个（项目行 + 成员行必须同一个事务）。 */
    private ProjectResolution resolveProject(String canonical, Path root, String name, Long userId) {
        Optional<Project> existing = projectRepository.findByLocalRoot(canonical);
        if (existing.isPresent()) {
            Project project = existing.get();
            if (!projectMemberService.hasReadPermission(project.getId(), userId)) {
                throw new IllegalArgumentException(LangText.of("该文件夹已被其他账号的项目使用", "This folder is already in use by another account's project"));
            }
            return new ProjectResolution(project, true);
        }
        rejectNestedRoot(root);
        Project project = new Project();
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
        return new ProjectResolution(project, false);
    }

    public record ReconcileResult(int changed, boolean rootMissing, boolean truncated) {}

    /**
     * 磁盘 ↔ 数据库文件树对账（watcher 触发；幂等，只写数据库、绝不写磁盘）：
     * ① 导入新增/变化的文件（无变化的行一字不动，避免 DB 行翻搅与版本记录噪声）；
     * ② 磁盘上已消失的行软删除（进回收站语义，signalChange 由服务方法自带）。
     * 根目录整个不可达（外置盘拔出/文件夹被移走）时整体跳过——绝不把「暂时看不见」
     * 当成「都被删了」，否则一次误判就把整棵文件树扫进回收站。
     *
     * 本方法故意不带 @Transactional：它调的 ProjectFileService.createFolder /
     * createOrUpdateFile / delete 各自都是独立 bean 上的 @Transactional（REQUIRED）方法。
     * 如果这里也开一个外层事务，内层就会"参与"进来共享同一个事务——某一条目录/文件
     * 因为同名冲突等原因抛异常时（比如磁盘上出现一个和库里"活着"的文件同名的目录，
     * createFolder 内部的查重不看 isFolder 类型），Spring 对参与型事务抛异常默认标记
     * rollback-only，下面的 catch 把异常本身接住继续扫下一条，但撤不回这个标记——
     * 方法体正常 return 之后，AOP 代理 commit 时发现 rollback-only，转而抛
     * UnexpectedRollbackException，把本轮已经处理完的其它条目一起回滚，而日志还已经
     * 打过"对账完成"。不开外层事务后，每次对 createFolder/createOrUpdateFile/delete
     * 的调用都各自独立成一个新事务、处理完就提交，一条坏条目只影响它自己那次调用，
     * 与"跳过这条坏数据、把其余导完"的既有设计意图一致。
     */
    public ReconcileResult reconcileProject(Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || !StringUtils.hasText(project.getLocalRoot())) {
            return new ReconcileResult(0, false, false);
        }
        Path root = Paths.get(project.getLocalRoot());
        if (!Files.isDirectory(root)) {
            log.warn("对账跳过：项目文件夹不可达 project={}, root={}", projectId, root);
            return new ReconcileResult(0, true, false);
        }
        Long ownerId = project.getUserId();

        ImportStats stats = importFolder(projectId, root, ownerId);
        int changed = stats.changed;
        if (stats.truncated) {
            // 此前 stats.truncated 只在 openLocalFolder 那侧被读取，watcher 触发的
            // reconcileProject 从不读它——超出 MAX_IMPORT_ENTRIES 上限的文件永远静默
            // 不进文件树，无日志无 API 信号。这里补上，与 openLocalFolder 的日志对齐。
            log.warn("对账触发导入上限截断: project={}, root={}, 上限={}", projectId, root, MAX_IMPORT_ENTRIES);
        }

        // 删除同步：行在库、物理不存在 → 软删除（文件按 filePath 解析，文件夹按父链拼相对路径）
        java.util.List<ProjectFile> rows = projectFileRepository.findByProjectId(projectId);
        Map<Long, ProjectFile> byId = new HashMap<>();
        for (ProjectFile f : rows) byId.put(f.getId(), f);
        for (ProjectFile f : rows) {
            if (Boolean.TRUE.equals(f.getIsDeleted())) continue;
            Path physical;
            if (Boolean.TRUE.equals(f.getIsFolder())) {
                physical = root.resolve(relativeFolderPath(f, byId));
            } else if (StringUtils.hasText(f.getFilePath())) {
                try {
                    physical = storageResolver.resolve(f.getFilePath());
                } catch (Exception e) {
                    continue; // 路径异常的行不动
                }
            } else {
                continue; // 无物理路径的行（历史遗留）不动
            }
            if (!Files.exists(physical)) {
                // 父级已被软删除的行会随递归一起处理，重复调用无害（幂等）
                try {
                    projectFileService.delete(f.getId(), ownerId);
                    changed++;
                } catch (Exception e) {
                    log.warn("对账软删除失败: file={} ({})", f.getId(), e.getMessage());
                }
            }
        }
        if (changed > 0) {
            log.info("对账完成: project={}, changed={}", projectId, changed);
        }
        return new ReconcileResult(changed, false, stats.truncated);
    }

    private String relativeFolderPath(ProjectFile folder, Map<Long, ProjectFile> byId) {
        StringBuilder sb = new StringBuilder(folder.getName());
        Long pid = folder.getParentId();
        int depth = 0;
        while (pid != null && depth < 30) {
            ProjectFile parent = byId.get(pid);
            if (parent == null) break;
            sb.insert(0, parent.getName() + "/");
            pid = parent.getParentId();
            depth++;
        }
        return sb.toString();
    }

    /** 校验并规范化用户选择的文件夹。 */
    private Path validateLocalRoot(String raw, boolean createFolder) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException(LangText.of("请选择一个文件夹", "Please select a folder"));
        }
        Path p = Paths.get(raw.trim());
        if (!p.isAbsolute()) {
            throw new IllegalArgumentException(LangText.of("文件夹路径必须是绝对路径", "The folder path must be absolute"));
        }
        p = p.normalize();
        if (p.getParent() == null) {
            throw new IllegalArgumentException(LangText.of("不能把整个磁盘根目录作为项目", "You cannot use an entire drive root as a project"));
        }
        Path global = storageResolver.globalRoot();
        if (p.startsWith(global)) {
            throw new IllegalArgumentException(LangText.of("该位置是软件内部数据目录，请选择其他文件夹", "This location is the application's internal data directory; please choose another folder"));
        }
        if (createFolder) {
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new IllegalArgumentException(LangText.of("创建文件夹失败: ", "Failed to create folder: ") + e.getMessage());
            }
        }
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException(LangText.of("文件夹不存在或不是目录: ", "Folder does not exist or is not a directory: ") + p);
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
                        LangText.of("该文件夹与已有项目「", "This folder overlaps with the folder of the existing project “") + other.getName()
                                + LangText.of("」的文件夹嵌套，请选择互不包含的文件夹", "”; please choose folders that do not contain each other"));
            }
        }
    }

    private String folderDisplayName(Path root) {
        Path fn = root.getFileName();
        return fn != null ? fn.toString() : root.toString();
    }

    private static final class ImportStats {
        int imported;   // 本次处理（含无变化跳过）的条目数，用于上限控制
        int changed;    // 真正新建/更新的条目数，用于「有没有发生变化」判断
        boolean truncated;
    }

    /**
     * 把文件夹现有内容登记进数据库文件树。幂等：
     * - 已有同名同大小同路径的行一字不动（避免 DB 行翻搅与版本记录噪声）；
     * - **回收站里的行（isDeleted）不复活**——软删除不动磁盘文件，重扫时把磁盘上
     *   仍存在的它当「新文件」再导入会静默撤销律师刚做的删除；
     * - 跳过点开头的隐藏项（.git/.awd/.DS_Store 等一并覆盖）。
     */
    private ImportStats importFolder(Long projectId, Path root, Long userId) {
        ImportStats stats = new ImportStats();
        Map<Path, Long> dirIds = new HashMap<>();
        dirIds.put(root, null);

        // 一次拉全量行建索引：既省 per-entry 查询，也让「含已删行」的查重成为可能
        Map<String, ProjectFile> rowIndex = new HashMap<>();
        for (ProjectFile f : projectFileRepository.findByProjectId(projectId)) {
            String key = rowKey(f.getParentId(), f.getName());
            ProjectFile prev = rowIndex.get(key);
            // 同键多行时非删除行优先
            if (prev == null || Boolean.TRUE.equals(prev.getIsDeleted())) {
                rowIndex.put(key, f);
            }
        }

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
                    if (parentId == null && !dir.getParent().equals(root)) {
                        return FileVisitResult.SKIP_SUBTREE; // 父目录导入失败/被跳过
                    }
                    String dirName = dir.getFileName().toString();
                    ProjectFile row = rowIndex.get(rowKey(parentId, dirName));
                    if (row != null && Boolean.TRUE.equals(row.getIsFolder())) {
                        if (Boolean.TRUE.equals(row.getIsDeleted())) {
                            return FileVisitResult.SKIP_SUBTREE; // 回收站里的文件夹不复活
                        }
                        dirIds.put(dir, row.getId());
                        stats.imported++;
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        ProjectFile created = projectFileService.createFolder(projectId, parentId, dirName, userId);
                        rowIndex.put(rowKey(parentId, dirName), created);
                        dirIds.put(dir, created.getId());
                        stats.imported++;
                        stats.changed++;
                    } catch (Exception e) {
                        log.warn("导入文件夹失败，跳过子树: {} ({})", dir, e.getMessage());
                        return FileVisitResult.SKIP_SUBTREE;
                    }
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
                    ProjectFile row = rowIndex.get(rowKey(parentId, fileName));
                    if (row != null && !Boolean.TRUE.equals(row.getIsFolder())) {
                        if (Boolean.TRUE.equals(row.getIsDeleted())) {
                            stats.imported++;
                            return FileVisitResult.CONTINUE; // 回收站里的文件不复活
                        }
                        boolean sameSize = row.getFileSize() != null && row.getFileSize() == attrs.size();
                        boolean samePath = logicalPath.equals(row.getFilePath());
                        if (sameSize && samePath) {
                            stats.imported++;
                            return FileVisitResult.CONTINUE; // 无变化不动行
                        }
                    }
                    int dot = fileName.lastIndexOf('.');
                    String ext = dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
                    try {
                        ProjectFile saved = projectFileService.createOrUpdateFile(projectId, parentId, fileName, ext,
                                attrs.size(), logicalPath, null, userId);
                        rowIndex.put(rowKey(parentId, fileName), saved);
                        stats.imported++;
                        stats.changed++;
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

    private static String rowKey(Long parentId, String name) {
        return (parentId == null ? "root" : String.valueOf(parentId)) + "/" + name;
    }
}
