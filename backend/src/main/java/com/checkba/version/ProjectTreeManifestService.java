package com.checkba.version;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 文件树清单：把数据库里的文件树随版本一起存进仓库，
 * 使「退回到某一版」能同时还原目录结构、排序与回收站状态。
 */
@Service
public class ProjectTreeManifestService {

    public static final String MANIFEST_PATH = ".awd/tree.json";

    private final ProjectFileRepository projectFileRepository;
    private final ProjectRepoService repoService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;

    public ProjectTreeManifestService(ProjectFileRepository projectFileRepository,
                                      ProjectRepoService repoService,
                                      ObjectMapper objectMapper,
                                      UserRepository userRepository,
                                      ProjectRepository projectRepository) {
        this.projectFileRepository = projectFileRepository;
        this.repoService = repoService;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
    }

    /** 从数据库采集当前文件树（清单 v2）。软删除节点也收；存量行缺 uid 就地回填。 */
    @Transactional
    public TreeManifest capture(long projectId) {
        try {
            List<ProjectFile> files = projectFileRepository.findByProjectId(projectId)
                    .stream().sorted(Comparator.comparing(ProjectFile::getId)).toList();
            for (ProjectFile f : files) {
                if (f.getUid() == null || f.getUid().isBlank()) {
                    f.setUid(UUID.randomUUID().toString());
                    projectFileRepository.save(f);
                }
            }
            Map<Long, String> uidById = new HashMap<>();
            for (ProjectFile f : files) uidById.put(f.getId(), f.getUid());
            Map<Long, String> authorCache = new HashMap<>();
            List<TreeManifest.Node> nodes = files.stream()
                    .map(f -> new TreeManifest.Node(
                            null, null, f.getName(),
                            Boolean.TRUE.equals(f.getIsFolder()),
                            f.getFileType(), f.getSortOrder(), null,
                            Boolean.TRUE.equals(f.getIsDeleted()), null,
                            f.getUid(),
                            f.getParentId() == null ? null : uidById.get(f.getParentId()),
                            repoRelative(projectId, f.getFilePath()),
                            authorUsername(f.getUserId(), authorCache)))
                    .toList();
            return new TreeManifest(TreeManifest.CURRENT_VERSION, nodes);
        } catch (Exception e) {
            throw new VersionException("采集文件树清单失败: project=" + projectId, e);
        }
    }

    private String authorUsername(Long userId, Map<Long, String> cache) {
        if (userId == null) return null;
        return cache.computeIfAbsent(userId, id ->
                userRepository.findById(id).map(User::getUsername).orElse(null));
    }

    public void writeToWorkTree(long projectId, TreeManifest manifest) {
        try {
            Path target = repoService.workTree(projectId).resolve(MANIFEST_PATH);
            Files.createDirectories(target.getParent());
            Files.writeString(target,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new VersionException("写入文件树清单失败: project=" + projectId, e);
        }
    }

    /** 读取某一版的清单；该版没有清单（例如开启版本记录之前的历史）时返回 null。 */
    public TreeManifest readAtRef(long projectId, String ref) {
        byte[] bytes = repoService.readBlobAtCommit(projectId, ref, MANIFEST_PATH);
        if (bytes == null || bytes.length == 0) return null;
        try {
            return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8),
                    TreeManifest.class);
        } catch (Exception e) {
            throw new VersionException("解析文件树清单失败: project=" + projectId + " ref=" + ref, e);
        }
    }

    public record SyncReport(int created, int updated, int softDeleted) {}

    /**
     * 把清单描述的文件树同步进数据库。差异同步，不是删表重建——
     * 保证回退不会丢掉当前状态里的任何东西，且回退本身可以再回退。
     */
    @Transactional
    public SyncReport applyToDatabase(long projectId, TreeManifest manifest) {
        return apply(projectId, manifest, true);
    }

    /**
     * 清单**并集**：把稿的清单叠加到当前数据库上，用于采纳一稿（spec 第 3 期）。
     *
     * 与 {@link #applyToDatabase} 共用同一个节点新建/更新内核，只差两条规则：
     * <ul>
     *   <li>数据库里有、稿的清单里没有的行**绝不动**——那是主线独有的文件，
     *       采纳一稿不该让它们消失。这正是「并集」与「同步」的分水岭。</li>
     *   <li>并集只加不减：已有的行不会被稿的清单从「在用」改成「回收站」。
     *       理由是切线协议本身会污染稿的清单——切到稿上时，主线独有的行会被
     *       {@link #applyToDatabase} 判成回收站状态，稿上随后的任何一次自动存档
     *       都会把这个状态写进稿的清单。照单执行就会在采纳后把主线自己的文件
     *       从文件树里抹掉。代价是稿上"删进回收站"这个动作不会随采纳带过来
     *       （软删除不动磁盘文件，律师再删一次即可），比起丢文件这是划算的一边。</li>
     * </ul>
     */
    @Transactional
    public SyncReport unionApply(long projectId, TreeManifest manifest) {
        return apply(projectId, manifest, false);
    }

    /**
     * applyToDatabase / unionApply 共用的内核。{@code syncDeletions} 为 true 是同步语义
     * （清单里没有的行进回收站，已有的行也照清单可增可减），为 false 是并集语义
     * （这两种「减」都不做）。
     */
    private SyncReport apply(long projectId, TreeManifest manifest, boolean syncDeletions) {
        try {
            Map<Long, ProjectFile> current = new HashMap<>();
            for (ProjectFile f : projectFileRepository.findByProjectId(projectId)) {
                current.put(f.getId(), f);
            }

            // 清单节点按「父先于子」排序，保证建父节点时 parentId 已可解析
            List<TreeManifest.Node> effective = manifest.version() >= 2
                    ? normalizeV2(projectId, manifest.nodes()) : manifest.nodes();
            List<TreeManifest.Node> ordered = topoSort(effective);

            Map<Long, Long> remap = new LinkedHashMap<>();
            int created = 0, updated = 0;

            for (TreeManifest.Node node : ordered) {
                Long targetParentId = node.parentId() == null
                        ? null : remap.getOrDefault(node.parentId(), node.parentId());

                ProjectFile existing = current.get(node.id());
                boolean idTakenByOther = existing != null && !sameNode(existing, node);

                if (existing != null && !idTakenByOther) {
                    boolean keepDbLocation =
                            !syncDeletions && mainlineLocationWins(projectId, existing, node);
                    if (applyAttributes(existing, node,
                            keepDbLocation ? existing.getParentId() : targetParentId,
                            syncDeletions, keepDbLocation)) {
                        projectFileRepository.save(existing);
                        updated++;
                    }
                    current.remove(node.id());
                    continue;
                }

                // 创建者优先取清单自带的 userId；清单也没有时才回退到数据库里同 id 的旧记录。
                // 两边都没有就宁可让它是 null（下面会抛异常），也不能静默把节点判给别人。
                Long userId = node.userId() != null
                        ? node.userId() : (existing != null ? existing.getUserId() : null);
                if (userId == null) {
                    throw new VersionException(
                            "清单缺少创建者信息，无法新建节点: project=" + projectId + " nodeId=" + node.id());
                }

                ProjectFile fresh = new ProjectFile();
                if (existing == null) fresh.setId(node.id());
                fresh.setProjectId(projectId);
                fresh.setUserId(userId);
                fresh.setCreatedAt(LocalDateTime.now());
                // 新建的行本来就不存在，照清单原样落地（含回收站状态），
                // 「并集只加不减」只约束已经存在的行。
                applyAttributes(fresh, node, targetParentId, true, false);
                ProjectFile saved = projectFileRepository.save(fresh);
                if (!Objects.equals(saved.getId(), node.id())) {
                    remap.put(node.id(), saved.getId());
                }
                created++;
                current.remove(node.id());
            }

            int softDeleted = 0;
            if (syncDeletions) {
                for (ProjectFile leftover : current.values()) {
                    if (Boolean.TRUE.equals(leftover.getIsDeleted())) continue;
                    leftover.setIsDeleted(true);
                    leftover.setDeletedAt(LocalDateTime.now());
                    projectFileRepository.save(leftover);
                    softDeleted++;
                }
            }

            return new SyncReport(created, updated, softDeleted);
        } catch (VersionException e) {
            throw e;
        } catch (Exception e) {
            throw new VersionException("同步文件树清单回数据库失败: project=" + projectId, e);
        }
    }

    /**
     * 同类型（文件夹/文件）即认为是同一个节点，改名、移动、改路径都算属性更新；
     * 类型都对不上，说明这个 id 已经被别的记录占用了，只能新建。
     * 不用 name 参与判断——改名恰恰是清单要更新的属性之一，用它做身份判据会把
     * 每一次改名都误判成「id 被占用」。
     */
    private boolean sameNode(ProjectFile f, TreeManifest.Node n) {
        return Boolean.TRUE.equals(f.getIsFolder()) == n.isFolder();
    }

    /**
     * 并集语义下的现实校验：稿的清单说这份文件在 A，但合并后的工作区里 A 根本不存在、
     * 数据库现有的 B 却真实存在——说明主线在稿开出去之后把这份文件改名/移了位，
     * git 的三方合并已经按主线那一侧落了盘。这时数据库要跟随磁盘现实保留 B，不能照抄
     * 稿的旧位置，否则文件树里会留下一行指向不存在路径的幽灵，律师双击就是打不开。
     * 其余情况仍然是 draft-wins（spec 的缺省）。
     *
     * 判断本身失败（路径不在本项目前缀下、文件系统异常）一律返回 false 退回缺省：
     * 清单同步是保险，不能因为多出来的这层校验反而改变原有行为。
     */
    private boolean mainlineLocationWins(long projectId, ProjectFile existing, TreeManifest.Node n) {
        try {
            String draftRel = repoRelative(projectId, n.filePath());
            String dbRel = repoRelative(projectId, existing.getFilePath());
            if (draftRel == null || dbRel == null || draftRel.equals(dbRel)) return false;
            Path work = repoService.workTree(projectId);
            return !Files.exists(work.resolve(draftRel)) && Files.exists(work.resolve(dbRel));
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code projects/{id}/xxx} → {@code xxx}；不在本项目前缀下（含文件夹的 null）返回 null。 */
    private String repoRelative(long projectId, String filePath) {
        String prefix = "projects/" + projectId + "/";
        if (filePath == null || !filePath.startsWith(prefix)
                || filePath.length() <= prefix.length()) {
            return null;
        }
        return filePath.substring(prefix.length());
    }

    /**
     * v2 → v1 形状：uid 命中本地行的节点换成该行真实 id；未命中的分配互不相同的
     * 合成负 id（走「清单有、库无」路径，save 时被 IDENTITY 重新分配 + remap 修
     * parentId——v1 既有机制）；relPath 加回本机前缀；author 三级回退解析 userId。
     */
    private List<TreeManifest.Node> normalizeV2(long projectId, List<TreeManifest.Node> nodes) {
        Map<String, ProjectFile> byUid = new HashMap<>();
        for (ProjectFile f : projectFileRepository.findByProjectId(projectId)) {
            if (f.getUid() != null) byUid.put(f.getUid(), f);
        }
        Long ownerId = projectRepository.findById(projectId)
                .map(Project::getUserId).orElse(null);
        Map<String, Long> idByUid = new HashMap<>();
        long synthetic = -1;
        for (TreeManifest.Node n : nodes) {
            if (n.uid() == null || n.uid().isBlank()) {
                // v2 契约要求每个节点必有 uid——多个 null/blank 键会在 idByUid 里互相
                // 覆盖，让 topoSort 的去重把后来者当重复静默丢弃（节点不落库、无异常无
                // 日志）。capture 自产的清单必回填 uid 不会触发；.awd/tree.json 是仓库里
                // 外部可编辑的文件，宁可显式失败，不能静默丢数据。
                throw new VersionException(
                        "清单 v2 节点缺少身份标识(uid): project=" + projectId + " name=" + n.name());
            }
            // .awd/tree.json 是仓库里外部可编辑（且随 push 跨机器传播）的文件，relPath 不可信：
            // 恶意成员写 "../{别的项目 id}/x.docx"，落库的 filePath 会指向别人项目的文件，
            // 下载端点按行上的 projectId 放行即打穿项目隔离（IDOR）。null 放行 = 文件夹节点。
            if (n.relPath() != null && !WorkSessionService.isSafeRepoRelativePath(n.relPath())) {
                throw new VersionException(
                        "清单 v2 节点路径不合法: project=" + projectId + " relPath=" + n.relPath());
            }
            ProjectFile match = byUid.get(n.uid());
            idByUid.put(n.uid(), match != null ? match.getId() : synthetic--);
        }
        Map<String, Long> userIdCache = new HashMap<>();
        List<TreeManifest.Node> out = new ArrayList<>();
        for (TreeManifest.Node n : nodes) {
            ProjectFile match = n.uid() == null ? null : byUid.get(n.uid());
            Long userId = null;
            if (n.author() != null) {
                userId = userIdCache.computeIfAbsent(n.author(), name ->
                        userRepository.findByUsername(name).map(User::getId).orElse(null));
            }
            if (userId == null && match != null) userId = match.getUserId();
            if (userId == null) userId = ownerId;
            out.add(new TreeManifest.Node(
                    idByUid.get(n.uid()),
                    n.parentUid() == null ? null : idByUid.get(n.parentUid()),
                    n.name(), n.isFolder(), n.fileType(), n.sortOrder(),
                    n.relPath() == null ? null : "projects/" + projectId + "/" + n.relPath(),
                    n.isDeleted(), userId,
                    n.uid(), n.parentUid(), n.relPath(), n.author()));
        }
        return out;
    }

    /**
     * 返回 true 表示确实改动了字段。{@code maySoftDelete} 为 false 时禁止把一条
     * 「在用」的行改成「回收站」（并集只加不减，见 {@link #unionApply}）。
     * {@code keepLocation} 为 true 时不动 name/filePath/parentId——主线的改名/移动
     * 胜出，见 {@link #mainlineLocationWins}。
     */
    private boolean applyAttributes(ProjectFile f, TreeManifest.Node n, Long parentId,
                                    boolean maySoftDelete, boolean keepLocation) {
        boolean changed = false;
        if (!keepLocation) {
            if (!Objects.equals(f.getParentId(), parentId)) { f.setParentId(parentId); changed = true; }
            if (!Objects.equals(f.getName(), n.name())) { f.setName(n.name()); changed = true; }
        }
        if (f.getIsFolder() == null || f.getIsFolder() != n.isFolder()) {
            f.setIsFolder(n.isFolder()); changed = true;
        }
        if (!Objects.equals(f.getFileType(), n.fileType())) { f.setFileType(n.fileType()); changed = true; }
        if (n.uid() != null && !Objects.equals(f.getUid(), n.uid())) { f.setUid(n.uid()); changed = true; }
        if (!Objects.equals(f.getSortOrder(), n.sortOrder())) { f.setSortOrder(n.sortOrder()); changed = true; }
        if (!keepLocation && !Objects.equals(f.getFilePath(), n.filePath())) {
            f.setFilePath(n.filePath()); changed = true;
        }
        boolean targetDeleted = n.isDeleted();
        if (!maySoftDelete && targetDeleted && !Boolean.TRUE.equals(f.getIsDeleted())) {
            targetDeleted = false;
        }
        if (Boolean.TRUE.equals(f.getIsDeleted()) != targetDeleted) {
            f.setIsDeleted(targetDeleted);
            f.setDeletedAt(targetDeleted ? LocalDateTime.now() : null);
            changed = true;
        }
        if (changed) f.setUpdatedAt(LocalDateTime.now());
        return changed;
    }

    /**
     * 父节点排在子节点之前；环或悬空父节点按原顺序兜底附加。
     *
     * 用显式递归的私有方法代替 brief 里 Consumer[] 数组自引用的写法——
     * 语义等价（先访问父节点再把自己放进结果，visiting 集合防环），
     * 但不需要那个数组技巧，也没有 unchecked 警告。
     */
    private List<TreeManifest.Node> topoSort(List<TreeManifest.Node> nodes) {
        Map<Long, TreeManifest.Node> byId = new LinkedHashMap<>();
        for (TreeManifest.Node n : nodes) byId.put(n.id(), n);

        List<TreeManifest.Node> out = new ArrayList<>();
        Set<Long> placed = new HashSet<>();
        Set<Long> visiting = new HashSet<>();

        for (TreeManifest.Node n : nodes) visitForTopoSort(n, byId, placed, visiting, out);
        for (TreeManifest.Node n : nodes) if (!placed.contains(n.id())) out.add(n);
        return out;
    }

    private void visitForTopoSort(TreeManifest.Node node, Map<Long, TreeManifest.Node> byId,
                                   Set<Long> placed, Set<Long> visiting, List<TreeManifest.Node> out) {
        if (node == null || placed.contains(node.id()) || !visiting.add(node.id())) return;
        if (node.parentId() != null) visitForTopoSort(byId.get(node.parentId()), byId, placed, visiting, out);
        visiting.remove(node.id());
        if (placed.add(node.id())) out.add(node);
    }
}
