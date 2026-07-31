package com.checkba.storage;

import com.checkba.model.entity.Project;
import com.checkba.repository.ProjectRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 逻辑存储路径 → 物理磁盘路径的唯一映射点。
 *
 * 逻辑路径（storage key）即 ProjectFile.filePath 的格式：
 *   projects/{projectId}/...   —— 项目命名空间
 *   avatars/... clipboard/... favorites/... checkpoints/... ocr/... repos/... —— 全局命名空间
 *
 * 项目命名空间的物理位置由 Project.localRoot 决定：
 *   localRoot 非空（IDE 化本地文件夹项目）→ {localRoot}/...
 *   localRoot 为空（存量托管项目）      → {globalRoot}/projects/{projectId}/...
 * 全局命名空间恒在 {globalRoot} 下。
 *
 * 版本记录的 git 工作树（ProjectRepoService.workTree）与本类 projectRoot 必须同源——
 * 两者都从这里取，"git 提交的内容 = 用户看到的文件" 由此成为契约保证而非巧合。
 */
@Component
public class ProjectStorageResolver {

    private static final Pattern PROJECT_KEY = Pattern.compile("^projects/(\\d+)(?:/(.*))?$");

    private final ProjectRepository projectRepository;
    private final Path globalRoot;
    private final Path templateDoc;
    /** projectId → localRoot（Optional.empty = 存量托管项目）。只缓存确实存在的项目行。 */
    private final Map<Long, Optional<Path>> localRootCache = new ConcurrentHashMap<>();

    public ProjectStorageResolver(StorageProperties storageProperties, ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
        this.globalRoot = resolveConfiguredPath(storageProperties.getLocal().getRootPath());
        this.templateDoc = resolveConfiguredPath(storageProperties.getLocal().getTemplatePath());
    }

    /**
     * 配置路径 → 绝对路径的唯一实现（此前被 LocalFileStorageService /
     * ProjectRepoService / ProjectRagService 各复制一份）。
     * 相对路径基于 user.dir 解析；dev 态 user.dir 是 backend 目录时上提一级到仓库根。
     */
    public static Path resolveConfiguredPath(String configured) {
        Path p = Paths.get(configured);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        String userDir = System.getProperty("user.dir");
        Path base = Paths.get(userDir);
        if (userDir.endsWith("backend")) {
            base = base.getParent();
        }
        return base.resolve(configured).normalize();
    }

    /** 全局存储根（data 根）。全局命名空间与托管项目都在它下面。 */
    public Path globalRoot() {
        return globalRoot;
    }

    /** 新建文档模板路径。 */
    public Path templateDoc() {
        return templateDoc;
    }

    /** 项目物理根目录：localRoot 非空取之，否则 {globalRoot}/projects/{id}。 */
    public Path projectRoot(long projectId) {
        return localRoot(projectId)
                .orElseGet(() -> globalRoot.resolve("projects").resolve(String.valueOf(projectId)));
    }

    /** 项目是否为 IDE 化本地文件夹项目（localRoot 非空）。 */
    public boolean hasLocalRoot(long projectId) {
        return localRoot(projectId).isPresent();
    }

    /** localRoot 变更（重新定位/新建）后必须调用，否则解析继续走旧缓存。 */
    public void invalidate(long projectId) {
        localRootCache.remove(projectId);
    }

    /**
     * 逻辑路径 → 物理路径。含越界围栏：normalize 后必须仍落在对应根内，
     * 防 "../" 逃出（项目命名空间的根是 projectRoot，全局命名空间的根是 globalRoot）。
     */
    public Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new StorageException("非法文件路径（空）");
        }
        String normalized = storageKey.replace('\\', '/');
        Matcher m = PROJECT_KEY.matcher(normalized);
        Path base;
        String remainder;
        if (m.matches()) {
            base = projectRoot(Long.parseLong(m.group(1))).normalize();
            remainder = m.group(2) == null ? "" : m.group(2);
        } else {
            base = globalRoot;
            remainder = normalized;
        }
        Path resolved = remainder.isEmpty() ? base : base.resolve(remainder).normalize();
        if (!resolved.startsWith(base)) {
            throw new StorageException("非法文件路径（越出存储根）: " + storageKey);
        }
        return resolved;
    }

    private Optional<Path> localRoot(long projectId) {
        Optional<Path> cached = localRootCache.get(projectId);
        if (cached != null) {
            return cached;
        }
        if (projectRepository == null) {
            // 测试态：无仓库注入，一律按托管项目处理
            return Optional.empty();
        }
        Optional<Project> row;
        try {
            row = projectRepository.findById(projectId);
        } catch (Exception e) {
            // 解析失败不阻断文件操作，按托管路径兜底（且不缓存，下次重查）
            return Optional.empty();
        }
        if (row.isEmpty()) {
            // 项目行不存在（并发创建窗口/已删除）：不缓存，避免建好后仍走空缓存
            return Optional.empty();
        }
        Optional<Path> result = row.map(Project::getLocalRoot)
                .filter(StringUtils::hasText)
                .map(s -> Paths.get(s).normalize());
        localRootCache.put(projectId, result);
        return result;
    }
}
